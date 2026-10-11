# Troubleshooting

Progress and warnings are printed to the console (stderr) with timestamps. Read those first — most problems show up there as an HTTP status for a specific endpoint.

## Login and connection

### `Login failed (400)` or `Login failed (401)`

- Check the username and password by logging in to the manager's web UI.
- For a user in a sub-tenant, the username format may need to be `TENANT\username`.
- Accounts that sign in through SAML/SSO usually can't use password login against the API. Generate an API access token for the user in the UI (*User Settings → API Access*) and run with `--token`.

### `Unauthorized - check credentials / token.`

The token was rejected. It may have expired or been regenerated in the UI. Create a new one or use username/password login.

### `SSLError` / `CERTIFICATE_VERIFY_FAILED`

The manager uses a certificate your machine doesn't trust (commonly self-signed). Either:

- run with `--insecure`, or
- install the manager's CA certificate and point Python at it:
  ```bash
  export REQUESTS_CA_BUNDLE=/path/to/ca-bundle.pem
  ```

### Connection timed out / refused

- Confirm the URL, including the port if it isn't 443.
- Check that HTTPS from your machine to the manager isn't blocked by a firewall or proxy.
- If you need a proxy, set `HTTPS_PROXY`. To bypass a proxy for the manager, add it to `NO_PROXY`.

## Missing or blank data

### A whole tab says "No data returned"

The console will show a warning such as `warn: /api/clusters -> HTTP 403`. The account doesn't have permission to that area. Give the reporting role read access to the relevant Infrastructure, Provisioning, Network or Storage permission, or run as an account that has it.

If **vInfo** is empty but **vHost** has rows, every server was classified as a host. Run with `--raw` and check `computeServerType.vmHypervisor` and `parentServer.id` in `raw_servers`, then open an issue with that information.

### vMemory usage columns are blank

*Size MiB* comes from the VM's configuration, but *Consumed MiB*, *Free MiB* and *Usage %* need live usage stats (`stats.usedMemory`). These are blank when:

- the manager's server list doesn't include a `stats` block for the VM (check for `stats.usedMemory` in `raw_servers`), or
- the VM has no usage data, typically because the Morpheus agent isn't installed or the VM is powered off.

The same applies to *CPU Usage %* in vCPU and *In Use MiB* in vInfo.

### vDatastore is empty or missing GFS2 datastores

- The console will show a warning if `/api/clusters/{id}/datastores` failed. Check the account can view the cluster's *Storage* tab in the UI.
- Run with `--raw` and inspect `raw_datastores`. If the datastores are there but columns are blank, the field names differ on your version — open an issue with a redacted row.

### Some columns are always blank

Field names vary between VME versions, and some fields (for example host vendor, model and serial) are only present on some versions. Run:

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
- `--page-size` controls items per API call (default 100). Larger values mean fewer calls; reduce it if the manager times out on big pages.
- To avoid querying the manager repeatedly while experimenting with the report, collect once with `--dump-json` and rebuild with `--from-json`.

## Reporting a problem

Please include:

1. The command you ran (with credentials removed).
2. The console output.
3. Your VME build version (vSource tab, or the manager's *About* page).
4. For data issues, the relevant redacted row from a `--raw` run.

Don't attach unredacted workbooks or JSON dumps to public issues — they contain hostnames, IP addresses and network details.
