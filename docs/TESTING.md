# Testing against a real OIE (Docker)

The plugin subclasses engine internals, so the real verification is running it inside OIE. Everything here
uses the official image **`openintegrationengine/engine`** (Java 17; admin console on `8443`).

## 0. Where the jars come from (build prerequisite)
The `com.mirth.connect:*` jars — including the `tcp-*` **extension** jars we subclass, which are not on
Maven — are pulled from the OIE image into your local Maven repo:

```bash
scripts/install-oie-artifacts.sh openintegrationengine/engine:latest 4.5.2   # version must match <mc.version>
mvn clean package                                                            # -> package/target/tcpmulti-<v>.zip
```

## 1. Quick smoke test (does it even load?)
```bash
scripts/smoke-test.sh openintegrationengine/engine:latest
```
Starts OIE with the built zip mounted at `/opt/engine/custom-extensions/` (the entrypoint auto-installs any
`.zip` there on boot) and fails if the connector doesn't load. This is what CI runs.

## 2. Interactive / manual testing
```bash
docker run --name oie -d -p 8443:8443 \
  -v "$PWD/package/target":/opt/engine/custom-extensions:ro \
  openintegrationengine/engine:latest
```
Open `https://localhost:8443` (accept the self-signed cert), log in (default `admin`/`admin`), add a
destination to a channel, and choose **"TCP Sender (Multi-Endpoint)"**. Configure two endpoints, strategy
**Failover**, then:
- **Connector settings that matter:** set the destination **queue ON**, **queue threads = 1** (required for
  Sticky), and connector **retry count = 0** (this plugin + the queue own retries).

## 3. End-to-end failover test (two HA sink endpoints)
Use the compose stack, which brings up OIE plus two MLLP sinks (simple TCP listeners that ACK):

```bash
docker compose -f docker/oie-test.compose.yaml up -d
```

Then drive a channel via your **`integration-engine-api`** client (it already generates an
`open-integration-engine` client):

```ts
import { createClient } from 'integration-engine-api/open-integration-engine/v4.5.2';
const oie = createClient({ baseUrl: 'https://localhost:8443/api', username: 'admin', password: 'admin' });
// 1. import a channel whose destination is "TCP Sender (Multi-Endpoint)" with endpoints sink-a:6661, sink-b:6661
// 2. deploy it, send a test HL7 message
// 3. `docker stop sink-a` -> next message must land on sink-b (failover); restart -> auto-failback
```

### Scenarios to verify (the acceptance bar before production)
| Scenario | Expected |
|---|---|
| Kill the primary sink (connection refused) | next message fails over to the secondary; primary auto-recovers → traffic returns |
| Sink returns a **NACK** | **no** failover — the NACK flows to the channel's response handling |
| Sink accepts then never ACKs (lost ACK) | **no** cross-endpoint move; the engine queue retries the **same** sink (receivers must be idempotent) |
| All sinks down | message goes to the **queue** (not dropped); drains on recovery |
| Sticky + queue threads > 1 | channel deploy is **rejected** (single-connection guarantee) |

> **Lock the failure classification here.** The connect-phase whitelist matches error-message text
> (`ConnectException`, ...). This is the place to confirm the exact strings OIE produces for connection-refused
> vs. ACK-timeout, and tighten `FailureClassifier` / the smoke-test grep to the real values.

## Notes
- Not cluster-safe (in-memory health) — run single-node.
- The two sinks in the compose stand in for HA replicas of one logical destination; they are interchangeable.
