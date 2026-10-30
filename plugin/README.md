# HVMTools cluster plugin (development preview)

A native Groovy/JVM plugin targeting **HPE VM Essentials 9.1.0.1**. It adds an
**HVMTools** cluster tab with inventory preview and Excel download. The Python CLI
in the repository root remains unchanged.

**Status:** compiled and tested offline with fictional inventory, including
workbook comparisons against the Python exporter. Installation, API session
authentication, CSRF integration, permissions and download behavior still need
validation on an actual 9.1.0.1 appliance. Do not treat this as live-certified.

## Build environment

Required on the development machine:

- **JDK 17** (including `javac`). Do not launch the pinned Gradle with Java 25.
- Internet access to Maven Central, the Gradle distribution service and Gradle
  Plugin Portal for the first build.
- Node.js 18+ to run the dependency-free browser logic tests.
- Python 3.8+ with the repository's `requirements.txt` dependencies only for the
  optional Python/workbook parity tests.

The current Linux development machine already has JDK 17, Node.js and the Python
dependencies installed. Its default `java` is Java 25, so select Java 17 explicitly.
No system-wide packages or shell profile modifications are necessary:

```bash
cd plugin
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./gradlew --version
./gradlew check workbookParity shadowJar --no-daemon
```

On other machines set `JAVA_HOME` to that machine's JDK 17 installation. The
checked-in Gradle 8.10.2 wrapper downloads Gradle and checks its SHA-256; a global
Gradle installation is not needed. Windows can use `gradlew.bat` after setting
`JAVA_HOME`.

If Python dependencies are missing, create a local virtual environment rather
than changing the system Python:

```bash
# Repository root
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements.txt
cd plugin
./gradlew check workbookParity shadowJar --no-daemon
```

Build without the optional Python comparison:

```bash
./gradlew check shadowJar --no-daemon
```

For packaging alone, neither Node.js nor Python is required:

```bash
./gradlew shadowJar --no-daemon
```

Upload **`build/libs/hvmtools-plugin-0.1.0-all.jar`**, not a thin JAR. Generated
workbooks, parity JSON and test reports stay under the ignored `build/` directory.
Gradle's first build requires downloads; `--offline` works only after all required
artifacts have been cached.

### Dependency choices

- Morpheus plugin API **1.2.14**, `javax.servlet` and Groovy **3.0.13** are
  compile-time/provided dependencies, not embedded copies of appliance libraries.
- Apache POI **5.4.1** and its runtime dependencies are bundled in the fat JAR.
- API 1.2 uses the `javax.servlet` controller contract. The newest online API
  documentation describes later SDKs, including a transition to `jakarta.servlet`.
  The selected SDK must be checked against the actual 9.1.0.1 appliance plugin
  runtime before release; the version number alone does not establish compatibility.
- The production plugin never starts Python or executes shell commands.

## Install and use on a lab appliance

1. In appliance **Administration / Integrations / Plugins** (the exact navigation
   label can vary), upload the `-all.jar`. Confirm it loads without linkage or
   class-loading errors in the appliance logs.
2. Grant the desired role the plugin permission **HVMTools cluster inventory:
   Read**. It defaults to no access. Users also need read access to clusters,
   servers, instances, clouds, groups, networks, datastores and, when selected,
   snapshots. No administrator token or stored service account is used.
3. Open an infrastructure cluster and select its **HVMTools** tab.
4. Choose snapshot/statistics options and click **Collect and preview**.
5. Select a worksheet; the preview pages through 100 rows at a time. Click
   **Download Excel** to download all rows, optionally including redacted raw tabs.
6. Collect again after changing snapshot collection, statistics or warning-age
   options. Downloads use the collected inventory, not a second live inventory
   query. Health age is evaluated when each report is built.

Inventory API reads use same-origin browser requests with the current user's
session. The plugin's report POSTs use the page's `_csrf` meta token. If this VME
version does not expose authenticated REST reads to the UI session, or does not
provide that CSRF token, stop and adapt the integration to its supported session
contract; do not work around it with an administrator token or disabled CSRF.

## Scope and workbook behavior

The tab collects only the selected cluster's report:

- Hosts listed in cluster membership; VMs whose parent is one of those hosts;
  servers directly referencing that cluster through `cluster` or `serverGroup`.
- Related instances, referenced networks/clouds/groups, cluster datastores
  (including unused stores), and other datastores referenced by the included disks.
- Snapshots are an **instance-level** API resource. For instances spanning multiple
  clusters, instance and snapshot metadata can describe the entire instance.
  Complete instance membership is retained, preventing incorrect attribution of
  instance-wide usage to one selected VM. Raw instance records also retain those
  membership references.
- Shared datastore capacity/free space is global; provisioned storage, disk/VM
  counts and cluster/host associations are scoped to this cluster.
- VM/host/network columns, all 13 standard tabs, optional raw tabs, UTC dates,
  units, health rules and workbook layout follow the existing exporter.
- `vSource` counts describe this report's scoped inventory. Manager version and
  current-user fields are blank: the browser collector does not request ping or
  identity details. The manager URL is the browser's current origin.

To find all related objects reliably, the browser currently paginates the
user-visible server, instance, cloud, group and network lists, then filters them.
It sends only the related inventory to the report controller, which scopes and
redacts it again. It does not collect every cluster's datastores or snapshots.
Large managers can still make these list requests expensive.

Failed/denied API reads, missing cluster membership or missing member records stop
collection with an error rather than producing a misleading empty workbook. Only
four per-object requests run concurrently. Unavailable individual metric fields
remain blank, as in the CLI.

The report controller is a renderer of user-submitted inventory, not an
authoritative source of appliance data: it does not fetch privileged server-side
inventory. Never treat its output as a signed audit or trust a client-supplied
cluster ID for server-side authorization in future extensions.

## Limits and safety

- All appliance inventory operations are GETs. POSTs go only to this plugin's
  report-rendering endpoints; no infrastructure resources are changed.
- No credentials are persisted. Credential-like keys are redacted before
  submission and again server-side, including optional raw output.
- Inventory and workbooks contain infrastructure names and addresses. Keep them
  private; do not commit real inventory or downloaded workbooks.
- Preview values use text nodes, not HTML. Workbook strings are written as text,
  never evaluated as spreadsheet formulas.
- Preview/export are in-memory and synchronous. The controller accepts at most
  25 MiB of inventory JSON; servlet/proxy form-body and request timeout limits may
  be lower. Downloads use a base64 JSON response (additional memory overhead).
  There is no persistent report cache, background job or cross-user inventory store.
- Use a trusted HTTPS appliance session, including for self-signed lab certificates.
  There is no plugin TLS-bypass switch.

## Validation and release checklist

`check` runs JVM integration/report tests and Node collection tests.
`workbookParity` builds a fixed-clock Python oracle from the fictional sample and
independently scopes each of its four clusters. It checks all report rows, then
compares the Python/native workbooks, including raw tabs, cell values/types,
number formats, fonts/fills, widths, autofilters and freeze panes. Python and native
files are generated only under `build/parity/`.

Before calling the plugin production-ready, validate on a lab appliance:

- Plugin load, the target SDK and servlet namespace.
- Tab visibility for an allowed role and absence for a denied role.
- Session-backed REST access and CSRF-protected preview/export POSTs.
- Correct host/VM membership for two different clusters; no unrelated rows.
- Empty cluster, shared datastore and multi-cluster instance behavior.
- Snapshot/statistics options, pagination and explicit 401/403 errors.
- Downloaded workbook in Excel/LibreOffice, including all raw tabs.
- Resource usage, form size limits and request timeouts on a large cluster.

References:
[plugin documentation](https://developer.morpheusdata.com/docs),
[API reference](https://developer.morpheusdata.com/api/),
[official tab sample](https://github.com/gomorpheus/morpheus-plugin-samples/tree/main/morpheus-tab-plugin),
[sheet reference](../docs/SHEETS.md).
