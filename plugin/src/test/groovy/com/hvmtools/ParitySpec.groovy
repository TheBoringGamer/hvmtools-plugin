package com.hvmtools

import com.hvmtools.report.InventoryReport
import com.hvmtools.report.WorkbookExporter
import groovy.json.JsonSlurper
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import spock.lang.Requires
import spock.lang.Specification

@Requires({ System.getenv('HVMTOOLS_ORACLE_JSON') })
class ParitySpec extends Specification {
    def 'all sample clusters match the Python oracle and produce native workbooks'() {
        given:
        File oracleFile = new File(System.getenv('HVMTOOLS_ORACLE_JSON'))
        Map oracle = new JsonSlurper().parse(oracleFile) as Map
        def clock = Clock.fixed(Instant.parse(oracle.now), ZoneOffset.UTC)
        File nativeDir = new File(oracleFile.parentFile, 'native')
        nativeDir.mkdirs()

        expect:
        oracle.cases.each { entry ->
            Map inventory = entry.name == 'all' ? entry.inventory :
                ClusterInventory.scope(oracle.inventory, entry.inventory.clusters[0].id)
            Map actual = new InventoryReport(clock).buildSheets(inventory)
            assert normalize(actual) == normalize(entry.sheets): entry.name
            new File(nativeDir, "${entry.name}.xlsx").bytes =
                new WorkbookExporter().export(actual, inventory, true)
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof LocalDateTime) {
            return value.toString()
        }
        if (value instanceof Map) {
            return value.collectEntries { k, v -> [(k): normalize(v)] }
        }
        if (value instanceof List) {
            return value.collect { normalize(it) }
        }
        if (value instanceof String && value ==~ /\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d)?/) {
            return LocalDateTime.parse(value).toString()
        }
        value
    }
}
