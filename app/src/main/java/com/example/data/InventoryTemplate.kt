package com.example.data

import java.io.ByteArrayOutputStream

object InventoryTemplate {
    const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    val STANDARD_HEADER_SEQUENCE = listOf(
        "序号",
        "设备编号",
        "设备名称",
        "规格型号",
        "生产厂家",
        "计量单位",
        "数量",
        "购置日期",
        "启用日期",
        "账面原值",
        "账面净值",
        "是否盘点",
        "备注",
        "资产分类"
    )

    fun headersForProject(columnHeadersJson: String?): List<String> {
        val extraHeaders = mutableListOf<String>()
        for (header in fromJsonList(columnHeadersJson)) {
            val trimmed = header.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.equals("uuid", ignoreCase = true) || trimmed.equals("uid", ignoreCase = true)) continue

            val isStandard = STANDARD_HEADER_SEQUENCE.any { standard ->
                standard == trimmed || trimmed.contains(standard) || standard.contains(trimmed)
            }
            if (!isStandard) {
                extraHeaders.add(trimmed)
            }
        }
        return STANDARD_HEADER_SEQUENCE + extraHeaders
    }

    fun createXlsxBytes(columnHeadersJson: String?): ByteArray {
        val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        try {
            val sheet = workbook.createSheet("盘点模板")
            sheet.setDisplayGridlines(true)

            val headerFont = workbook.createFont().apply {
                bold = true
            }
            val headerStyle = workbook.createCellStyle().apply {
                setFont(headerFont)
                alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
                verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
            }

            val headerRow = sheet.createRow(0)
            headersForProject(columnHeadersJson).forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = headerStyle
                sheet.setColumnWidth(index, 15 * 256)
            }

            return ByteArrayOutputStream().use { outputStream ->
                workbook.write(outputStream)
                outputStream.toByteArray()
            }
        } finally {
            try {
                workbook.close()
            } catch (e: NoSuchMethodError) {
                // Some test/runtime classpaths load a commons-compress variant that fails during
                // XSSFWorkbook.close() after write() has already produced valid XLSX bytes.
            }
        }
    }

    private fun fromJsonList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json).map { match ->
                match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
