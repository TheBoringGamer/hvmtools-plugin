const test = require('node:test');
const assert = require('node:assert/strict');
const {pages, collect, scopeServers, scopeInstances, redact} = require('../../main/js/cluster-tab');

test('pagination honors meta.total even when a server caps page size', async () => {
    const calls = [];
    const rows = await pages(async path => {
        calls.push(path);
        return calls.length === 1 ? {servers: [{id: 1}], meta: {total: 2}} :
            {servers: [{id: 2}], meta: {total: 2}};
    }, '/api/servers', 'servers');
    assert.deepEqual(rows.map(s => s.id), [1, 2]);
    assert.match(calls[1], /offset=1$/);
});

test('malformed, incomplete and denied API responses stop collection', async () => {
    await assert.rejects(pages(async () => ({}), '/api/servers', 'servers'), /Unexpected API/);
    await assert.rejects(pages(async () => ({servers: [], meta: {total: 3}}),
        '/api/servers', 'servers'), /Incomplete API/);
    await assert.rejects(pages(async () => { throw new Error('HTTP 403'); },
        '/api/servers', 'servers'), /403/);
});

test('cluster scope includes members, descendants and direct cluster references only', () => {
    const cluster = {id: 1, servers: [{id: 10}]};
    const servers = [{id: 10}, {id: 20, parentServer: {id: 10}},
        {id: 30, serverGroup: {id: 1}}, {id: 40, cluster: {id: 1}},
        {id: 50, parentServer: {id: 60}}, {id: 60}];
    assert.deepEqual(scopeServers(servers, cluster).map(s => s.id), [10, 20, 30, 40]);
    assert.deepEqual(scopeInstances([{id: 1, servers: [20, 50]},
        {id: 2, containers: [{server: {id: 30}}]}, {id: 3, servers: [50]}],
    scopeServers(servers, cluster)).map(i => i.id), [1, 2]);
});

test('redaction handles nested credentials without erasing metrics', () => {
    assert.deepEqual(redact({apiKey: 'example', stats: {usedMemory: 0},
        rows: [{sshPasswordHash: 'example', name: 'host'}]}),
    {apiKey: '<redacted>', stats: {usedMemory: 0},
        rows: [{sshPasswordHash: '<redacted>', name: 'host'}]});
});

test('collector sends only related inventory and keeps zero stats and full instance membership', async () => {
    const paths = [];
    const responses = {
        '/api/clusters/1': {cluster: {id: 1, name: 'one', servers: [10], zone: {id: 5}}},
        '/api/servers': {servers: [
            {id: 10, zone: {id: 5}, stats: {usedMemory: 0}},
            {id: 20, parentServer: {id: 10}, site: {id: 7}, stats: {usedMemory: 0},
                volumes: [{datastoreId: 60}], interfaces: [{network: {id: 70}}]},
            {id: 30, serverGroup: {id: 2}, apiKey: 'never-forward'}]},
        '/api/instances': {instances: [{id: 40, servers: [20, 30], password: 'example'},
            {id: 50, servers: [30]}]},
        '/api/clusters/1/datastores': {datastores: [{id: 61, name: 'empty-store'}]},
        '/api/zones': {zones: [{id: 5, name: 'cloud'}, {id: 6}]},
        '/api/groups': {groups: [{id: 7}, {id: 8}]},
        '/api/networks': {networks: [{id: 70}, {id: 71}]},
        '/api/zones/5/data-stores': {datastores: [{id: 60}, {id: 62}]},
        '/api/instances/40/snapshots': {snapshots: [{id: 80, token: 'example'}]}
    };
    const result = await collect(async path => {
        paths.push(path);
        const key = path.split('?')[0];
        assert.ok(responses[key], 'unexpected API request ' + path);
        return structuredClone(responses[key]);
    }, '1', {manager: 'https://example.invalid', stats: true, snapshots: true}, () => {});
    assert.deepEqual(result.servers.map(s => s.id), [10, 20]);
    assert.deepEqual(result.instances.map(i => i.id), [40]);
    assert.deepEqual(result.instances[0].servers, [20, 30]);
    assert.deepEqual(result.datastores.map(d => d.id), [61, 60]);
    assert.deepEqual(result.networks.map(n => n.id), [70]);
    assert.deepEqual(result.groups.map(g => g.id), [7]);
    assert.equal(result.instances[0].password, '<redacted>');
    assert.equal(result.snapshots['40'][0].token, '<redacted>');
    assert.ok(!paths.some(p => /^\/api\/servers\/\d/.test(p)), 'zero memory is not missing');
});

test('missing membership fails closed rather than exporting the whole manager', async () => {
    await assert.rejects(collect(async () => ({cluster: {id: 1}}), '1',
        {stats: false, snapshots: false}, () => {}), /membership/);
});
