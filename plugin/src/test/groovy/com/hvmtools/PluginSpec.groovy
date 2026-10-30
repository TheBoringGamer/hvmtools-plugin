package com.hvmtools

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.web.MorpheusWebRequestService
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import spock.lang.Specification
import groovy.json.JsonOutput

class PluginSpec extends Specification {
    def 'plugin registers the cluster tab and report routes'() {
        given:
        def plugin = new HvmToolsPlugin()

        when:
        plugin.initialize()

        then:
        plugin.pluginProviders['hvmtools-cluster-tab'] instanceof HvmToolsClusterTab
        plugin.controllers[0].routes*.url == ['/hvmtools/preview', '/hvmtools/export']
        plugin.permissions*.code == ['hvmtools-export']
    }

    def 'tab visibility requires explicit export permission'() {
        given:
        def provider = new HvmToolsClusterTab(new HvmToolsPlugin(), null)
        def cluster = new ComputeServerGroup(id: 1L, name: 'Sample')

        expect:
        provider.show(cluster, new User(permissions: [(HvmToolsPlugin.PERMISSION): access]), null) == visible

        where:
        access | visible
        'read' | true
        'full' | true
        'none' | false
        null   | false
    }

    def 'cluster template includes scoped controls and inlined browser code'() {
        given:
        def plugin = new HvmToolsPlugin()
        def webRequest = Stub(MorpheusWebRequestService) { getNonceToken() >> 'test-nonce' }
        plugin.classLoader = this.class.classLoader
        plugin.morpheus = Stub(MorpheusContext) { getWebRequest() >> webRequest }
        plugin.initialize()
        def provider = plugin.pluginProviders['hvmtools-cluster-tab']

        when:
        def response = provider.renderTemplate(new ComputeServerGroup(id: 123L, name: '<test>'))

        then:
        response.status == 200
        response.html.contains('data-cluster-id="123"')
        response.html.contains('&lt;test&gt;')
        response.html.contains('async function collect(')
        response.html.contains('nonce="test-nonce"')
        !response.html.contains('@HVMTOOLS_SCRIPT@')
    }

    def 'controller enforces authentication, permission and POST'() {
        given:
        def controller = new HvmToolsController(new HvmToolsPlugin(), null)
        def request = Stub(HttpServletRequest) { getMethod() >> verb }
        def user = access == null ? null : new User(permissions: [(HvmToolsPlugin.PERMISSION): access])

        expect:
        controller.preview(new ViewModel(user: user, request: request)).status == expected

        where:
        access | verb   | expected
        null   | 'POST' | 403
        'none' | 'POST' | 403
        'read' | 'GET'  | 405
    }

    def 'controller creates preview and downloadable workbook without appliance service access'() {
        given:
        Map inv = new ClusterInventorySpec().inventory()
        inv.manager = 'https://sample.invalid'
        def request = Stub(HttpServletRequest) {
            getMethod() >> 'POST'
            getParameter('inventory') >> JsonOutput.toJson(inv)
            getParameter('clusterId') >> '1'
            getParameter('snapshotAge') >> '7'
            getParameter('raw') >> 'true'
        }
        def response = Mock(HttpServletResponse)
        def model = new ViewModel(user: new User(permissions: ['hvmtools-export': 'read']),
            request: request, response: response)
        def controller = new HvmToolsController(new HvmToolsPlugin(), null)

        when:
        def preview = controller.preview(model)
        def download = controller.export(model)

        then:
        preview.status == 200
        preview.data.success
        preview.data.sheets.keySet().size() == 13
        preview.data.sheets.vCluster*.Cluster == ['one']
        download.status == 200
        download.data.filename.startsWith('HVMTools_cluster-1_')
        Base64.decoder.decode(download.data.content)[0..1] == [80, 75]
        2 * response.setHeader('Cache-Control', 'no-store')
    }
}
