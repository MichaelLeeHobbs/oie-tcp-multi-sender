# Multi-Endpoint TCP Sender (OIE / Mirth connector)

A **destination connector** for [Open Integration Engine](https://openintegrationengine.org) (the open-source
fork of Mirth Connect) that sends to a **list of `host:port` endpoints** with a selectable strategy, instead
of the stock TCP Sender's single Remote Address/Port. Built for **HA replicas of one logical destination**
(e.g. an active/standby PACS/RIS pair), reusing all of the stock connector's transport (MLLP framing,
keep-alive, ACK handling, TLS).

> **Status:** v1 in development. AI-assisted and human-reviewed against the engine source; **not yet
> compiled/tested against a running OIE** — see CI and the testing checklist before any production use.

## Strategies
- **Failover** — endpoints ordered by `priority`; always uses the highest-priority reachable endpoint and
  **auto-fails-back** when a higher-priority one recovers. One active connection.
- **Sticky** — pins to one endpoint and stays until it fails, then pins to another (no priority, no
  failback). This is the mode for systems whose keep-alive/ACK behavior needs a single stable connection.

## Why not Round-Robin (deliberate omission)
Round-robin was considered and **intentionally left out of v1**. Three reasons, all from how the engine and
HL7/MLLP actually behave:
1. **It fights keep-alive.** The engine caches one socket per `host:port` *per queue thread*; rotating
   endpoints holds N idle persistent connections instead of one, which remote servers often drop.
2. **It's unsafe for per-session-ACK systems.** Some HL7 endpoints (e.g. PowerScribe 360 4.0) send the ACK
   to the *last-connected* session rather than the one that sent the message. Multiple concurrent connections
   (which round-robin needs) then misroute ACKs → false timeouts. Sticky/Failover keep a single connection
   and avoid this.
3. **Failover + Sticky already cover the goal** (HA replicas). Round-robin is load-spreading, which these
   deployments don't need.
If real demand appears, RR can be added later as an opt-in — with these caveats documented and a
guard that it isn't selected for per-session-ACK endpoints.

## Operational requirements (read before deploying)
These come from the transport's real behavior — not optional niceties:
- **Receivers must be idempotent / tolerate duplicates.** Delivery is at-least-once (inherent to MLLP). The
  connector **fails over only on connect-phase failures** (nothing was written), so it never cross-delivers a
  message that may already have been received; but the engine's normal queue retry can still redeliver to the
  **same** endpoint after a lost ACK.
- **Sticky requires the destination queue set to 1 thread** (otherwise the engine opens one socket per thread,
  defeating the single-connection guarantee). The connector rejects a Sticky config with >1 queue thread.
- **Set the connector `retryCount = 0`** and **enable the destination queue** — this connector + the queue own
  retries/failover; the "never drop, queue instead" behavior depends on the queue being on.
- **Not cluster-safe.** Health/selection state is in-memory per engine (OIE runs single-node in practice). Do
  not deploy behind an assumption of shared cross-node state.
- **Turn the logger up to actually see failover.** The connector logs endpoint **state transitions** at `WARN`
  — `endpoint[i] host:port marked DOWN … failing over` and `endpoint[i] … RECOVERED` — once per transition,
  not per message (a sustained outage under load won't flood the log; per-message failovers are `DEBUG`). But
  OIE ships with `rootLogger = ERROR`, which suppresses these. To see them, add to `conf/log4j2.properties`:
  ```properties
  logger.tcpmulti.name = com.mirth.connect.connectors.tcpmulti
  logger.tcpmulti.level = INFO
  ```
- **Failover latency ≈ `responseTimeout` per dead endpoint** (the engine has no separate connect timeout), so
  keep `responseTimeout` modest.
- **With Keep-Connection-Open, set `sendTimeout > 0`** so idle sockets are reaped.

## Which endpoint received a message?
On a successful send the connector records the endpoint that actually received the message, in two places:
- the response **status message** — shown in the message browser's *Response* view as
  `SENT: Message successfully sent. [sent to host:port]`;
- the **connector map** key `tcpmultiEndpoint` (= `host:port`) — visible in the *Connector Map* view and
  usable in downstream filters/transformers/mappings.

## Configuration
An endpoint table (**host, port, enabled, priority**) + **strategy** (Failover / Sticky) + **failure
threshold** (consecutive connect failures before an endpoint is marked down) + **cooldown** (how long it
stays down before a single probe). All other TCP settings (transmission mode / MLLP, TLS, timeouts, queue)
are the stock TCP sender's. Every field has a hover tooltip in the Administrator.

## Build & install
Maven multi-module (client / shared / server) producing an OIE **extension** (`destination.xml` + jars),
zipped into `extensions/tcpmulti/`. Building requires the OIE engine artifacts on the classpath — see
[`docs/BUILD.md`](docs/BUILD.md). CI builds and tests against pinned OIE version(s).

## Design docs
- [`DESIGN.md`](DESIGN.md) — what & why (decisions, strategies, failure semantics).
- [`SPEC.md`](SPEC.md) — the build-facing spec (classes, algorithms, acceptance criteria), rewritten after
  three independent source-grounded reviews.

## License & credit
MPL-2.0. Thanks to **[@pacmano1](https://github.com/pacmano1)** whose OIE plugin-development guide informed
the build, and to the Open Integration Engine project.
