# Sheet and column reference

Every tab in the HVMTools workbook, every column, and the Morpheus API field it comes from.

The mappings below were checked against a live HPE VM Essentials **9.1.0.1** manager. The bundled fictional sample (`examples/sample-inventory.json`: 4 clusters, 17 hosts, 109 VMs) exercises every tab:

```bash
python hvmtools.py --from-json examples/sample-inventory.json -o sample.xlsx
```

**Tabs:** [vInfo](#vinfo--one-row-per-vm) · [vCPU](#vcpu--one-row-per-vm) · [vMemory](#vmemory--one-row-per-vm) · [vDisk](#vdisk--one-row-per-vm-disk) · [vNetwork](#vnetwork--one-row-per-vm-nic) · [vSnapshot](#vsnapshot--one-row-per-snapshot) · [vHost](#vhost--one-row-per-hypervisor-host) · [vCluster](#vcluster--one-row-per-cluster) · [vDatastore](#vdatastore--one-row-per-datastore) · [vNetworks](#vnetworks--one-row-per-network) · [vInstance](#vinstance--one-row-per-morpheus-instance) · [vHealth](#vhealth--one-row-per-finding) · [vSource](#vsource--collection-details) · [raw_*](#raw_servers--raw_instances--raw_datastores-with---raw)

Conventions used below:

- `server.*` is a record from `GET /api/servers` (VMs and hosts are both "servers" in Morpheus).
- `instance.*` is a record from `GET /api/instances`.
- `server.stats` comes from the server list; where the list has no `stats.usedMemory` for a server it is filled in from `GET /api/servers/{id}` (skipped with `--no-stats`). `instance.stats.*` is only used as a fallback when the instance has a single VM, since instance stats are totals across its VMs.
- Where several fields are listed with `→`, the first one that has a value is used.
- Sizes are converted from bytes to **MiB** (bytes ÷ 1,048,576, rounded).
- Dates are converted to UTC and written as Excel dates.
- Power state is normalised to RVTools wording: `on` → `poweredOn`, `off` → `poweredOff`, `suspended`/`paused` → `suspended`.

A blank cell means the manager did not return that field for the object.

---

## vInfo — one row per VM

The main inventory: one row per VM with its configuration, placement and ownership. Start here for migration assessments; filter on *Cluster*, *Powerstate* or *OS* to scope a wave.

| Column | Source |
|---|---|
| VM | `server.displayName` → `server.name` |
| Powerstate | `server.powerState` (normalised) |
| Status | `server.status` |
| Template | Always `False` (templates are not returned as servers) |
| DNS Name | `server.hostname` → `server.externalFqdn` |
| Agent Installed | `server.agentInstalled` |
| Agent Version | `server.agentVersion` |
| Creation date | `server.dateCreated` |
| Last Updated | `server.lastUpdated` |
| CPUs | `server.maxCores` → `instance.maxCores` → `instance.plan.maxCores` |
| Sockets | CPUs ÷ Cores per Socket |
| Cores per Socket | `server.coresPerSocket` → `instance.coresPerSocket` |
| Memory MiB | `server.maxMemory` → `instance.maxMemory` |
| NICs | Count of `server.interfaces` |
| Disks | Count of `server.volumes` |
| Total disk capacity MiB | Sum of `server.volumes[].maxStorage` |
| Provisioned MiB | `server.maxStorage` |
| In Use MiB | `server.stats.usedStorage` → `server.usedStorage` → `instance.stats.usedStorage` |
| Primary IP Address | `server.internalIp` → `server.externalIp` → `server.sshHost` |
| All IP Addresses | Internal, external and all NIC IPs, de-duplicated |
| Network #1–#4 | Network names of the first four NICs |
| Resource Pool | `server.resourcePool.name` |
| Group | `instance.group.name` → `server.site.name` |
| Instance | `instance.name` of the instance that owns this VM |
| Instance Type | `instance.instanceType.name` |
| Layout | `instance.layout.name` |
| Plan | `server.plan.name` → `instance.plan.name` |
| Environment | `instance.environment` |
| Owner | `instance.owner.username` → `instance.createdBy.username` → `server.owner.username` |
| Annotation | `server.description` → `instance.description` |
| Cloud | `server.zone.name` → `server.cloud.name` |
| Cluster | See [cluster matching](HOW-IT-WORKS.md#matching-objects-together) |
| Host | `server.parentServer.name` |
| OS | `server.serverOs.name` → `server.osType` |
| OS Platform | `server.platform` |
| OS Version | `server.platformVersion` |
| Tags | `server.tags` → `instance.tags`, as `key=value; key=value` |
| Labels | `server.labels` → `instance.labels`, comma-separated |
| Managed | `server.managed` → `server.computeServerType.managed` (`False` for VMs discovered on a host but not managed by VME, such as the manager appliance itself) |
| Discovered | `server.discovered` |
| Server Type | `server.computeServerType.name` |
| VM ID | `server.id` |
| Instance ID | `instance.id` |
| External ID | `server.externalId` (the hypervisor's own ID) |
| UUID | `server.uuid` |
| Manager | Manager URL the data was collected from |

## vCPU — one row per VM

| Column | Source |
|---|---|
| VM, Powerstate | As vInfo |
| CPUs, Sockets, Cores per Socket | As vInfo |
| CPU Usage % | `server.stats.cpuUsage` → `server.stats.usedCpu` → `instance.stats.cpuUsage` → `instance.stats.usedCpu`, rounded to one decimal place |
| Plan | `server.plan.name` |
| OS | As vInfo |
| Host, Cluster, Cloud, VM ID | As vInfo |

## vMemory — one row per VM

Configured versus consumed memory per VM. Sort by *Usage %* to find right-sizing candidates before a migration.

| Column | Source |
|---|---|
| VM, Powerstate | As vInfo |
| Size MiB | Configured memory (as vInfo *Memory MiB*) |
| Consumed MiB | `server.stats.usedMemory` → `server.usedMemory` → `instance.stats.usedMemory` |
| Free MiB | Size − Consumed |
| Usage % | Consumed ÷ Size × 100 |
| Plan | `server.plan.name` |
| Host, Cluster, Cloud, VM ID | As vInfo |

*Consumed*, *Free* and *Usage %* require usage stats from the manager. If the `/api/servers` list omits them, they are read from `GET /api/servers/{id}` (skipped with `--no-stats`). See [Troubleshooting](TROUBLESHOOTING.md#vmemory-usage-columns-are-blank).

## vDisk — one row per VM disk

| Column | Source |
|---|---|
| VM, Powerstate | As vInfo |
| Disk | `volume.name` |
| Capacity MiB | `volume.maxStorage` |
| Used MiB | `volume.usedStorage` |
| Root/Boot | `volume.rootVolume` |
| Device | `volume.deviceDisplayName` → `volume.deviceName` |
| Controller | `volume.controller.name` → `volume.controllerId` |
| Unit # | `volume.unitNumber` |
| Datastore | `volume.datastore.name`, or looked up from `volume.datastoreId` |
| Storage Type | `volume.type.name` → `volume.storageType.name` → `volume.typeName` |
| Thin | `volume.thin` |
| Status | `volume.status` |
| Disk ID | `volume.id` |
| External ID | `volume.externalId` |
| Host, Cluster, Cloud, VM ID | As vInfo |

## vNetwork — one row per VM NIC

| Column | Source |
|---|---|
| VM, Powerstate | As vInfo |
| NIC | `interface.name` → `interface.displayName` |
| Network | `interface.network.name` |
| VLAN | `network.vlanId` of the attached network |
| Primary | `interface.primaryInterface` |
| IPv4 Address | `interface.ipAddress` |
| IPv6 Address | `interface.ipv6Address` |
| MAC Address | `interface.macAddress` |
| Adapter | `interface.type.name` |
| IP Mode | `interface.ipMode` |
| Connected | `interface.active` |
| NIC ID | `interface.id` |
| Host, Cluster, Cloud, VM ID | As vInfo |

## vSnapshot — one row per snapshot

Source: `GET /api/instances/{id}/snapshots`. Empty when `--no-snapshots` is used.

| Column | Source |
|---|---|
| Instance | Owning `instance.name` |
| Name | `snapshot.name` |
| Description | `snapshot.description` |
| Date / time | `snapshot.snapshotCreated` → `snapshot.dateCreated` |
| Age (days) | Days between *Date / time* and collection time |
| Type | `snapshot.snapshotType` |
| State | `snapshot.state` → `snapshot.status` (9.1 reports `complete` in `status` and leaves `state` empty) |
| Size MiB | `snapshot.maxStorage` → sum of `snapshot.snapshotFiles[].fileSize` |
| Memory | `snapshot.memorySnapshot` (whether VM memory was captured) |
| Current | `snapshot.currentlyActive` |
| Cloud | `instance.cloud.name` |
| Snapshot ID | `snapshot.id` |
| External ID | `snapshot.externalId` |
| Instance ID | Owning `instance.id` |

## vHost — one row per hypervisor host

Capacity, usage and VM density per host. *vCPUs per Core* is the vCPU:pCore overcommit ratio.

| Column | Source |
|---|---|
| Host | `server.name` |
| Cloud | `server.zone.name` |
| Cluster | Cluster the host belongs to |
| Status | `server.status` |
| Powerstate | `server.powerState` (normalised) |
| IP Address | `server.internalIp` → `server.externalIp` → `server.sshHost` |
| CPU Model | `server.hardwareCpuModel` → `server.hostInfo.cpuModel` → `server.cpuModel` |
| CPU MHz | `server.hardwareCpuFrequency` |
| # Sockets | `server.maxSockets` → `server.maxCpu` → `server.hostInfo.cpuSockets` |
| # Cores | `server.maxCores` → `server.hostInfo.cpuCores` |
| CPU Usage % | `server.stats.cpuUsage`, rounded to one decimal place |
| Memory MiB | `server.stats.maxMemory` → `server.maxMemory` |
| Memory Used MiB | `server.stats.usedMemory` → `server.usedMemory` |
| Memory Usage % | Used ÷ Memory × 100 |
| Storage MiB / Storage Used MiB | `server.stats.maxStorage` / `server.stats.usedStorage` |
| # NICs | Count of `server.interfaces` |
| # VMs | VMs whose parent is this host |
| # VMs On | Those VMs that are `poweredOn` |
| # vCPUs | Sum of CPUs of those VMs |
| vCPUs per Core | # vCPUs ÷ # Cores |
| vRAM MiB | Sum of configured memory of those VMs |
| OS / OS Version | `server.serverOs.name` → `server.platform` → `server.osType` / `server.platformVersion` |
| Hypervisor | `server.computeServerType.name` |
| Agent Version | `server.agentVersion` |
| Vendor / Model | `server.hardwareProductVendor` / `server.hardwareProductName` (trailing whitespace removed). Nested or virtualised lab hosts report their hypervisor here, for example `VMware, Inc.` |
| Maintenance | `server.maintenanceMode` |
| Creation date | `server.dateCreated` |
| Host ID / External ID / UUID | `server.id` / `server.externalId` / `server.uuid` |
| Manager | Manager URL |

## vCluster — one row per cluster

| Column | Source |
|---|---|
| Cluster | `cluster.name` |
| Type | `cluster.type.name` |
| Layout | `cluster.layout.name` |
| Cloud | `cluster.zone.name` → `cluster.cloud.name` |
| Group | `cluster.site.name` → `cluster.group.name` |
| Status | `cluster.status` |
| Enabled | `cluster.enabled` |
| # Hosts | Count of `cluster.servers` |
| # VMs | VMs running on the cluster's hosts |
| Total Cores | Sum of member hosts' `maxCores` |
| Total Memory MiB | Sum of member hosts' `maxMemory` |
| # vCPUs / vRAM MiB | Sum of CPUs / configured memory of VMs on the cluster |
| Created | `cluster.dateCreated` |
| Cluster ID | `cluster.id` |

## vDatastore — one row per datastore

Sources: `GET /api/clusters/{id}/datastores` (each cluster's *Storage → Data Stores* tab — GFS2, OCFS2, NFS pools, local) merged with `GET /api/zones/{id}/data-stores` (cloud level). A datastore returned by both appears once.

| Column | Source |
|---|---|
| Name | `datastore.name` |
| Display Name | `datastore.displayName` |
| Type | `datastore.datastoreType.name` → `datastore.type` (ignored when it is the placeholder `generic`) → `datastoreType.code` → `externalType`. On 9.1 this gives `HPE Clustered Datastore (Shared LUN)` for GFS2, `NFS Datastore` and `Directory Datastore (Local)` |
| Status | `datastore.status`, or `online` if `datastore.online` is true |
| Accessible | `datastore.online` |
| Active | `datastore.active` |
| Capacity MiB | `datastore.storageSize` → `maxStorage` → `capacity` |
| Provisioned MiB | Sum of capacity of all VM disks placed on this datastore |
| In Use MiB | Capacity − Free |
| Free MiB | `datastore.freeSpace` |
| Free % | Free ÷ Capacity × 100 |
| % Provisioned | Provisioned ÷ Capacity × 100 (over 100 = thin overcommit) |
| # Disks / # VMs | VM disks / distinct VMs on this datastore |
| # Hosts / Hosts | Hosts of the cluster(s) the datastore is attached to |
| Cluster | Cluster(s) whose Storage tab lists the datastore |
| Cloud | `datastore.zone.name` |
| Address | `config.sourceHostname` → `sourceHost` → `host` → `serverHost` → `target` → `portal` (the NFS server; blank for block and local datastores) |
| Source | `config.sourceDirPath` → `exportFolder` → `sourceDir` → `blockDevice` (the NFS export, or the shared LUN's device such as `/dev/mapper/mpatha` for GFS2) |
| Path / URL | `externalPath` → `config.targetPath` → `path` → `mountPath` (where the datastore is mounted on each host) |
| Category, Visibility, Default, Allow Provision, Heartbeat Target | Matching datastore fields |
| Datastore ID / External ID | `datastore.id` / `datastore.externalId` |

## vNetworks — one row per network

Source: `GET /api/networks`.

| Column | Source |
|---|---|
| Network / Display Name | `network.name` / `network.displayName` |
| Type | `network.type.name` |
| Cloud | `network.zone.name` |
| CIDR, Gateway | `network.cidr`, `network.gateway` |
| DNS Primary / Secondary | `network.dnsPrimary` / `network.dnsSecondary` |
| VLAN ID | `network.vlanId` |
| DHCP | `network.dhcpServer` |
| IP Pool | `network.pool.name` |
| Active, Visibility | `network.active`, `network.visibility` |
| # NICs | VM NICs attached to this network (from vNetwork) |
| Network ID / External ID | `network.id` / `network.externalId` |

## vInstance — one row per Morpheus instance

An *instance* is Morpheus's provisioning unit; it usually contains one VM but can contain several.

| Column | Source |
|---|---|
| Instance / Display Name | `instance.name` / `instance.displayName` |
| Status | `instance.status` |
| Instance Type / Layout / Plan | `instanceType.name` / `layout.name` / `plan.name` |
| Group / Cloud | `group.name` / `cloud.name` |
| Environment | `instance.environment` |
| Created By / Owner | `createdBy.username` / `owner.username` |
| Created | `instance.dateCreated` |
| CPUs / Memory MiB / Storage MiB | `maxCores` / `maxMemory` / `maxStorage` |
| IP Addresses | `connectionInfo[].ip` (the `0.0.0.0` placeholder reported for VMs without an address is skipped) |
| # VMs / VMs | Member servers and their names |
| Hostname | `instance.hostName` |
| Tags / Labels / Description | Matching instance fields |
| Instance ID | `instance.id` |

## vHealth — one row per finding

Sorted Critical → Warning → Info and colour-coded. Filter on *Severity* for a quick health summary before a migration or audit.

| Check | Severity | Condition |
|---|---|---|
| Host status | Critical | Host status is not `provisioned`, `ok` or `running` |
| Host memory | Warning | Host memory usage above 90% |
| Host maintenance | Info | `server.maintenanceMode` is true |
| Datastore space | Warning | Datastore free space below 10% |
| Snapshot age | Warning | Snapshot older than `--snapshot-age` days (default 7) |
| VM status | Warning | VM status is not `provisioned`, `running`, `ok` or `stopped` |
| Missing agent | Warning | VM powered on with `agentInstalled = false` |
| Powered off | Info | VM is powered off |

## vSource — collection details

Manager URL, build version (`/api/ping`), appliance URL, connected user and tenant (`/api/whoami`), collection time (UTC), object counts, and the HVMTools version. Check *Build Version* first when a column is unexpectedly blank.

## raw_servers / raw_instances / raw_datastores (with `--raw`)

Every field returned by the API, flattened two levels deep (`zone.name`, `computeServerType.format`, …). Lists and deeper objects are stored as JSON text. Use these to find data that isn't mapped to a friendly column.

Credential-like fields are replaced with `<redacted>` before anything is written: any key containing `password`, `secret`, `token`, `apiKey`, `privateKey`, `serviceAccess` or `serviceCert`. On 9.1 that covers each server's agent `apiKey`, `sshPassword` and `sshPasswordHash`, and the cluster `service*` credentials. The same redaction applies to `--dump-json` files and to JSON loaded with `--from-json`.
