# HVMTools

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**RVTools-style inventory reports for HPE Morpheus VM Essentials (VME / HVM).**

HVMTools connects to an HPE VM Essentials manager through the Morpheus REST API, collects a full inventory of the environment, and writes it to a single Excel workbook laid out like an RVTools export: one tab per object type, one row per object, with filters and frozen headers ready for sorting, pivoting, and handing to a customer or colleague.

It is intended for VMware-to-VME migration assessments, capacity planning, audits, health checks, and as-built documentation.

---

## Features

- **One command, one workbook.** Logs in, gathers everything, writes an `.xlsx`.
- **RVTools-familiar tabs:** `vInfo`, `vCPU`, `vMemory`, `vDisk`, `vNetwork`, `vSnapshot`, `vHost`, `vCluster`, `vDatastore`, `vNetworks`, `vHealth`, `vSource`, plus a Morpheus-specific `vInstance` tab.
- **Cluster storage awareness:** `vDatastore` includes the datastores on each cluster's *Storage → Data Stores* tab (GFS2, OCFS2, NFS pools, local) alongside cloud-level datastores, with capacity, free %, provisioned/overcommit %, VM and disk counts, and the hosts that see each datastore.
- **Built-in health checks (`vHealth`):** hosts in a bad state, host memory above 90%, datastores under 10% free, stopped VMs, running VMs without the Morpheus agent, and snapshots older than a configurable number of days.
- **Raw mode:** `--raw` adds sheets containing every field the API returned, so nothing is lost even if a field isn't mapped to a friendly column.
- **Offline rebuilds:** `--dump-json` saves the collected data; `--from-json` rebuilds the workbook later without touching the manager. Useful for collecting on a jump box and reporting elsewhere.
- **Read-only.** HVMTools only issues `GET` requests (plus the login call). It never changes anything in your environment.
- **Single file, two dependencies:** `requests` and `openpyxl`.

---

## Requirements

- Python 3.8 or newer
- Network access from the machine running the tool to the VME manager on HTTPS (443)
- A VME / Morpheus user account (or API token) with read access to Infrastructure (clouds, clusters, hosts, VMs), Provisioning (instances), Networks and Storage. A read-only role is sufficient.

---

## Installation

```bash
git clone https://github.com/grahamfairbank/hvmtools.git
cd hvmtools
pip install -r requirements.txt
```

Using a virtual environment is recommended:

```bash
python3 -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

---

## Quick start

```bash
python hvmtools.py --url https://vme-manager.example.com -u admin
```

You will be prompted for the password (it is not echoed). When collection finishes, a file named like `HVMTools_vme-manager.example.com_20261001_0930.xlsx` is written to the current directory.

If the manager uses a self-signed certificate:

```bash
python hvmtools.py --url https://vme-manager.example.com -u admin --insecure
```

Using an API access token instead of a username and password:

```bash
python hvmtools.py --url https://vme-manager.example.com --token <ACCESS_TOKEN>
```

Try it without a manager, using the bundled sample data:

```bash
python hvmtools.py --from-json examples/sample-inventory.json -o sample.xlsx
```

---

## Usage

```
python hvmtools.py [options]
```

| Option | Description |
|---|---|
| `--url URL` | Manager URL, e.g. `https://vme-manager.example.com`. `https://` is added if omitted. |
| `-u`, `--username` | Username to log in with. Prompted if not supplied. |
| `-p`, `--password` | Password. Prompted securely if omitted (recommended — avoids it landing in shell history). |
| `--token TOKEN` | Use an existing API access token instead of username/password. |
| `--insecure` | Skip TLS certificate verification (for self-signed certificates). |
| `-o`, `--output FILE` | Output workbook path. Default: `HVMTools_<host>_<YYYYMMDD_HHMM>.xlsx`. |
| `--no-snapshots` | Skip per-instance snapshot queries. Speeds up large environments; `vSnapshot` will be empty. |
| `--no-stats` | Skip the per-server usage stats queries made when the server list omits stats. Memory, CPU and disk usage columns may then be blank. |
| `--raw` | Add `raw_servers`, `raw_instances` and `raw_datastores` sheets containing every API field. |
| `--dump-json FILE` | Save the raw collected inventory to a JSON file as well. |
| `--from-json FILE` | Build the workbook from a previously dumped JSON file. No API calls are made. |
| `--snapshot-age N` | `vHealth` flags snapshots older than N days. Default: 7. |
| `--page-size N` | Items requested per API page. Default: 100. |
| `--workers N` | Parallel threads used for per-instance snapshot and per-server stats queries. Default: 8. |

### Environment variables

Any of these can be set instead of passing the matching option:

| Variable | Equivalent option |
|---|---|
| `MORPHEUS_URL` | `--url` |
| `MORPHEUS_USER` | `--username` |
| `MORPHEUS_PASS` | `--password` |
| `MORPHEUS_TOKEN` | `--token` |

Example:

```bash
export MORPHEUS_URL=https://vme-manager.example.com
export MORPHEUS_USER=readonly-report
python hvmtools.py --insecure
```

### Common recipes

Collect on one machine, report on another:

```bash
# On the jump box (has access to the manager)
python hvmtools.py --url https://vme-mgr -u admin --dump-json inventory.json

# Anywhere else
python hvmtools.py --from-json inventory.json -o customer-report.xlsx
```

Capture everything for troubleshooting or field mapping:

```bash
python hvmtools.py --url https://vme-mgr -u admin --raw --dump-json inventory.json
```

Fast run on a large environment:

```bash
python hvmtools.py --url https://vme-mgr -u admin --no-snapshots
```

---

## Output workbook

| Tab | One row per | Summary |
|---|---|---|
| `vInfo` | VM | Main VM inventory: power state, CPU, memory, disks, IPs, networks, host, cluster, group, plan, OS, tags, IDs |
| `vCPU` | VM | vCPU count, sockets, cores per socket, CPU usage % |
| `vMemory` | VM | Configured memory, consumed, free, usage % |
| `vDisk` | VM disk | Capacity, used, boot disk, device, controller, datastore, storage type |
| `vNetwork` | VM NIC | Network, VLAN, IPv4/IPv6, MAC, adapter type, primary flag |
| `vSnapshot` | Snapshot | Instance, name, created date, age in days, type, state |
| `vHost` | Hypervisor host | CPU/memory capacity and usage, VM count, vCPU:core ratio, vRAM, OS, hardware |
| `vCluster` | Cluster | Hosts, VMs, total cores and memory, allocated vCPU and vRAM |
| `vDatastore` | Datastore | Type (GFS2, NFS, …), capacity, free, provisioned, overcommit %, hosts, cluster |
| `vNetworks` | Network | CIDR, gateway, DNS, VLAN, DHCP, IP pool, NIC count |
| `vInstance` | Morpheus instance | Instance type, layout, plan, group, environment, owner, member VMs |
| `vHealth` | Finding | Health check results, colour-coded by severity |
| `vSource` | Property | Manager URL, build version, connected user, collection time, object counts |

Sizes are reported in **MiB** to match RVTools. Dates are UTC.

See **[docs/SHEETS.md](docs/SHEETS.md)** for every column and where its data comes from.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/SHEETS.md](docs/SHEETS.md) | Column-by-column reference for every tab, with the API field each value comes from |
| [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) | Authentication, API endpoints used, how hosts/VMs/clusters/datastores are matched up |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Login failures, certificate errors, blank columns, permissions, performance |
| [docs/RVTOOLS-MAPPING.md](docs/RVTOOLS-MAPPING.md) | How HVMTools tabs and columns relate to RVTools for VMware admins |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## Security notes

- Credentials are used only to obtain an OAuth access token from the manager and are never written to disk or to the workbook.
- Prefer the password prompt or `MORPHEUS_TOKEN` over `-p` on the command line so secrets don't end up in shell history.
- Use a dedicated **read-only** account for reporting.
- `--insecure` disables certificate checking; use it only on networks you trust, or install the manager's CA certificate instead.
- The workbook and any `--dump-json` file contain infrastructure details (hostnames, IPs, MACs, network layout). Treat them as sensitive and don't commit them to the repository — the included `.gitignore` excludes `*.xlsx` and `inventory*.json` by default.

---

## Known limitations

- Field names in the Morpheus API can vary between VME versions. Columns whose data your version doesn't return are left blank rather than failing. Run with `--raw` to see exactly what your manager returns.
- Usage figures (consumed memory, CPU usage %, used disk) depend on the manager having stats for the VM, which generally requires the Morpheus agent or hypervisor-reported stats.
- Host hardware columns (CPU model, vendor, model, serial) are only populated if the manager exposes them.
- Snapshot collection makes one API call per instance; use `--no-snapshots` if this is slow.
- If the server list omits usage stats, one extra API call is made per affected server; use `--no-stats` to skip it.

---

## Contributing

Issues and pull requests are welcome. When reporting a blank or incorrect column, please include:

1. Your VME version (shown on the `vSource` tab as *Build Version*).
2. The relevant row from the `raw_*` sheet produced with `--raw`, with hostnames, IPs and other sensitive values redacted.

---

## License

HVMTools is released under the [MIT License](LICENSE). You are free to use, modify and distribute it, including for commercial work, provided the copyright notice is kept. The software is provided "as is", without warranty of any kind.

---

## Disclaimer

HVMTools is an independent community project. It is not affiliated with, endorsed by, or supported by Hewlett Packard Enterprise, Morpheus Data, Broadcom/VMware, or the authors of RVTools. HPE, Morpheus and VMware are trademarks of their respective owners.
