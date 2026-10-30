// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Graham Fairbank. See LICENSE for details.
package com.hvmtools.report

import java.time.*
import java.util.regex.Pattern

/** Native inventory-to-row mapping, preserving the standalone CLI's column order. */
class InventoryReport {
    final Clock clock
    InventoryReport(Clock clock = Clock.systemUTC()) { this.clock = clock }

    static final Pattern SECRET = Pattern.compile('password|passwd|secret|token|apikey|api_key|privatekey|serviceaccess|servicecert', Pattern.CASE_INSENSITIVE)
    static final List<String> TABS = ['vInfo', 'vCPU', 'vMemory', 'vDisk', 'vNetwork', 'vSnapshot',
                                    'vHost', 'vCluster', 'vDatastore', 'vNetworks', 'vInstance', 'vHealth', 'vSource']

    // Unlike Groovy's Elvis operator, Python first() retains false and numeric zero.
    static Object first(Object... values) {
        values.find { it != null && it != '' && it != [] && it != [:] }
    }

    static Object g(Object value, Object... path) {
        Object cur = value
        for (Object key : path) {
            if (cur instanceof Map) cur = cur.get(key)
            else if (cur instanceof List && key instanceof Number) {
                int idx = ((Number) key).intValue()
                if (idx < 0) idx += cur.size()
                cur = idx >= 0 && idx < cur.size() ? cur[idx] : null
            } else return null
            if (cur == null) return null
        }
        cur
    }

    static Long integer(Object value) {
        if (value == null || value == '') return null
        if (value instanceof Boolean) return value ? 1L : 0L
        try {
            double number = Double.parseDouble(value.toString())
            Double.isFinite(number) ? (long) number : null
        } catch (NumberFormatException ignored) { null }
    }

    static Double rounded(Object value, int digits = 1) {
        if (value == null || value == '') return null
        try {
            double n = value instanceof Boolean ? (value ? 1d : 0d) : Double.parseDouble(value.toString())
            if (!Double.isFinite(n)) return n
            // BigDecimal(double) retains the binary value, as Python round() does.
            new BigDecimal(n).setScale(digits, java.math.RoundingMode.HALF_EVEN).doubleValue()
        } catch (NumberFormatException ignored) { null }
    }

    static Long mib(Object value) {
        Long n = integer(value)
        n == null ? null : (long) Math.rint(n / 1048576d)
    }

    static Double pct(Object used, Object total) {
        Long u = integer(used), t = integer(total)
        u != null && t ? rounded(u / (double) t * 100d) : null
    }

    static Object date(Object value) {
        if (!(value instanceof String) || !value) return null
        String s = value.replace(' ', 'T')
        try { return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime() }
        catch (java.time.format.DateTimeParseException ignored) {
            try { return LocalDateTime.parse(s) }
            catch (java.time.format.DateTimeParseException ignoredAgain) {
                try { return LocalDate.parse(s).atStartOfDay() }
                catch (java.time.format.DateTimeParseException invalid) { return value }
            }
        }
    }

    static Object power(Object value) {
        if (!value) return null
        ['on': 'poweredOn', 'off': 'poweredOff', 'paused': 'suspended', 'suspended': 'suspended'].get(value.toString().toLowerCase(), value)
    }
    static Object name(Object obj) { obj instanceof Map ? first(obj.name, obj.displayName, obj.username) : obj }
    static Object strip(Object value) { value instanceof String ? (value.trim() ?: null) : value }
    static String pyString(Object value) {
        value == null ? 'None' : value instanceof Boolean ? (value ? 'True' : 'False') : value.toString()
    }
    static Object tags(Object value) {
        if (!value) return null
        if (!(value instanceof List)) return pyString(value)
        value.collect { t ->
            if (!(t instanceof Map)) return pyString(t)
            def k = t.name ?: t.key
            t.value != null && t.value != '' ? "${pyString(k)}=${pyString(t.value)}".toString() : pyString(k)
        }.join('; ')
    }

    /** Returns a copy; callers' collected inventory is never modified. */
    static Object redact(Object value) {
        if (value instanceof Map) {
            Map out = [:]
            value.each { k, v ->
                out[k] = SECRET.matcher(k.toString()).find() && first(v) != null ? '<redacted>' : redact(v)
            }
            return out
        }
        if (value instanceof List) return value.collect { redact(it) }
        value
    }

    private static Map index(List values) { values.collectEntries { [(it.id): it] } }
    private static Map emptyStats() { [vms: 0L, vcpu: 0L, vram: 0L, on: 0L] }
    private static void health(List rows, Object entity, String type, String severity, String message) {
        rows << [Entity: entity, Type: type, Severity: severity, Message: message]
    }

    Map<String, List<Map>> buildSheets(Map inventory, int snapshotAgeDays = 7, int dsFreePct = 10) {
        Map inv = (Map) redact(inventory)
        List servers = inv.servers ?: [], instances = inv.instances ?: [], clusters = inv.clusters ?: []
        List datastores = inv.datastores ?: [], networks = inv.networks ?: []
        def manager = inv.manager
        Map srvById = index(servers), dsById = index(datastores), netById = index(networks), groupById = index(inv.groups ?: [])
        Set hostIds = servers.collect { g(it, 'parentServer', 'id') }.findAll { it != null }.toSet()
        servers.each {
            if (g(it, 'computeServerType', 'vmHypervisor') == Boolean.TRUE ||
                    g(it, 'computeServerType', 'format') == 'vmHypervisor') hostIds.add(it.id)
        }
        List hosts = servers.findAll { hostIds.contains(it.id) }, vms = servers.findAll { !hostIds.contains(it.id) }
        Map hostCluster = [:], instByServer = [:], instServers = [:]
        clusters.each { cl -> (cl.servers ?: []).each { m -> hostCluster[m instanceof Map ? m.id : m] = cl.name } }
        def clusterOf = { s -> first(name(s.cluster), name(s.serverGroup), hostCluster[s.id], hostCluster[g(s, 'parentServer', 'id')]) }
        instances.each { i ->
            (i.servers ?: []).each { sid -> instByServer[sid instanceof Map ? sid.id : sid] = i }
            (i.containers ?: []).each { ct ->
                def sid = g(ct, 'server', 'id')
                if (sid && !instByServer.containsKey(sid)) instByServer[sid] = i
            }
        }
        instByServer.each { sid, i -> instServers[i.id] = (instServers[i.id] ?: 0) + 1 }
        def stat = { s, inst, List keys ->
            List vals = keys.collect { g(s, 'stats', it) } + [s[keys[0]]]
            if (inst && instServers[inst.id] == 1) vals.addAll(keys.collect { g(inst, 'stats', it) })
            first(*vals)
        }
        def hostName = { s -> first(g(s, 'parentServer', 'name'), name(srvById[g(s, 'parentServer', 'id')])) }
        def cloudName = { s -> first(g(s, 'zone', 'name'), g(s, 'cloud', 'name')) }
        def dsName = { v -> first(g(v, 'datastore', 'name'), name(dsById[first(v.datastoreId, g(v, 'datastore', 'id'))])) }
        Map<String, List<Map>> rows = TABS.collectEntries { [(it): []] }
        List healthRows = rows.vHealth
        Map hostVmStats = [:]
        vms.each { s ->
            Map inst = instByServer[s.id] ?: [:]
            List vols = s.volumes ?: [], nics = s.interfaces ?: []
            def ps = power(s.powerState)
            Long cores = integer(first(s.maxCores, inst.maxCores, g(inst, 'plan', 'maxCores')))
            Long cps = integer(first(s.coresPerSocket, inst.coresPerSocket)) ?: null
            Long mem = integer(first(s.maxMemory, inst.maxMemory))
            def usedMem = stat(s, inst, ['usedMemory']), cpuUse = stat(s, inst, ['cpuUsage', 'usedCpu'])
            def cluster = clusterOf(s), host = hostName(s)
            Map common = ['Host': host, 'Cluster': cluster, 'Cloud': cloudName(s), 'VM ID': s.id]
            List netnames = nics.collect { n -> first(g(n, 'network', 'name'), name(netById[g(n, 'network', 'id')])) }
            List ipList = ([s.internalIp, s.externalIp] + nics.collect { it.ipAddress }).findAll { it }.unique()
            def group = first(g(inst, 'group', 'name'), g(s, 'site', 'name'), name(groupById[g(s, 'site', 'id')]))
            def vm = first(s.displayName, s.name)
            Long sockets = cores && cps ? Math.floorDiv(cores, cps) : null
            rows.vInfo << [
                'VM': vm, 'Powerstate': ps, 'Status': s.status, 'Template': false,
                'DNS Name': first(s.hostname, s.externalFqdn), 'Agent Installed': s.agentInstalled, 'Agent Version': s.agentVersion,
                'Creation date': date(s.dateCreated), 'Last Updated': date(s.lastUpdated),
                'CPUs': cores, 'Sockets': sockets, 'Cores per Socket': cps, 'Memory MiB': mib(mem),
                'NICs': nics.size(), 'Disks': vols.size(),
                'Total disk capacity MiB': vols ? mib(vols.sum { integer(it.maxStorage) ?: 0L }) : null,
                'Provisioned MiB': mib(s.maxStorage), 'In Use MiB': mib(stat(s, inst, ['usedStorage'])),
                'Primary IP Address': first(s.internalIp, s.externalIp, s.sshHost), 'All IP Addresses': ipList.join(', ') ?: null,
                'Network #1': g(netnames, 0), 'Network #2': g(netnames, 1), 'Network #3': g(netnames, 2), 'Network #4': g(netnames, 3),
                'Resource Pool': g(s, 'resourcePool', 'name'), 'Group': group, 'Instance': inst.name,
                'Instance Type': g(inst, 'instanceType', 'name'), 'Layout': g(inst, 'layout', 'name'),
                'Plan': first(g(s, 'plan', 'name'), g(inst, 'plan', 'name')), 'Environment': inst.environment,
                'Owner': first(g(inst, 'owner', 'username'), g(inst, 'createdBy', 'username'), g(s, 'owner', 'username')),
                'Annotation': first(s.description, inst.description), 'Cloud': common.Cloud, 'Cluster': cluster, 'Host': host,
                'OS': first(g(s, 'serverOs', 'name'), s.osType), 'OS Platform': s.platform, 'OS Version': s.platformVersion,
                'Tags': tags(first(s.tags, inst.tags)), 'Labels': (first(s.labels, inst.labels) ?: []).join(', ') ?: null,
                'Managed': first(s.managed, g(s, 'computeServerType', 'managed')), 'Discovered': s.discovered,
                'Server Type': g(s, 'computeServerType', 'name'), 'VM ID': s.id, 'Instance ID': inst.id,
                'External ID': s.externalId, 'UUID': s.uuid, 'Manager': manager]
            rows.vCPU << ([
                'VM': vm, 'Powerstate': ps, 'CPUs': cores, 'Sockets': sockets, 'Cores per Socket': cps,
                'CPU Usage %': rounded(cpuUse), 'Plan': g(s, 'plan', 'name'), 'OS': first(g(s, 'serverOs', 'name'), s.osType)] + common)
            rows.vMemory << ([
                'VM': vm, 'Powerstate': ps, 'Size MiB': mib(mem), 'Consumed MiB': mib(usedMem),
                'Free MiB': mem && usedMem != null ? mib(mem - integer(usedMem)) : null,
                'Usage %': pct(usedMem, mem), 'Plan': g(s, 'plan', 'name')] + common)
            vols.each { v ->
                rows.vDisk << ([
                    'VM': vm, 'Powerstate': ps, 'Disk': v.name, 'Capacity MiB': mib(v.maxStorage),
                    'Used MiB': mib(v.usedStorage), 'Root/Boot': v.rootVolume, 'Device': first(v.deviceDisplayName, v.deviceName),
                    'Controller': first(g(v, 'controller', 'name'), v.controllerId), 'Unit #': integer(v.unitNumber),
                    'Datastore': dsName(v), 'Storage Type': first(g(v, 'type', 'name'), g(v, 'storageType', 'name'), v.typeName),
                    'Thin': v.thin, 'Status': v.status, 'Disk ID': v.id, 'External ID': v.externalId] + common)
            }
            nics.each { n ->
                Map net = netById[g(n, 'network', 'id')] ?: [:]
                rows.vNetwork << ([
                    'VM': vm, 'Powerstate': ps, 'NIC': first(n.name, n.displayName), 'Network': first(g(n, 'network', 'name'), net.name),
                    'VLAN': net.vlanId, 'Primary': n.primaryInterface, 'IPv4 Address': n.ipAddress, 'IPv6 Address': n.ipv6Address,
                    'MAC Address': n.macAddress, 'Adapter': first(g(n, 'type', 'name'), n.type instanceof String ? n.type : null),
                    'IP Mode': n.ipMode, 'Connected': n.active, 'NIC ID': n.id] + common)
            }
            if (ps == 'poweredOff') health(healthRows, s.name, 'VM', 'Info', 'VM is powered off')
            if (s.agentInstalled == Boolean.FALSE && ps == 'poweredOn') health(healthRows, s.name, 'VM', 'Warning', 'Morpheus agent not installed')
            if (s.status && !(s.status.toString().toLowerCase() in ['provisioned', 'running', 'ok', 'stopped']))
                health(healthRows, s.name, 'VM', 'Warning', "Status: ${s.status}")
            def hid = g(s, 'parentServer', 'id')
            if (!hostVmStats.containsKey(hid)) hostVmStats[hid] = emptyStats()
            Map st = hostVmStats[hid]
            st.vms++; st.vcpu += cores ?: 0L; st.vram += mem ?: 0L; st.on += ps == 'poweredOn' ? 1 : 0
        }
        hosts.each { h ->
            Map st = hostVmStats[h.id] ?: emptyStats()
            Long cores = integer(first(h.maxCores, g(h, 'hostInfo', 'cpuCores')))
            def mem = first(g(h, 'stats', 'maxMemory'), h.maxMemory)
            def used = first(g(h, 'stats', 'usedMemory'), h.usedMemory)
            rows.vHost << [
                'Host': h.name, 'Cloud': cloudName(h), 'Cluster': clusterOf(h), 'Status': h.status, 'Powerstate': power(h.powerState),
                'IP Address': first(h.internalIp, h.externalIp, h.sshHost),
                'CPU Model': strip(first(h.hardwareCpuModel, g(h, 'hostInfo', 'cpuModel'), h.cpuModel)),
                'CPU MHz': integer(h.hardwareCpuFrequency),
                '# Sockets': first(integer(h.maxSockets), integer(h.maxCpu), integer(g(h, 'hostInfo', 'cpuSockets'))),
                '# Cores': cores, 'CPU Usage %': rounded(g(h, 'stats', 'cpuUsage')),
                'Memory MiB': mib(mem), 'Memory Used MiB': mib(used), 'Memory Usage %': pct(used, mem),
                'Storage MiB': mib(first(g(h, 'stats', 'maxStorage'), h.maxStorage)),
                'Storage Used MiB': mib(first(g(h, 'stats', 'usedStorage'), h.usedStorage)),
                '# NICs': (h.interfaces ?: []).size(), '# VMs': st.vms, '# VMs On': st.on, '# vCPUs': st.vcpu,
                'vCPUs per Core': cores ? rounded(st.vcpu / (double) cores, 2) : null, 'vRAM MiB': mib(st.vram),
                'OS': first(g(h, 'serverOs', 'name'), h.platform, h.osType), 'OS Version': h.platformVersion,
                'Hypervisor': g(h, 'computeServerType', 'name'), 'Agent Version': h.agentVersion,
                'Vendor': strip(first(h.hardwareProductVendor, h.manufacturer, g(h, 'hostInfo', 'manufacturer'))),
                'Model': strip(first(h.hardwareProductName, h.model, g(h, 'hostInfo', 'model'))),
                'Maintenance': h.maintenanceMode, 'Creation date': date(h.dateCreated), 'Host ID': h.id,
                'External ID': h.externalId, 'UUID': h.uuid, 'Manager': manager]
            if (h.status && !(h.status.toString().toLowerCase() in ['provisioned', 'ok', 'running']))
                health(healthRows, h.name, 'Host', 'Critical', "Host status: ${h.status}")
            if (h.maintenanceMode) health(healthRows, h.name, 'Host', 'Info', 'Host is in maintenance mode')
            if (pct(used, mem) != null && pct(used, mem) > 90)
                health(healthRows, h.name, 'Host', 'Warning', "Memory usage ${pct(used, mem)}%")
        }
        clusters.each { cl ->
            List members = (cl.servers ?: []).collect { it instanceof Map ? it.id : it }
            List hs = members.collect { srvById[it] ?: [:] }, stats = members.collect { hostVmStats[it] ?: [:] }
            rows.vCluster << [
                'Cluster': cl.name, 'Type': g(cl, 'type', 'name'), 'Layout': g(cl, 'layout', 'name'),
                'Cloud': first(g(cl, 'zone', 'name'), g(cl, 'cloud', 'name')),
                'Group': first(g(cl, 'site', 'name'), g(cl, 'group', 'name')), 'Status': cl.status, 'Enabled': cl.enabled,
                '# Hosts': members.size(), '# VMs': stats.sum { it.vms ?: 0L } ?: 0L,
                'Total Cores': hs.sum { integer(it.maxCores) ?: 0L } ?: null,
                'Total Memory MiB': mib(hs.sum { integer(it.maxMemory) ?: 0L } ?: 0L) ?: null,
                '# vCPUs': stats.sum { it.vcpu ?: 0L } ?: 0L, 'vRAM MiB': mib(stats.sum { it.vram ?: 0L } ?: 0L),
                'Created': date(cl.dateCreated), 'Cluster ID': cl.id]
        }
        Map dsUsage = [:], clusterHosts = [:]
        vms.each { s -> (s.volumes ?: []).each { v ->
            def dsid = first(v.datastoreId, g(v, 'datastore', 'id'))
            if (!dsUsage.containsKey(dsid)) dsUsage[dsid] = [disks: 0L, vms: new HashSet(), prov: 0L]
            Map u = dsUsage[dsid]
            u.disks++; u.vms.add(s.id); u.prov += integer(v.maxStorage) ?: 0L
        } }
        clusters.each { cl -> clusterHosts[cl.name] = (cl.servers ?: []).collect { m ->
            name(srvById[m instanceof Map ? m.id : m]) ?: (m instanceof Map ? m.name : m)
        } }
        datastores.each { d ->
            Map cfg = d.config instanceof Map ? d.config : [:]
            Long cap = integer(first(d.storageSize, d.maxStorage, d.capacity)), free = integer(first(d.freeSpace, d.free))
            Map u = dsUsage[d.id] ?: [disks: 0L, vms: [], prov: 0L]
            def freePct = cap && free != null ? rounded(free / (double) cap * 100d) : null
            List dClusters = d._clusters ?: (d.cluster ? [name(d.cluster)] : [])
            List dHosts = dClusters.collectMany { clusterHosts[it] ?: [] }.findAll { it }.unique().sort()
            def inUse = cap && free != null ? cap - free : null
            rows.vDatastore << [
                'Name': d.name, 'Display Name': d.displayName,
                'Type': first(g(d, 'datastoreType', 'name'), d.type instanceof Map ? name(d.type) : (d.type != 'generic' ? d.type : null),
                              g(d, 'datastoreType', 'code'), d.externalType),
                'Status': first(d.status, d.online ? 'online' : null), 'Accessible': d.online, 'Active': d.active,
                'Capacity MiB': mib(cap), 'Provisioned MiB': mib(u.prov), 'In Use MiB': mib(inUse), 'Free MiB': mib(free), 'Free %': freePct,
                '% Provisioned': cap ? rounded(u.prov / (double) cap * 100d) : null,
                '# Disks': u.disks, '# VMs': u.vms.size(), '# Hosts': dHosts.size() ?: null, 'Hosts': dHosts.join(', ') ?: null,
                'Cluster': dClusters.findAll { it }.join(', ') ?: null, 'Cloud': first(g(d, 'zone', 'name'), g(d, 'cloud', 'name')),
                'Address': first(cfg.sourceHostname, cfg.sourceHost, cfg.host, cfg.serverHost, cfg.target, cfg.portal),
                'Source': first(cfg.sourceDirPath, cfg.exportFolder, cfg.sourceDir, cfg.blockDevice),
                'Path / URL': first(d.externalPath, cfg.targetPath, cfg.path, cfg.mountPath),
                'Category': d.category, 'Visibility': d.visibility, 'Default': d.defaultStore, 'Allow Provision': d.allowProvision,
                'Heartbeat Target': d.heartbeatTarget, 'Datastore ID': d.id, 'External ID': d.externalId]
            if (freePct != null && freePct < dsFreePct) health(healthRows, d.name, 'Datastore', 'Warning', "Only ${freePct}% free")
        }
        Map nicCount = [:]
        rows.vNetwork.each { r -> nicCount[r.Network] = (nicCount[r.Network] ?: 0L) + 1 }
        networks.each { n ->
            rows.vNetworks << [
                'Network': n.name, 'Display Name': n.displayName, 'Type': g(n, 'type', 'name'), 'Cloud': g(n, 'zone', 'name'),
                'CIDR': n.cidr, 'Gateway': n.gateway, 'DNS Primary': n.dnsPrimary, 'DNS Secondary': n.dnsSecondary,
                'VLAN ID': n.vlanId, 'DHCP': n.dhcpServer, 'IP Pool': g(n, 'pool', 'name'), 'Active': n.active,
                'Visibility': n.visibility, '# NICs': nicCount[n.name] ?: 0L, 'Network ID': n.id, 'External ID': n.externalId]
        }
        instances.each { i ->
            List srvIds = (i.servers ?: []).collect { it instanceof Map ? it.id : it }
            def conn = (i.connectionInfo ?: []).findAll { it.ip != null && it.ip != '' && it.ip != '0.0.0.0' }.collect { pyString(it.ip) }.join(', ') ?: null
            rows.vInstance << [
                'Instance': i.name, 'Display Name': i.displayName, 'Status': i.status, 'Instance Type': g(i, 'instanceType', 'name'),
                'Layout': g(i, 'layout', 'name'), 'Plan': g(i, 'plan', 'name'), 'Group': g(i, 'group', 'name'), 'Cloud': g(i, 'cloud', 'name'),
                'Environment': i.environment, 'Created By': first(g(i, 'createdBy', 'username'), g(i, 'createdBy', 'name')),
                'Owner': g(i, 'owner', 'username'), 'Created': date(i.dateCreated), 'CPUs': integer(i.maxCores),
                'Memory MiB': mib(i.maxMemory), 'Storage MiB': mib(i.maxStorage), 'IP Addresses': conn, '# VMs': srvIds.size(),
                'VMs': srvIds.collect { pyString(name(srvById[it]) ?: it) }.join(', ') ?: null, 'Hostname': i.hostName,
                'Tags': tags(i.tags), 'Labels': (i.labels ?: []).join(', ') ?: null, 'Description': i.description, 'Instance ID': i.id]
        }
        Map instById = instances.collectEntries { [(pyString(it.id)): it] }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        (inv.snapshots ?: [:]).each { iid, snaps ->
            Map inst = instById[iid] ?: [:]
            snaps.each { sn ->
                def created = date(first(sn.snapshotCreated, sn.dateCreated))
                Long age = created instanceof LocalDateTime ? Math.floorDiv(Duration.between(created, now).seconds, 86400L) : null
                rows.vSnapshot << [
                    'Instance': inst.name, 'Name': sn.name, 'Description': sn.description, 'Date / time': created,
                    'Age (days)': age, 'Type': sn.snapshotType, 'State': first(sn.state, sn.status),
                    'Size MiB': mib(first(sn.maxStorage, (sn.snapshotFiles ?: []).sum { integer(it.fileSize) ?: 0L } ?: 0L)),
                    'Memory': sn.memorySnapshot, 'Current': sn.currentlyActive, 'Cloud': g(inst, 'cloud', 'name'),
                    'Snapshot ID': sn.id, 'External ID': sn.externalId, 'Instance ID': integer(iid)]
                if (age != null && age > snapshotAgeDays)
                    health(healthRows, "${pyString(inst.name)} / ${pyString(sn.name)}".toString(), 'Snapshot', 'Warning', "Snapshot is ${age} days old")
            }
        }
        Map ping = inv.ping ?: [:], who = inv.whoami ?: [:]
        [
            ['Manager URL', manager], ['Build Version', ping.buildVersion], ['Appliance URL', ping.applianceUrl],
            ['Connected User', g(who, 'user', 'username')], ['Tenant', g(who, 'user', 'account', 'name')],
            ['Collected (UTC)', date(inv.collected)], ['Clouds', (inv.clouds ?: []).size()], ['Clusters', clusters.size()],
            ['Hosts', hosts.size()], ['VMs', vms.size()], ['Instances', instances.size()], ['Datastores', datastores.size()],
            ['Networks', networks.size()], ['Tool', 'HVMTools 1.0.0']
        ].each { rows.vSource << [Property: it[0], Value: it[1]] }
        Map severity = [Critical: 0, Warning: 1, Info: 2], typeOrder = [Host: 0, Datastore: 1, Snapshot: 2, VM: 3]
        healthRows.sort { a, b -> severity.get(a.Severity, 3) <=> severity.get(b.Severity, 3) ?: typeOrder.get(a.Type, 4) <=> typeOrder.get(b.Type, 4) }
        rows
    }
}
