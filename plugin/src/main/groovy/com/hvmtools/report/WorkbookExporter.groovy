// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Graham Fairbank. See LICENSE for details.
package com.hvmtools.report

import groovy.json.JsonOutput
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.*

import java.time.*

/** In-memory OOXML export. Untrusted inventory strings are always string cells, never formulas. */
class WorkbookExporter {
    byte[] export(Map<String, List<Map>> sheets, Map inventory = [:], boolean raw = false) {
        XSSFWorkbook wb = new XSSFWorkbook()
        try {
            Map styles = styles(wb)
            Map safeSheets = (Map) InventoryReport.redact(sheets)
            safeSheets.each { title, data -> writeSheet(wb, title.toString(), data, styles) }
            if (raw && inventory) {
                Map inv = (Map) InventoryReport.redact(inventory)
                ['servers', 'instances', 'datastores'].each { key ->
                    writeSheet(wb, "raw_${key}", (inv[key] ?: []).collect { flatten(it) }, styles)
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream()
            wb.write(out)
            out.toByteArray()
        } finally { wb.close() }
    }

    static Map flatten(Map obj, String prefix = '', Map out = [:], int depth = 0) {
        (obj ?: [:]).each { k, v ->
            String key = prefix + k
            if (v instanceof Map && depth < 2) flatten(v, key + '.', out, depth + 1)
            else if (v instanceof Map || v instanceof List) out[key] = json(v).take(32000)
            else out[key] = v
        }
        out
    }

    private static String json(Object value) {
        if (value instanceof Map)
            return '{' + value.collect { k, v -> JsonOutput.toJson(k.toString()) + ': ' + json(v) }.join(', ') + '}'
        if (value instanceof List) return '[' + value.collect { json(it) }.join(', ') + ']'
        if (value == null || value instanceof Boolean || value instanceof Number) return JsonOutput.toJson(value)
        // Python json.dumps(default=str) uses the space-separated datetime representation.
        String text = value instanceof LocalDateTime ? value.toString().replace('T', ' ') : value.toString()
        JsonOutput.toJson(text)
    }

    private static XSSFColor color(String hex) {
        new XSSFColor(java.util.HexFormat.of().parseHex(hex), new DefaultIndexedColorMap())
    }

    private static Map styles(XSSFWorkbook wb) {
        Font bodyFont = wb.createFont()
        bodyFont.fontName = 'Arial'; bodyFont.fontHeightInPoints = 10
        XSSFFont headerFont = wb.createFont()
        headerFont.fontName = 'Arial'; headerFont.fontHeightInPoints = 10; headerFont.bold = true
        headerFont.setColor(color('FFFFFF'))
        XSSFCellStyle body = wb.createCellStyle(); body.setFont(bodyFont)
        XSSFCellStyle header = wb.createCellStyle(); header.setFont(headerFont)
        header.setFillForegroundColor(color('1F4E78')); header.fillPattern = FillPatternType.SOLID_FOREGROUND
        header.verticalAlignment = VerticalAlignment.CENTER
        Map styles = [body: body, header: header]
        Map fills = [Critical: 'F8CBAD', Warning: 'FFE699', Info: 'DDEBF7']
        ([null] + fills.keySet().toList()).each { severity ->
            ['general', 'integer', 'date'].each { format ->
                XSSFCellStyle st = wb.createCellStyle(); st.cloneStyleFrom(body)
                if (severity) {
                    st.setFillForegroundColor(color(fills[severity]))
                    st.fillPattern = FillPatternType.SOLID_FOREGROUND
                }
                if (format == 'integer') st.dataFormat = wb.createDataFormat().getFormat('#,##0')
                if (format == 'date') st.dataFormat = wb.createDataFormat().getFormat('yyyy-mm-dd hh:mm')
                styles["${severity ?: ''}:${format}".toString()] = st
            }
        }
        styles
    }

    private static boolean isDate(Object value) {
        value instanceof LocalDateTime || value instanceof LocalDate || value instanceof Date ||
                value instanceof Instant || value instanceof OffsetDateTime || value instanceof ZonedDateTime
    }

    private static LocalDateTime asDate(Object value) {
        if (value instanceof LocalDateTime) return value
        if (value instanceof LocalDate) return value.atStartOfDay()
        Instant instant = value instanceof Date ? value.toInstant() :
                value instanceof Instant ? value : value.toInstant()
        LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
    }

    private static void setValue(Cell cell, Object value) {
        if (value == null) return
        if (isDate(value)) cell.setCellValue(asDate(value))
        else if (value instanceof Boolean) cell.setCellValue((boolean) value)
        else if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue())
        else {
            String text = value instanceof Map || value instanceof List ? json(value) : value.toString()
            cell.setCellValue(text.replaceAll('[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]', '').take(32767))
        }
    }

    private static void writeSheet(XSSFWorkbook wb, String title, List<Map> rows, Map styles) {
        Sheet sheet = wb.createSheet(title.take(31))
        if (!rows) {
            Cell c = sheet.createRow(0).createCell(0)
            c.setCellValue('No data returned'); c.cellStyle = styles.body
            return
        }
        List<String> cols = []
        rows.each { r -> r.keySet().each { k -> if (!cols.contains(k.toString())) cols << k.toString() } }
        Row header = sheet.createRow(0)
        List<Integer> widths = cols.collect { it.length() + 3 }
        cols.eachWithIndex { c, idx ->
            Cell cell = header.createCell(idx); setValue(cell, c); cell.cellStyle = styles.header
        }
        rows.eachWithIndex { r, idx ->
            Row row = sheet.createRow(idx + 1)
            String severity = title == 'vHealth' && r.Severity in ['Critical', 'Warning', 'Info'] ? r.Severity : ''
            cols.eachWithIndex { c, col ->
                def v = r[c]
                Cell cell = row.createCell(col)
                setValue(cell, v)
                String format = isDate(v) ? 'date' :
                        v instanceof Number && !(v instanceof Float || v instanceof Double || v instanceof BigDecimal) && !c.endsWith('ID') ? 'integer' : 'general'
                cell.cellStyle = styles["${severity}:${format}".toString()]
                int width = v == null ? 0 : isDate(v) ? 16 : InventoryReport.pyString(v).length()
                widths[col] = Math.max(widths[col], width)
            }
        }
        widths.eachWithIndex { w, idx -> sheet.setColumnWidth(idx, Math.min(Math.max(w + 2, 8), 60) * 256) }
        sheet.createFreezePane(1, 1)
        sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, cols.size() - 1))
    }
}
