package com.hvmtools

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer

class HvmToolsPlugin extends Plugin {
    static final String PERMISSION = 'hvmtools-export'

    @Override
    String getCode() { 'hvmtools-plugin' }

    @Override
    void initialize() {
        name = 'HVMTools'
        renderer = new HandlebarsRenderer(this.class.classLoader)
        registerProvider(new HvmToolsClusterTab(this, morpheus))
        controllers.add(new HvmToolsController(this, morpheus))
    }

    @Override
    List<Permission> getPermissions() {
        [new Permission('HVMTools cluster inventory', PERMISSION,
            [Permission.AccessType.none, Permission.AccessType.read])]
    }

    @Override
    void onDestroy() {}
}
