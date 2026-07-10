# Building the Multi-Endpoint TCP Sender

This is a four-module Maven project (`shared`, `server`, `client`, `package`) that produces an
installable OIE **extension** zip.

## Prerequisites
- **JDK 17** (the OIE server runs on Java 17)
- **Maven 3.8+**
- **Docker** — the engine jars are extracted from the official OIE image (see below).

## Where the OIE jars come from
OIE/Mirth do **not** publish a complete Maven artifact set. The core jars exist on a community repo (as
Mirth Connect 4.5.x), **but the TCP connector we subclass (`com.mirth.connect.connectors.tcp.*`) is a
separate *extension* jar that is on no Maven repo.** So the build sources every engine jar — core **and**
the `tcp-*` extension jars — from the official **OIE Docker image**, at the exact OIE version, into your
local Maven repo:

```bash
scripts/install-oie-artifacts.sh openintegrationengine/engine:latest 4.5.2
#                                 ^ image                              ^ must equal <mc.version> in pom.xml
```

That installs, as `provided`-scope (`com.mirth.connect:<id>:<mc.version>`):

| artifact | provides |
|---|---|
| `mirth-server` | `TemplateValueReplacer`, transmission-mode / model types |
| `donkey-server` | `DestinationConnector`, `Response`, `Status`, `ConnectorProperties`, `DonkeyElement` |
| `mirth-client`, `mirth-client-core` | `ConnectorSettingsPanel`, Swing/model types |
| **`tcp-server`** | `TcpDispatcher` (our server superclass) |
| **`tcp-shared`** | `TcpDispatcherProperties` (our properties superclass) |
| `tcp-client` | stock TCP client panels (installed for completeness; the GUI uses the transmission-mode SPI, not this) |

> If the script reports a missing jar, the image layout differs — run
> `find <extracted>/opt/engine -name '*.jar'` and adjust the name map in the script. The community core
> repo (`repo.repsy.io/mvn/kpalang/mirthconnect`, in `pom.xml`) is a partial fallback for the **core** jars
> only; it does not contain the `tcp-*` extension jars.

## Build

```bash
# Full build + unit tests + extension zip
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Target a specific OIE version
mvn clean package -Dmc.version=4.5.2

# JAR signing (required to load in the Administrator Launcher — see below)
mvn clean package -Psigning \
  -Dsigning.keystore=certificate/keystore.jks -Dsigning.alias=selfsigned -Dsigning.storepass=storepass
```

## Signing for the Administrator Launcher

The Mirth/OIE **Administrator Launcher** verifies that every extension jar it downloads from the server
is code-signed. An unsigned build fails to open with:

```
Error verifying entry "META-INF/MANIFEST.MF" in JAR file tcpmulti-shared-1.0.0.jar
… has no code signers.
```

(The **server** loads unsigned jars fine — signing only matters for the Administrator GUI. Default `mvn
package` is unsigned, which is correct for CI, unit tests, and the Docker smoke/failover tests.)

Stock OIE signs its jars with a self-signed cert too, so a self-signed cert is enough — the Launcher just
has to be told to accept it.

**1. Generate a self-signed dev keystore (once).** Mirth requires the proprietary **JKS** format:

```bash
keytool -genkeypair -keyalg RSA -keysize 2048 -alias selfsigned \
  -keystore certificate/keystore.jks -storepass storepass -keypass storepass \
  -validity 3650 -storetype JKS \
  -dname "CN=OIE Multi-Endpoint TCP Sender (dev self-signed), OU=dev, O=oie-contrib, C=US"
```

A dev keystore is committed at `certificate/keystore.jks` (password `storepass`) so `-Psigning` works out of
the box. It is a throwaway self-signed cert — **not** a release-signing key.

**2. Build signed** (command above).

**3. Point the Administrator Launcher at self-signed jars.** Add the `-k` (`--allow-self-signed`) flag:
- **Windows:** append ` -k` to the *Target* field of the Administrator Launcher shortcut.
- **macOS/Linux:** `java -jar mirth-client-launcher.jar -k`

Then connect to the server and open the channel — the "TCP Sender (Multi-Endpoint)" destination will load.

> For a real OIE release the connector would be signed with OIE's CA cert (add a `<tsa>` back and pass
> `-Dsigning.keystore` pointing at that keystore); end users then need no `-k` flag.

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
