# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] - 2026-10-01

### Added
- Initial release under the MIT License.
- Username/password (OAuth) and API token authentication; `--insecure` for self-signed certificates.
- Paged collection of clouds, groups, clusters, servers (hosts and VMs), instances, networks, datastores and snapshots.
- Tabs: vInfo, vCPU, vMemory, vDisk, vNetwork, vSnapshot, vHost, vCluster, vDatastore, vNetworks, vInstance, vHealth, vSource.
- vDatastore includes cluster Storage-tab datastores (GFS2, OCFS2, NFS pools, local) merged with cloud datastores, with provisioned/overcommit %, host and cluster columns.
- Health checks for host state and memory, datastore free space, snapshot age, VM state and missing agents.
- `--raw` sheets (raw_servers, raw_instances, raw_datastores), `--dump-json` and `--from-json`.
- Environment variable configuration (MORPHEUS_URL, MORPHEUS_USER, MORPHEUS_PASS, MORPHEUS_TOKEN).
