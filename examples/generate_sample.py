#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Graham Fairbank. See LICENSE for details.
"""
Generate examples/sample-inventory.json: a fictional but realistic VM Essentials
inventory for offline testing, documentation and screenshots.

The record shapes follow what a VM Essentials 9.1 manager returns from
/api/servers, /api/instances, /api/clusters, /api/networks and the datastore
endpoints. Every name, address and figure is invented; the output is
deterministic (fixed seed) so the docs and screenshots stay reproducible.

    python examples/generate_sample.py            # writes examples/sample-inventory.json
    python hvmtools.py --from-json examples/sample-inventory.json -o sample.xlsx
"""
import datetime as dt
import json
import os
import random
import uuid

GIB = 1024 ** 3
TIB = 1024 ** 4
COLLECTED = dt.datetime(2026, 10, 20, 9, 30, tzinfo=dt.timezone.utc)
rnd = random.Random(20261020)


def ts(days_ago, hours=0):
    return (COLLECTED - dt.timedelta(days=days_ago, hours=hours)).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def uid():
    return str(uuid.UUID(int=rnd.getrandbits(128), version=4))


def mac():
    return "52:54:00:" + ":".join(f"{rnd.randrange(256):02x}" for _ in range(3))


ids = {}


def next_id(kind, start=1):
    ids[kind] = ids.get(kind, start - 1) + 1
    return ids[kind]


# --------------------------------------------------------------------------- topology
CLOUDS = [{"id": 1, "name": "dc-east", "code": "standard", "zoneType": {"code": "standard", "name": "Private Cloud"},
           "status": "ok", "enabled": True},
          {"id": 2, "name": "dc-west", "code": "standard", "zoneType": {"code": "standard", "name": "Private Cloud"},
           "status": "ok", "enabled": True}]
GROUPS = [{"id": 1, "name": "Production"}, {"id": 2, "name": "Non-Production"}, {"id": 3, "name": "Disaster Recovery"}]

HW = {
    "gen11-intel": {"vendor": "HPE", "model": "ProLiant DL380 Gen11", "cpu": "INTEL(R) XEON(R) GOLD 6530",
                    "mhz": 2100.0, "sockets": 2, "cores": 64, "mem": 1024 * GIB},
    "gen11-amd": {"vendor": "HPE", "model": "ProLiant DL365 Gen11", "cpu": "AMD EPYC 9354 32-Core Processor",
                  "mhz": 3250.0, "sockets": 2, "cores": 64, "mem": 768 * GIB},
    "gen10": {"vendor": "HPE", "model": "ProLiant DL360 Gen10 Plus", "cpu": "Intel(R) Xeon(R) Gold 6338 CPU @ 2.00GHz",
              "mhz": 2000.0, "sockets": 2, "cores": 64, "mem": 512 * GIB},
}

CLUSTERS = [
    {"name": "east-prod-01", "cloud": 1, "group": 1, "hosts": 6, "hw": "gen11-intel", "net": "10.10", "load": 0.62},
    {"name": "east-prod-02", "cloud": 1, "group": 1, "hosts": 4, "hw": "gen11-amd", "net": "10.11", "load": 0.55},
    {"name": "east-dev-01", "cloud": 1, "group": 2, "hosts": 3, "hw": "gen10", "net": "10.12", "load": 0.40},
    {"name": "west-dr-01", "cloud": 2, "group": 3, "hosts": 4, "hw": "gen11-intel", "net": "10.20", "load": 0.18},
]

NETWORKS = [
    # name, cloud, vlan, cidr, gateway
    ("Management", 1, 10, "10.10.0.0/24", "10.10.0.1"),
    ("VM-Prod-Web", 1, 110, "10.10.110.0/24", "10.10.110.1"),
    ("VM-Prod-App", 1, 120, "10.10.120.0/24", "10.10.120.1"),
    ("VM-Prod-DB", 1, 130, "10.10.130.0/24", "10.10.130.1"),
    ("VM-Dev", 1, 210, "10.12.210.0/23", "10.12.210.1"),
    ("Backup", 1, 300, "10.10.30.0/24", None),
    ("DR-Management", 2, 10, "10.20.0.0/24", "10.20.0.1"),
    ("DR-VM", 2, 410, "10.20.110.0/24", "10.20.110.1"),
]

# app, count, cores, mem GiB, disks GiB, os, network, group cluster
WORKLOADS = [
    ("web", 12, 2, 4, [40], "rhel9", "VM-Prod-Web", "east-prod-01"),
    ("api", 10, 4, 8, [40, 50], "rhel9", "VM-Prod-App", "east-prod-01"),
    ("pay", 6, 8, 16, [60, 100], "rhel9", "VM-Prod-App", "east-prod-01"),
    ("sqlprd", 4, 16, 128, [80, 500, 200], "win2022", "VM-Prod-DB", "east-prod-01"),
    ("pgprd", 4, 8, 64, [60, 400], "ubuntu2404", "VM-Prod-DB", "east-prod-02"),
    ("k8s-w", 12, 8, 32, [120], "ubuntu2404", "VM-Prod-App", "east-prod-02"),
    ("k8s-cp", 3, 4, 16, [80], "ubuntu2404", "VM-Prod-App", "east-prod-02"),
    ("kafka", 6, 8, 32, [60, 1000], "rhel9", "VM-Prod-App", "east-prod-02"),
    ("ad-dc", 2, 2, 8, [80], "win2022", "Management", "east-prod-02"),
    ("files", 2, 4, 16, [80, 2000], "win2022", "VM-Prod-App", "east-prod-02"),
    ("jump", 2, 2, 8, [80], "win2022", "Management", "east-prod-01"),
    ("dev-web", 14, 2, 4, [40], "rhel9", "VM-Dev", "east-dev-01"),
    ("dev-api", 10, 2, 8, [40], "ubuntu2404", "VM-Dev", "east-dev-01"),
    ("dev-sql", 3, 4, 32, [80, 200], "win2022", "VM-Dev", "east-dev-01"),
    ("ci-runner", 8, 8, 16, [200], "ubuntu2404", "VM-Dev", "east-dev-01"),
    ("dr-web", 6, 2, 4, [40], "rhel9", "DR-VM", "west-dr-01"),
    ("dr-sql", 2, 16, 128, [80, 500, 200], "win2022", "DR-VM", "west-dr-01"),
    ("dr-pg", 2, 8, 64, [60, 400], "ubuntu2404", "DR-VM", "west-dr-01"),
]

OS = {
    "rhel9": {"platform": "rhel", "version": "9.4", "osType": "linux",
              "serverOs": {"code": "rhel.9.64", "name": "redhat 9 64-bit", "vendor": "redhat", "platform": "linux"},
              "image": "RHEL 9.4", "type": "HVM"},
    "ubuntu2404": {"platform": "ubuntu", "version": "24.04", "osType": "linux",
                   "serverOs": {"code": "ubuntu.24.04.64", "name": "ubuntu 24.04 64-bit", "vendor": "canonical",
                                "platform": "linux"}, "image": "Ubuntu 24.04", "type": "HVM"},
    "win2022": {"platform": "windows", "version": "2022", "osType": "windows",
                "serverOs": {"code": "windows.server.2022", "name": "windows server 2022", "vendor": "microsoft",
                             "platform": "windows"}, "image": "Windows Server 2022", "type": "HVM"},
}

OWNERS = ["jsmith", "apatel", "mgarcia", "lchen", "svc-terraform"]


def plan_for(cores, mem_gib):
    return {"id": 100 + cores * 10 + mem_gib // 4, "code": f"kvm-vm-{mem_gib * 1024}",
            "name": f"{cores} CPU, {mem_gib}GB Memory"}


def main():
    inv = {"collected": COLLECTED.strftime("%Y-%m-%dT%H:%M:%SZ"), "manager": "https://hvm-manager.example.com",
           "ping": {"success": True, "buildVersion": "9.1.0.1"},
           "whoami": {"user": {"id": 2, "username": "svc-inventory", "displayName": "Inventory service account",
                               "account": {"id": 1, "name": "Example Corp"}}},
           "clouds": CLOUDS, "groups": GROUPS, "clusters": [], "servers": [], "instances": [],
           "networks": [], "datastores": [], "snapshots": {}}
    cloud = {c["id"]: {"id": c["id"], "name": c["name"], "type": "standard"} for c in CLOUDS}
    group = {gr["id"]: gr for gr in GROUPS}

    # networks
    net = {}
    for name, cl, vlan, cidr, gw in NETWORKS:
        n = {"id": next_id("net"), "name": name, "displayName": None, "zone": {"id": cl, "name": cloud[cl]["name"]},
             "type": {"id": 37, "name": "HVM Standard Network", "code": "ovsPortGroup"}, "cidr": cidr,
             "gateway": gw, "dnsPrimary": "10.10.0.53" if gw else None, "dnsSecondary": "10.20.0.53" if gw else None,
             "vlanId": vlan, "dhcpServer": name in ("VM-Dev",), "active": True, "visibility": "private",
             "externalId": name, "bridgeName": "hvmbr0", "pool": {"id": vlan, "name": f"{name}-pool"} if gw else None,
             "config": {"hvmNetworkLayout": "linux-bridge"}}
        net[name] = n
        inv["networks"].append(n)

    # clusters + hosts + datastores
    hosts_by_cluster, cl_by_name, ds_by_cluster = {}, {}, {}
    for spec in CLUSTERS:
        hw = HW[spec["hw"]]
        cid = next_id("cluster")
        cl = {"id": cid, "uuid": uid(), "name": spec["name"], "type": {"id": 4, "name": "HVM"},
              "layout": {"id": 256, "name": "HVM 2.0 Cluster on HVM/Ubuntu 24.04"},
              "zone": {"id": spec["cloud"], "name": cloud[spec["cloud"]]["name"]},
              "site": {"id": spec["group"], "name": group[spec["group"]]["name"]},
              "status": "ok", "enabled": True, "managed": True, "dateCreated": ts(400 - cid * 40), "servers": []}
        hosts = []
        for n in range(1, spec["hosts"] + 1):
            hid = next_id("server")
            name = f"{spec['name']}-h{n:02d}"
            ip = f"{spec['net']}.0.{10 + n}"
            h = {"id": hid, "uuid": uid(), "externalId": None, "name": name, "externalName": name, "hostname": name,
                 "serverGroup": {"id": cid, "name": spec["name"]},
                 "account": {"id": 1, "name": "Example Corp"}, "zone": cloud[spec["cloud"]],
                 "plan": {"id": 2, "code": "manual-default", "name": "Default Manual"},
                 "computeServerType": {"id": 18, "code": "mvmHost", "name": "HVM Hypervisor", "managed": True,
                                       "format": "vmHypervisor"},
                 "sshHost": ip, "externalIp": ip, "internalIp": ip, "platform": "ubuntu", "platformVersion": "24.04",
                 "osType": "linux", "serverOs": None, "dateCreated": ts(400 - cid * 40, n), "lastUpdated": ts(0, 1),
                 "status": "provisioned", "powerState": "on", "agentInstalled": True, "agentVersion": "4.0.1",
                 "maxCores": hw["cores"], "maxSockets": hw["sockets"], "coresPerSocket": hw["cores"] // hw["sockets"],
                 "maxMemory": hw["mem"], "maxStorage": 960 * GIB,
                 "hardwareCpuModel": hw["cpu"], "hardwareProductVendor": hw["vendor"] + "\n",
                 "hardwareProductName": hw["model"] + "\n", "hardwareCpuFrequency": hw["mhz"],
                 "maintenanceMode": False, "serverGroupMemberStatus": "online",
                 "volumes": [{"id": next_id("vol"), "name": "disk-sda", "rootVolume": True, "deviceName": "/dev/sda",
                              "deviceDisplayName": "sda", "maxStorage": 960 * GIB, "type": {"name": "Disk"},
                              "status": "provisioned"}],
                 "interfaces": [{"id": next_id("nic"), "name": nm, "ipAddress": ip if nm == "hvmbr0" else None,
                                 "macAddress": f"94:40:c9:{rnd.randrange(256):02x}:{rnd.randrange(256):02x}:{rnd.randrange(256):02x}",
                                 "active": True, "type": {"code": "bridge" if nm.startswith("hvm") else "ethernet",
                                                          "name": "bridge" if nm.startswith("hvm") else "ethernet"}}
                                for nm in ("hvmbr0", "ens1f0np0", "ens1f1np1", "ens2f0np0")],
                 "tags": [], "labels": [], "containers": [], "instance": None, "apiKey": uid(),
                 "sshUsername": "hvmadmin", "sshPassword": "************", "sshPasswordHash": uid().replace("-", "")}
            hosts.append(h)
            cl["servers"].append({"id": hid, "name": name, "computeServerType": {"code": "mvmHost"},
                                  "serverGroupMemberStatus": "online"})
        # one DR host in maintenance, one prod host with a failed status
        if spec["name"] == "west-dr-01":
            hosts[-1]["maintenanceMode"] = True
        if spec["name"] == "east-dev-01":
            hosts[-1]["status"] = "error"
            hosts[-1]["statusMessage"] = "Agent heartbeat missed"
        hosts_by_cluster[spec["name"]] = hosts
        cl_by_name[spec["name"]] = cl
        inv["clusters"].append(cl)
        inv["servers"].extend(hosts)

        # datastores: shared GFS2 LUN, local directory, plus NFS for prod-01 / dev
        dss = []
        size = {"east-prod-01": 40 * TIB, "east-prod-02": 24 * TIB, "east-dev-01": 8 * TIB, "west-dr-01": 30 * TIB}
        gfs = {"id": next_id("ds"), "name": f"{spec['name']}-gfs2-01", "type": "generic",
               "datastoreType": {"id": 5, "code": "libvirt-dir-gfs2", "name": "HPE Clustered Datastore (Shared LUN)",
                                 "diskType": "file"},
               "config": {"blockDevice": f"/dev/mapper/mpath{chr(96 + cid)}", "allowReformat": False},
               "storageSize": size[spec["name"]], "externalPath": f"/mnt/{uid()}"}
        dss.append(gfs)
        if spec["name"] in ("east-prod-01", "east-prod-02"):
            dss.append({"id": next_id("ds"), "name": f"{spec['name']}-gfs2-02", "type": "generic",
                        "datastoreType": dict(gfs["datastoreType"]),
                        "config": {"blockDevice": f"/dev/mapper/mpath{chr(100 + cid)}", "allowReformat": False},
                        "storageSize": 16 * TIB, "externalPath": f"/mnt/{uid()}"})
        dss.append({"id": next_id("ds"), "name": f"{spec['name']}-local", "type": "Directory Datastore (Local)",
                    "datastoreType": {"id": 1, "code": "libvirt-dir", "name": "Directory Datastore (Local)",
                                      "diskType": "file"},
                    "config": {"targetPath": "/var/morpheus/kvm/local"}, "storageSize": 800 * GIB,
                    "externalPath": "/var/morpheus/kvm/local"})
        for d in dss:
            d.update({"visibility": "private", "active": True, "online": True, "allowProvision": True,
                      "defaultStore": d is gfs, "heartbeatTarget": d is gfs, "status": "provisioned",
                      "externalId": d["name"], "refType": "ComputeServerGroup", "refId": cid,
                      "zone": {"id": spec["cloud"], "name": cloud[spec["cloud"]]["name"]},
                      "_clusters": [spec["name"]]})
        ds_by_cluster[spec["name"]] = dss
        inv["datastores"].extend(dss)

    nfs = {"id": next_id("ds"), "name": "nfs-isos", "type": "generic",
           "datastoreType": {"id": 3, "code": "libvirt-netfs-nfs", "name": "NFS Datastore", "diskType": "file"},
           "config": {"sourceHostname": "nas01.example.com", "sourceVersion": "4.1", "sourceDirPath": "/export/isos"},
           "storageSize": 4 * TIB, "externalPath": f"/mnt/{uid()}", "visibility": "private", "active": True,
           "online": True, "allowProvision": False, "defaultStore": False, "heartbeatTarget": False,
           "status": "provisioned", "externalId": "nfs-isos", "zone": cloud[1],
           "_clusters": ["east-prod-01", "east-prod-02", "east-dev-01"]}
    nfs_bk = dict(nfs, id=next_id("ds"), name="nfs-backup", externalId="nfs-backup", storageSize=20 * TIB,
                  config={"sourceHostname": "nas02.example.com", "sourceVersion": "4.1",
                          "sourceDirPath": "/export/vme-backup"}, externalPath=f"/mnt/{uid()}",
                  _clusters=["east-prod-01", "east-prod-02"])
    inv["datastores"] += [nfs, nfs_bk]

    # VMs + instances
    used_ds = {}
    vm_count = 0
    for app, count, cores, mem_gib, disks, osk, netname, clname in WORKLOADS:
        os_ = OS[osk]
        hosts = hosts_by_cluster[clname]
        dss = [d for d in ds_by_cluster[clname] if "gfs2" in d["name"]]
        load = next(c["load"] for c in CLUSTERS if c["name"] == clname)
        env = {"east-prod-01": "Production", "east-prod-02": "Production", "east-dev-01": "Development",
               "west-dr-01": "DR"}[clname]
        for n in range(1, count + 1):
            vm_count += 1
            sid, iid = next_id("server"), next_id("inst")
            name = f"{app}{n:02d}"
            host = hosts[(vm_count + n) % len(hosts)]
            if host.get("maintenanceMode"):
                host = hosts[0]
            created = rnd.randint(5, 700)
            off = (env == "DR" and rnd.random() < 0.5) or rnd.random() < 0.04
            agent = os_["osType"] == "linux" and rnd.random() < 0.85 or os_["osType"] == "windows" and rnd.random() < 0.6
            mem = mem_gib * GIB
            used_mem = 0 if off else int(mem * min(0.97, max(0.08, rnd.gauss(load, 0.18))))
            if app == "sqlprd":
                used_mem = 0 if off else int(mem * 0.95)
            cpu = 0.0 if off else round(min(98.0, max(0.5, rnd.gauss(load * 45, 15))), 6)
            octet = 10 + n + (30 if app.endswith(("api", "pay", "k8s-w")) else 0)
            base = net[netname]["cidr"].rsplit(".", 2)[0]
            ip = f"{base}.{net[netname]['cidr'].split('.')[2]}.{octet}"
            ctrl = {"id": next_id("ctrl"), "name": "VIRTIO 0"}
            vols = []
            for di, gb in enumerate(disks):
                d = dss[(n + di) % len(dss)]
                vols.append({"id": next_id("vol"), "name": "root" if di == 0 else f"data-{di}",
                             "controller": ctrl, "controllerId": ctrl["id"], "rootVolume": di == 0,
                             "unitNumber": str(di), "deviceName": f"/dev/vd{chr(97 + di)}",
                             "deviceDisplayName": f"vd{chr(97 + di)}", "volumeType": "disk",
                             "diskMode": "virtio", "type": {"id": 1, "code": "standard", "name": "Disk"},
                             "status": "provisioned", "maxStorage": gb * GIB,
                             "usedStorage": int(gb * GIB * rnd.uniform(0.15, 0.8)),
                             "datastore": {"id": d["id"], "name": d["name"]}, "datastoreId": d["id"],
                             "externalId": f"{name}/hvm_{iid}-disk-{di}"})
                used_ds[d["id"]] = used_ds.get(d["id"], 0) + gb * GIB
            if os_["osType"] == "linux" and rnd.random() < 0.3:
                vols.append({"id": next_id("vol"), "name": "CD ROM", "rootVolume": False, "deviceName": "/dev/sda",
                             "deviceDisplayName": "sda", "volumeType": "cdrom", "type": {"name": "CD ROM"},
                             "status": "provisioned", "maxStorage": 0, "usedStorage": 0})
            nics = [{"id": next_id("nic"), "name": "eth0", "network": {"id": net[netname]["id"], "name": netname},
                     "ipAddress": None if off else ip, "ipv6Address": None, "macAddress": mac(),
                     "primaryInterface": True, "active": True, "dhcp": netname == "VM-Dev",
                     "ipMode": "dhcp" if netname == "VM-Dev" else "static",
                     "type": {"id": 10, "code": "virtio", "name": "Virt IO"}}]
            if app in ("sqlprd", "pgprd", "files", "dr-sql", "dr-pg", "kafka"):
                nics.append({"id": next_id("nic"), "name": "eth1", "network": {"id": net["Backup"]["id"],
                                                                                   "name": "Backup"},
                             "ipAddress": None if off else f"10.10.30.{octet + 100}", "macAddress": mac(),
                             "primaryInterface": False, "active": True, "ipMode": "static",
                             "type": {"id": 10, "code": "virtio", "name": "Virt IO"}})
            owner = rnd.choice(OWNERS)
            tags = [{"name": "app", "value": app.split("-")[0].rstrip("0123456789")},
                    {"name": "env", "value": env.lower()}]
            if rnd.random() < 0.5:
                tags.append({"name": "costcentre", "value": rnd.choice(["CC-1001", "CC-2040", "CC-3300"])})
            plan = plan_for(cores, mem_gib)
            vm = {"id": sid, "uuid": uid(), "externalId": name, "internalId": f"/var/morpheus/kvm/vms/{name}",
                  "name": name, "externalName": name, "hostname": name,
                  "parentServer": {"id": host["id"], "name": host["name"]},
                  "account": {"id": 1, "name": "Example Corp"}, "owner": {"id": 3, "username": owner},
                  "zone": host["zone"], "plan": plan,
                  "computeServerType": {"id": 65, "code": "morpheusVm", "name": "KVM Instance", "managed": True,
                                        "format": "guestVm"},
                  "internalIp": None if off else ip, "externalIp": None if off else ip,
                  "platform": os_["platform"], "platformVersion": os_["version"], "osType": os_["osType"],
                  "serverOs": os_["serverOs"], "sourceImage": {"name": os_["image"]},
                  "dateCreated": ts(created), "lastUpdated": ts(rnd.randint(0, 3), rnd.randint(0, 23)),
                  "status": "provisioned" if not off else "stopped", "powerState": "off" if off else "on",
                  "agentInstalled": agent, "agentVersion": "4.0.1" if agent else None,
                  "guestAgentStatus": "connected" if not off else "disconnected",
                  "maxCores": cores, "coresPerSocket": cores if cores <= 4 else cores // 2,
                  "maxMemory": mem, "maxStorage": sum(disks) * GIB,
                  "stats": {"ts": ts(0), "running": not off, "cpuUsage": cpu, "usedMemory": used_mem,
                            "freeMemory": mem - used_mem, "maxMemory": mem, "maxStorage": sum(disks) * GIB,
                            "usedStorage": sum(v["usedStorage"] for v in vols)},
                  "volumes": vols, "controllers": [ctrl], "interfaces": nics, "tags": tags, "labels": [],
                  "description": None, "containers": [iid], "instance": {"id": iid, "name": name},
                  "uefi": os_["osType"] == "windows", "secureBoot": os_["osType"] == "windows",
                  "tpm": os_["osType"] == "windows", "maintenanceMode": False, "apiKey": uid()}
            if rnd.random() < 0.06 and not off:
                vm["status"] = "warning"
                vm["statusMessage"] = "Guest agent not responding"
            inv["servers"].append(vm)
            inv["instances"].append({
                "id": iid, "uuid": uid(), "name": name, "displayName": name,
                "instanceType": {"id": 10, "code": "mvm", "name": os_["type"]},
                "group": group[next(c["group"] for c in CLUSTERS if c["name"] == clname)],
                "cloud": host["zone"], "cluster": {"id": cl_by_name[clname]["id"], "name": clname},
                "containers": [iid], "servers": [sid], "layout": {"id": 31, "name": "Single HVM"}, "plan": plan,
                "environment": env, "createdBy": {"id": 3, "username": owner}, "owner": {"id": 3, "username": owner},
                "connectionInfo": [{"ip": ip if not off else "0.0.0.0", "port": 22 if os_["osType"] == "linux" else 3389}],
                "maxCores": cores, "coresPerSocket": vm["coresPerSocket"], "maxMemory": mem,
                "maxStorage": sum(disks) * GIB, "status": "stopped" if off else "running",
                "dateCreated": ts(created), "lastUpdated": vm["lastUpdated"], "hostName": name,
                "tags": tags, "labels": [], "description": None,
                "stats": {"usedMemory": used_mem, "maxMemory": mem, "cpuUsage": cpu, "usedCpu": cpu}})

            # snapshots: a few pre-patch snapshots, some forgotten
            if rnd.random() < 0.12:
                age = rnd.choice([1, 2, 3, 12, 35, 96, 210])
                inv["snapshots"][str(iid)] = [{
                    "id": next_id("snap"), "name": rnd.choice(["pre-patch", "before-upgrade", "change-CHG0041"]),
                    "description": "Taken before maintenance", "snapshotCreated": ts(age),
                    "snapshotType": "vm", "state": "available", "currentlyActive": True,
                    "externalId": f"{name}-snap-{age}"}]

    # one unmanaged (discovered) VM: the VME manager appliance itself
    h0 = hosts_by_cluster["east-prod-01"][0]
    mgr_id = next_id("server")
    inv["servers"].append({
        "id": mgr_id, "uuid": uid(), "externalId": "hvm-manager", "name": "hvm-manager", "hostname": "hvm-manager",
        "parentServer": {"id": h0["id"], "name": h0["name"]}, "zone": h0["zone"],
        "plan": {"id": 24, "code": "kvm-vm-16384", "name": "4 CPU, 16GB Memory"},
        "computeServerType": {"id": 21, "code": "kvmUnmanaged", "name": "KVM Instance", "managed": False,
                              "format": "guestVm"},
        "internalIp": "10.10.0.5", "externalIp": "10.10.0.5", "sshHost": "10.10.0.5", "platform": "ubuntu",
        "platformVersion": "24.04", "osType": "linux",
        "serverOs": {"code": "ubuntu.24.04.64", "name": "ubuntu 24.04 64-bit"},
        "dateCreated": ts(410), "lastUpdated": ts(0, 2), "status": "provisioned", "powerState": "on",
        "agentInstalled": False, "maxCores": 4, "maxMemory": 16 * GIB, "maxStorage": 250 * GIB, "appliance": True,
        "stats": {"cpuUsage": 31.4, "usedMemory": int(11.2 * GIB), "maxMemory": 16 * GIB,
                  "usedStorage": 61 * GIB, "maxStorage": 250 * GIB},
        "volumes": [{"id": next_id("vol"), "name": "HPE_Morpheus_Manager-9.1.0-1.qcow2", "rootVolume": True,
                     "deviceDisplayName": "sda", "maxStorage": 250 * GIB, "usedStorage": 61 * GIB,
                     "type": {"name": "Disk"}, "status": "provisioned",
                     "datastore": {"id": ds_by_cluster["east-prod-01"][-1]["id"],
                                   "name": ds_by_cluster["east-prod-01"][-1]["name"]},
                     "datastoreId": ds_by_cluster["east-prod-01"][-1]["id"]}],
        "interfaces": [{"id": next_id("nic"), "name": "eth0", "network": {"id": net["Management"]["id"],
                                                                         "name": "Management"},
                        "ipAddress": "10.10.0.5", "macAddress": mac(), "primaryInterface": True, "active": True,
                        "type": {"name": "Virt IO"}}],
        "tags": [], "labels": [], "containers": [], "instance": None})

    # host usage from the VMs placed on them
    vms = [s for s in inv["servers"] if s.get("parentServer")]
    for h in (s for s in inv["servers"] if not s.get("parentServer")):
        mine = [v for v in vms if v["parentServer"]["id"] == h["id"] and v["powerState"] == "on"]
        used = sum(v["stats"]["usedMemory"] for v in mine) + 48 * GIB
        if h["name"] == "east-prod-01-h02":
            used = int(h["maxMemory"] * 0.93)  # one hot host for vHealth
        cpu = 0.0 if h.get("maintenanceMode") else round(min(95, 4 + sum(v["stats"]["cpuUsage"] * v["maxCores"]
                                                                         for v in mine) / h["maxCores"]), 6)
        h["stats"] = {"ts": ts(0), "usedMemory": min(used, h["maxMemory"]), "maxMemory": h["maxMemory"],
                      "freeMemory": max(0, h["maxMemory"] - used), "cpuUsage": cpu,
                      "maxStorage": h["maxStorage"], "usedStorage": int(h["maxStorage"] * rnd.uniform(0.1, 0.3))}
        h["containers"] = [v["instance"]["id"] for v in mine if v.get("instance")]

    # datastore free space from what was placed on them; one nearly full
    for d in inv["datastores"]:
        used = used_ds.get(d["id"], 0) * 0.55 + d["storageSize"] * rnd.uniform(0.02, 0.1)
        if d["name"] == "east-prod-02-gfs2-02":
            used = d["storageSize"] * 0.94
        if d["name"] == "nfs-backup":
            used = d["storageSize"] * 0.71
        d["freeSpace"] = int(max(0, d["storageSize"] - used))

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample-inventory.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(inv, f, indent=1)
    print(f"Wrote {out}: {len(inv['clusters'])} clusters, "
          f"{sum(1 for s in inv['servers'] if not s.get('parentServer'))} hosts, {len(vms)} VMs, "
          f"{len(inv['instances'])} instances, {len(inv['datastores'])} datastores, {len(inv['networks'])} networks")


if __name__ == "__main__":
    main()
