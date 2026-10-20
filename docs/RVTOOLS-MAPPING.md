# RVTools → HVMTools mapping

For VMware administrators used to RVTools, this is how the concepts and tabs line up in HPE VM Essentials.

## Concepts

| VMware / RVTools | HPE VM Essentials / HVMTools |
|---|---|
| vCenter (VI SDK Server) | VME manager (*Manager* column, vSource tab) |
| Datacenter | Cloud |
| Cluster | Cluster (HPE VM cluster) |
| ESXi host | HVM host (KVM-based; *Hypervisor* = `HVM Hypervisor`) |
| Maintenance mode | Maintenance mode (vHost *Maintenance*) |
| VM | VM (a Morpheus *server*) |
| — | Instance (Morpheus provisioning unit that owns one or more VMs) |
| Folder | Group |
| Resource pool | Resource pool |
| VMFS datastore | HPE Clustered Datastore (Shared LUN), i.e. GFS2 |
| NFS datastore | NFS Datastore |
| Local VMFS | Directory Datastore (Local) |
| Port group / dvPortgroup | Network |
| VMware Tools | Morpheus agent |
| Annotation | Description |
| Custom attributes / tags | Tags and labels |

## Tabs

| RVTools tab | HVMTools tab | Notes |
|---|---|---|
| vInfo | vInfo | Adds Instance, Instance Type, Layout, Plan, Environment, Owner, Labels |
| vCPU | vCPU | |
| vMemory | vMemory | Usage needs stats from the manager |
| vDisk | vDisk | Datastore and controller per disk |
| vPartition | — | Guest partitions aren't exposed by the API |
| vNetwork | vNetwork | Adds VLAN and IPv6 |
| vCD / vUSB | — | Not applicable |
| vSnapshot | vSnapshot | Collected per instance |
| vTools | vInfo (*Agent Installed*, *Agent Version*) | |
| vSource | vSource | |
| vRP | vInfo (*Resource Pool*) | |
| vCluster | vCluster | |
| vHost | vHost | CPU model, MHz, sockets, cores, vendor, model; no serial number (not in the API) |
| vHBA / vNIC / vSwitch / vPort | — | Physical host networking/HBA detail isn't exposed by the API |
| dvSwitch / dvPort | — | |
| vSC_VMK | — | |
| vDatastore | vDatastore | Includes cluster Storage-tab datastores (GFS2, NFS, local), NFS server/export or LUN device, and % Provisioned |
| vMultiPath | — | |
| vLicense | — | |
| vFileInfo | — | |
| vHealth | vHealth | Different checks; see [SHEETS.md](SHEETS.md#vhealth--one-row-per-finding) |
| — | vNetworks | Network definitions (CIDR, gateway, DNS, VLAN, pools) |
| — | vInstance | Morpheus instance view |

## Units

As in RVTools, capacity and memory are in **MiB**. To convert in Excel: GiB = MiB / 1024, TiB = MiB / 1,048,576.
