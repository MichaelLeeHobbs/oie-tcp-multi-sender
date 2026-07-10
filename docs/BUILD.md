# Building the Multi-Endpoint TCP Sender

This is a four-module Maven project (`shared`, `server`, `client`, `package`) that produces an
installable OIE **extension** zip.

## Prerequisites
- **JDK 17** (the OIE server runs on Java 17)
- **Maven 3.8+**
- Network access to the OIE/Mirth Maven repository for the `provided`-scope engine artifacts.

## Where the OIE jars come from
The build resolves the engine artifacts from the community Mirth Connect repository declared in the
root `pom.xml`:

```xml
<repository>
  <id>mirth-libs</id>
  <url>https://repo.repsy.io/mvn/kpalang/mirthconnect</url>
</repository>
```

It needs these coordinates (all `provided` — never bundled):

| artifact | why |
|---|---|
| `com.mirth.connect:mirth-server` | `TcpDispatcher`, `TcpDispatcherProperties`, `TemplateValueReplacer`, transmission-mode SPI |
| `com.mirth.connect:donkey-server` | `DestinationConnector`, `Response`, `Status`, `ConnectorProperties`, `DonkeyElement` |
| `com.mirth.connect:mirth-client` | `ConnectorSettingsPanel`, `Frame`, `Mirth*` Swing components |
| `com.mirth.connect:mirth-client-core` | shared client/model types |

> **Assumption to verify per release:** the stock TCP connector classes (`com.mirth.connect.connectors.tcp.*`)
> are assumed to be inside the published `mirth-server` (and `mirth-client` for `TcpSender`) artifacts,
> as they are in the engine's Ant build. If a given OIE release publishes the connectors as separate
> artifacts, add those dependencies to `shared/pom.xml`, `server/pom.xml`, and `client/pom.xml`.

### If the artifacts aren't in a public repo
Build the engine yourself and install the jars locally:

```bash
# from an OIE engine checkout (Ant build)
mvn install:install-file -Dfile=server/mirth-server.jar   -DgroupId=com.mirth.connect -DartifactId=mirth-server      -Dversion=4.5.2 -Dpackaging=jar
mvn install:install-file -Dfile=donkey/donkey-server.jar  -DgroupId=com.mirth.connect -DartifactId=donkey-server     -Dversion=4.5.2 -Dpackaging=jar
mvn install:install-file -Dfile=client/mirth-client.jar   -DgroupId=com.mirth.connect -DartifactId=mirth-client      -Dversion=4.5.2 -Dpackaging=jar
mvn install:install-file -Dfile=client/mirth-client-core.jar -DgroupId=com.mirth.connect -DartifactId=mirth-client-core -Dversion=4.5.2 -Dpackaging=jar
```

## Build

```bash
# Full build + unit tests + extension zip
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Target a specific OIE version
mvn clean package -Dmc.version=4.5.2

# Optional JAR signing
mvn clean package -Psigning -Dsigning.keystore=... -Dsigning.alias=... -Dsigning.storepass=...
```

The installable artifact is **`package/target/tcpmulti-<version>.zip`**, whose internal layout is:

```
tcpmulti/
├── tcpmulti-server-<version>.jar
├── tcpmulti-shared-<version>.jar
├── tcpmulti-client-<version>.jar
└── destination.xml
```

## Install
1. Copy the zip into the OIE `extensions/` directory (or upload via the Administrator's Extension
   Manager), so it unpacks to `extensions/tcpmulti/`.
2. **Restart the OIE server** — connectors are loaded at startup; there is no hot-reload.
3. In a channel, add a destination and choose **"TCP Sender (Multi-Endpoint)"** as the connector type.

## Tests

```bash
mvn test                       # unit tests only (fast, no server needed)
mvn verify -Dtcpmulti.it=true  # also enable the integration scenarios (see below)
```

- **Unit tests** cover the safety-critical logic that needs no live server: failover ordering +
  auto-failback, sticky persistence/re-pick, health threshold/cooldown, atomic health under
  concurrency, half-open single-prober, failure classification (`SENT`/connect-phase/post-write),
  empty-candidate response, clone deep-copy, `equals`, and an export/import round-trip.
- **Integration scenarios** (`IntegrationScenariosIT`) exercise the full `send()` path and therefore
  need a deployed OIE channel. They are gated behind `-Dtcpmulti.it=true` **and** a harness
  (`-Dtcpmulti.oie.home=...` plus a running OIE); without it they self-skip. Wiring a deployed channel
  around the provided `MllpSink` helper is the remaining work. These are the CI substitute for a
  compile-time contract and should be run against **each supported OIE release**.
