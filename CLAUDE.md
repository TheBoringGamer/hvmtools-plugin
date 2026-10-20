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
docs/USING-THE-WORKBOOK.md      recipes: health check, migration, right-sizing, capacity
examples/generate_sample.py     deterministic generator for the fictional sample (9.1 record shapes)
examples/sample-inventory.json  generated fake data for offline testing (--from-json); don't hand-edit
CHANGELOG.md, LICENSE, requirements.txt, .gitignore (ignores *.xlsx, inventory*.json)
```

## Code structure (hvmtools.py)
- Helpers: `g()` safe nested get, `first()` first non-empty value, `mib()`, `pct()`, `parse_date()`, `power()` (on/off -> poweredOn/poweredOff), `fmt_tags()`, `flatten()` for raw sheets.
- `MorpheusClient`: OAuth login `POST /oauth/token?grant_type=password&scope=write&client_id=morph-api` (form body username/password) or `--token`; `get()`; `list()` paginates with `max`/`offset` until `meta.total`. Non-401 HTTP errors on list calls warn and return empty; 401 exits.
- `collect()`: `/api/ping`, `/api/whoami`, `/api/zones` (fallback `/api/clouds`), `/api/groups`, `/api/clusters` (+ `/api/clusters/{id}` if `servers` missing), `/api/servers`, `/api/instances`, `/api/networks`, datastores, and `/api/instances/{id}/snapshots` in a thread pool.
  - Usage stats: `/api/servers/{id}` for servers whose list record lacks `stats.usedMemory` (skipped with `--no-stats`).
  - Datastores: `/api/clusters/{id}/datastores` (cluster Storage tab: GFS2, OCFS2, NFS, local) merged by id with `/api/zones/{id}/data-stores`. Cluster names stored in `_clusters` on each datastore record.
- `redact()` blanks credential-like keys (apiKey, sshPassword(Hash), service* creds...) in `collect()` output and `--from-json` input.
- `run()` wraps `main()` and turns TLS/connection/HTTP/permission/JSON errors into one-line `SystemExit` messages. `/api/ping` is non-quiet so connection failures stop the run.
- `build_sheets()`: hosts = servers with `computeServerType.format == "vmHypervisor"` (9.1) or `computeServerType.vmHypervisor == true` OR referenced as another server's `parentServer`; everything else is a VM. Host cluster from `serverGroup.name`. Joins VM->host (`parentServer`), host->cluster (`cluster.servers`), VM->instance (`instance.servers` / `containers[].server.id`), disk->datastore (`datastoreId`), NIC->network.
- `write_workbook()` / `write_sheet()`: Arial 10, dark-blue bold header, freeze `B2`, autofilter, auto widths (8-60), `#,##0` ints (not ID columns), `yyyy-mm-dd hh:mm` dates, vHealth rows shaded by severity, empty tabs show "No data returned".
- Tabs: vInfo, vCPU, vMemory, vDisk, vNetwork, vSnapshot, vHost, vCluster, vDatastore, vNetworks, vInstance, vHealth, vSource; with `--raw`: raw_servers, raw_instances, raw_datastores.
- CLI flags: `--version --url -u -p --token --insecure -o --no-snapshots --no-stats --raw --dump-json --from-json --snapshot-age --page-size --workers`; env vars `MORPHEUS_URL/USER/PASS/TOKEN`.
- Sizes in MiB (RVTools convention); dates UTC. Default output `HVMTools_<host>_<YYYYMMDD_HHMM>.xlsx`.

## Testing
Verified against a live VME **9.1.0.1** lab (3 nested hosts on VMware, GFS2 + NFS + local datastores) on 2026-10-20. Offline test:
```
python examples/generate_sample.py
python hvmtools.py --from-json examples/sample-inventory.json -o /tmp/test.xlsx
```
9.1 field facts: hosts have `computeServerType.code=mvmHost, format=vmHypervisor` (no `vmHypervisor` bool); host hardware in `hardwareCpuModel/hardwareCpuFrequency/hardwareProductVendor/hardwareProductName` (vendor/name end in a newline); no serial field (checked list, `/api/servers/{id}`, `?details=true` and `/api/clusters/{id}` on 2026-10-20; add a Serial column if a later version exposes one); datastore `type` is often `generic` - real type is `datastoreType.name`; NFS config `sourceHostname/sourceDirPath`, GFS2 config `blockDevice`; `/api/servers` list already includes `stats`. Older versions are untested; keep `first()`/`g()` fallbacks and confirm against `--raw` output before changing mappings.
Real lab dumps contain hostnames, IPs and the user's email - keep them outside the repo (never commit them) and never put real data in docs or screenshots; use the generated sample.

## Open items / known issues
1. vMemory usage, datastore and host hardware mappings: verified on 9.1.0.1 (the 9.1 server list includes stats, so the `/api/servers/{id}` fallback path is still only stub-tested).
2. vSnapshot verified on 9.1.0.1: `state` is null, use `status` (`complete`); size in `maxStorage` / `snapshotFiles[].fileSize`.
3. Possible future work: zero-dependency/standalone packaging (stdlib-only rewrite, vendored zipapp, or PyInstaller) for air-gapped jump boxes.

## Conventions
- Keep it a single, readable script; tolerate missing fields rather than failing.
- Never write credentials to disk or into the workbook.
- Never commit generated workbooks or JSON inventory dumps (they contain customer infrastructure details).
- Update docs/SHEETS.md and CHANGELOG.md whenever columns or behaviour change.
