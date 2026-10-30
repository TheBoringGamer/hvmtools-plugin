// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Graham Fairbank. See LICENSE for details.
package com.hvmtools.report

import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import spock.lang.Specification

import java.time.LocalDateTime

class WorkbookExporterSpec extends Specification {
    def 'workbook styles types widths panes filter and severity fills match CLI'() {
        given:
        Map rows = [
            vInfo: [['VM': '=HYPERLINK("https://example.com")\u0000', 'CPUs': 1000L, 'VM ID': 1000L,
                     'Enabled': false, 'CPU Usage %': 0d, 'Created': LocalDateTime.parse('2026-10-20T09:30:00'), 'Blank': null],
                    ['VM': '+not-a-formula', 'CPUs': 0L, 'Extra': 'x' * 100]],
            vHealth: [[Entity: 'h', Type: 'Host', Severity: 'Critical', Message: 'bad'],
                      [Entity: 'd', Type: 'Datastore', Severity: 'Warning', Message: 'full'],
                      [Entity: 'v', Type: 'VM', Severity: 'Info', Message: 'off']],
            vDisk: []
        ]
        when:
        byte[] bytes = new WorkbookExporter().export(rows)
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))
        def sheet = wb.getSheet('vInfo')
        then:
        wb.numberOfSheets == 3
        sheet.getRow(0).getCell(0).stringCellValue == 'VM'
        sheet.getRow(0).getCell(7).stringCellValue == 'Extra'
        sheet.getRow(0).getCell(0).cellStyle.fillForegroundXSSFColor.RGB == HexFormat.of().parseHex('1F4E78')
        sheet.getRow(0).getCell(0).cellStyle.verticalAlignment == VerticalAlignment.CENTER
        sheet.getRow(0).getCell(0).cellStyle.font.bold
        sheet.getRow(0).getCell(0).cellStyle.font.fontName == 'Arial'
        sheet.getRow(0).getCell(0).cellStyle.font.XSSFColor.RGB == HexFormat.of().parseHex('FFFFFF')
        sheet.getRow(1).getCell(0).cellType == CellType.STRING
        sheet.getRow(1).getCell(0).stringCellValue == '=HYPERLINK("https://example.com")'
        !sheet.getRow(1).getCell(0).cellStyle.font.bold
        sheet.getRow(1).getCell(0).cellStyle.font.fontHeightInPoints == 10
        sheet.getRow(1).getCell(1).numericCellValue == 1000d
        sheet.getRow(1).getCell(1).cellStyle.dataFormatString == '#,##0'
        sheet.getRow(1).getCell(2).cellStyle.dataFormatString == 'General'
        sheet.getRow(1).getCell(3).cellType == CellType.BOOLEAN
        !sheet.getRow(1).getCell(3).booleanCellValue
        sheet.getRow(1).getCell(4).cellStyle.dataFormatString == 'General'
        sheet.getRow(1).getCell(5).cellStyle.dataFormatString == 'yyyy-mm-dd hh:mm'
        sheet.getRow(1).getCell(5).localDateTimeCellValue == LocalDateTime.parse('2026-10-20T09:30:00')
        sheet.getRow(1).getCell(6).cellType == CellType.BLANK
        sheet.getColumnWidth(7) == 60 * 256
        sheet.getColumnWidth(1) == 9 * 256
        sheet.paneInformation.verticalSplitPosition == 1
        sheet.paneInformation.horizontalSplitPosition == 1
        sheetXml(bytes, 1).contains('<autoFilter ref="A1:H3"')
        wb.getSheet('vHealth').getRow(1).getCell(0).cellStyle.fillForegroundXSSFColor.RGB == HexFormat.of().parseHex('F8CBAD')
        wb.getSheet('vHealth').getRow(2).getCell(0).cellStyle.fillForegroundXSSFColor.RGB == HexFormat.of().parseHex('FFE699')
        wb.getSheet('vHealth').getRow(3).getCell(0).cellStyle.fillForegroundXSSFColor.RGB == HexFormat.of().parseHex('DDEBF7')
        wb.getSheet('vDisk').getRow(0).getCell(0).stringCellValue == 'No data returned'
        wb.getSheet('vDisk').paneInformation == null
        !sheetXml(bytes, 3).contains('<autoFilter')
        cleanup:
        wb?.close()
    }

    def 'raw sheets flatten nested data redact credentials and keep all strings formula safe'() {
        given:
        Map inventory = [
            servers: [[id: 1, name: '=2+2', sshPassword: 'do-not-export',
                       config: [apiKey: 'also-secret', nested: [deep: [password: 'deep-secret', visible: 'hello']]],
                       interfaces: [[name: '@SUM(1,2)', token: 'nested-secret']], longList: ['x' * 33000]]],
            instances: [], datastores: []
        ]
        when:
        byte[] bytes = new WorkbookExporter().export([vInfo: [[VM: '-2+3']]], inventory, true)
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))
        def raw = wb.getSheet('raw_servers')
        Map<String, Cell> cells = [:]
        raw.getRow(0).each { c -> cells[c.stringCellValue] = raw.getRow(1).getCell(c.columnIndex) }
        then:
        wb.numberOfSheets == 4
        cells.name.cellType == CellType.STRING
        cells.name.stringCellValue == '=2+2'
        cells.sshPassword.stringCellValue == '<redacted>'
        cells['config.apiKey'].stringCellValue == '<redacted>'
        cells['config.nested.deep'].stringCellValue.contains('"password": "<redacted>"')
        !cells.interfaces.stringCellValue.contains('nested-secret')
        cells.interfaces.stringCellValue.contains('@SUM(1,2)')
        cells.longList.stringCellValue.length() == 32000
        wb.getSheet('raw_instances').getRow(0).getCell(0).stringCellValue == 'No data returned'
        inventory.servers[0].sshPassword == 'do-not-export'
        cleanup:
        wb?.close()
    }

    static String sheetXml(byte[] bytes, int number) {
        def zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))
        try {
            java.util.zip.ZipEntry entry
            while ((entry = zip.nextEntry) != null) {
                if (entry.name == "xl/worksheets/sheet${number}.xml") return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            }
            throw new IllegalArgumentException("Missing sheet ${number}")
        } finally { zip.close() }
    }
}
