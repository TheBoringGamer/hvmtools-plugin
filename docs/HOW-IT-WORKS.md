# How HVMTools works

## Overview

```
login ─► collect (read-only GET calls) ─► build rows per tab ─► write .xlsx
                    │
                    └─► optional --dump-json  ◄── optional --from-json (skips login/collect)
```

The script has three stages, each a separate function in `hvmtools.py`:

| Stage | Function | What it does |
|---|---|---|
| Collect | `collect()` | Calls the Morpheus API and stores the raw responses in a single dictionary |
| Build | `build_sheets()` | Matches objects together and produces one list of rows per tab |
| Write | `write_workbook()` | Writes and formats the Excel file |

Because the build stage works only on the collected dictionary, the same data can be saved with `--dump-json` and rebuilt later with `--from-json`.

## Authentication

With a username and password, HVMTools requests an OAuth access token from the manager:

```
POST /oauth/token?grant_type=password&scope=write&client_id=morph-api
Content-Type: application/x-www-form-urlencoded

username=<user>&password=<password>
```

The returned `access_token` is sent as `Authorization: Bearer <token>` on every later request. With `--token`, that header is set directly and no login call is made.

Despite the `scope=write` parameter (required by the Morpheus token endpoint), HVMTools only performs `GET` requests after login.

## API endpoints used

| Endpoint | Used for |
|---|---|
| `GET /api/ping` | Build version and appliance URL (vSource) |
| `GET /api/whoami` | Connected user and tenant (vSource) |
| `GET /api/zones` (fallback `/api/clouds`) | Clouds |
| `GET /api/groups` | Groups |
| `GET /api/clusters` | Clusters |
| `GET /api/clusters/{id}` | Cluster member hosts, if the list view omitted them |
| `GET /api/clusters/{id}/datastores` | Datastores on each cluster's Storage tab (GFS2, OCFS2, NFS, local) |
| `GET /api/zones/{id}/data-stores` | Cloud-level datastores |
| `GET /api/servers` | VMs and hypervisor hosts, with volumes and interfaces |
| `GET /api/servers/{id}` | Usage stats, only for servers whose list record has no `stats.usedMemory` (skipped with `--no-stats`) |
| `GET /api/instances` | Morpheus instances |
| `GET /api/instances/{id}/snapshots` | Snapshots (skipped with `--no-snapshots`) |
| `GET /api/networks` | Networks |

List endpoints are paged with `max` and `offset` until `meta.total` items have been read. If an endpoint returns an HTTP error (for example 403 because the account lacks a permission), a warning is printed and collection continues with that data empty. A 401 stops the run, since it means the credentials or token are invalid.

Snapshot queries run in parallel (`--workers`, default 8) because they need one call per instance. The per-server usage stats queries use the same thread pool.

The server list view can omit the `stats` block, which leaves the usage columns (vMemory *Consumed MiB* / *Free MiB* / *Usage %*, vCPU *CPU Usage %*, vInfo *In Use MiB*) blank. For each server without `stats.usedMemory`, HVMTools reads the detail record and merges both `server.stats` and the top-level `stats` from that response into the server record, keeping any values the list already had. When building the sheets, a VM that still has no value falls back to its instance's `stats`, but only if the instance has exactly one VM.

## Matching objects together

The Morpheus API returns VMs and hypervisor hosts from the same `/api/servers` endpoint, and relationships are expressed through IDs. HVMTools joins them as follows.

### Hosts vs VMs

A server is treated as a **host** if either:

- `computeServerType.vmHypervisor` is `true`, or
- another server lists it as its `parentServer`.

Every other server is treated as a **VM**.

### VM → host

`server.parentServer.id` / `.name`.

### Host → cluster, VM → cluster

Each cluster's `servers` list gives its member hosts. A VM's cluster is the cluster of its parent host (or `server.cluster`, if the API provides it directly).

### VM → instance

Each instance's `servers` list (and `containers[].server.id`) gives the VMs it owns. This supplies instance-level details such as group, plan, environment and owner to vInfo.

### Disk → datastore

`volume.datastoreId` or `volume.datastore.id`, looked up in the merged datastore list. This is also how vDatastore calculates *Provisioned MiB*, *# Disks* and *# VMs*.

### Datastore → cluster → hosts

A datastore returned by `/api/clusters/{id}/datastores` is tagged with that cluster's name. Its *Hosts* column lists the member hosts of every cluster it was found on. If the same datastore also appears at cloud level, the two records are merged into one row.

### NIC → network

`interface.network.id`, looked up in `/api/networks` to add VLAN details.

## Workbook formatting

- Header row: bold white text on dark blue, frozen, with AutoFilter enabled.
- First column frozen, so VM/host names stay visible when scrolling right.
- Column widths sized to content (8–60 characters).
- Integers formatted with thousands separators (except ID columns); dates as `yyyy-mm-dd hh:mm`.
- vHealth rows shaded by severity.
- Characters that Excel cannot store are stripped from text values.
- A tab with no data shows "No data returned" rather than being omitted, so the tab layout is always the same.

## Extending

To add a column, add a key to the row dictionary for that tab in `build_sheets()`; column order follows the order of keys. To add a tab, add its name to the `rows` dictionary and append rows to it. To collect a new endpoint, add a call in `collect()` and store the result in `inv` so it is also included in `--dump-json` output.

The helper `g(obj, "a", "b")` performs a safe nested lookup, and `first(x, y, z)` returns the first non-empty value — use them to tolerate fields that differ between VME versions.
