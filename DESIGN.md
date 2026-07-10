# Multi-Endpoint TCP Sender — design (draft for review)

An OIE/Mirth **destination connector plugin**: a TCP Sender that takes a *list* of `host:port` endpoints and
a user-selected **strategy**, instead of the stock connector's single Remote Address/Port. The endpoint list
is edited in the Administrator GUI (so IT/ops can change endpoints without touching code).

## Goal / non-goals
- **Goal:** failover / round-robin / sticky delivery across interchangeable endpoints of *one logical
  destination* (HA replicas / a VIP's members), with GUI-editable config and full reuse of the stock TCP
  transport (MLLP framing, keep-alive, ACK handling, TLS).
- **Non-goals:** broadcast/fan-out to *different* systems (that's just multiple Mirth destinations); being a
  general load balancer for unrelated systems. Endpoints are assumed interchangeable.

## Architecture (verified against the engine source)
Subclass the stock connector — **reuse, don't fork**:
- `MultiEndpointTcpDispatcherProperties extends TcpDispatcherProperties` — adds `List<Endpoint>` + `strategy`
  + health params. **`clone()` must deep-copy the endpoint list** (per-message clone is what makes per-call
  host/port mutation thread-safe). Namespace under **`com.mirth.connect.connectors.tcpmulti`** so XStream
  auto-whitelists it.
- `MultiEndpointTcpDispatcher extends TcpDispatcher` — override **only** `send()` (select endpoint, set
  `remoteAddress`/`remotePort` on the passed-in props, call `super.send()`; for failover, loop to the next
  endpoint on a transport-level `ERROR`) and `replaceConnectorProperties()` (template-replace the endpoint
  list too, then `super`). Everything else (`onDeploy/onStart/...`) delegates to `super`.
- `MultiEndpointTcpSender extends ConnectorSettingsPanel` — the GUI: an endpoint table + a strategy dropdown.
  `getConnectorName()` must equal the properties' `getName()` and the descriptor `<name>` exactly.
- Register via a `destination.xml` `ConnectorMetaData` (unique `<name>` e.g. "TCP Sender (Multi-Endpoint)",
  `type=DESTINATION`, `protocol=TCP` to reuse the stock SSL config lookup). Ship client/shared/server jars in
  an `extensions/<name>/` zip (the `oie-plugin-development` skill scaffolds this Maven layout).
- Migration no-op methods (`migrate3_x_y`) are required or channel import/upgrade breaks.

## Strategies (user selects per connector)
| Strategy | Behavior | Connection model |
|---|---|---|
| **Failover** | Ordered by `priority`; always use the highest-priority reachable endpoint; on transport failure advance to the next; **auto-fails-back** to a higher-priority endpoint once it recovers (this is the default and the whole point vs. Sticky). | One active connection at a time. |
| **Sticky** | Pin to the current endpoint until it fails, then pin to another (no priority, no auto-failback). The keep-open / ACK-quirky-friendly mode (e.g. PS360). | One active connection. |
| **Round-robin** (± weights) | Rotate per message across endpoints. | Multiple connections — **do not use with per-session-ACK systems** (see below). |
| *(optional, later)* **Random / weighted-random** | Pick per message; no shared cursor → clustering-friendly. | Multiple connections. |

## Failure semantics (critical — corrected after review)
- **Fail over to another endpoint ONLY on connect-phase (pre-write) failures** (connect refused, blank
  address, invalid port). Once bytes are written, a timeout/error does **not** prove non-delivery — the
  endpoint may have processed the message and only the ACK was lost. Moving *that* message to another HA
  replica = **duplicate HL7 order/result** (a patient-safety bug). So **post-write failures (ACK-read
  timeout, write/IO error) do NOT fail over** — they return unchanged and the engine queue retries the *same*
  endpoint.
- **An MLLP NACK is NOT a failover trigger** — the stock connector returns any received response (ACK *or*
  NACK) as `SENT`; NACK is judged later by the response validator, outside our `send()`. So NACKs never reach
  the failover path.
- **Receivers must be idempotent / tolerate duplicates** (at-least-once; lost-ACK duplicates are inherent to
  MLLP — we avoid *amplifying* them across endpoints, but the same-endpoint queue retry can still duplicate).
- **All endpoints unreachable → queue + alert, never drop** (requires the destination queue enabled).

## Health / circuit-breaking
- **Passive** health (learned from real sends): mark an endpoint down after **N consecutive transport
  failures**; a **cooldown** before it's eligible again; flap-dampening so it doesn't oscillate.
- State lives in memory (per connector). **Not cluster-safe** — health/cursor are per-engine, not shared. OIE
  runs single-node in practice, so this is acceptable for v1; call it out in the README so no one deploys it
  behind a clustered assumption.

## PS360 / ACK-quirk note
Some HL7 systems (PS360 4.0) send the ACK to the *last-connected* session, not the sending one. That means
**multiple concurrent connections corrupt ACK routing** → false timeouts → false failover. Mitigation:
use **Sticky** — but Sticky only truly gives one connection if the **destination queue is set to 1 thread**:
the stock socket cache is keyed per queue thread, so N threads open N sockets to the *same* sticky endpoint,
re-creating the ACK corruption. **`checkProperties` enforces queue=1 thread when strategy=STICKY.** Also, the
stock socket-close helpers are `private`, so the subclass can't force-close a stale socket on switch — another
reason strict close-before-open across multiple live endpoints is **out of v1 scope** (would need a fork).

## Observability
- Log the **chosen endpoint** per message and every failover (endpoint + reason), via the channel logger.
- Per-endpoint success/failure counters + current up/down; surface "all endpoints down" as an alert.

## MVP scope
All three strategies — **Failover** (priority + auto-failback), **Sticky** (pin until failure), and
**Round-robin** (with optional `weight`) — plus: GUI endpoint table (host/port/enabled/priority/weight) +
strategy dropdown; passive health (N-fail threshold + cooldown); transport-failure-only failover; all-down →
queue+alert; the chosen endpoint + every failover logged. **Out of v1:** pure random strategy, shared
cluster health (single-node only), strict close-before-open across multiple live endpoints (needs a fork).

## Decisions (locked 2026-07-08)
1. **Endpoints are HA replicas** of one logical destination — interchangeable. (Not a general LB for
   different systems.)
2. **Single-node only. The plugin is NOT cluster-safe** — health/cursor state is in-memory per engine. In
   practice OIE runs single-node (clustering isn't really supported), so this is acceptable; state it plainly
   in the docs/README.
3. **All three strategies in v1:** Failover, Sticky, Round-robin.
4. **Failover auto-fails-back by default** (reclaims the highest-priority endpoint when it recovers). Users
   who want "don't move back" use **Sticky** — that's the distinction between the two modes.
5. **Endpoint fields: `host`, `port`, `enabled`, `priority` (failover ordering), `weight` (weighted
   round-robin).** Each field applies to the strategies where it's meaningful; ignored otherwise.

## Gotchas checklist (from the source review)
- `clone()` returns the subclass type and **deep-copies `endpoints`** (else it slices to the parent and drops
  them). · `Endpoint` needs `equals()`/`hashCode()` (parent uses reflection equals; GUI dirty-check relies on
  it). · Namespace `com.mirth.connect.connectors.tcpmulti` (XStream whitelist). · Unique `<name>` matched
  across descriptor + `getName()` + `getConnectorName()`. · `getProtocol()="TCP"` to reuse SSL config. ·
  **Do NOT re-declare the inherited `migrate3_x_y` methods** — they carry real logic; only add a *new* migrate
  for the version that introduces our fields (call `super` first). · MLLP/transmission mode is inherited via
  `super.send()`. · Set connector `retryCount=0` (plugin + queue own retries).
