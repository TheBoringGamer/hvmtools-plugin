// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Graham Fairbank. See LICENSE for details.
package com.hvmtools.report

import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification

import java.time.*

class InventoryReportSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-10-29T00:00:00Z')
    InventoryReport report = new InventoryReport(Clock.fixed(NOW, ZoneOffset.UTC))

    def 'first retains zero and false rather than selecting a nonzero fallback'() {
        expect:
        InventoryReport.first(null, '', [], [:], 0, 5) == 0
        InventoryReport.first(false, true) == false
        InventoryReport.first(null, '', [], [:]) == null
        InventoryReport.integer('2.9') == 2
        InventoryReport.integer('bad') == null
        InventoryReport.mib(524288) == 0
        InventoryReport.mib(1572864) == 2
        InventoryReport.pct(0, 1024) == 0d
        InventoryReport.pct(1, 0) == null
        InventoryReport.rounded('2.25') == 2.2d
    }

    def 'VM fallbacks preserve zero statistics false flags and unknown optional fields'() {
        given:
        Map inv = [
            groups: [[id: 4, name: 'Group']],
            servers: [[id: 10, name: 'vm', displayName: '', powerState: 'on', managed: false,
                       computeServerType: [managed: true], maxCores: 0, maxMemory: 10485760,
                       site: [id: 4], stats: [usedMemory: 0, cpuUsage: 0, usedCpu: 80, usedStorage: 0],
                       coresPerSocket: 0, internalIp: '1.2.3.4', externalIp: '1.2.3.4',
                       interfaces: [[id: 9, network: [id: 7], ipAddress: '1.2.3.5', type: 'virtio']],
                       volumes: [[id: 3, datastoreId: 6, maxStorage: 0, usedStorage: 0, unitNumber: 0]]]],
            instances: [[id: 1, name: 'instance', servers: [10], maxCores: 8,
                         stats: [usedMemory: 9999, cpuUsage: 99], plan: [name: 'fallback'],
                         owner: [username: 'owner'], tags: [[name: 'number', value: 0], [key: 'boolean', value: false]]]],
            datastores: [[id: 6, name: 'ds', storageSize: 0, maxStorage: 1048576, freeSpace: 0]],
            networks: [[id: 7, name: 'network', vlanId: 0]]
        ]

        when:
        Map rows = report.buildSheets(inv)

        then:
        rows.keySet().toList() == InventoryReport.TABS
        rows.vInfo[0].CPUs == 0
        rows.vInfo[0].Managed == false
        rows.vInfo[0].Sockets == null
        rows.vInfo[0].Group == 'Group'
        rows.vInfo[0].Plan == 'fallback'
        rows.vInfo[0].Owner == 'owner'
        rows.vInfo[0].Tags == 'number=0; boolean=False'
        rows.vInfo[0]['All IP Addresses'] == '1.2.3.4, 1.2.3.5'
        rows.vInfo[0]['Network #1'] == 'network'
        rows.vInfo[0]['Network #2'] == null
        rows.vCPU[0]['CPU Usage %'] == 0d
        rows.vCPU[0].Plan == null
        rows.vMemory[0]['Consumed MiB'] == 0
        rows.vMemory[0]['Free MiB'] == 10
        rows.vMemory[0]['Usage %'] == 0d
        rows.vDisk[0]['Unit #'] == 0
        rows.vDisk[0].Datastore == 'ds'
        rows.vNetwork[0].Adapter == 'virtio'
        rows.vNetwork[0].VLAN == 0
        rows.vDatastore[0]['Capacity MiB'] == 0
        rows.vDatastore[0]['Free %'] == null
        rows.vNetworks[0]['# NICs'] == 1
    }

    def 'instance statistics are used only for single VM instances including container joins'() {
        given:
        Map inv = [
            servers: [[id: 1, name: 'one'], [id: 2, name: 'two'], [id: 3, name: 'three']],
            instances: [
                [id: 10, servers: [1, [id: 2]], stats: [usedMemory: 1048576, cpuUsage: 50]],
                [id: 11, containers: [[server: [id: 3]]], stats: [usedMemory: 2097152, usedCpu: 20]]
            ]
        ]
        when:
        Map rows = report.buildSheets(inv)
        then:
        rows.vMemory*.'Consumed MiB' == [null, null, 2]
        rows.vCPU*.'CPU Usage %' == [null, null, 20d]
        rows.vInfo*.'Instance ID' == [10, 10, 11]
    }

    def 'cross-cluster instance references prevent aggregate statistics leaking into scoped VM rows'() {
        given:
        Map inv = [
            servers: [[id: 1, name: 'selected-vm', maxMemory: 10485760]],
            instances: [[id: 10, name: 'cross-cluster', servers: references, containers: containers,
                         stats: [usedMemory: 1048576, cpuUsage: 50, usedStorage: 2097152]]]
        ]

        when:
        Map rows = report.buildSheets(inv)

        then:
        rows.vInfo.size() == 1
        rows.vInfo[0]['Instance ID'] == 10
        rows.vInfo[0]['In Use MiB'] == null
        rows.vMemory[0]['Consumed MiB'] == null
        rows.vMemory[0]['Free MiB'] == null
        rows.vMemory[0]['Usage %'] == null
        rows.vCPU[0]['CPU Usage %'] == null

        where:
        references       | containers
        [1, 2]           | []
        [[id: 1], [id: 2]] | []
        [1]              | [[server: [id: 2]]]
        []               | [[server: [id: 1]], [server: [id: 2]]]
    }

    def 'host detection joins capacity aggregation and datastore hardware fallbacks match CLI'() {
        given:
        Map inv = [
            servers: [
                [id: 1, name: 'h1', computeServerType: [format: 'vmHypervisor'], maxCores: 4, maxMemory: 104857600,
                 stats: [usedMemory: 99614720, maxMemory: 104857600], hardwareCpuModel: ' Model\n',
                 hardwareProductVendor: ' Vendor\n', hardwareProductName: ' Hardware\n', maxSockets: 0, maxCpu: 2,
                 hardwareCpuFrequency: '2400.9', serverGroup: [name: 'preferred']],
                [id: 2, name: 'h2', maxCores: 4, maxMemory: 104857600],
                [id: 3, name: 'h3', computeServerType: [vmHypervisor: true]],
                [id: 10, name: 'vm', maxCores: 2, maxMemory: 1048576, powerState: 'on',
                 parentServer: [id: 2], volumes: [[datastoreId: 20, maxStorage: 2097152], [datastore: [id: 20], maxStorage: 1048576]]]
            ],
            clusters: [[id: 4, name: 'cl', servers: [[id: 1], 2, [id: 99, name: 'unreturned-host']]]],
            datastores: [[id: 20, name: 'ds', type: 'generic', datastoreType: [name: 'NFS'],
                          _clusters: ['cl'], storageSize: 10485760, freeSpace: 0, online: true,
                          config: [sourceHostname: 'nfs', sourceDirPath: '/export']]]
        ]

        when:
        Map rows = report.buildSheets(inv)

        then:
        rows.vInfo.size() == 1
        rows.vInfo[0].Host == 'h2'
        rows.vInfo[0].Cluster == 'cl'
        rows.vHost*.Host == ['h1', 'h2', 'h3']
        rows.vHost[0].Cluster == 'preferred'
        rows.vHost[0]['CPU Model'] == 'Model'
        rows.vHost[0].Vendor == 'Vendor'
        rows.vHost[0].Model == 'Hardware'
        rows.vHost[0]['# Sockets'] == 0
        rows.vHost[0]['CPU MHz'] == 2400
        rows.vHost[1]['# VMs On'] == 1
        rows.vHost[1]['vCPUs per Core'] == 0.5d
        rows.vCluster[0]['# Hosts'] == 3
        rows.vCluster[0]['Total Cores'] == 8
        rows.vCluster[0]['Total Memory MiB'] == 200
        rows.vDatastore[0].Type == 'NFS'
        rows.vDatastore[0]['# Disks'] == 2
        rows.vDatastore[0]['# VMs'] == 1
        rows.vDatastore[0]['Provisioned MiB'] == 3
        rows.vDatastore[0]['Free %'] == 0d
        rows.vDatastore[0].Hosts == 'h1, h2, unreturned-host'
        rows.vDatastore[0].Address == 'nfs'
        rows.vDatastore[0].Source == '/export'
    }

    def 'snapshot date parsing file size fallback and health ordering use fixed UTC clock'() {
        given:
        Map inv = [
            servers: [
                [id: 1, name: 'host', computeServerType: [vmHypervisor: true], status: 'failed',
                 maintenanceMode: true, maxMemory: 100, stats: [usedMemory: 91]],
                [id: 2, name: 'off', powerState: 'off', status: 'error'],
                [id: 3, name: 'on', powerState: 'on', agentInstalled: false]
            ],
            datastores: [[id: 1, name: 'full', capacity: 100, free: 0]],
            instances: [[id: 5, name: 'app']],
            snapshots: ['5': [
                [id: 1, name: 'old', snapshotCreated: '2026-10-20T02:00:00+02:00', state: null, status: 'complete',
                 snapshotFiles: [[fileSize: 1048576], [fileSize: '1048576']]],
                [id: 2, name: 'future', dateCreated: '2026-10-29T00:00:01Z', maxStorage: 0, snapshotFiles: [[fileSize: 1048576]]],
                [id: 3, name: 'invalid', snapshotCreated: 'bad-date']
            ]]
        ]
        when:
        Map rows = report.buildSheets(inv)
        then:
        rows.vSnapshot[0]['Date / time'] == LocalDateTime.parse('2026-10-20T00:00:00')
        rows.vSnapshot*.'Age (days)' == [9, -1, null]
        rows.vSnapshot*.'Size MiB' == [2, 0, 0]
        rows.vSnapshot[0].State == 'complete'
        rows.vSnapshot[2]['Date / time'] == 'bad-date'
        rows.vHealth*.Severity == ['Critical', 'Warning', 'Warning', 'Warning', 'Warning', 'Warning', 'Info', 'Info']
        rows.vHealth*.Type == ['Host', 'Host', 'Datastore', 'Snapshot', 'VM', 'VM', 'Host', 'VM']
        report.buildSheets(inv, 9, 0).vHealth.every { !(it.Type in ['Datastore', 'Snapshot']) }
    }

    def 'redaction recursively copies credential fields even when their value is zero or false'() {
        given:
        Map inv = [servers: [[id: 1, name: 'safe', config: [sshPassword: 'secret', serviceCert: [privateKey: 'secret'], apiKey: 0, token: false]]]]
        when:
        Map safe = InventoryReport.redact(inv)
        then:
        safe.servers[0].config.values().every { it == '<redacted>' }
        inv.servers[0].config.sshPassword == 'secret'
    }

    def 'empty inventory still returns all tabs and complete source metadata'() {
        when:
        Map rows = report.buildSheets([:])
        then:
        rows.size() == 13
        rows.vSource.size() == 14
        rows.vSource.find { it.Property == 'VMs' }.Value == 0
        rows.vSource.last() == [Property: 'Tool', Value: 'HVMTools 1.0.0']
        rows.findAll { k, v -> k != 'vSource' }.values().every { it.empty }
    }

    def 'fictional sample produces all populated tabs and expected columns'() {
        given:
        Map inv = new JsonSlurper().parse(sampleFile()) as Map
        when:
        Map rows = report.buildSheets(inv)
        then:
        rows.values().every { !it.empty }
        rows.vInfo[0].keySet().toList().take(6) == ['VM', 'Powerstate', 'Status', 'Template', 'DNS Name', 'Agent Installed']
        rows.vInfo.size() + rows.vHost.size() == inv.servers.size()
        rows.vInstance.size() == inv.instances.size()
        rows.vDisk.size() == rows.vInfo.sum { it.Disks }
    }

    // Oracle can contain a sheets-only object, or {inventory, now, sheets}.
    // Dates in canonical Python maps must be ISO strings; numeric types compare by value.
    @Requires({ System.getenv('HVMTOOLS_ORACLE_JSON') })
    def 'all rows and column ordering match the external Python oracle'() {
        given:
        Map oracle = new JsonSlurper().parse(new File(System.getenv('HVMTOOLS_ORACLE_JSON'))) as Map
        Map inv = oracle.inventory instanceof Map ? oracle.inventory : new JsonSlurper().parse(sampleFile()) as Map
        Instant now = oracle.now ? Instant.parse(oracle.now) : NOW
        Map expected = oracle.sheets instanceof Map ? oracle.sheets : oracle
        Map actual = new InventoryReport(Clock.fixed(now, ZoneOffset.UTC)).buildSheets(inv)

        expect:
        actual.keySet().toList() == expected.keySet().toList()
        actual.every { tab, data ->
            data.size() == expected[tab].size() && data.withIndex().every { row, idx ->
                row.keySet().toList() == expected[tab][idx].keySet().toList() &&
                        normalize(row) == normalize(expected[tab][idx])
            }
        }
    }

    static File sampleFile() {
        List<File> candidates = [new File('../examples/sample-inventory.json'), new File('examples/sample-inventory.json')]
        candidates.find { it.isFile() } ?: candidates[0]
    }

    static Object normalize(Object value) {
        if (value instanceof Map) return value.collectEntries { k, v -> [(k): normalize(v)] }
        if (value instanceof List) return value.collect { normalize(it) }
        if (value instanceof LocalDateTime) return value.toString()
        if (value instanceof String) {
            def d = InventoryReport.date(value)
            if (d instanceof LocalDateTime) return d.toString()
        }
        value
    }
}
