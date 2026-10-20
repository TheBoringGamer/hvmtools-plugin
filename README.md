# HVMTools

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Python 3.8+](https://img.shields.io/badge/python-3.8%2B-blue.svg)
![VME 9.1 verified](https://img.shields.io/badge/HPE%20VM%20Essentials-9.1%20verified-01a982.svg)

**RVTools-style inventory reports for HPE Morpheus VM Essentials (VME / HVM).**

HVMTools connects to an HPE VM Essentials manager through the Morpheus REST API, collects a full inventory of the environment, and writes it to a single Excel workbook laid out like an RVTools export. Each object type gets its own tab and each object its own row, with filters and frozen headers ready for sorting, pivoting, and handing to a customer or colleague.

Use it for VMware-to-VME migration assessments, capacity planning, audits, health checks and as-built documentation.

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Usage](#usage)
- [What's in the workbook](#whats-in-the-workbook)
- [Documentation](#documentation)
- [Security](#security)
- [Known limitations](#known-limitations)
- [Contributing](#contributing)

---

## Features

- **One command, one workbook.** Logs in, gathers everything, writes an `.xlsx`.
- **RVTools-familiar tabs:** `vInfo`, `vCPU`, `vMemory`, `vDisk`, `vNetwork`, `vSnapshot`, `vHost`, `vCluster`, `vDatastore`, `vNetworks`, `vHealth`, `vSource`, plus a Morpheus-specific `vInstance` tab.
- **Cluster storage awareness:** `vDatastore` includes the datastores on each cluster's *Storage → Data Stores* tab (GFS2 shared LUNs, NFS, local directories) alongside cloud-level datastores. Each row has capacity, free %, provisioned/overcommit %, VM and disk counts, and the hosts that see the datastore.
- **Host hardware:** CPU model and speed, sockets, cores, vendor and model for every HVM host, with vCPU:core ratio and allocated vRAM.
- **Built-in health checks (`vHealth`):** hosts in a bad state or in maintenance mode, host memory above 90%, datastores under 10% free, VMs in a bad state or powered off, running VMs without the Morpheus agent, and snapshots older than a configurable number of days.
- **Raw mode:** `--raw` adds sheets containing every field the API returned, so nothing is lost even if a field isn't mapped to a friendly column. Credential fields are redacted.
- **Offline rebuilds:** `--dump-json` saves the collected data, and `--from-json` rebuilds the workbook later without touching the manager. Collect on a jump box, report anywhere.
- **Read-only.** HVMTools only issues `GET` requests (plus the login call). It never changes anything in your environment.
- **Single file, two dependencies:** `requests` and `openpyxl`.

---

## Requirements

- Python 3.8 or newer, on Windows, macOS or Linux
- HTTPS (443) access from the machine running the tool to the VME manager
- A VME user account (or API token) with read access to Infrastructure (clouds, clusters, hosts, VMs), Provisioning (instances), Networks and Storage. A read-only role is enough.

Verified against HPE VM Essentials **9.1.0.1**. See [Compatibility](docs/HOW-IT-WORKS.md#compatibility).

---

## Installation

```bash
git clone https://github.com/grahamfairbank/hvmtools.git
cd hvmtools
python3 -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

---

## Quick start

**1. Try it without a manager**, using the bundled sample data:

```bash
python hvmtools.py --from-json examples/sample-inventory.json -o sample.xlsx
```

**2. Run it against your manager.** You will be prompted for the password, which isn't echoed:

```bash
python hvmtools.py --url https://hvm-manager.example.com -u svc-inventory --insecure
```

`--insecure` is needed when the manager still has its default self-signed certificate. Leave it off if the certificate is trusted.

```
[09:30:02] Connecting / ping
[09:30:04] Servers (hosts + VMs)
[09:30:06]   126 servers
[09:30:06] Instances
[09:30:07]   108 instances
[09:30:08] Datastores (cluster Storage tabs + clouds)
[09:30:09]   12 datastores
[09:30:09] Snapshots (108 instances)
[09:30:12] Wrote HVMTools_hvm-manager.example.com_20261020_0930.xlsx: vInfo=109, vCPU=109, vMemory=109, vDisk=181, ...
```

**3. Open the workbook** and start with **vSource** (what was collected, from where and when) and **vHealth** (anything that needs attention). [Using the workbook](docs/USING-THE-WORKBOOK.md) walks through migration, capacity and health-check recipes.

Using an API access token (*User Settings → API Access* in the VME UI) instead of a password:

```bash
python hvmtools.py --url https://hvm-manager.example.com --token <ACCESS_TOKEN>
```

---

## Usage

```
python hvmtools.py [options]
```

| Option | Description |
|---|---|
| `--url URL` | Manager URL, e.g. `https://hvm-manager.example.com`. `https://` is added if omitted. |
| `-u`, `--username` | Username to log in with. Prompted if not supplied. |
| `-p`, `--password` | Password. Prompted securely if omitted (recommended, so it stays out of shell history). |
| `--token TOKEN` | Use an existing API access token instead of username/password. |
| `--insecure` | Skip TLS certificate verification (for self-signed certificates). |
| `-o`, `--output FILE` | Output workbook path. Default: `HVMTools_<host>_<YYYYMMDD_HHMM>.xlsx`. |
| `--no-snapshots` | Skip per-instance snapshot queries. Speeds up large environments; `vSnapshot` will be empty. |
| `--no-stats` | Skip the per-server usage stats queries made when the server list omits stats. Memory, CPU and disk usage columns may then be blank. |
| `--raw` | Add `raw_servers`, `raw_instances` and `raw_datastores` sheets containing every API field (credentials redacted). |
| `--dump-json FILE` | Save the collected inventory to a JSON file as well (credentials redacted). |
| `--from-json FILE` | Build the workbook from a previously dumped JSON file. No API calls are made. |
| `--snapshot-age N` | `vHealth` flags snapshots older than N days. Default: 7. |
| `--page-size N` | Items requested per API page. Default: 100. |
| `--workers N` | Parallel threads for per-instance snapshot and per-server stats queries. Default: 8. |
| `--version` | Print the HVMTools version and exit. |

### Environment variables

Any of these can be set instead of passing the matching option:

| Variable | Equivalent option |
|---|---|
| `MORPHEUS_URL` | `--url` |
| `MORPHEUS_USER` | `--username` |
| `MORPHEUS_PASS` | `--password` |
| `MORPHEUS_TOKEN` | `--token` |

```bash
export MORPHEUS_URL=https://hvm-manager.example.com
export MORPHEUS_USER=svc-inventory
python hvmtools.py --insecure
```

### Common recipes

Collect on one machine, report on another:

```bash
# On the jump box (has access to the manager)
python hvmtools.py --url https://hvm-mgr -u svc-inventory --dump-json inventory.json

# Anywhere else
python hvmtools.py --from-json inventory.json -o customer-report.xlsx
```

Capture everything for troubleshooting or field mapping:

```bash
python hvmtools.py --url https://hvm-mgr -u svc-inventory --raw --dump-json inventory.json
```

Fast run on a large environment:

```bash
python hvmtools.py --url https://hvm-mgr -u svc-inventory --no-snapshots
```

Flag only snapshots older than a month:

```bash
python hvmtools.py --url https://hvm-mgr -u svc-inventory --snapshot-age 30
```

---

## What's in the workbook

| Tab | One row per | Summary |
|---|---|---|
| `vInfo` | VM | Main VM inventory: power state, CPU, memory, disks, IPs, networks, host, cluster, group, plan, OS, tags, IDs |
| `vCPU` | VM | vCPU count, sockets, cores per socket, CPU usage % |
| `vMemory` | VM | Configured memory, consumed, free, usage % |
| `vDisk` | VM disk | Capacity, used, boot disk, device, controller, datastore, storage type |
| `vNetwork` | VM NIC | Network, VLAN, IPv4/IPv6, MAC, adapter type, primary flag |
| `vSnapshot` | Snapshot | Instance, name, created date, age in days, type, state |
| `vHost` | Hypervisor host | CPU model, sockets, cores, memory, usage, VM count, vCPU:core ratio, vRAM, OS, vendor/model, maintenance mode |
| `vCluster` | Cluster | Hosts, VMs, total cores and memory, allocated vCPU and vRAM |
| `vDatastore` | Datastore | Type (GFS2 shared LUN, NFS, local), capacity, free, provisioned %, NFS server/export or LUN device, hosts, cluster |
| `vNetworks` | Network | CIDR, gateway, DNS, VLAN, DHCP, IP pool, NIC count |
| `vInstance` | Morpheus instance | Instance type, layout, plan, group, environment, owner, member VMs |
| `vHealth` | Finding | Health check results, colour-coded by severity |
| `vSource` | Property | Manager URL, build version, connected user, collection time, object counts |

Sizes are in **MiB** to match RVTools. Dates are UTC.

See **[docs/SHEETS.md](docs/SHEETS.md)** for every column and where its data comes from.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/USING-THE-WORKBOOK.md](docs/USING-THE-WORKBOOK.md) | Recipes: health check, migration assessment, right-sizing, capacity review, snapshot hygiene |
| [docs/SHEETS.md](docs/SHEETS.md) | Column-by-column reference for every tab, with the API field each value comes from |
| [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) | Authentication, API endpoints, compatibility, how hosts/VMs/clusters/datastores are matched, redaction, error handling |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Login failures, certificate errors, blank columns, permissions, performance |
| [docs/RVTOOLS-MAPPING.md](docs/RVTOOLS-MAPPING.md) | How HVMTools tabs and columns relate to RVTools, for VMware admins |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## Security

- Credentials are used only to obtain an OAuth access token from the manager. They are never written to disk or to the workbook.
- Credential-like fields the API returns (each server's agent `apiKey`, `sshPassword`, `sshPasswordHash`, cluster service passwords and tokens) are replaced with `<redacted>` in `--raw` sheets and `--dump-json` files.
- Prefer the password prompt or `MORPHEUS_TOKEN` over `-p` on the command line, so secrets don't end up in shell history.
- Use a dedicated **read-only** account for reporting.
- `--insecure` disables certificate checking. Use it only on networks you trust, or install the manager's CA certificate instead (see [Troubleshooting](docs/TROUBLESHOOTING.md#tls-error--certificate_verify_failed)).
- The workbook and any `--dump-json` file contain infrastructure details (hostnames, IPs, MACs, network layout). Treat them as sensitive and don't commit them to a repository. The included `.gitignore` excludes `*.xlsx` and `inventory*.json`.

---

## Known limitations

- Field names in the Morpheus API can vary between VME versions. Columns your version doesn't return are left blank rather than failing; run with `--raw` to see exactly what your manager returns.
- Usage figures (consumed memory, CPU usage %, used disk) are a single point-in-time sample reported by the manager.
- The 9.1 API doesn't expose host serial numbers, guest partitions, or physical NIC/HBA detail, so there are no columns for them.
- Snapshot collection makes one API call per instance; use `--no-snapshots` if this is slow.
- If the server list omits usage stats, one extra API call is made per affected server; use `--no-stats` to skip it.

---

## Contributing

Issues and pull requests are welcome. When reporting a blank or incorrect column, please include:

1. Your VME version (shown on the `vSource` tab as *Build Version*).
2. The relevant row from the `raw_*` sheet produced with `--raw`, with hostnames, IPs and other sensitive values removed.

If you add a column, update [docs/SHEETS.md](docs/SHEETS.md) and [CHANGELOG.md](CHANGELOG.md), and add the field to `examples/generate_sample.py` so the sample exercises it.

---

## License

HVMTools is released under the [MIT License](LICENSE). You are free to use, modify and distribute it, including for commercial work, provided the copyright notice is kept. The software is provided "as is", without warranty of any kind.

---

## Disclaimer

HVMTools is an independent community project. It is not affiliated with, endorsed by, or supported by Hewlett Packard Enterprise, Morpheus Data, Broadcom/VMware, or the authors of RVTools. HPE, Morpheus and VMware are trademarks of their respective owners.
