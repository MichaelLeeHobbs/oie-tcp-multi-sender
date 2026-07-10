# Multi-Endpoint TCP Sender (OIE / Mirth connector)

A **destination connector** for [Open Integration Engine](https://openintegrationengine.org) (the open-source
fork of Mirth Connect) that sends to a **list of `host:port` endpoints** with a selectable strategy, instead
of the stock TCP Sender's single Remote Address/Port. Built for **HA replicas of one logical destination**
(e.g. an active/standby PACS/RIS pair), reusing all of the stock connector's transport (MLLP framing,
keep-alive, ACK handling, TLS).

> **Status:** v1. Built and **live-validated against a real OIE 4.5.2 container** — all failover scenarios
> green (baseline, connect-phase failover, auto-failback, NACK-no-failover, lost-ACK-no-cross-deliver,
> all-down-queue-and-drain), plus 35 unit tests; signed for the Administrator Launcher. Review the testing
> checklist before production use.

## Why this plugin?

**The short version:** it's by far the simplest, most convenient way to load-balance / fail over across HA
endpoints in Mirth/OIE — with a **clean UI any IT staffer can update in seconds**. Change an IP, hit save,
done. No config files, no redeploys, no extra infrastructure. And it's a **drop-in**: it *is* the stock TCP
Sender plus a list of endpoints, so MLLP framing, TLS, keep-alive, ACK handling, and queueing all work
exactly as before — you're extending your existing connector, not replacing it.

### Why not nginx / HAProxy / a TCP load balancer?

- **No GUI for the people who actually run it.** These are config-file tools. Updating an endpoint means
  editing a file, committing it, pushing to git, triggering a CI/CD pipeline to build a new image, and
  redeploying it to wherever your containers run — versus opening the channel and typing a new IP. For a
  cutover at 2 a.m., that difference is everything.
- **Another box to run and maintain.** A VIP/LB is one more moving part, one more failure point, one more
  thing to patch, monitor, and reason about — in front of an engine that already speaks TCP/MLLP natively.
- **It doesn't understand MLLP/ACK semantics.** A generic TCP balancer can't tell a delivered-and-ACKed
  message from a dropped one — it just moves bytes. This plugin fails over **only on connect-phase failures**
  (nothing was written), so it never re-sends a message a downstream may already have received. No duplicate
  HL7 orders or results.

### Why not just add multiple destinations to the channel?

You *can* build failover this way — the **response transformer** can inspect the ACK/NACK and the error to
tell *why* a send failed (down vs. rejected message vs. transient), and the queue can drive the retries. We
built exactly that years ago; it worked, but it was messy, and that mess is what the two problems below are
really about. (That was pre-AI, so it's fair to say a cleaner version might be more achievable today — but
these two don't go away):

- **The endpoints end up buried in code.** Choosing destinations from a transformer/code template puts the
  **IP addresses in a script**, not in visible connector config where IT can see and change them.
- **It's bespoke for every channel.** Each channel that needs this gets its own custom transformer +
  response-transformer wiring to build, test, and maintain — versus just selecting **"TCP Sender
  (Multi-Endpoint)"** from the connector dropdown and filling in a table.

This plugin turns "down → try the next, recovered → come back" into a **single, tested behavior you
configure**, not one you re-implement per channel. It also **auto-fails-back** when the primary recovers and
**tells you what it did** — it logs each endpoint going DOWN / RECOVERED and stamps which endpoint received
every message (in the response and the connector map), so there's no black box to guess at.

### Special case: PowerScribe 360 4.0 (and systems like it)

PS360 4.0 has two quirks this plugin is built around:

- It sends the TCP **response back to the last-connected session**, not the one that sent the message. That
  breaks HAProxy/nginx **health checks** — the balancer's probe connection steals the ACK — and misroutes
  responses whenever more than one connection is open.
- It's simply **more reliable with Keep-Connection-Open** — one stable, long-lived socket.

The **Sticky** strategy exists exactly for this: one endpoint, one persistent connection, no health-probing
and no concurrent sessions to confuse it — while still failing over if that endpoint goes down.

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
- **Same duplicate semantics as the stock TCP Sender — no new risk.** Mirth TCP/MLLP delivery is
  at-least-once: after a *lost ACK* the queue retries, so a receiver can see a message twice — but that's
  equally true of the plain single-destination TCP Sender, so if your receivers already tolerate that, they
  need nothing extra here. This plugin adds **no cross-endpoint duplication**: it **fails over only on
  connect-phase failures** (the socket never connected, so nothing was written), and on any post-write outcome
  (lost ACK, write/IO error) it does **not** move to another endpoint — it returns the message so the queue
  retries the **same** endpoint, exactly as the stock connector does.
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
An endpoint table (**host, port, enabled, priority, notes**) + **strategy** (Failover / Sticky) + **failure
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
