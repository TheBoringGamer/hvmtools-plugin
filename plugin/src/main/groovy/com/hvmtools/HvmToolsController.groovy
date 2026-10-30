package com.hvmtools

import com.hvmtools.report.InventoryReport
import com.hvmtools.report.WorkbookExporter
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory

class HvmToolsController implements PluginController {
    private static final int MAX_INPUT = 25 * 1024 * 1024
    private static final LOG = LoggerFactory.getLogger(HvmToolsController)
    final Plugin plugin
    final MorpheusContext morpheus
    final String code = 'hvmtools-controller'
    final String name = 'HVMTools export'

    HvmToolsController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    List<Route> getRoutes() {
        ['preview', 'export'].collect {
            Route.build("/hvmtools/${it}", it, Permission.build(HvmToolsPlugin.PERMISSION, 'read'))
        }
    }

    def preview(ViewModel model) { generate(model, false) }

    def export(ViewModel model) { generate(model, true) }

    private JsonResponse generate(ViewModel model, boolean download) {
        if (!model.user || !(model.user.permissions?.get(HvmToolsPlugin.PERMISSION) in ['read', 'full'])) {
            return failure('HVMTools permission is required.', 403)
        }
        if (model.request?.method != 'POST') {
            return failure('Use POST to generate an inventory report.', 405)
        }
        model.response?.setHeader('Cache-Control', 'no-store')
        try {
            String input = model.request.getParameter('inventory')
            if (!input || input.length() > MAX_INPUT) {
                return failure('Inventory is missing or exceeds the 25 MiB report limit.', 400)
            }
            def parsed = new JsonSlurper().parseText(input)
            if (!(parsed instanceof Map)) {
                return failure('Inventory must be a JSON object.', 400)
            }
            Map inv = ClusterInventory.scope(parsed, model.request.getParameter('clusterId'))
            int age = Integer.parseInt(model.request.getParameter('snapshotAge') ?: '7')
            if (age < 1 || age > 3650) {
                return failure('Snapshot age must be between 1 and 3650 days.', 400)
            }
            Map sheets = new InventoryReport().buildSheets(inv, age)
            if (!download) {
                Map preview = sheets.collectEntries { title, rows ->
                    [(title): rows.collect { row ->
                        row.collectEntries { key, value ->
                            [(key): value instanceof java.time.LocalDateTime ? value.toString() : value]
                        }
                    }]
                }
                return JsonResponse.of([success: true, sheets: preview])
            }
            byte[] workbook = new WorkbookExporter().export(sheets, inv,
                model.request.getParameter('raw') == 'true')
            String filename = "HVMTools_cluster-${inv.clusters[0].id}_" +
                java.time.LocalDateTime.now(java.time.Clock.systemUTC()).format(
                    java.time.format.DateTimeFormatter.ofPattern('yyyyMMdd_HHmm')) + '.xlsx'
            JsonResponse.of([success: true, filename: filename,
                content: Base64.encoder.encodeToString(workbook)])
        } catch (IllegalArgumentException | groovy.json.JsonException e) {
            LOG.warn('HVMTools rejected an invalid inventory request: {}', e.class.simpleName)
            failure('Invalid inventory or report options. Refresh the cluster tab and try again.', 400)
        } catch (IOException e) {
            LOG.error('HVMTools workbook generation failed', e)
            failure('Workbook generation failed. Check the appliance plugin logs.', 500)
        }
    }

    private static JsonResponse failure(String message, int status) {
        JsonResponse response = JsonResponse.of([success: false, message: message])
        response.status = status
        response
    }
}
