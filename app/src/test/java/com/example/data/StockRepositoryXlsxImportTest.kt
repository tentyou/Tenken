package com.example.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StockRepositoryXlsxImportTest {
    private lateinit var stockItemDao: FakeStockItemDao
    private lateinit var projectDao: FakeProjectDao
    private lateinit var repository: StockRepository
    private val unusedContext: Context = ContextWrapper(null)

    @Before
    fun setUp() {
        stockItemDao = FakeStockItemDao()
        projectDao = FakeProjectDao()
        repository = StockRepository(stockItemDao, projectDao)
    }

    @Test
    fun xlsxTemplateUsesSharedWifiHeadersAndProjectExtras() {
        val columnHeadersJson = repository.toJsonList(
            listOf("设备编号", "设备名称", "自定义保管人", "uuid", "资产分类")
        )

        val expectedHeaders = InventoryTemplate.STANDARD_HEADER_SEQUENCE + "自定义保管人"
        assertEquals(expectedHeaders, InventoryTemplate.headersForProject(columnHeadersJson))

        val workbook = XSSFWorkbook(ByteArrayInputStream(InventoryTemplate.createXlsxBytes(columnHeadersJson)))
        workbook.use {
            val sheet = it.getSheetAt(0)
            val actualHeaders = (0 until expectedHeaders.size).map { index ->
                sheet.getRow(0).getCell(index).stringCellValue
            }
            assertEquals(expectedHeaders, actualHeaders)
        }
    }

    @Test
    fun xlsxImportAppendsItemsAndStoresHeaders() = runTest {
        val targetProjectId = "project-xlsx"
        val otherProjectId = "project-other"
        projectDao.insertProject(Project(id = targetProjectId, name = "XLSX Project"))
        projectDao.insertProject(Project(id = otherProjectId, name = "Other Project"))
        stockItemDao.insertItem(
            StockItem(uid = "old-other", name = "Old Other", category = "Old", projectId = otherProjectId)
        )

        val imported = repository.parseAndImportXlsx(
            context = unusedContext,
            inputStream = ByteArrayInputStream(createImportWorkbookBytes()),
            projectId = targetProjectId,
            replace = false
        )

        val targetItems = stockItemDao.getItemsByProjectSync(targetProjectId)
        val otherItems = stockItemDao.getItemsByProjectSync(otherProjectId)
        val project = projectDao.getProjectById(targetProjectId)

        assertTrue(imported)
        assertEquals(1, targetItems.size)
        assertEquals("A-010", targetItems.single().originalCode)
        assertEquals("扫描仪", targetItems.single().name)
        assertEquals("电子设备", targetItems.single().category)
        assertEquals(1, targetItems.single().rowOrder)
        assertFalse(targetItems.single().shouldCheck)
        assertEquals(InventoryConstants.PDF_STATUS_PENDING, targetItems.single().pdfStatus)
        assertEquals(listOf("old-other"), otherItems.map { it.uid })
        assertEquals(InventoryTemplate.STANDARD_HEADER_SEQUENCE, repository.fromJsonList(project?.columnHeadersJson))
    }

    private fun createImportWorkbookBytes(): ByteArray {
        val workbook = XSSFWorkbook()
        workbook.use {
            val sheet = it.createSheet("盘点模板")
            val headerRow = sheet.createRow(0)
            InventoryTemplate.STANDARD_HEADER_SEQUENCE.forEachIndexed { index, header ->
                headerRow.createCell(index).setCellValue(header)
            }

            val row = sheet.createRow(1)
            row.createCell(0).setCellValue("1")
            row.createCell(1).setCellValue("A-010")
            row.createCell(2).setCellValue("扫描仪")
            row.createCell(11).setCellValue("false")
            row.createCell(13).setCellValue("电子设备")

            return ByteArrayOutputStream().use { outputStream ->
                it.write(outputStream)
                outputStream.toByteArray()
            }
        }
    }
}

private class FakeStockItemDao : StockItemDao {
    private val items = linkedMapOf<String, StockItem>()

    override fun getAllItems(): Flow<List<StockItem>> = flowOf(items.values.sorted())

    override suspend fun getAllItemsSync(): List<StockItem> = items.values.toList()

    override fun getAllItemsByProject(projectId: String): Flow<List<StockItem>> {
        return flowOf(getItemsByProject(projectId))
    }

    override suspend fun getItemsByProjectSync(projectId: String): List<StockItem> {
        return getItemsByProject(projectId)
    }

    override suspend fun getItemByUid(uid: String): StockItem? = items[uid]

    override suspend fun insertItem(item: StockItem) {
        items[item.uid] = item
    }

    override suspend fun insertAll(items: List<StockItem>) {
        items.forEach { insertItem(it) }
    }

    override suspend fun updateItem(item: StockItem) {
        items[item.uid] = item
    }

    override suspend fun updatePhotoState(uid: String, photoCount: Int, pdfStatus: String) {
        items[uid]?.let { item ->
            items[uid] = item.copy(photoCount = photoCount, pdfStatus = pdfStatus)
        }
    }

    override suspend fun deleteItem(item: StockItem) {
        items.remove(item.uid)
    }

    override suspend fun deleteAll() {
        items.clear()
    }

    override suspend fun deleteItemsByProject(projectId: String) {
        items.values.filter { it.projectId == projectId }.forEach { items.remove(it.uid) }
    }

    override fun getSyncItemsCount(): Int = items.size

    override fun getSyncItemsCountByProject(projectId: String): Int {
        return items.values.count { it.projectId == projectId }
    }

    private fun getItemsByProject(projectId: String): List<StockItem> {
        return items.values.filter { it.projectId == projectId }.sorted()
    }

    private fun Collection<StockItem>.sorted(): List<StockItem> {
        return sortedWith(compareBy<StockItem> { it.rowOrder }.thenBy { it.uid })
    }
}

private class FakeProjectDao : ProjectDao {
    private val projects = linkedMapOf<String, Project>()

    override fun getAllProjectsFlow(): Flow<List<Project>> = flowOf(projects.values.sortedBy { it.name })

    override suspend fun getAllProjects(): List<Project> = projects.values.sortedBy { it.name }

    override suspend fun getProjectById(id: String): Project? = projects[id]

    override fun getProjectByIdFlow(id: String): Flow<Project?> = flowOf(projects[id])

    override suspend fun insertProject(project: Project) {
        projects[project.id] = project
    }

    override suspend fun deleteProject(project: Project) {
        projects.remove(project.id)
    }
}
