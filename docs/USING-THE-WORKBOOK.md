# Using the workbook

Practical recipes for the most common jobs: migration assessments, capacity reviews, health checks and as-built documentation. Every example works on the bundled fictional sample, so you can try them before running against a real manager:

```bash
python hvmtools.py --from-json examples/sample-inventory.json -o sample.xlsx
```

Every tab has AutoFilter on the header row and frozen panes, so the filter arrows and the name column are always to hand.

---

## Read the vSource tab first

vSource records **which manager**, **which build**, **who collected** and **when** (UTC). Keep it with the workbook whenever you hand it on, because it is what makes the numbers traceable.

The object counts at the bottom (Clouds, Clusters, Hosts, VMs, Instances, Datastores, Networks) are a quick sanity check against the VME UI.

---

## Health check in two minutes

Open **vHealth**. Findings are already sorted Critical → Warning → Info and shaded by severity.

| You see | What to do |
|---|---|
| *Host status: error* (Critical) | Check the host in *Infrastructure → Clusters → Hosts*; the agent may have lost contact |
| *Memory usage 93%* (Warning) | That host has no headroom for an HA restart; rebalance VMs across the cluster |
| *Only 6% free* on a datastore | Expand the LUN or move disks before thin-provisioned disks fill it |
| *Snapshot is 210 days old* | Old snapshots grow and slow disk I/O; confirm with the owner and delete |
| *Morpheus agent not installed* | Usage columns for that VM rely on hypervisor stats only; install the agent for guest-level metrics |
| *Host is in maintenance mode* (Info) | Expected during patching; make sure it is taken out afterwards |

Change the snapshot threshold with `--snapshot-age 30`.

---

## Migration assessment (VMware → VME, or between VME clusters)

### 1. Scope the VMs

In **vInfo**, filter:

- *Powerstate* = `poweredOn` (powered-off VMs are often candidates for retirement rather than migration),
- *Cluster* or *Group* to the scope of the wave,
- *Managed* = `True`, to leave out discovered appliances such as the VME manager itself.

*Tags* holds `key=value` pairs (for example `app=web; env=production`), so a text filter such as *contains* `env=production` is a quick way to pick an environment.

### 2. Size the target

Select the filtered *CPUs*, *Memory MiB* and *Provisioned MiB* columns: Excel's status bar shows the sums. Convert with GiB = MiB ÷ 1,024 and TiB = MiB ÷ 1,048,576.

For a per-cluster summary, insert a PivotTable on vInfo:

| PivotTable field | Setting |
|---|---|
| Rows | Cluster |
| Values | Count of VM, Sum of CPUs, Sum of Memory MiB, Sum of Provisioned MiB |
| Filters | Powerstate |

### 3. Right-size before moving

In **vMemory**, sort *Usage %* ascending. VMs using under 25% of their configured memory over a representative period are candidates for a smaller plan. In **vCPU**, sort *CPU Usage %* the same way.

The usage figures are a single point-in-time sample from the manager. Collect at a busy time of day, or collect several times and compare.

### 4. Check storage

**vDisk** lists every disk with its datastore. **vDatastore** shows, per datastore, *% Provisioned* (allocated capacity ÷ size) next to *Free %* (what's actually free). A datastore with high *% Provisioned* but plenty of free space is thin-provisioned and fine today, but could fill up if the disks grow.

*Type* distinguishes `HPE Clustered Datastore (Shared LUN)` (GFS2), `NFS Datastore` and `Directory Datastore (Local)`. Only shared datastores let VMs move live between hosts.

---

## Capacity review

### Hosts

**vHost** gives, per host, physical cores and memory alongside what the VMs on it are allocated:

| Column | Healthy range (typical guidance) |
|---|---|
| *vCPUs per Core* | Under 4:1 for general workloads; under 2:1 for latency-sensitive ones |
| *Memory Usage %* | Low enough that the cluster can lose one host: roughly (*hosts* − 1) ÷ *hosts* × 100, minus a margin |
| *# VMs On* vs *# VMs* | A big gap means many powered-off VMs are still holding allocated resources |

### Clusters

**vCluster** rolls the same figures up per cluster: total cores and memory against allocated vCPUs and vRAM.

*vRAM MiB* ÷ *Total Memory MiB* is the memory allocation ratio. Allow for the host reserved memory and for losing a host, and keep it comfortably below 100%.

---

## Snapshot hygiene

**vSnapshot** lists every snapshot with its age in days. Sort *Age (days)* descending to find forgotten ones; *Instance* tells you whose VM it is (look up *Owner* in vInfo or vInstance).

---

## Network documentation

- **vNetworks** is the network list: VLAN ID, CIDR, gateway, DNS, IP pool and the number of VM NICs attached.
- **vNetwork** is every VM NIC with its network, VLAN, IP and MAC address. Filter *Network* to list everything on a VLAN before a change.

---

## Comparing two collections

Collect with `--dump-json` each time, keeping the JSON files somewhere safe, outside the repository:

```bash
python hvmtools.py --url https://hvm-mgr -u svc-inventory --dump-json inventory-2026-10.json
```

Then rebuild any snapshot later with `--from-json`, and compare the two workbooks by matching *VM ID* across the vInfo tabs with `XLOOKUP`, or with Microsoft Spreadsheet Compare where your Office edition includes it.

---

## Handing the workbook on

The workbook contains hostnames, IP and MAC addresses and network layout. Before sending it outside your organisation:

- delete the `raw_*` sheets if you used `--raw` (credential fields are already redacted, but the sheets contain everything else),
- consider whether vNetwork (MAC addresses) and vNetworks (subnets) are needed,
- keep vSource, so the recipient knows where and when the data came from.
