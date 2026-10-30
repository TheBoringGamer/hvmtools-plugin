package com.hvmtools

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

class HvmToolsClusterTab extends AbstractClusterTabProvider {
    final Plugin plugin
    final MorpheusContext morpheus
    final String code = 'hvmtools-cluster-tab'
    final String name = 'HVMTools'

    HvmToolsClusterTab(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        cluster?.id != null && user != null &&
            user.permissions?.get(HvmToolsPlugin.PERMISSION) in ['read', 'full']
    }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        renderer.renderTemplate('hbs/clusterTab', ViewModel.of([id: cluster.id, name: cluster.name]))
    }
}
