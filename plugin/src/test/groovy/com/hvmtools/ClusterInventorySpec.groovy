package com.hvmtools

import spock.lang.Specification

class ClusterInventorySpec extends Specification {
    Map inventory() {
        [
            clusters: [
                [id: 1, name: 'one', servers: [[id: 10]], zone: [id: 100]],
                [id: 2, name: 'two', servers: [[id: 11]]]
            ],
            servers: [
                [id: 10, zone: [id: 100]],
                [id: 20, parentServer: [id: 10], site: [id: 200],
                    volumes: [[datastoreId: 300]], interfaces: [[network: [id: 400]]]],
                [id: 21, parentServer: [id: 11]],
                [id: 22, serverGroup: [id: 1]]
            ],
            instances: [[id: 500, servers: [20, 21]], [id: 501, containers: [[server: [id: 22]]]],
                [id: 502, servers: [21], containers: [99]]],
            datastores: [[id: 300], [id: 301, _clusters: ['one', 'two']], [id: 302]],
            networks: [[id: 400], [id: 401]], clouds: [[id: 100], [id: 101]],
            groups: [[id: 200], [id: 201]],
            snapshots: ['500': [[id: 600]], '502': [[id: 601]]],
            whoami: [user: [username: 'not-exported']]
        ]
    }

    def 'scope filters all inventory surfaces and preserves cross-cluster instance membership'() {
        when:
        Map result = ClusterInventory.scope(inventory(), '1')

        then:
        result.clusters*.id == [1]
        result.servers*.id == [10, 20, 22]
        result.instances*.id == [500, 501]
        result.instances[0].servers == [20, 21]
        result.datastores*.id == [300, 301]
        result.datastores[1]._clusters == ['one']
        result.networks*.id == [400]
        result.clouds*.id == [100]
        result.groups*.id == [200]
        result.snapshots.keySet() == ['500'] as Set
        result.whoami == [:]
    }

    def 'raw inventory credentials are recursively redacted'() {
        given:
        Map inv = inventory()
        inv.servers[0].apiKey = 'example-key'
        inv.instances[0].config = [sshPasswordHash: 'example-hash', usedMemory: 0]

        when:
        Map result = ClusterInventory.scope(inv, '1')

        then:
        result.servers[0].apiKey == '<redacted>'
        result.instances[0].config.sshPasswordHash == '<redacted>'
        result.instances[0].config.usedMemory == 0
    }

    def 'missing cluster, invalid IDs and malformed top-level data are rejected'() {
        when:
        ClusterInventory.scope(inv, id)

        then:
        thrown(IllegalArgumentException)

        where:
        inv                   | id
        inventory()           | '2x'
        inventory()           | '0'
        inventory()           | '99'
        inventory() + [servers: null] | '1'
        inventory() + [snapshots: []] | '1'
    }
}
