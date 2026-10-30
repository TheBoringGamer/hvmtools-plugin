(function () {
    'use strict';

    const refId = value => String(value && typeof value === 'object' ? value.id : value);
    const list = value => Array.isArray(value) ? value : [];
    const secretKey = /password|passwd|secret|token|apikey|api_key|privatekey|serviceaccess|servicecert/i;

    function redact(value) {
        if (Array.isArray(value)) {
            value.forEach(redact);
        } else if (value && typeof value === 'object') {
            Object.keys(value).forEach(key => {
                if (secretKey.test(key) && value[key] != null && value[key] !== '') {
                    value[key] = '<redacted>';
                } else {
                    redact(value[key]);
                }
            });
        }
        return value;
    }

    function scopeServers(servers, cluster) {
        const members = new Set(list(cluster.servers).map(refId));
        return servers.filter(s => members.has(refId(s)) || members.has(refId(s.parentServer)) ||
            refId(s.cluster) === refId(cluster) || refId(s.serverGroup) === refId(cluster));
    }

    function scopeInstances(instances, servers) {
        const ids = new Set(servers.map(refId));
        return instances.filter(i => list(i.servers).some(s => ids.has(refId(s))) ||
            list(i.containers).some(c => c && typeof c === 'object' && ids.has(refId(c.server))));
    }

    async function pages(get, path, key) {
        const result = [];
        let offset = 0;
        while (true) {
            const data = await get(path + (path.includes('?') ? '&' : '?') + 'max=100&offset=' + offset);
            if (!data || !Array.isArray(data[key])) {
                throw new Error('Unexpected API response for ' + path + ' (missing ' + key + ').');
            }
            const page = data[key];
            result.push(...page);
            offset += page.length;
            const total = data.meta && data.meta.total;
            if (!page.length && total != null && offset < Number(total)) {
                throw new Error('Incomplete API pagination for ' + path + '. Retry collection.');
            }
            if (!page.length || (total != null ? offset >= Number(total) : page.length < 100)) {
                return result;
            }
        }
    }

    async function pool(values, operation) {
        let index = 0;
        await Promise.all(Array.from({length: Math.min(4, values.length)}, async () => {
            while (index < values.length) {
                await operation(values[index++]);
            }
        }));
    }

    async function collect(get, clusterId, options, status) {
        status('Loading cluster membership...');
        const detail = await get('/api/clusters/' + clusterId);
        const cluster = detail.cluster;
        if (!cluster || refId(cluster) !== String(clusterId) || !Array.isArray(cluster.servers)) {
            throw new Error('Cluster detail does not contain server membership. Export stopped to avoid an incorrect scope.');
        }
        const inv = {
            collected: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
            manager: options.manager, ping: {}, whoami: {}, clusters: [cluster], snapshots: {}
        };
        status('Loading hosts and VMs...');
        inv.servers = scopeServers(await pages(get, '/api/servers', 'servers'), cluster);
        const returnedIds = new Set(inv.servers.map(refId));
        if (cluster.servers.some(s => !returnedIds.has(refId(s)))) {
            throw new Error('Some cluster members are missing from the server inventory. Check your API permissions and retry.');
        }
        status('Matching instances...');
        inv.instances = scopeInstances(await pages(get, '/api/instances', 'instances'), inv.servers);
        status('Loading cluster datastores...');
        inv.datastores = await pages(get, '/api/clusters/' + clusterId + '/datastores', 'datastores');
        inv.datastores.forEach(d => {
            d._clusters = [cluster.name];
            if (!d.zone) d.zone = cluster.zone || cluster.cloud;
        });
        const dsIds = new Set(inv.servers.flatMap(s => list(s.volumes))
            .map(v => refId(v.datastore || v.datastoreId)));
        const cloudIds = new Set([cluster.zone, cluster.cloud, ...inv.servers.flatMap(s => [s.zone, s.cloud])]
            .filter(Boolean).map(refId));
        inv.clouds = (await pages(get, '/api/zones', 'zones')).filter(c => cloudIds.has(refId(c)));
        const groupIds = new Set([cluster.site, cluster.group, ...inv.servers.map(s => s.site),
            ...inv.instances.map(i => i.group)].filter(Boolean).map(refId));
        inv.groups = (await pages(get, '/api/groups', 'groups')).filter(g => groupIds.has(refId(g)));
        const netIds = new Set(inv.servers.flatMap(s => list(s.interfaces)).map(n => refId(n.network)));
        inv.networks = (await pages(get, '/api/networks', 'networks')).filter(n => netIds.has(refId(n)));
        for (const cloud of inv.clouds) {
            const stores = await pages(get, '/api/zones/' + cloud.id + '/data-stores', 'datastores');
            stores.forEach(d => {
                const existing = inv.datastores.find(ds => refId(ds) === refId(d));
                if (existing) {
                    Object.keys(d).forEach(key => {
                        if (existing[key] == null) existing[key] = d[key];
                    });
                } else if (dsIds.has(refId(d))) {
                    d.zone = d.zone || {id: cloud.id, name: cloud.name};
                    inv.datastores.push(d);
                }
            });
        }
        if (options.stats) {
            status('Fetching missing usage statistics...');
            await pool(inv.servers.filter(s => (!s.stats || s.stats.usedMemory == null) &&
                s.usedMemory == null), async s => {
                const detail = await get('/api/servers/' + s.id);
                s.stats = s.stats || {};
                [detail.server && detail.server.stats, detail.stats].filter(Boolean).forEach(stats => {
                    Object.keys(stats).forEach(key => {
                        if (s.stats[key] == null || s.stats[key] === '') s.stats[key] = stats[key];
                    });
                });
            });
        }
        if (options.snapshots) {
            status('Loading snapshots...');
            await pool(inv.instances, async instance => {
                const data = await pages(get, '/api/instances/' + instance.id + '/snapshots', 'snapshots');
                if (data.length) inv.snapshots[String(instance.id)] = data;
            });
        }
        return redact(inv);
    }

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = {pages, collect, scopeServers, scopeInstances, redact};
        return;
    }

    const root = document.currentScript.closest('.hvmtools-panel');
    const find = role => root.querySelector('[data-role="' + role + '"]');
    const action = name => root.querySelector('[data-action="' + name + '"]');
    const option = name => root.querySelector('[data-option="' + name + '"]');
    const clusterId = root.dataset.clusterId;
    let inventory = null;
    let sheets = {};
    let page = 0;
    const pageSize = 100;
    let busy = false;
    const status = text => { find('status').textContent = text; };

    async function get(path) {
        const response = await fetch(path, {credentials: 'same-origin', headers: {Accept: 'application/json'}});
        if (!response.ok) {
            throw new Error(path.split('?')[0] + ' returned HTTP ' + response.status +
                '. Check your API permissions and session, then retry.');
        }
        return response.json();
    }

    function post(endpoint) {
        return new Promise((resolve, reject) => {
            if (!window.jQuery) {
                reject(new Error('Morpheus jQuery request support is unavailable. Reload the page.'));
                return;
            }
            const csrf = document.querySelector('meta[name="_csrf"]');
            if (!csrf || !csrf.content) {
                reject(new Error('The Morpheus CSRF token is unavailable. Reload the page and sign in again.'));
                return;
            }
            window.jQuery.ajax({
                url: '/plugin/hvmtools/' + endpoint, method: 'POST', dataType: 'json',
                data: {inventory: JSON.stringify(inventory), clusterId,
                    snapshotAge: option('snapshotAge').value, raw: option('raw').checked,
                    _csrf: csrf.content},
                success: data => data.success ? resolve(data) :
                    reject(new Error(data.message || 'Report generation failed.')),
                error: xhr => reject(new Error((xhr.responseJSON && xhr.responseJSON.message) ||
                    'Report generation returned HTTP ' + xhr.status + '. Check the appliance plugin logs.'))
            });
        });
    }

    function setBusy(value) {
        busy = value;
        action('preview').disabled = value;
        action('download').disabled = value || !inventory;
        root.querySelectorAll('[data-option]').forEach(input => { input.disabled = value; });
        root.setAttribute('aria-busy', String(value));
    }

    function draw() {
        const rows = sheets[find('sheet').value] || [];
        const cols = [...new Set(rows.flatMap(row => Object.keys(row)))];
        const container = find('preview');
        container.replaceChildren();
        const table = document.createElement('table');
        table.className = 'table table-striped table-condensed';
        const head = table.createTHead().insertRow();
        cols.forEach(col => {
            const th = document.createElement('th');
            th.textContent = col;
            th.scope = 'col';
            head.appendChild(th);
        });
        const body = table.createTBody();
        rows.slice(page * pageSize, (page + 1) * pageSize).forEach(row => {
            const tr = body.insertRow();
            cols.forEach(col => {
                const value = row[col];
                tr.insertCell().textContent = value == null ? '' :
                    typeof value === 'object' ? JSON.stringify(value) : String(value);
            });
        });
        container.appendChild(table);
        find('count').textContent = rows.length ?
            rows.length + ' rows; showing ' + (page * pageSize + 1) + '-' +
                Math.min((page + 1) * pageSize, rows.length) + '. Excel includes all rows.' : 'No data returned';
        action('previous').disabled = page === 0;
        action('next').disabled = (page + 1) * pageSize >= rows.length;
    }

    action('preview').addEventListener('click', async () => {
        if (busy) return;
        const age = Number(option('snapshotAge').value);
        if (!Number.isInteger(age) || age < 1 || age > 3650) {
            status('Snapshot age must be a whole number between 1 and 3650.');
            return;
        }
        inventory = null;
        sheets = {};
        find('preview').replaceChildren();
        find('sheet').replaceChildren();
        find('sheet').disabled = true;
        find('count').textContent = '';
        action('previous').disabled = true;
        action('next').disabled = true;
        setBusy(true);
        try {
            inventory = await collect(get, clusterId, {
                manager: window.location.origin, snapshots: option('snapshots').checked,
                stats: option('stats').checked
            }, status);
            status('Building report preview...');
            const result = await post('preview');
            sheets = result.sheets;
            Object.keys(sheets).forEach(name => {
                const item = document.createElement('option');
                item.value = name;
                item.textContent = name + ' (' + sheets[name].length + ')';
                find('sheet').appendChild(item);
            });
            find('sheet').disabled = false;
            page = 0;
            draw();
            status('Inventory collected at ' + inventory.collected + '. Ready to download.');
        } catch (error) {
            inventory = null;
            status('Collection failed: ' + error.message);
        } finally {
            setBusy(false);
        }
    });

    action('download').addEventListener('click', async () => {
        if (busy || !inventory) return;
        setBusy(true);
        status('Building Excel workbook...');
        try {
            const result = await post('export');
            const bytes = Uint8Array.from(atob(result.content), c => c.charCodeAt(0));
            const url = URL.createObjectURL(new Blob([bytes], {
                type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
            }));
            const link = document.createElement('a');
            link.href = url;
            link.download = result.filename;
            root.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            status('Workbook generated. Inventory collected at ' + inventory.collected + '.');
        } catch (error) {
            status('Download failed: ' + error.message);
        } finally {
            setBusy(false);
        }
    });
    find('sheet').addEventListener('change', () => { page = 0; draw(); });
    action('previous').addEventListener('click', () => { page--; draw(); });
    action('next').addEventListener('click', () => { page++; draw(); });
    ['snapshots', 'stats', 'snapshotAge'].forEach(name => {
        option(name).addEventListener('change', () => {
            inventory = null;
            action('download').disabled = true;
            status('Options changed. Collect again to refresh the preview and download.');
        });
    });
}());
