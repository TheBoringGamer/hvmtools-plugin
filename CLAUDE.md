# CLAUDE.md — HVMTools project context

## What this is
HVMTools is a single-file Python CLI (`hvmtools.py`) that connects to an **HPE Morpheus VM Essentials (VME/HVM) manager** via the Morpheus REST API, collects a full inventory, and writes an **RVTools-style Excel workbook**. It is intended to be the VME equivalent of RVTools (VMware). It is read-only (GET calls only, plus OAuth login).

- Author / copyright holder: **Graham Fairbank** (use this name in all copyright notices)
- License: MIT (`LICENSE`, plus SPDX header in `hvmtools.py`)
- Version: 1.0.0 (see `CHANGELOG.md`)
- Previously called `vmetools` / VMETools; renamed everywhere to `hvmtools` / HVMTools.
- Dependencies: `requests`, `openpyxl` (see `requirements.txt`). Python 3.8+.

## Repo layout
```
hvmtools.py                     the whole tool
README.md                       overview, install, usage, security, limitations
docs/SHEETS.md                  every tab + column and the API field it comes from
docs/HOW-IT-WORKS.md            auth, endpoints, object-matching logic, how to extend
docs/TROUBLESHOOTING.md         login/TLS errors, blank columns, permissions, performance
docs/RVTOOLS-MAPPING.md         VMware/RVTools <-> VME/HVMTools mapping
examples/sample-inventory.json  fake data for offline testing (--from-json)
CHANGELOG.md, LICENSE, requirements.txt, .gitignore (ignores *.xlsx, inventory*.json)
```

## Code structure (hvmtools.py)
- Helpers: `g()` safe nested get, `first()` first non-empty value, `mib()`, `pct()`, `parse_date()`, `power()` (on/off -> poweredOn/poweredOff), `fmt_tags()`, `flatten()` for raw sheets.
- `MorpheusClient`: OAuth login `POST /oauth/token?grant_type=password&scope=write&client_id=morph-api` (form body username/password) or `--token`; `get()`; `list()` paginates with `max`/`offset` until `meta.total`. Non-401 HTTP errors on list calls warn and return empty; 401 exits.
- `collect()`: `/api/ping`, `/api/whoami`, `/api/zones` (fallback `/api/clouds`), `/api/groups`, `/api/clusters` (+ `/api/clusters/{id}` if `servers` missing), `/api/servers`, `/api/instances`, `/api/networks`, datastores, and `/api/instances/{id}/snapshots` in a thread pool.
  - Usage stats: `/api/servers/{id}` for servers whose list record lacks `stats.usedMemory` (skipped with `--no-stats`).
  - Datastores: `/api/clusters/{id}/datastores` (cluster Storage tab: GFS2, OCFS2, NFS, local) merged by id with `/api/zones/{id}/data-stores`. Cluster names stored in `_clusters` on each datastore record.
- `build_sheets()`: hosts = servers with `computeServerType.vmHypervisor == true` OR referenced as another server's `parentServer`; everything else is a VM. Joins VM->host (`parentServer`), host->cluster (`cluster.servers`), VM->instance (`instance.servers` / `containers[].server.id`), disk->datastore (`datastoreId`), NIC->network.
- `write_workbook()` / `write_sheet()`: Arial 10, dark-blue bold header, freeze `B2`, autofilter, auto widths (8-60), `#,##0` ints (not ID columns), `yyyy-mm-dd hh:mm` dates, vHealth rows shaded by severity, empty tabs show "No data returned".
- Tabs: vInfo, vCPU, vMemory, vDisk, vNetwork, vSnapshot, vHost, vCluster, vDatastore, vNetworks, vInstance, vHealth, vSource; with `--raw`: raw_servers, raw_instances, raw_datastores.
- CLI flags: `--url -u -p --token --insecure -o --no-snapshots --no-stats --raw --dump-json --from-json --snapshot-age --page-size --workers`; env vars `MORPHEUS_URL/USER/PASS/TOKEN`.
- Sizes in MiB (RVTools convention); dates UTC. Default output `HVMTools_<host>_<YYYYMMDD_HHMM>.xlsx`.

## Testing
No live manager has been available during development; everything so far was tested only against the sample data:
```
python hvmtools.py --from-json examples/sample-inventory.json -o /tmp/test.xlsx
```
API field names were mapped from Morpheus API knowledge and may differ by VME version. Always use `first()`/`g()` fallbacks and confirm against `--raw` output from a real manager before changing mappings.

## Open items / known issues
1. **vMemory usage columns blank** (Consumed MiB, Free MiB, Usage %) on a real manager. Fix implemented but **not yet verified against a real manager** (tested with a stubbed client only): `collect()` calls `GET /api/servers/{id}` in the thread pool for servers missing `stats.usedMemory` and merges `server.stats` + top-level `stats` into the record; `build_sheets()` `stat()` falls back to instance stats for single-VM instances. Also feeds vCPU *CPU Usage %* and vInfo *In Use MiB*. If columns are still blank, check the stats field names in `raw_servers` / `raw_instances`.
2. Validate datastore/GFS2 field mappings (Type, Address, Path) against a real `raw_datastores` sheet.
3. Validate host hardware fields (CPU model, vendor, model, serial) against real `raw_servers`.
4. Possible future work: zero-dependency/standalone packaging (stdlib-only rewrite, vendored zipapp, or PyInstaller) for air-gapped jump boxes.

## Conventions
- Keep it a single, readable script; tolerate missing fields rather than failing.
- Never write credentials to disk or into the workbook.
- Never commit generated workbooks or JSON inventory dumps (they contain customer infrastructure details).
- Update docs/SHEETS.md and CHANGELOG.md whenever columns or behaviour change.
