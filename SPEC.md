# Multi-Endpoint TCP Sender — build spec (v2, post-review)

Precise "how" for [`DESIGN.md`](./DESIGN.md), **rewritten after three independent source-grounded reviews**.
Java 17, OIE destination-connector plugin, namespace **`com.mirth.connect.connectors.tcpmulti`** (XStream
wildcard auto-whitelists `com.mirth.connect.connectors.**` — a private package would be rejected, so this
namespace is *required*).

> **Reviewer consensus:** the subclass-and-delegate architecture is sound and confirmed against source, but
> the original spec had a **patient-safety defect** (failover on lost ACK → duplicate HL7 delivery) and two
> harmful doc errors (migration no-ops, clone rationale). This v2 fixes all of them.

## Classes (unchanged skeleton — confirmed sound)
- `MultiEndpointTcpDispatcherProperties extends TcpDispatcherProperties`
- `MultiEndpointTcpDispatcher extends TcpDispatcher` — override **only** `send()` + `replaceConnectorProperties()`
- `MultiEndpointTcpSender extends ConnectorSettingsPanel`
- `destination.xml` descriptor. Everything else (socket/MLLP/ACK/keep-alive/TLS/lifecycle) inherited.

## Data model
```
enum Strategy { FAILOVER, STICKY, ROUND_ROBIN }
class Endpoint { String host; String port; boolean enabled=true; int priority=0; int weight=1;
                 /* MUST implement equals()/hashCode() — parent uses reflectionEquals + GUI dirty-check */ }
```
Properties add: `List<Endpoint> endpoints`, `Strategy strategy=FAILOVER`, `int failureThreshold=3`,
`long cooldownMillis=30_000`. Stock `remoteAddress`/`remotePort` are per-send scratch (hidden in GUI).

**`clone()` (corrected rationale):** override to `new MultiEndpointTcpDispatcherProperties(this)`; the copy
constructor calls `super(props)` then **deep-copies `endpoints`** (list + each `Endpoint`). Per-call host/port
mutation is safe because the engine hands `send()` a **per-message clone** (verified:
`DestinationConnector.process()` clones at `:493`, queue path at `:685`/`:677-681`) and host/port are scalar
copies — *not* because the list is deep-copied. Deep-copy the list anyway, or `clone()` slices to the parent
type and silently drops every endpoint.

**Migrations (corrected — the original spec was WRONG):** **do NOT re-declare the `migrate3_x_y` methods.**
They are inherited with real logic (`TcpDispatcherProperties.migrate3_1_0` maps `processHL7ACK`→
`validateResponse`, `:309-319`); an empty override destroys that migration. Only add a *new* migrate method
for the version that introduces our fields, and call `super` first.

## Failure classification & failover policy  ← the correctness core (rewritten)
Verified from `TcpDispatcher.send()` response paths:

| Outcome | Status | Failover? |
|---|---|---|
| Any bytes received (ACK **or** NACK), or `ignoreResponse` | `SENT` | **No — success.** (NACK is judged later, outside `send()`, by the response validator.) |
| Connect refused / blank address / invalid port (**pre-write**) | `QUEUED` | **Yes — safe to try next endpoint (nothing was written).** |
| Write/IO error, ACK-read timeout, other read error (**post-write**) | `QUEUED`/`ERROR` | **No — return unchanged; the engine queue retries the *same* endpoint.** |

**Rule:** `SENT` → success. Non-`SENT` → **only fail over to a different endpoint on a *connect-phase*
failure** (detect via a small whitelist of pre-write signatures in `responseError`/`responseStatusMessage`:
`ConnectException`, `"Remote address is blank"`, `"Remote port is invalid"`). **Anything else that isn't
plainly connect-phase → do NOT move; return the `Response` unchanged** so the engine queue retries the same
endpoint. Default-safe: if a signature isn't recognized, we *stay* (no cross-endpoint move).

**Why (patient safety):** once bytes are written, a `QUEUED`/timeout does **not** mean the endpoint didn't
receive the message — it may have processed it and only the ACK was lost. Moving that message to another HA
replica = **duplicate HL7 order/result**. So we never fail over post-write. The residual at-least-once
duplicate (same endpoint, via the stock queue) is inherent to MLLP — **document that receivers must be
idempotent / tolerate duplicates.** `ignoreResponse=true` always returns `SENT`, so a silently-black-holing
endpoint won't fail over — documented limitation.

## `send()` algorithm (rewritten)
```
Response send(props, msg):
  if Thread.interrupted(): throw InterruptedException      // stop failing over during connector halt
  candidates = select(props.strategy)                      // ordered, strategy-specific, UP endpoints first
  if candidates.isEmpty():
     return new Response(queueEnabled ? QUEUED : ERROR, "no enabled endpoints")   // NEVER return null
  seen = {}
  for ep in candidates:
     if ep.index in seen: continue                         // record each endpoint's health at most once/send
     seen.add(ep.index)
     props.setRemoteAddress(resolve(ep.host)); props.setRemotePort(resolve(ep.port))
     Response r = super.send(props, msg)
     if r.status == SENT:
        health.recordSuccess(ep.index); log("sent", ep); return r
     if isConnectPhaseFailure(r):                          // pre-write → safe to move
        health.recordFailure(ep.index); log("failover", ep, r); continue
     else:
        return r                                           // post-write → DO NOT move; queue retries same ep
     if Thread.interrupted(): throw InterruptedException
  log(ERROR, "all endpoints unreachable (connect-phase)")
  return lastConnectFailureResponse                        // QUEUED/ERROR → engine queues
```
**Retry ownership:** this makes **one connect-phase pass**; set the connector's `retryCount=0` and let the
engine *queue* own repeats (documented). Health is recorded **once per endpoint per `send()`** so one message's
retries can't trip the breaker (the old bug: N retries counted as N "consecutive failures").

## Health state (corrected)
- **Keyed by endpoint list-index, NOT resolved host:port** — survives `${velocity}` in host/port and bounds
  the map (host:port keying leaked unboundedly and made health meaningless when velocity varies per message).
- Per-endpoint state is a single **atomic unit** (`AtomicReference<Health{failures,downUntil}>` updated by CAS,
  or `synchronized` per entry) — not two independent atomics (which race into "down with 0 failures").
- `isUp(i, now) = enabled && now >= downUntil`. `recordFailure`: `if (++failures >= threshold) downUntil =
  now + cooldownMillis`. `recordSuccess`: reset.
- **Half-open probe gate:** when cooldown expires, CAS a per-endpoint `probing` flag so **exactly one** thread
  re-probes; others use the fallback until it resolves (else every queue thread stampedes the dead host each
  cooldown). Document: auto-failback sacrifices one real message per cooldown as the probe.

## Selection (per strategy; state keyed by index; thread-safe)
- **FAILOVER:** sort enabled by `(priority asc, index asc)`; yield up-first. Restarting from top each message
  *is* auto-failback. One active endpoint.
- **STICKY:** `AtomicReference<Integer> current`; if `current` still up, use it; else CAS to the first up
  endpoint. On connect-phase failure, CAS-clear and re-pick. **Hard constraint:** true single-connection
  requires the **destination queue = 1 thread** (socket cache is keyed per queue thread, so N threads = N
  sockets to the sticky endpoint → reintroduces the PS360 ACK corruption). **`checkProperties` must reject
  STICKY unless queue threads = 1**, and the README must state it.
- **ROUND_ROBIN (weighted):** compute the **up-set first**, then rotate with an `AtomicInteger` cursor over a
  weight-expanded list of the up-set (lock-free; do NOT use mutable smooth-WRR — it races). Guard `weight<=0`
  in the dispatcher (clamp to skip/1), not just the GUI (imported channels bypass the GUI). RR holds one
  keep-open socket **per endpoint per thread** — documented; unsafe for per-session-ACK systems.

## GUI (`MultiEndpointTcpSender`) — flagged as the biggest effort
Endpoint table (host/port/enabled/priority/weight, add/remove/reorder) + strategy dropdown + failure
threshold + cooldown. **Reuse of the stock TCP sender's transmission-mode/TLS/timeout sub-panels may be
blocked** (several `TcpSender` sub-components are likely package-private) — budget for possibly copying the
panel. `getConnectorName()` == properties `getName()` == descriptor `<name>`. `checkProperties`: ≥1 enabled
endpoint; ports resolve 1..65535; weight≥1; threshold≥1; cooldown≥0; **STICKY ⇒ queue=1 thread**; warn if the
destination **queue is disabled** (the never-drop guarantee depends on it — `fixStatus` turns `QUEUED`→`ERROR`
when queue is off).

## Descriptor
`<connectorMetaData>`: unique `<name>TCP Sender (Multi-Endpoint)</name>`, the three `com.mirth.connect.connectors.tcpmulti.*`
classes, `<protocol>TCP</protocol>` (reuses stock TLS config lookup — verified), `<type>DESTINATION</type>`,
`<library>` entries. Decide on `<apiProvider>` (stock registers `TcpConnectorServletInterface` for
Test-Connection/TLS GUI endpoints — reuse it or drop those affordances).

## Operational constraints to DOCUMENT (from the reviews — these are real)
- **Not cluster-safe** (in-memory per-engine state). OIE is effectively single-node; state it in the README.
  *(Upstream consideration: expose health as a small pluggable interface, default in-memory, so a clustered
  future isn't blocked. Optional for v1.)*
- **Sticky/PS360 ⇒ destination queue = 1 thread** (enforced).
- **Failover latency:** connect uses `responseTimeout` (no separate connect timeout), so each dead endpoint
  costs ~`responseTimeout` before moving on — recommend a low `responseTimeout` (tension: it also shortens the
  legitimate ACK wait).
- **Keep-open ⇒ set `sendTimeout > 0`** so idle sockets to non-current endpoints get reaped (else RR holds
  N persistent idle sockets per thread).
- **Receivers must be idempotent** (at-least-once; lost-ACK duplicates are inherent to MLLP).
- **Set connector `retryCount = 0`** (this plugin + the queue own ret/failover; don't multiply).

## Acceptance criteria
Unit: FAILOVER order + auto-failback; STICKY persistence + single-thread rejection in `checkProperties`;
weighted RR distribution over the up-set + `weight<=0` guard; health threshold/cooldown; **atomic health under
concurrency (no "down with 0 failures")**; half-open (one prober); **`SENT`→success, connect-phase→failover,
post-write→no-move**; empty candidates → non-null `Response`; interrupt → stops; `clone()` deep-copies
endpoints; `Endpoint.equals/hashCode`. Serialization: **export → import → `equals` round-trip** (properties
class). Integration (local sink): connect-refused → next endpoint; killed-after-connect → same endpoint via
queue (no cross-endpoint duplicate); all-down → queue (queue enabled); NACK → no failover. CI: **version-pinned
integration test against each supported OIE release** (the substitute for a compile-time contract — subclassing
couples to `TcpDispatcher` internals).
```
```
## License / contribution
New files use the **OIE** MPL header convention (not a copied "Mirth Corporation" header — confirm OIE's).
MPL-2.0. README states the constraints above.
