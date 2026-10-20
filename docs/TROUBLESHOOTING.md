# Troubleshooting

Progress and warnings are printed to the console (stderr) with timestamps. Read those first — most problems show up there as an HTTP status for a specific endpoint.

**Quick index:** [Login failed](#login-failed-400-or-login-failed-401) · [Unauthorized](#unauthorized---check-credentials--token) · [TLS error](#tls-error--certificate_verify_failed) · [Cannot connect](#cannot-connect-to-the-manager--timed-out) · [Permission denied](#cannot-access--permission-denied) · [No data returned](#a-whole-tab-says-no-data-returned) · [Blank usage columns](#vmemory-usage-columns-are-blank) · [Blank columns](#some-columns-are-always-blank) · [Performance](#performance)

## Login and connection

### `Login failed (400)` or `Login failed (401)`

A wrong password on 9.1 looks like this:

```
Login failed (400): {"error_description":"Invalid user credentials","error":"invalid_grant"}
```

- Check the username and password by logging in to the manager's web UI.
- For a user in a sub-tenant, the username format may need to be `TENANT\username`.
- Accounts that sign in through SAML/SSO usually can't use password login against the API. Generate an API access token for the user in the UI (*User Settings → API Access*) and run with `--token`.

### `Unauthorized - check credentials / token.`

The token was rejected. It may have expired or been regenerated in the UI. Create a new one or use username/password login.

### `TLS error` / `CERTIFICATE_VERIFY_FAILED`

```
TLS error: HTTPSConnectionPool(host='hvm-manager.example.com', port=443): ... certificate verify failed: self-signed certificate
If the manager uses a self-signed certificate, add --insecure.
```

The manager uses a certificate your machine doesn't trust (commonly self-signed). Either:

- run with `--insecure`, or
- install the manager's CA certificate and point Python at it:
  ```bash
  export REQUESTS_CA_BUNDLE=/path/to/ca-bundle.pem
  ```

### `Cannot connect to the manager` / timed out

```
Cannot connect to the manager: ... Failed to establish a new connection: [Errno 111] Connection refused
Check --url, DNS and that port 443 is reachable.
```

- Confirm the URL, including the port if it isn't 443.
- Check that HTTPS from your machine to the manager isn't blocked by a firewall or proxy.
- If you need a proxy, set `HTTPS_PROXY`. To bypass a proxy for the manager, add it to `NO_PROXY`.

### `Cannot access ...: permission denied`

```
Cannot access HVMTools_hvm-manager_20261020_0930.xlsx: permission denied (if it's the workbook, is it open in Excel?)
```

On Windows, Excel locks a workbook while it's open. Close it, or write to a different file with `-o`. Nothing is lost if you also used `--dump-json`: rebuild with `--from-json` instead of collecting again.

## Missing or blank data

### A whole tab says "No data returned"

The console will show a warning such as `warn: /api/clusters -> HTTP 403`. The account doesn't have permission to that area. Give the reporting role read access to the relevant Infrastructure, Provisioning, Network or Storage permission, or run as an account that has it.

If the console shows `warn: no servers returned`, the account can log in but can't see Infrastructure. vInfo, vHost and most other tabs will be empty.

If **vInfo** is empty but **vHost** has rows, every server was classified as a host. Run with `--raw` and check `computeServerType.format`, `computeServerType.code` and `parentServer.id` in `raw_servers`, then open an issue with that information. On 9.1, hosts have `format` = `vmHypervisor` and VMs have `format` = `guestVm`.

### vMemory usage columns are blank

*Size MiB* comes from the VM's configuration, but *Consumed MiB*, *Free MiB* and *Usage %* need live usage stats (`stats.usedMemory`). These are blank when:

- the VM has no usage data on the manager, typically because the Morpheus agent isn't installed or the VM is powered off,
- you ran with `--no-stats`, or
- the per-server stats query failed (the console shows `warn: /api/servers/{id} -> HTTP ...`).

When the manager's server list doesn't include usage stats, HVMTools fetches them per server from `GET /api/servers/{id}` and, failing that, uses the stats of the VM's instance. The console reports this as `Usage stats (N servers without stats in list view)` followed by `M of N returned memory usage`. If M is 0, run with `--raw` and check `raw_servers` for `stats.usedMemory`, and `raw_instances` for `stats.usedMemory`; if the manager reports usage under a different field name, open an issue with that information.

The same applies to *CPU Usage %* in vCPU and *In Use MiB* in vInfo.

### vDatastore is empty or missing GFS2 datastores

- The console will show a warning if `/api/clusters/{id}/datastores` failed. Check the account can view the cluster's *Storage* tab in the UI.
- Run with `--raw` and inspect `raw_datastores`. If the datastores are there but columns are blank, the field names differ on your version — open an issue with a redacted row.

### Some columns are always blank

Some columns are blank by nature on 9.1:

| Column | Why |
|---|---|
| vInfo *Discovered*, *Resource Pool* | Not returned for HVM VMs |
| vDisk *Thin* | Not returned; HVM disks are file-backed qcow2 on GFS2/NFS/local datastores |
| vDatastore *Address* | Only NFS datastores have a remote server; GFS2 uses a shared LUN (see *Source*) |
| vHost *External ID* | HVM hosts have no external ID |
| vInfo *Agent Version* | Blank where the Morpheus agent isn't installed |

There is no host serial number column. VME 9.1 doesn't return one anywhere: the host list, `GET /api/servers/{id}` (with or without `?details=true`) and the cluster member list all carry the same four hardware fields (`hardwareCpuModel`, `hardwareCpuFrequency`, `hardwareProductVendor`, `hardwareProductName`). If a later VME version adds a serial, run with `--raw` and open an issue with the field name, and it will be mapped.

Field names also vary between VME versions. Run:

```bash
python hvmtools.py --url https://vme-mgr -u admin --raw
```

and look in `raw_servers`, `raw_instances` and `raw_datastores` for the value you expect. If it's there under a different name, the mapping can be added in `build_sheets()` (see [HOW-IT-WORKS.md](HOW-IT-WORKS.md#extending)).

### vSnapshot is empty

- You may have used `--no-snapshots`.
- Snapshots are collected per instance. VMs that were discovered rather than provisioned through Morpheus, and so have no instance, won't have snapshots listed.

### Cluster or Host column is blank for some VMs

The VM's `parentServer` isn't set, or its host isn't listed in any cluster's `servers`. This can happen for VMs that are still syncing, or for unmanaged/discovered VMs.

## Performance

- The slowest part is usually snapshot collection on large environments. Use `--no-snapshots`, or raise `--workers` (e.g. `--workers 16`) if the manager can handle more parallel requests.
- If the console shows `Usage stats (N servers without stats in list view)` with a large N, that is one extra API call per server. Use `--no-stats` to skip it; the usage columns will then be blank for those servers.
- `--page-size` controls items per API call (default 100). Larger values mean fewer calls; reduce it if the manager times out on big pages.
- To avoid querying the manager repeatedly while experimenting with the report, collect once with `--dump-json` and rebuild with `--from-json`.

## Reporting a problem

Please include:

1. The command you ran (with credentials removed).
2. The console output.
3. Your VME build version (vSource tab, or the manager's *About* page).
4. For data issues, the relevant redacted row from a `--raw` run.

Don't attach unredacted workbooks or JSON dumps to public issues — they contain hostnames, IP addresses and network details.
