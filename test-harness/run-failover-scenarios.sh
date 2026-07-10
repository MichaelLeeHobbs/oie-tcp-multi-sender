#!/usr/bin/env bash
#
# Live failover behavior driver for the Multi-Endpoint TCP Sender.
#
# Against a running test stack (docker/oie-test.compose.yaml: OIE + the controllable MLLP sink) this
# deploys channel-failover.xml and drives every routing case, asserting behavior via the sink's /state
# oracle. Fully scripted + deterministic -> suitable for CI.
#
#   1. bring the stack up:   docker compose -f docker/oie-test.compose.yaml up -d --build
#   2. run this driver:      test-harness/run-failover-scenarios.sh
#
# Scenarios:
#   S1 baseline          both up            -> primary (6661) receives, 6662 idle
#   S2 failover          6661 down          -> 6662 receives (connect-phase failover)
#   S3 auto-failback     6661 back up +cd   -> 6661 receives again (health probe re-selects)
#   S4 NACK no-failover  6661 nack          -> 6661 receives, 6662 idle (AE is post-write, not a failover trigger)
#   S5 lost-ACK          6661 hang          -> 6661 receives, 6662 idle (post-write timeout never cross-delivers)
#   S6 all-down + drain  both down->6661 up -> queued (never dropped), drains to 6661 when it returns
set -uo pipefail

API="https://127.0.0.1:8443/api"
SINK="http://127.0.0.1:19000"
CH="tcpmulti-failover-test"
FIXTURE="$(cd "$(dirname "$0")" && pwd)/channel-failover.xml"
COOKIES="$(cd "$(dirname "$0")" && pwd)/cookies.txt"
CD_WAIT=4          # > cooldownMillis (3000) so failback probe fires
FAILS=0

api()  { curl -k -s -b "$COOKIES" -c "$COOKIES" -H "X-Requested-With: driver" "$@"; }
sink() { curl -s "$@"; }
cnt()  { sink "$SINK/state" | python -c "import sys,json;print(json.load(sys.stdin)['ports']['$1']['count'])"; }
reset_sink() { sink -s -X POST "$SINK/reset" >/dev/null; }
set_port() { sink -s -X POST "$SINK/control" -H 'content-type: application/json' -d "$1" >/dev/null; }

# assert_eq LABEL ACTUAL EXPECTED
assert_eq() {
  if [ "$2" = "$3" ]; then echo "   PASS  $1 (=$2)"; else echo "   FAIL  $1  expected $3 got $2"; FAILS=$((FAILS+1)); fi
}
# assert_ge LABEL ACTUAL MIN
assert_ge() {
  if [ "$2" -ge "$3" ]; then echo "   PASS  $1 (=$2 >= $3)"; else echo "   FAIL  $1  expected >= $3 got $2"; FAILS=$((FAILS+1)); fi
}

# inject CONTROLID  -> POST a raw HL7 message into the channel source (Channel Reader).
# destinationMetaDataId=1 is REQUIRED: the REST param binds to an empty set when omitted, which routes the
# message to ZERO destinations (source-only) instead of all. Pin it to our destination (metaDataId 1).
inject() {
  local cid="$1"
  printf 'MSH|^~\\&|DRIVER|DRV|SINK|RCV|20260710120000||ADT^A01|%s|P|2.3\rPID|1||12345^^^MRN||DOE^JOHN\r' "$cid" \
    | api -X POST "$API/channels/$CH/messages?destinationMetaDataId=1" -H 'Content-Type: text/plain' --data-binary @- >/dev/null
}

# wait until port count stops changing for two consecutive reads (drain/settle)
wait_settle() {
  local port="$1" prev=-1 now
  for _ in $(seq 1 20); do
    now="$(cnt "$port")"
    [ "$now" = "$prev" ] && [ "$now" != "0" ] && return 0
    prev="$now"; sleep 0.5
  done
}

login() {
  api -X POST "$API/users/_login" -H 'Accept: application/json' \
    --data-urlencode username=admin --data-urlencode password=admin -o /dev/null
  grep -qi jsessionid "$COOKIES" || { echo "!! login failed"; exit 1; }
}

# wait until BOTH the channel and the "Destination 1" child report STARTED (a stale channel-level STARTED
# alone can race ahead of the destination actually being deployed).
wait_started() {
  for _ in $(seq 1 30); do
    local n
    n="$(api -H 'Accept: application/json' "$API/channels/statuses" \
         | python -c "import sys,json
try: d=json.load(sys.stdin)['list']['dashboardStatus']
except Exception: print(0); raise SystemExit
ok = d['state']=='STARTED' and any(c['name']=='Destination 1' and c['state']=='STARTED' for c in d.get('childStatuses',{}).get('dashboardStatus',[]))
print(1 if ok else 0)" 2>/dev/null)"
    [ "$n" = "1" ] && return 0
    sleep 1
  done
  echo "   !! channel/destination did not reach STARTED"; return 1
}

# Clean (re)deploy: undeploy first so the destination connector is always freshly recreated (redeploying a
# stuck channel can leave it source-only), then wait for the destination to be live.
deploy() {
  api -X POST "$API/channels/$CH/_undeploy" -o /dev/null 2>/dev/null || true
  api -X POST "$API/channels/$CH/_deploy" -o /dev/null
  wait_started
}

echo "==> login"; login
echo "==> (re)create channel from fixture"
api -X DELETE "$API/channels?channelId=$CH" -o /dev/null
api -X POST "$API/channels" -H 'Content-Type: application/xml' --data-binary "@$FIXTURE" -o /dev/null
echo "==> deploy"; deploy

# ---- S1 baseline: both up -> primary gets it ----
echo ">> S1 baseline (both up)"
reset_sink; inject S1; sleep 1.5
assert_eq "6661 primary receives" "$(cnt 6661)" 1
assert_eq "6662 idle"             "$(cnt 6662)" 0

# ---- S2 failover: 6661 down -> 6662 gets it (connect-phase failover) ----
echo ">> S2 failover (6661 down)"
reset_sink; set_port '{"port":6661,"up":false}'; sleep 0.5
inject S2; sleep 1.5
assert_eq "6661 down, no receive" "$(cnt 6661)" 0
assert_eq "6662 failover receive" "$(cnt 6662)" 1

# ---- S3 auto-failback: 6661 back up + cooldown -> primary re-selected ----
echo ">> S3 auto-failback (6661 up, wait cooldown ${CD_WAIT}s)"
set_port '{"port":6661,"up":true}'; sleep "$CD_WAIT"
reset_sink; inject S3; sleep 1.5
assert_eq "6661 failback receive" "$(cnt 6661)" 1
assert_eq "6662 idle again"       "$(cnt 6662)" 0

# ---- S4 NACK no-failover: 6661 replies AE -> stays on 6661 (post-write, not a failover trigger) ----
echo ">> S4 NACK no-failover (6661 nack)"
deploy   # fresh health
reset_sink; set_port '{"port":6661,"mode":"nack"}'; sleep 0.5
inject S4; sleep 1.5
assert_eq "6661 received (NACK)"     "$(cnt 6661)" 1
assert_eq "6662 NOT failed over to"  "$(cnt 6662)" 0

# ---- S5 lost-ACK no cross-deliver: 6661 hangs -> retries 6661, never 6662 ----
echo ">> S5 lost-ACK no cross-deliver (6661 hang)"
deploy
reset_sink; set_port '{"port":6661,"mode":"hang"}'; sleep 0.5
inject S5; sleep 4
assert_ge "6661 received (>=1, retrying)" "$(cnt 6661)" 1
assert_eq "6662 NOT cross-delivered"      "$(cnt 6662)" 0
set_port '{"port":6661,"mode":"ack"}'; wait_settle 6661   # drain the stuck message

# ---- S6 all-down queue + drain: never dropped, drains when an endpoint returns ----
echo ">> S6 all-down queue + drain"
deploy
reset_sink
set_port '{"port":6661,"up":false}'; set_port '{"port":6662,"up":false}'; sleep 0.5
inject S6; sleep 3
assert_eq "6661 down, queued not sent" "$(cnt 6661)" 0
assert_eq "6662 down, queued not sent" "$(cnt 6662)" 0
set_port '{"port":6661,"up":true}'; wait_settle 6661
assert_ge "6661 drains queued msg"     "$(cnt 6661)" 1

echo ""
if [ "$FAILS" -eq 0 ]; then echo "==> ALL SCENARIOS PASSED"; else echo "==> $FAILS ASSERTION(S) FAILED"; fi
exit "$FAILS"
