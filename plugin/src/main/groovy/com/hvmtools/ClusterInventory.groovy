package com.hvmtools

class ClusterInventory {
    static Map scope(Map source, Object clusterId) {
        String id = clusterId?.toString()
        if (!(id ==~ /[1-9][0-9]*/)) {
            throw new IllegalArgumentException('A positive cluster ID is required')
        }
        ['clusters', 'servers', 'instances', 'datastores', 'networks', 'clouds', 'groups'].each { key ->
            if (!(source[key] instanceof List) || source[key].any { !(it instanceof Map) }) {
                throw new IllegalArgumentException("Invalid inventory collection: ${key}")
            }
        }
        Map cluster = source.clusters.find { it.id?.toString() == id }
        if (!cluster) {
            throw new IllegalArgumentException('Selected cluster is not present')
        }
        Set members = (cluster.servers ?: []).collect { refId(it) }.findAll { it != null }.toSet()
        List servers = source.servers.findAll { s ->
            members.contains(refId(s)) || members.contains(refId(s.parentServer)) ||
                refId(s.cluster) == id || refId(s.serverGroup) == id
        }
        Set serverIds = servers.collect { refId(it) }.toSet()
        List instances = source.instances.findAll { i ->
            (i.servers ?: []).any { serverIds.contains(refId(it)) } ||
                (i.containers ?: []).any { it instanceof Map && serverIds.contains(refId(it.server)) }
        }
        // Keep full instance membership: it controls whether instance-level usage can
        // safely be attributed to a single VM, including cross-cluster instances.
        Set datastoreIds = servers.collectMany { it.volumes ?: [] }.collect {
            refId(it.datastore) ?: it.datastoreId?.toString()
        }.findAll { it != null }.toSet()
        Set networkIds = servers.collectMany { it.interfaces ?: [] }.collect {
            refId(it.network)
        }.findAll { it != null }.toSet()
        List datastores = source.datastores.findAll { d ->
            datastoreIds.contains(refId(d)) || (d._clusters ?: []).contains(cluster.name) ||
                refId(d.cluster) == id
        }.collect { d ->
            Map scoped = new LinkedHashMap(d)
            scoped._clusters = (d._clusters ?: []).contains(cluster.name) || refId(d.cluster) == id ?
                [cluster.name] : []
            scoped
        }
        Set cloudIds = [refId(cluster.zone), refId(cluster.cloud)]
        servers.each { cloudIds.add(refId(it.zone)); cloudIds.add(refId(it.cloud)) }
        Set groupIds = [refId(cluster.site), refId(cluster.group)]
        servers.each { groupIds.add(refId(it.site)) }
        instances.each { groupIds.add(refId(it.group)) }
        Set instanceIds = instances.collect { refId(it) }.toSet()
        if (!(source.snapshots instanceof Map)) {
            throw new IllegalArgumentException('Invalid snapshots')
        }
        Map result = [
            collected: source.collected, manager: source.manager, ping: source.ping ?: [:],
            whoami: [:], clusters: [cluster], servers: servers, instances: instances,
            datastores: datastores, networks: source.networks.findAll { networkIds.contains(refId(it)) },
            clouds: source.clouds.findAll { cloudIds.contains(refId(it)) },
            groups: source.groups.findAll { groupIds.contains(refId(it)) },
            snapshots: source.snapshots.findAll { key, value -> instanceIds.contains(key.toString()) }
        ]
        redact(result)
    }

    static String refId(Object ref) {
        (ref instanceof Map ? ref.id : ref)?.toString()
    }

    static def redact(Object value) {
        if (value instanceof Map) {
            value.each { key, child ->
                if (key.toString() =~ /(?i)password|passwd|secret|token|apikey|api_key|privatekey|serviceaccess|servicecert/) {
                    if (child != null && child != '' && child != [] && child != [:]) {
                        value[key] = '<redacted>'
                    }
                } else {
                    redact(child)
                }
            }
        } else if (value instanceof List) {
            value.each { redact(it) }
        }
        value
    }
}
