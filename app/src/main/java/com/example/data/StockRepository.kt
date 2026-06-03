package com.example.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.util.CellRangeAddress
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StockRepository(
    private val stockItemDao: StockItemDao,
    private val projectDao: ProjectDao
) {

    fun toJsonList(list: List<String>): String {
        return "[" + list.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"" } + "]"
    }

    fun fromJsonList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        try {
            val result = mutableListOf<String>()
            val matches = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json)
            for (match in matches) {
                val value = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                result.add(value)
            }
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    fun formatToYearMonth(raw: String): String {
        if (raw.isBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 6) {
            val year = digits.substring(0, 4)
            val month = digits.substring(4, 6)
            return "${year}年${month}月"
        }
        return raw
    }

    fun formatToYearMonthDay(raw: String): String {
        if (raw.isBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 8) {
            val year = digits.substring(0, 4)
            val month = digits.substring(4, 6)
            val day = digits.substring(6, 8)
            return "${year}年${month}月${day}日"
        } else if (digits.length >= 6) {
            val year = digits.substring(0, 4)
            val month = digits.substring(4, 6)
            return "${year}年${month}月01日"
        }
        return raw
    }

    fun generateXlsxReport(context: Context, projectId: String, items: List<StockItem>, outputFile: File) {
        try {
            val project = kotlinx.coroutines.runBlocking {
                projectDao.getProjectById(projectId)
            } ?: Project(id = projectId, name = InventoryConstants.DEFAULT_PROJECT_NAME)

            val wb = XSSFWorkbook()

            // 1. Create Fonts and Styles
            val fontSimSun18Bold = wb.createFont().apply {
                setFontName("宋体")
                fontHeightInPoints = 18.toShort()
                setBold(true)
            }
            val fontSimSun10 = wb.createFont().apply {
                setFontName("宋体")
                fontHeightInPoints = 10.toShort()
            }
            val fontSimSun10Bold = wb.createFont().apply {
                setFontName("宋体")
                fontHeightInPoints = 10.toShort()
                setBold(true)
            }
            val fontSimSun9 = wb.createFont().apply {
                setFontName("宋体")
                fontHeightInPoints = 9.toShort()
            }

            // Style for Row 1 (Title): bold 18 SimSun, centered
            val styleTitle = wb.createCellStyle().apply {
                setFont(fontSimSun18Bold)
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
            }

            // Style for Row 2 (Base Date): 9 SimSun, centered
            val styleBaseDate = wb.createCellStyle().apply {
                setFont(fontSimSun9)
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
            }

            // Style for Row 4 (Left): 10 SimSun, left aligned
            val styleLabelLeft = wb.createCellStyle().apply {
                setFont(fontSimSun10)
                alignment = HorizontalAlignment.LEFT
                verticalAlignment = VerticalAlignment.CENTER
            }

            // Style for Row 4 (Right): 10 SimSun, right aligned
            val styleLabelRight = wb.createCellStyle().apply {
                setFont(fontSimSun10)
                alignment = HorizontalAlignment.RIGHT
                verticalAlignment = VerticalAlignment.CENTER
            }

            // Style for Table Headers: 10 SimSun Bold, centered, thin borders
            val styleHeader = wb.createCellStyle().apply {
                setFont(fontSimSun10Bold)
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
                borderTop = BorderStyle.THIN
                borderBottom = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
            }

            // Style for Text Data Cells: 10 SimSun, left, thin borders
            val styleDataText = wb.createCellStyle().apply {
                setFont(fontSimSun10)
                alignment = HorizontalAlignment.LEFT
                verticalAlignment = VerticalAlignment.CENTER
                borderTop = BorderStyle.THIN
                borderBottom = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
            }

            // Style for Numeric Currency Data Cells (千分位): 10 SimSun, right, thin borders, #,##0.00
            val styleDataCurrency = wb.createCellStyle().apply {
                setFont(fontSimSun10)
                alignment = HorizontalAlignment.RIGHT
                verticalAlignment = VerticalAlignment.CENTER
                borderTop = BorderStyle.THIN
                borderBottom = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
                dataFormat = wb.createDataFormat().getFormat("#,##0.00")
            }

            val columnHeadersJson = project.columnHeadersJson
            val baseHeaderCells = if (columnHeadersJson.isNotEmpty()) {
                fromJsonList(columnHeadersJson)
            } else {
                listOf("序号", "设备编号", "设备名称", "规格型号", "生产厂家", "计量单位", "数量", "购置日期", "启用日期", "账面原值", "账面净值", "备注", "资产分类")
            }

            val standardList = listOf(
                "序号", "设备编号", "设备名称", "规格型号", "生产厂家", "计量单位", "数量", "购置日期", "启用日期", "账面原值", "账面净值", "备注", "是否盘点"
            )
            val extraHeaderCells = baseHeaderCells.filter { originalHeader ->
                val trimmed = originalHeader.trim()
                if (trimmed.equals("uuid", ignoreCase = true) || trimmed.equals("uid", ignoreCase = true) || trimmed.equals("资产分类", ignoreCase = true)) {
                    false
                } else {
                    standardList.none { std -> trimmed == std || trimmed.contains(std) || std.contains(trimmed) }
                }
            }.map { it.trim() }

            val exportedHeaders = mutableListOf<String>()
            exportedHeaders.add("序号")
            exportedHeaders.add("设备编号")
            exportedHeaders.add("设备名称")
            exportedHeaders.add("规格型号")
            exportedHeaders.add("生产厂家")
            exportedHeaders.add("计量单位")
            exportedHeaders.add("数量")
            exportedHeaders.add("购置日期")
            exportedHeaders.add("启用日期")
            exportedHeaders.add("账面原值")
            exportedHeaders.add("账面净值")
            exportedHeaders.addAll(extraHeaderCells)
            exportedHeaders.add("备注")

            val totalExportedHeaders = exportedHeaders.toMutableList()
            totalExportedHeaders.add("UUID")

            val grouped = items.groupBy { it.category.ifEmpty { "默认分类" } }
            var createdSheetCount = 0

            for ((category, categoryItems) in grouped) {
                createdSheetCount++
                val sheetName = category.replace("/", "_").replace("\\", "_").replace(":", "_").trim()
                val sheet = wb.createSheet(sheetName)
                sheet.setDisplayGridlines(true)

                val excelFooter = sheet.footer
                excelFooter.left = "监盘人员："
                excelFooter.center = "盘点人员："
                excelFooter.right = "盘点日期："

                val lastVisibleColIdx = exportedHeaders.size - 1

                val r0 = sheet.createRow(0)
                r0.heightInPoints = 30f
                val c0 = r0.createCell(0)
                c0.setCellValue("${category}评估盘点表")
                c0.cellStyle = styleTitle
                if (lastVisibleColIdx > 0) {
                    sheet.addMergedRegion(CellRangeAddress(0, 0, 0, lastVisibleColIdx))
                }

                val r1 = sheet.createRow(1)
                r1.heightInPoints = 20f
                val c1 = r1.createCell(0)
                val baseDateText = if (project.baseDate.isNotEmpty()) project.baseDate else "2026年05月29日"
                c1.setCellValue("评估基准日：$baseDateText")
                c1.cellStyle = styleBaseDate
                if (lastVisibleColIdx > 0) {
                    sheet.addMergedRegion(CellRangeAddress(1, 1, 0, lastVisibleColIdx))
                }

                sheet.createRow(2).heightInPoints = 15f

                val r3 = sheet.createRow(3)
                r3.heightInPoints = 20f
                val c3Left = r3.createCell(0)
                val unitLabel = if (project.reportType == InventoryConstants.REPORT_TYPE_EVALUATION) "被评估单位" else "产权持有单位"
                val companyNameText = if (project.companyName.isNotEmpty()) project.companyName else "未指定代评单位"
                c3Left.setCellValue("$unitLabel：$companyNameText")
                c3Left.cellStyle = styleLabelLeft

                val unitColIdx = exportedHeaders.size - 1
                val c3Right = r3.createCell(if (unitColIdx > 0) unitColIdx else 1)
                c3Right.setCellValue("金额单位：人民币元")
                c3Right.cellStyle = styleLabelRight
                if (unitColIdx > 0) {
                    sheet.addMergedRegion(CellRangeAddress(3, 3, 0, unitColIdx - 1))
                }

                val r4 = sheet.createRow(4)
                r4.heightInPoints = 22f
                for (idx in totalExportedHeaders.indices) {
                    val cell = r4.createCell(idx)
                    cell.setCellValue(totalExportedHeaders[idx])
                    cell.cellStyle = styleHeader
                }

                val sortedItems = categoryItems // Matches itemDao rowOrder automatically
                sortedItems.forEachIndexed { itemIdx, item ->
                    val rData = sheet.createRow(5 + itemIdx)
                    rData.heightInPoints = 18f

                    val itemValues = mutableMapOf<String, String>()
                    val baseCells = if (item.originalRowJson.isNotEmpty()) {
                        fromJsonList(item.originalRowJson)
                    } else {
                        emptyList()
                    }
                    for (i in baseHeaderCells.indices) {
                        val value = baseCells.getOrNull(i) ?: ""
                        itemValues[baseHeaderCells[i].trim()] = value
                    }

                    val shouldPutCheckmark = item.shouldCheck && item.photoCount > 0

                    for (colIdx in totalExportedHeaders.indices) {
                        val headerName = totalExportedHeaders[colIdx]

                        if (headerName == "UUID") {
                            val cellUuid = rData.createCell(colIdx)
                            cellUuid.setCellValue(item.uid)
                            cellUuid.cellStyle = styleDataText
                            continue
                        }

                        var rawStr = ""
                        if (headerName == "序号") {
                            rawStr = (itemIdx + 1).toString()
                        } else if (headerName == "备注") {
                            rawStr = if (shouldPutCheckmark) "已盘点" else ""
                        } else {
                            val originalKey = baseHeaderCells.find { originalHeader ->
                                val trimmed = originalHeader.trim()
                                trimmed == headerName || 
                                (headerName != "备注" && trimmed.contains(headerName)) || 
                                (headerName != "备注" && headerName.contains(trimmed))
                            }
                            rawStr = if (originalKey != null) {
                                itemValues[originalKey]?.trim() ?: ""
                            } else {
                                ""
                            }
                            if (rawStr.isEmpty()) {
                                rawStr = when (headerName) {
                                    "设备编号" -> item.originalCode
                                    "设备名称" -> item.name
                                    else -> ""
                                }
                            }
                        }

                        val isBuyDate = headerName == "购置日期"
                        val isUseDate = headerName == "启用日期"
                        val isOriginalVal = headerName == "账面原值"
                        val isNetVal = headerName == "账面净值"

                        val cell = rData.createCell(colIdx)
                        if ((isBuyDate || isUseDate) && rawStr.isNotEmpty()) {
                            cell.setCellValue(formatToYearMonthDay(rawStr))
                            cell.cellStyle = styleDataText
                        } else if ((isOriginalVal || isNetVal) && rawStr.isNotEmpty()) {
                            val doubleVal = rawStr.replace(",", "").toDoubleOrNull()
                            if (doubleVal != null) {
                                cell.setCellValue(doubleVal)
                                cell.cellStyle = styleDataCurrency
                            } else {
                                cell.setCellValue(rawStr)
                                cell.cellStyle = styleDataText
                            }
                        } else {
                            cell.setCellValue(rawStr)
                            cell.cellStyle = styleDataText
                        }
                    }
                }

                for (col in 0 until exportedHeaders.size) {
                    val headerName = exportedHeaders[col]
                    var allEmpty = true
                    for (rowIdx in sortedItems.indices) {
                        val r = sheet.getRow(5 + rowIdx) ?: continue
                        val cell = r.getCell(col)
                        val valStr = cell?.toString()?.trim() ?: ""
                        if (valStr.isNotEmpty()) {
                            allEmpty = false
                            break
                        }
                    }
                    if (allEmpty && headerName != "序号" && headerName != "设备编号" && headerName != "设备名称" && headerName != "备注") {
                        sheet.setColumnHidden(col, true)
                    }
                }

                sheet.setColumnHidden(totalExportedHeaders.size - 1, true)
                for (col in 0 until totalExportedHeaders.size) {
                    if (!sheet.isColumnHidden(col)) {
                        sheet.setColumnWidth(col, 15 * 256)
                    }
                }
            }

            if (createdSheetCount == 0) {
                // Return fallback worksheet to prevent POI blank workbook crash
                val sheet = wb.createSheet("暂无资产列表")
                sheet.setDisplayGridlines(true)
                val r0 = sheet.createRow(0)
                val c0 = r0.createCell(0)
                c0.setCellValue("暂无符合条件的资产记录。")
                c0.cellStyle = styleTitle
            }

            FileOutputStream(outputFile).use { fos ->
                wb.write(fos)
            }
            wb.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val allItems: Flow<List<StockItem>> = stockItemDao.getAllItems()

    suspend fun listAllItemsSync(): List<StockItem> = withContext(Dispatchers.IO) {
        stockItemDao.getAllItemsSync()
    }

    // Projects data access
    val allProjects: Flow<List<Project>> = projectDao.getAllProjectsFlow()

    suspend fun insertProject(project: Project) = withContext(Dispatchers.IO) {
        projectDao.insertProject(project)
    }

    suspend fun deleteProject(context: Context, project: Project) = withContext(Dispatchers.IO) {
        // Delete all items of this project and their files
        val itemsToDelete = stockItemDao.getItemsByProjectSync(project.id)
        for (item in itemsToDelete) {
            File(context.filesDir, "pdfs/${item.uid}").deleteRecursively()
            File(context.filesDir, "photos/${item.uid}").deleteRecursively()
        }
        stockItemDao.deleteItemsByProject(project.id)
        projectDao.deleteProject(project)
    }

    suspend fun getProjectById(id: String): Project? = withContext(Dispatchers.IO) {
        projectDao.getProjectById(id)
    }

    fun getProjectFlow(id: String): Flow<Project?> {
        return projectDao.getProjectByIdFlow(id)
    }

    suspend fun listProjectsSync(): List<Project> = withContext(Dispatchers.IO) {
        projectDao.getAllProjects()
    }

    fun getItemsByProject(projectId: String): Flow<List<StockItem>> {
        return stockItemDao.getAllItemsByProject(projectId)
    }

    suspend fun getItemsByProjectSync(projectId: String): List<StockItem> {
        return stockItemDao.getItemsByProjectSync(projectId)
    }

    suspend fun insertItem(item: StockItem) = withContext(Dispatchers.IO) {
        stockItemDao.insertItem(item)
    }

    suspend fun insertAll(items: List<StockItem>) = withContext(Dispatchers.IO) {
        stockItemDao.insertAll(items)
    }

    suspend fun updatePhotoState(uid: String, photoCount: Int, pdfStatus: String) = withContext(Dispatchers.IO) {
        stockItemDao.updatePhotoState(uid, photoCount, pdfStatus)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        stockItemDao.deleteAll()
    }

    suspend fun deleteItem(item: StockItem) = withContext(Dispatchers.IO) {
        stockItemDao.deleteItem(item)
    }

    suspend fun getItemByUid(uid: String): StockItem? = withContext(Dispatchers.IO) {
        stockItemDao.getItemByUid(uid)
    }

    /**
     * Parses simple CSV content and returns a list of parsed StockItems.
     */
    suspend fun parseAndImportCsv(context: Context, inputStream: InputStream, projectId: String, replace: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val newList = mutableListOf<StockItem>()
            inputStream.bufferedReader().use { reader ->
                val lines = reader.readLines()
                if (lines.isEmpty()) return@withContext false

                // Try to detect headers or just parse
                val headerLine = lines[0]
                val columns = headerLine.split(",").map { it.trim().replace("\"", "") }

                // Save header columns to Project
                val project = projectDao.getProjectById(projectId)
                if (project != null) {
                    val headerJson = toJsonList(columns)
                    projectDao.insertProject(project.copy(columnHeadersJson = headerJson))
                }

                // Map header index
                var nameIdx = -1
                var categoryIdx = -1
                var locationIdx = -1
                var codeIdx = -1
                var shouldCheckIdx = -1

                for (i in columns.indices) {
                    val col = columns[i]
                    if ((col.contains("名称") || col.contains("name") || col.contains("商品") || col.contains("资产")) && !col.contains("编号") && !col.contains("分类") && !col.contains("类别") && !col.contains("代码")) {
                        if (nameIdx == -1) nameIdx = i
                    } else if (col.contains("名称") && col.contains("设备")) {
                        if (nameIdx == -1) nameIdx = i
                    } else if (col.contains("分类") || col.contains("类别") || col.contains("category") || col.contains("属性")) {
                        if (categoryIdx == -1) categoryIdx = i
                    } else if (col.contains("位置") || col.contains("区域") || col.contains("location") || col.contains("地方")) {
                        if (locationIdx == -1) locationIdx = i
                    } else if (col.contains("编号") || col.contains("code") || col.contains("id") || col.contains("序号") || col.contains("代码")) {
                        if (codeIdx == -1) codeIdx = i
                    } else if (col.contains("是否盘点") || col.contains("盘点") || col.contains("shouldcheck") || col.contains("requirecheck")) {
                        if (shouldCheckIdx == -1) shouldCheckIdx = i
                    }
                }

                // If naming match failed, do a default position fallback ONLY if they match positions,
                // but we must ultimately guarantee nameIdx and categoryIdx exist.
                if (nameIdx == -1) {
                    nameIdx = if (columns.size > 1) 1 else 0
                }
                if (categoryIdx == -1) {
                    categoryIdx = if (columns.size > 2) 2 else -1
                }
                
                // Enforce that CSV must contain name and category
                if (nameIdx == -1 || categoryIdx == -1 || nameIdx >= columns.size || categoryIdx >= columns.size) {
                    return@withContext false
                }

                if (codeIdx == -1) {
                    codeIdx = if (columns.size > 0 && 0 != nameIdx && 0 != categoryIdx) 0 else -1
                }
                if (locationIdx == -1) {
                    locationIdx = if (columns.size > 3) 3 else -1
                }

                var uuidIdx = -1
                for (i in columns.indices) {
                    val col = columns[i]
                    if (col.lowercase() == "uuid" || col.lowercase() == "uid") {
                        uuidIdx = i
                    }
                }

                // Parse standard CSV data lines
                for (i in 1 until lines.size) {
                    val line = lines[i].trim()
                    if (line.isEmpty()) continue

                    // Parse split handling quotes conservatively
                    val cells = parseCsvLine(line)
                    if (cells.isEmpty()) continue

                    val itemCode = if (codeIdx >= 0 && codeIdx < cells.size) cells[codeIdx] else "C_${1000 + i}"
                    val itemName = if (nameIdx >= 0 && nameIdx < cells.size) cells[nameIdx] else "未命名盘点物 $i"
                    val itemCat = if (categoryIdx >= 0 && categoryIdx < cells.size) cells[categoryIdx] else "默认分类"
                    val itemLoc = if (locationIdx >= 0 && locationIdx < cells.size) cells[locationIdx] else "默认区域"

                    val shouldCheckStr = if (shouldCheckIdx >= 0 && shouldCheckIdx < cells.size) cells[shouldCheckIdx] else "true"
                    val isCheck = !(shouldCheckStr.lowercase() == "false" || shouldCheckStr == "否" || shouldCheckStr == "0")

                    // Skip corrupt rows with completely blank name/category
                    if (itemName.isBlank() || itemCat.isBlank()) continue

                    val itemUid = if (uuidIdx >= 0 && uuidIdx < cells.size && cells[uuidIdx].trim().isNotEmpty()) {
                        cells[uuidIdx].trim()
                    } else {
                        UUID.randomUUID().toString()
                    }

                    newList.add(
                        StockItem(
                            uid = itemUid,
                            name = itemName,
                            category = itemCat,
                            location = itemLoc,
                            originalCode = itemCode,
                            photoCount = 0,
                            pdfStatus = InventoryConstants.PDF_STATUS_PENDING,
                            projectId = projectId,
                            shouldCheck = isCheck,
                            originalRowJson = toJsonList(cells),
                            rowOrder = i
                        )
                    )
                }
            }

            if (replace) {
                val oldItems = stockItemDao.getItemsByProjectSync(projectId)
                for (item in oldItems) {
                    File(context.filesDir, "pdfs/${item.uid}").deleteRecursively()
                    File(context.filesDir, "photos/${item.uid}").deleteRecursively()
                }
                stockItemDao.deleteItemsByProject(projectId)
            }

            if (newList.isNotEmpty()) {
                stockItemDao.insertAll(newList)
                return@withContext true
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Parses XLSX workbook zip sheet and imports into database.
     */
    suspend fun parseAndImportXlsx(context: Context, inputStream: InputStream, projectId: String, replace: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val newList = mutableListOf<StockItem>()
            val wb = org.apache.poi.xssf.usermodel.XSSFWorkbook(inputStream)
            val sheet = wb.getSheetAt(0) ?: return@withContext false
            val firstRow = sheet.getRow(0) ?: return@withContext false
            val lastCol = firstRow.lastCellNum.toInt()
            if (lastCol <= 0) return@withContext false

            val headerList = mutableListOf<String>()
            for (colIdx in 0 until lastCol) {
                val cell = firstRow.getCell(colIdx)
                val value = cell?.toString()?.trim() ?: ""
                headerList.add(value)
            }

            // Save headers to Project
            val project = projectDao.getProjectById(projectId)
            if (project != null) {
                val headerJson = toJsonList(headerList)
                projectDao.insertProject(project.copy(columnHeadersJson = headerJson))
            }

            var nameColIdx = -1
            var categoryColIdx = -1
            var locationColIdx = -1
            var codeColIdx = -1
            var shouldCheckColIdx = -1
            var uuidColIdx = -1

            // 1. First pass: strict match
            for (colIdx in headerList.indices) {
                val h = headerList[colIdx].trim().lowercase()
                if (h == "设备编号" || h == "资产编号" || h == "设备代码" || h == "assetcode" || h == "code") {
                    codeColIdx = colIdx
                }
                if (h == "设备名称" || h == "资产名称" || h == "name" || h == "assetname") {
                    nameColIdx = colIdx
                }
                if (h == "资产分类" || h == "设备分类" || h == "分类" || h == "类别" || h == "category") {
                    categoryColIdx = colIdx
                }
                if (h == "存放位置" || h == "存放地点" || h == "位置" || h == "location") {
                    locationColIdx = colIdx
                }
                if (h == "是否盘点" || h == "是否点检" || h == "shouldcheck") {
                    shouldCheckColIdx = colIdx
                }
                if (h == "uuid" || h == "uid") {
                    uuidColIdx = colIdx
                }
            }

            // 2. Second pass: regex-like fallback
            for (colIdx in headerList.indices) {
                val h = headerList[colIdx].trim().lowercase()
                if (nameColIdx == -1 && (h.contains("名称") || h.contains("name")) && !h.contains("分类") && !h.contains("类别")) {
                    nameColIdx = colIdx
                }
                if (categoryColIdx == -1 && (h.contains("分类") || h.contains("类别") || h.contains("category"))) {
                    categoryColIdx = colIdx
                }
                if (locationColIdx == -1 && (h.contains("位置") || h.contains("地点") || h.contains("location") || h.contains("区域"))) {
                    locationColIdx = colIdx
                }
                if (codeColIdx == -1 && (h.contains("编号") || h.contains("code") || h.contains("代码") || h.contains("条码")) && !h.contains("序号")) {
                    codeColIdx = colIdx
                }
                if (shouldCheckColIdx == -1 && (h.contains("盘点") || h.contains("点检") || h.contains("check"))) {
                    shouldCheckColIdx = colIdx
                }
                if (uuidColIdx == -1 && (h.contains("uuid") || h.contains("uid") || h.contains("唯一")) && !h.contains("编号") && !h.contains("名称")) {
                    uuidColIdx = colIdx
                }
            }

            // 3. Fallbacks
            for (colIdx in headerList.indices) {
                val h = headerList[colIdx].trim().lowercase()
                if (codeColIdx == -1 && (h.contains("序号") || h.contains("index") || h == "id")) {
                    codeColIdx = colIdx
                }
            }

            if (nameColIdx == -1) {
                if (headerList.size > 1) {
                    nameColIdx = 1
                } else if (headerList.isNotEmpty()) {
                    nameColIdx = 0
                }
            }
            if (categoryColIdx == -1) {
                for (idx in headerList.indices) {
                    if (idx != nameColIdx && idx != codeColIdx && idx != uuidColIdx) {
                        categoryColIdx = idx
                        break
                    }
                }
                if (categoryColIdx == -1 && headerList.isNotEmpty()) {
                    categoryColIdx = nameColIdx
                }
            }

            if (nameColIdx == -1 || categoryColIdx == -1) {
                return@withContext false
            }

            val rowCount = sheet.lastRowNum
            for (rowIdx in 1..rowCount) {
                val row = sheet.getRow(rowIdx) ?: continue
                val rCells = mutableListOf<String>()
                for (colIdx in 0 until lastCol) {
                    val cell = row.getCell(colIdx)
                    var cellVal = ""
                    if (cell != null) {
                        cellVal = when (cell.cellType) {
                            org.apache.poi.ss.usermodel.CellType.NUMERIC -> {
                                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                                    val date = cell.dateCellValue
                                    if (date != null) {
                                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                        sdf.format(date)
                                    } else {
                                        ""
                                    }
                                } else {
                                    val dVal = cell.numericCellValue
                                    if (dVal == dVal.toLong().toDouble()) {
                                        dVal.toLong().toString()
                                    } else {
                                        val dfStr = cell.toString().trim()
                                        if (dfStr.endsWith(".0")) dfStr.substring(0, dfStr.length - 2) else dfStr
                                    }
                                }
                            }
                            org.apache.poi.ss.usermodel.CellType.STRING -> {
                                cell.stringCellValue ?: ""
                            }
                            org.apache.poi.ss.usermodel.CellType.BOOLEAN -> {
                                cell.booleanCellValue.toString()
                            }
                            org.apache.poi.ss.usermodel.CellType.FORMULA -> {
                                try {
                                    cell.stringCellValue ?: ""
                                } catch (e: Exception) {
                                    try {
                                        cell.numericCellValue.toString()
                                    } catch (ex: Exception) {
                                        ""
                                    }
                                }
                            }
                            else -> ""
                        }
                    }
                    rCells.add(cellVal.trim())
                }

                if (rCells.all { it.isEmpty() }) {
                    continue
                }

                val itemCode = rCells.getOrNull(codeColIdx)?.trim()?.ifEmpty { "E_${1000 + rowIdx}" } ?: "E_${1000 + rowIdx}"
                val itemName = rCells.getOrNull(nameColIdx)?.trim()?.ifEmpty { "未命名盘点物 $rowIdx" } ?: "未命名盘点物 $rowIdx"
                val itemCat = rCells.getOrNull(categoryColIdx)?.trim()?.ifEmpty { "默认分类" } ?: "默认分类"
                val itemLoc = rCells.getOrNull(locationColIdx)?.trim()?.ifEmpty { "默认区域" } ?: "默认区域"

                val shouldCheckStr = rCells.getOrNull(shouldCheckColIdx)?.trim() ?: "true"
                val isCheck = !(shouldCheckStr.lowercase() == "false" || shouldCheckStr == "否" || shouldCheckStr == "0")

                val originalRowJsonStr = toJsonList(rCells)
                val itemUid = if (uuidColIdx != -1 && rCells.getOrNull(uuidColIdx)?.trim()?.isNotEmpty() == true) {
                    rCells[uuidColIdx].trim()
                } else {
                    UUID.randomUUID().toString()
                }

                newList.add(
                    StockItem(
                        uid = itemUid,
                        name = itemName,
                        category = itemCat,
                        location = itemLoc,
                        originalCode = itemCode,
                        photoCount = 0,
                        pdfStatus = InventoryConstants.PDF_STATUS_PENDING,
                        projectId = projectId,
                        shouldCheck = isCheck,
                        originalRowJson = originalRowJsonStr,
                        rowOrder = rowIdx
                    )
                )
            }

            if (replace) {
                val oldItems = stockItemDao.getItemsByProjectSync(projectId)
                for (item in oldItems) {
                    File(context.filesDir, "pdfs/${item.uid}").deleteRecursively()
                    File(context.filesDir, "photos/${item.uid}").deleteRecursively()
                }
                stockItemDao.deleteItemsByProject(projectId)
            }

            if (newList.isNotEmpty()) {
                stockItemDao.insertAll(newList)
                return@withContext true
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun colLetterToNumber(letter: String): Int {
        var num = 0
        for (i in 0 until letter.length) {
            num = num * 26 + (letter[i] - 'A' + 1)
        }
        return num
    }

    /**
     * CSV line parser to properly handle quotes that might contain commas
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var curVal = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '\"') {
                    if (i + 1 < line.length && line[i + 1] == '\"') {
                        curVal.append('\"') // Escaped quote
                        i++
                    } else {
                        inQuotes = false // Closing quote
                    }
                } else {
                    curVal.append(ch)
                }
            } else {
                if (ch == '\"') {
                    inQuotes = true
                } else if (ch == ',') {
                    result.add(curVal.toString().trim())
                    curVal = StringBuilder()
                } else {
                    curVal.append(ch)
                }
            }
            i++
        }
        result.add(curVal.toString().trim())
        return result
    }

    /**
     * Aggregates photos of an item and builds a local PDF inside a persistent directory.
     */
    suspend fun generatePdfForItem(context: Context, item: StockItem): File? = withContext(Dispatchers.IO) {
        val photoDir = File(context.filesDir, "photos/${item.uid}")
        val imageFiles = photoDir.listFiles()?.filter { 
            it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "jpeg") 
        }?.sortedBy { it.name }

        if (imageFiles.isNullOrEmpty()) {
            updatePhotoState(item.uid, 0, InventoryConstants.PDF_STATUS_PENDING)
            return@withContext null
        }

        val pdfDir = File(context.filesDir, "pdfs/${item.uid}")
        if (!pdfDir.exists()) pdfDir.mkdirs()
        
        // Output PDF named as photos.pdf or 照片.pdf, let's use the standard "照片.pdf"
        val pdfFile = File(pdfDir, "照片.pdf")

        val pdfDocument = PdfDocument()
        try {
            val project = projectDao.getProjectById(item.projectId) ?: Project(id = item.projectId, name = InventoryConstants.DEFAULT_PROJECT_NAME)
            val isWatermarkEnabled = project.watermarkEnabled
            val watermarkText = if (isWatermarkEnabled && project.watermarkTrEnabled) {
                val prefix = getCategoryPrefix(context, item.category)
                val allProjectItems = getItemsByProjectSync(item.projectId).filter { it.category == item.category }
                val sortedItems = allProjectItems.sortedBy { it.originalCode.ifEmpty { it.uid } }
                val index = sortedItems.indexOfFirst { it.uid == item.uid }
                val sequenceStr = String.format("%04d", if (index != -1) index + 1 else 1)
                "$prefix-$sequenceStr"
            } else null

            for (imageFile in imageFiles) {
                // Resize during load to ensure extremely low RAM and compact file footprint
                val bitmap = getResizedBitmap(imageFile.absolutePath, 1200) ?: continue
                
                // Keep clean aspect ratio sized dynamic page without redundant white bleed footers
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Fill the canvas with solid white backdrop first
                val bgPaint = android.graphics.Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), bgPaint)

                // Draw the photograph at top 0, 0
                canvas.drawBitmap(bitmap, 0f, 0f, null)

                if (watermarkText != null) {
                    val paintText = android.graphics.Paint().apply {
                        color = 0xFFFF0000.toInt() // Pure Red
                        textSize = (bitmap.width * 0.035f).coerceIn(24f, 48f)
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }

                    val textBounds = android.graphics.Rect()
                    paintText.getTextBounds(watermarkText, 0, watermarkText.length, textBounds)
                    val textWidth = textBounds.width()
                    val textHeight = textBounds.height()

                    val marginX = (bitmap.width * 0.04f).coerceAtLeast(30f)
                    val marginY = (bitmap.height * 0.04f).coerceAtLeast(30f)

                    val textX = bitmap.width - marginX - textWidth
                    val textY = marginY + textHeight

                    canvas.drawText(watermarkText, textX, textY, paintText)
                }

                // Bottom-Left Watermark Block
                val blEnabled = isWatermarkEnabled && project.watermarkBlEnabled
                if (blEnabled) {
                    val linesList = mutableListOf<String>()
                    val photoMeta = com.example.util.PhotoMetadataUtils.readPhysicalMetadata(imageFile)

                    if (project.watermarkBlShowDate) {
                        linesList.add("拍摄日期：${photoMeta.dateStr}")
                    }
                    if (project.watermarkBlShowTime) {
                        linesList.add("时间：${photoMeta.timeStr}")
                    }
                    if (project.watermarkBlShowGps) {
                        linesList.add(String.format(java.util.Locale.CHINA, "经度：%.2f  纬度：%.2f", photoMeta.longitude, photoMeta.latitude))
                    }
                    if (project.watermarkBlShowAddress) {
                        linesList.add("位置：${photoMeta.address}")
                    }

                    if (linesList.isNotEmpty()) {
                        val blPaint = android.graphics.Paint().apply {
                            color = 0xFFFFFFFF.toInt() // White text
                            textSize = (bitmap.width * 0.024f).coerceIn(16f, 32f)
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                        }

                        val marginX = (bitmap.width * 0.03f).coerceAtLeast(20f)
                        val marginY = (bitmap.height * 0.03f).coerceAtLeast(20f)

                        var maxLineWidth = 0f
                        var maxLineHeight = 0f
                        val textBounds = android.graphics.Rect()
                        for (line in linesList) {
                            blPaint.getTextBounds(line, 0, line.length, textBounds)
                            val w = blPaint.measureText(line)
                            if (w > maxLineWidth) maxLineWidth = w
                            if (textBounds.height() > maxLineHeight) maxLineHeight = textBounds.height().toFloat()
                        }

                        val spacing = (bitmap.height * 0.008f).coerceIn(4f, 10f)
                        val boxPadding = (bitmap.width * 0.015f).coerceIn(10f, 20f)

                        val boxHeight = maxLineHeight * linesList.size + spacing * (linesList.size - 1) + boxPadding * 2
                        val boxWidth = maxLineWidth + boxPadding * 2

                        val boxLeft = marginX
                        val boxBottom = bitmap.height - marginY
                        val boxTop = boxBottom - boxHeight
                        val boxRight = boxLeft + boxWidth

                        // Draw backing box
                        val bgPaint = android.graphics.Paint().apply {
                            color = 0x66000000.toInt() // 40% opaque black background
                            style = android.graphics.Paint.Style.FILL
                        }
                        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, bgPaint)

                        // Draw each line inside the backing box
                        var currentY = boxTop + boxPadding
                        for (line in linesList) {
                            val textY = currentY + maxLineHeight
                            canvas.drawText(line, boxLeft + boxPadding, textY, blPaint)
                            currentY += maxLineHeight + spacing
                        }
                    }
                }

                pdfDocument.finishPage(page)
                bitmap.recycle()
            }
            
            FileOutputStream(pdfFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            
            // Update SQLite Room records
            updatePhotoState(item.uid, imageFiles.size, InventoryConstants.PDF_STATUS_GENERATED)
            return@withContext pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            pdfDocument.close()
        }
    }

    /**
     * Compresses and resizes a bitmap to fit a maximum dimension for lightweight PDF compilation.
     */
    fun getResizedBitmap(imagePath: String, maxDimension: Int = 1200): android.graphics.Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imagePath, options)
        
        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null
        
        var sampleSize = 1
        while (srcWidth / sampleSize > maxDimension || srcHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val decodedBitmap = BitmapFactory.decodeFile(imagePath, decodeOptions) ?: return null
        
        val currentWidth = decodedBitmap.width
        val currentHeight = decodedBitmap.height
        val scale = Math.min(
            maxDimension.toFloat() / currentWidth,
            maxDimension.toFloat() / currentHeight
        )
        
        val scaledBitmap = if (scale >= 1.0f) {
            decodedBitmap
        } else {
            val targetWidth = (currentWidth * scale).toInt()
            val targetHeight = (currentHeight * scale).toInt()
            val result = android.graphics.Bitmap.createScaledBitmap(decodedBitmap, targetWidth, targetHeight, true)
            if (result != decodedBitmap) {
                decodedBitmap.recycle()
            }
            result
        }

        // Apply rotation to match original EXIF orientation
        return try {
            val exifInterface = android.media.ExifInterface(imagePath)
            val orientation = exifInterface.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val rotationDegrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                val rotated = android.graphics.Bitmap.createBitmap(
                    scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true
                )
                if (rotated != scaledBitmap) {
                    scaledBitmap.recycle()
                }
                rotated
            } else {
                scaledBitmap
            }
        } catch (e: Exception) {
            scaledBitmap
        }
    }

    /**
     * Applies optical enhancement filters like grayscale, high contrast mono, or brightness boost.
     */
    fun applyImageFilter(imageFile: File, filterType: String): File {
        try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return imageFile
            val width = bitmap.width
            val height = bitmap.height
            val resultBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(resultBitmap)
            val paint = android.graphics.Paint()
            
            when (filterType) {
                "grayscale" -> {
                    val colorMatrix = android.graphics.ColorMatrix().apply {
                        setSaturation(0f)
                    }
                    paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                }
                "bw" -> {
                    // Turn gray levels directly into deep black & bright white Document look
                    val colorMatrix = android.graphics.ColorMatrix().apply {
                        setSaturation(0f)
                        val scale = 3.0f
                        val translate = -220f
                        val matrixVals = floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                        set(matrixVals)
                    }
                    paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                }
                "magic" -> {
                    // Magic optical color document look (Contrast and brightness boost)
                    val colorMatrix = android.graphics.ColorMatrix().apply {
                        val scale = 1.4f
                        val translate = 40f
                        val matrixVals = floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                        set(matrixVals)
                    }
                    paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                }
                else -> { // "original"
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                }
            }
            
            FileOutputStream(imageFile).use { out ->
                resultBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
            resultBitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return imageFile
    }

    /**
     * Recursively counts photographic files inside an item's directory.
     */
    fun countPhotos(context: Context, uid: String): Int {
        val photoDir = File(context.filesDir, "photos/$uid")
        val files = photoDir.listFiles()?.filter { 
            it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "jpeg") 
        }
        return files?.size ?: 0
    }

    /**
     * Gets custom prefix configured for the specified category, or dynamic fallback logic
     */
    fun getCategoryPrefix(context: Context, category: String): String {
        val prefs = context.getSharedPreferences("category_prefixes_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(category, "")
        if (!saved.isNullOrEmpty()) {
            return saved
        }
        return when (category) {
            "机器设备类", "机器设备" -> "C4-6-4"
            "车辆类", "车辆" -> "C4-6-5"
            "电子设备类", "电子设备", "电子产品" -> "C4-6-6"
            "办公家具类", "办公家具" -> "C-1-3"
            "无形资产类", "无形资产" -> "C-1-4"
            else -> {
                if (category.length >= 2) {
                    category.take(2).uppercase()
                } else if (category.isNotEmpty()) {
                    category.uppercase()
                } else {
                    "QC"
                }
            }
        }
    }

    /**
     * Saves custom prefix configured for the specified category
     */
    fun saveCategoryPrefix(context: Context, category: String, prefix: String) {
        val prefs = context.getSharedPreferences("category_prefixes_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(category, prefix).apply()
    }

    /**
     * Physical folder photo files sequential renumbering/renaming (0001.jpg, 0002.jpg...)
     */
    fun renumberPhotos(context: Context, uid: String) {
        try {
            val photoDir = File(context.filesDir, "photos/$uid")
            if (!photoDir.exists()) return
            val imageFiles = photoDir.listFiles()?.filter {
                it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "jpeg")
            }?.sortedBy { it.name } ?: return

            // Temp renaming phase to avoid shifting names collision issues
            val tempFiles = imageFiles.map { file ->
                val tempFile = File(photoDir, "temp_renumber_${UUID.randomUUID().toString().take(6)}.jpg")
                if (file.renameTo(tempFile)) tempFile else file
            }

            // Target sequential naming phase
            tempFiles.forEachIndexed { index, file ->
                val targetName = String.format("%04d.jpg", index + 1)
                val targetFile = File(photoDir, targetName)
                file.renameTo(targetFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Applies physical cropping border adjustments to a specific photograph file
     */
    fun cropImageFile(file: File, topPct: Float, bottomPct: Float, leftPct: Float, rightPct: Float): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
            val width = bitmap.width
            val height = bitmap.height

            val cropLeft = (width * (leftPct / 100f)).toInt().coerceIn(0, width - 1)
            val cropTop = (height * (topPct / 100f)).toInt().coerceIn(0, height - 1)
            
            val cropRight = (width * (1f - rightPct / 100f)).toInt().coerceIn(cropLeft + 10, width)
            val cropBottom = (height * (1f - bottomPct / 100f)).toInt().coerceIn(cropTop + 10, height)

            val targetW = cropRight - cropLeft
            val targetH = cropBottom - cropTop

            if (targetW <= 10 || targetH <= 10) {
                bitmap.recycle()
                return false
            }

            val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, cropLeft, cropTop, targetW, targetH)
            FileOutputStream(file).use { out ->
                croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            croppedBitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Compresses all inventory-generated folders and generated PDFs into a single ZIP file.
     * The ZIP layout strictly conforms to the requested directory hierarchy:
     *   Category_1\PDF_File_Name.pdf
     *   Category_2\PDF_File_Name.pdf
     * Root Level compiles:
     *   盘点表.csv (with UTF-8 BOM, including UUID, Photo Count, and generated PDF filename)
     */
    suspend fun createExportZip(context: Context, items: List<StockItem>, outputZipFile: File): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false

        try {
            if (outputZipFile.exists()) {
                outputZipFile.delete()
            }
            outputZipFile.parentFile?.mkdirs()

            // Group all items by category
            val grouped = items.groupBy { it.category }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
                var pdfCount = 0

                for ((category, categoryItems) in grouped) {
                    val cleanCategoryDir = category.replace("/", "_").replace("\\", "_").replace(":", "_").trim()
                    
                    // Sort items consistently within the same category to allocate sequence numbers
                    val sortedItems = categoryItems.sortedBy { it.originalCode.ifEmpty { it.uid } }
                    val prefix = getCategoryPrefix(context, category)

                    sortedItems.forEachIndexed { index, item ->
                        val sequenceStr = String.format("%04d", index + 1)
                        val pdfFileName = "$prefix $sequenceStr ${item.name}.pdf"

                        val pdfFile = File(context.filesDir, "pdfs/${item.uid}/照片.pdf")
                        
                        // Compile PDF on the fly if user captured photos but hasn't updated PDF
                        val finalPdf = if (!pdfFile.exists() && item.photoCount > 0) {
                            generatePdfForItem(context, item)
                        } else if (pdfFile.exists()) {
                            pdfFile
                        } else null

                        var assignedName = "暂无(未拍照存档)"

                        if (finalPdf != null && finalPdf.exists()) {
                            val entryPath = "$cleanCategoryDir/$pdfFileName"
                            assignedName = pdfFileName
                            zos.putNextEntry(ZipEntry(entryPath))
                            finalPdf.inputStream().use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                            pdfCount++
                        }
                    }
                }

                // Append the index Excel workbook "盘点表.xlsx" straight at the ZIP root folder
                val tempXlsxFile = File(context.cacheDir, "temp_export_${UUID.randomUUID()}.xlsx")
                val pId = items.firstOrNull()?.projectId ?: InventoryConstants.DEFAULT_PROJECT_ID
                generateXlsxReport(context, pId, items, tempXlsxFile)
                if (tempXlsxFile.exists()) {
                    val proj = getProjectById(pId)
                    val companyName = proj?.companyName?.trim() ?: ""
                    val xlsxName = if (companyName.isNotEmpty()) "盘点表-$companyName.xlsx" else "盘点表.xlsx"
                    zos.putNextEntry(ZipEntry(xlsxName))
                    tempXlsxFile.inputStream().use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                    tempXlsxFile.delete()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
