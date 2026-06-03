package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Project
import com.example.data.StockItem
import com.example.data.StockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class StockViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val repository: StockRepository

    // Project & Stock lists
    val allProjects: StateFlow<List<Project>>
    val activeProject: StateFlow<Project?>
    
    private val _activeProjectId = MutableStateFlow<String>("default_project")
    val activeProjectId = _activeProjectId.asStateFlow()

    val stockItems: StateFlow<List<StockItem>>

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()

    // Current capturing stock item
    private val _activeItemForPhoto = MutableStateFlow<StockItem?>(null)
    val activeItemForPhoto = _activeItemForPhoto.asStateFlow()

    // Track active item photo files for instant camera overlay updates
    private val _activeSessionPhotos = MutableStateFlow<List<File>>(emptyList())
    val activeSessionPhotos = _activeSessionPhotos.asStateFlow()

    // Background alert triggers
    private val _backgroundPdfMessage = MutableStateFlow<String?>(null)
    val backgroundPdfMessage = _backgroundPdfMessage.asStateFlow()

    private val _wifiTransferEnabled = MutableStateFlow(false)
    val wifiTransferEnabled = _wifiTransferEnabled.asStateFlow()

    private val _deviceIpAddress = MutableStateFlow<String?>(null)
    val deviceIpAddress = _deviceIpAddress.asStateFlow()

    private val prefs = context.getSharedPreferences("dianjian_prefs", Context.MODE_PRIVATE)
    
    private val _showTutorial = MutableStateFlow(prefs.getBoolean("show_tutorial", true))
    val showTutorial = _showTutorial.asStateFlow()

    private val _watermarkEnabled = MutableStateFlow(false)
    val watermarkEnabled = _watermarkEnabled.asStateFlow()

    private val _watermarkTrEnabled = MutableStateFlow(true)
    val watermarkTrEnabled = _watermarkTrEnabled.asStateFlow()

    private val _isWatermarking = MutableStateFlow(false)
    val isWatermarking = _isWatermarking.asStateFlow()

    fun setWatermarkEnabled(enabled: Boolean, onFinished: (Int) -> Unit = {}) {
        _watermarkEnabled.value = enabled
        viewModelScope.launch {
            val pid = _activeProjectId.value
            val project = repository.getProjectById(pid)
            if (project != null) {
                repository.insertProject(project.copy(watermarkEnabled = enabled))
                
                _isWatermarking.value = true
                var processedCount = 0
                withContext(Dispatchers.IO) {
                    try {
                        val allProjectItems = repository.getItemsByProjectSync(pid)
                        val itemsWithPdf = allProjectItems.filter { it.pdfStatus == "已生成" }
                        processedCount = itemsWithPdf.size
                        for (item in itemsWithPdf) {
                            repository.generatePdfForItem(context, item)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                _isWatermarking.value = false
                onFinished(processedCount)
            } else {
                onFinished(0)
            }
        }
    }

    fun completeTutorial() {
        _showTutorial.value = false
        prefs.edit().putBoolean("show_tutorial", false).apply()
    }

    private val _watermarkBlEnabled = MutableStateFlow(true)
    val watermarkBlEnabled = _watermarkBlEnabled.asStateFlow()

    private val _watermarkBlShowDate = MutableStateFlow(true)
    val watermarkBlShowDate = _watermarkBlShowDate.asStateFlow()

    private val _watermarkBlShowTime = MutableStateFlow(true)
    val watermarkBlShowTime = _watermarkBlShowTime.asStateFlow()

    private val _watermarkBlShowGps = MutableStateFlow(true)
    val watermarkBlShowGps = _watermarkBlShowGps.asStateFlow()

    private val _watermarkBlShowAddress = MutableStateFlow(true)
    val watermarkBlShowAddress = _watermarkBlShowAddress.asStateFlow()

    private val _watermarkBlAddress = MutableStateFlow("上海市黄浦区人民大道100号")
    val watermarkBlAddress = _watermarkBlAddress.asStateFlow()

    private val _watermarkBlLat = MutableStateFlow("31.2304")
    val watermarkBlLat = _watermarkBlLat.asStateFlow()

    private val _watermarkBlLng = MutableStateFlow("121.4737")
    val watermarkBlLng = _watermarkBlLng.asStateFlow()

    fun updateWatermarkBlSettings(
        enabled: Boolean? = null,
        showDate: Boolean? = null,
        showTime: Boolean? = null,
        showGps: Boolean? = null,
        showAddress: Boolean? = null,
        address: String? = null,
        lat: String? = null,
        lng: String? = null
    ) {
        viewModelScope.launch {
            val pid = _activeProjectId.value
            val project = repository.getProjectById(pid) ?: return@launch
            
            enabled?.let { _watermarkBlEnabled.value = it }
            showDate?.let { _watermarkBlShowDate.value = it }
            showTime?.let { _watermarkBlShowTime.value = it }
            showGps?.let { _watermarkBlShowGps.value = it }
            showAddress?.let { _watermarkBlShowAddress.value = it }
            
            val updatedProject = project.copy(
                watermarkBlEnabled = enabled ?: project.watermarkBlEnabled,
                watermarkBlShowDate = showDate ?: project.watermarkBlShowDate,
                watermarkBlShowTime = showTime ?: project.watermarkBlShowTime,
                watermarkBlShowGps = showGps ?: project.watermarkBlShowGps,
                watermarkBlShowAddress = showAddress ?: project.watermarkBlShowAddress
            )
            
            // Mark update in real time
            withContext(Dispatchers.IO) {
                repository.insertProject(updatedProject)
                
                // Immediately update current PDFs if watermark is enabled
                val allProjectItems = repository.getItemsByProjectSync(pid)
                val itemsWithPdf = allProjectItems.filter { it.pdfStatus == "已生成" }
                for (item in itemsWithPdf) {
                    repository.generatePdfForItem(context, item)
                }
            }
        }
    }

    fun updateWatermarkTrSetting(enabled: Boolean) {
        _watermarkTrEnabled.value = enabled
        viewModelScope.launch {
            val pid = _activeProjectId.value
            val project = repository.getProjectById(pid) ?: return@launch
            val updatedProject = project.copy(watermarkTrEnabled = enabled)
            withContext(Dispatchers.IO) {
                repository.insertProject(updatedProject)
                
                // Regenerate PDFs
                val allProjectItems = repository.getItemsByProjectSync(pid)
                val itemsWithPdf = allProjectItems.filter { it.pdfStatus == "已生成" }
                for (item in itemsWithPdf) {
                    repository.generatePdfForItem(context, item)
                }
            }
        }
    }

    fun startTutorial() {
        _showTutorial.value = true
        prefs.edit().putBoolean("show_tutorial", true).apply()
    }

    private var wifiServer: WifiTransferServer? = null

    init {
        val database = AppDatabase.getDatabase(context)
        repository = StockRepository(database.stockItemDao(), database.projectDao())
        
        allProjects = repository.allProjects.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        activeProject = _activeProjectId.flatMapLatest { projId ->
            repository.getProjectFlow(projId)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

        stockItems = _activeProjectId.flatMapLatest { projId ->
            repository.getItemsByProject(projId)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        viewModelScope.launch {
            activeProject.collect { project ->
                if (project != null) {
                    _watermarkEnabled.value = project.watermarkEnabled
                    _watermarkBlEnabled.value = project.watermarkBlEnabled
                    _watermarkBlShowDate.value = project.watermarkBlShowDate
                    _watermarkBlShowTime.value = project.watermarkBlShowTime
                    _watermarkBlShowGps.value = project.watermarkBlShowGps
                    _watermarkBlShowAddress.value = project.watermarkBlShowAddress
                    _watermarkTrEnabled.value = project.watermarkTrEnabled
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.listProjectsSync()
            if (existing.isEmpty()) {
                val defaultProj = Project(id = "default_project", name = "默认项目")
                repository.insertProject(defaultProj)
                _activeProjectId.value = "default_project"
            } else {
                _activeProjectId.value = existing[0].id
            }
        }
    }

    fun selectProject(projectId: String) {
        _activeProjectId.value = projectId
    }

    fun addProject(name: String, baseDate: String = "", companyName: String = "", reportType: String = "评估报告") {
        viewModelScope.launch(Dispatchers.IO) {
            val newProj = Project(
                name = name,
                baseDate = baseDate,
                companyName = companyName,
                reportType = reportType
            )
            repository.insertProject(newProj)
            withContext(Dispatchers.Main) {
                _activeProjectId.value = newProj.id
            }
        }
    }

    fun renameProject(projectId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getProjectById(projectId)
            if (existing != null) {
                repository.insertProject(existing.copy(name = newName))
            } else {
                repository.insertProject(Project(id = projectId, name = newName))
            }
        }
    }

    fun updateProjectMeta(projectId: String, baseDate: String, companyName: String, reportType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getProjectById(projectId)
            if (existing != null) {
                repository.insertProject(existing.copy(
                    baseDate = baseDate,
                    companyName = companyName,
                    reportType = reportType
                ))
            }
        }
    }

    fun deleteProject(project: Project, onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProject(context, project)
            val remaining = repository.listProjectsSync()
            withContext(Dispatchers.Main) {
                if (remaining.isNotEmpty()) {
                    selectProject(remaining[0].id)
                } else {
                    selectProject("")
                }
                onDeleted()
            }
        }
    }

    /**
     * Triggers active camera session for an item
     */
    fun startPhotoCapture(item: StockItem) {
        _activeItemForPhoto.value = item
        refreshActiveSessionPhotos(item.uid)
    }

    /**
     * Closes the photography viewport and compiles PDFs in the background.
     */
    fun endPhotoCapture(onDismissUi: () -> Unit) {
        val item = _activeItemForPhoto.value
        if (item != null) {
            // Dismiss UI IMMEDIATELY
            _activeItemForPhoto.value = null
            _activeSessionPhotos.value = emptyList()
            onDismissUi()

            // Perform PDF compilation in background asynchronously
            viewModelScope.launch(Dispatchers.IO) {
                val currentPhotoCount = repository.countPhotos(context, item.uid)
                if (currentPhotoCount > 0) {
                    val freshPdf = repository.generatePdfForItem(context, item)
                    if (freshPdf != null && freshPdf.exists()) {
                        repository.updatePhotoState(item.uid, currentPhotoCount, "已生成")
                        _backgroundPdfMessage.value = "盘点单「${item.name}」拍照拼合 PDF 完成！照片拼合生成并自动进行高质无损压缩（体积通常缩减92%以上）。"
                    } else {
                        repository.updatePhotoState(item.uid, currentPhotoCount, "未生成")
                    }
                } else {
                    repository.updatePhotoState(item.uid, 0, "未生成")
                }
            }
        } else {
            _activeItemForPhoto.value = null
            _activeSessionPhotos.value = emptyList()
            onDismissUi()
        }
    }

    /**
     * Applies optical enhancement filters like black and white document, magic color, or grayscale.
     */
    fun applyFilterToPhoto(file: File, filterType: String) {
        val activeItem = _activeItemForPhoto.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.applyImageFilter(file, filterType)
            withContext(Dispatchers.Main) {
                refreshActiveSessionPhotos(activeItem.uid)
                Toast.makeText(context, "滤镜渲染与黑白防噪美化处理生效！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshActiveSessionPhotos(uid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renumberPhotos(context, uid)
            val photoDir = File(context.filesDir, "photos/$uid")
            val imageFiles = photoDir.listFiles()?.filter { 
                it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "jpeg") 
            }?.sortedBy { it.name } ?: emptyList()
            
            withContext(Dispatchers.Main) {
                _activeSessionPhotos.value = imageFiles
            }
        }
    }

    /**
     * Deletes individual device's photos and compiled PDF
     */
    fun deleteItemPdf(item: StockItem) {
        viewModelScope.launch(Dispatchers.IO) {
            File(context.filesDir, "pdfs/${item.uid}").deleteRecursively()
            File(context.filesDir, "photos/${item.uid}").deleteRecursively()
            repository.updatePhotoState(item.uid, 0, "未生成")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已成功清除「${item.name}」的全部照片和PDF数据", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Deletes individual photo, renumbers other files, and updates compiled PDF/status
     */
    fun deletePhoto(file: File) {
        val activeItem = _activeItemForPhoto.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (file.exists()) {
                file.delete()
            }
            repository.renumberPhotos(context, activeItem.uid)
            val currentPhotoCount = repository.countPhotos(context, activeItem.uid)
            if (currentPhotoCount > 0) {
                repository.generatePdfForItem(context, activeItem)
            } else {
                repository.updatePhotoState(activeItem.uid, 0, "未生成")
            }
            withContext(Dispatchers.Main) {
                refreshActiveSessionPhotos(activeItem.uid)
                Toast.makeText(context, "照片已删除并自适应重新连号", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Applies physical clipping border adjustments to a specific photograph file
     */
    fun applyCropToPhoto(file: File, topPct: Float, bottomPct: Float, leftPct: Float, rightPct: Float) {
        val activeItem = _activeItemForPhoto.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val success = repository.cropImageFile(file, topPct, bottomPct, leftPct, rightPct)
            if (success) {
                repository.generatePdfForItem(context, activeItem)
                withContext(Dispatchers.Main) {
                    refreshActiveSessionPhotos(activeItem.uid)
                    Toast.makeText(context, "纸张裁剪与图像校正切边在App中生效！", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "裁剪失败，请确认识别区域是否过窄", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun getCategoryPrefix(category: String): String {
        return repository.getCategoryPrefix(context, category)
    }

    fun saveCategoryPrefix(category: String, prefix: String) {
        repository.saveCategoryPrefix(context, category, prefix)
    }

    /**
     * Update individual items (for checking/unchecking isInventoried / shouldCheck value)
     */
    fun updateItem(item: StockItem) {
        viewModelScope.launch {
            repository.insertItem(item)
        }
    }

    /**
     * Programmatically generates a simulated physical asset photo with a detailed overlay
     */
    fun simulateCapture(item: StockItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetFile = File(
                context.filesDir,
                "photos/${item.uid}/photo_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}.jpg"
            )
            targetFile.parentFile?.mkdirs()

            try {
                val bitmap = android.graphics.Bitmap.createBitmap(1080, 1080, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val paint = android.graphics.Paint()

                // Draw solid background container with elegant slate-blue colors
                paint.color = 0xFF1E293B.toInt()
                canvas.drawRect(0f, 0f, 1080f, 1080f, paint)

                // High-contrast warning safety lines
                paint.color = 0xFF3B82F6.toInt() // Slate blue
                canvas.drawRect(40f, 40f, 1040f, 60f, paint)
                canvas.drawRect(40f, 1020f, 1040f, 1040f, paint)

                paint.color = android.graphics.Color.WHITE
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 46f
                paint.isAntiAlias = true

                canvas.drawText("【 盘 点 资 产 現 场 实 勘 存 证 】", 80f, 180f, paint)

                paint.textSize = 36f
                paint.color = 0xFF94A3B8.toInt()
                canvas.drawText("核算核验状态: 物理核算真实一致", 80f, 260f, paint)

                paint.color = android.graphics.Color.WHITE
                paint.textSize = 42f
                canvas.drawText("物力资产名称: ${item.name}", 80f, 380f, paint)
                canvas.drawText("实物资产编号: ${item.originalCode}", 80f, 460f, paint)
                canvas.drawText("存放所在位置: ${item.location}", 80f, 540f, paint)
                canvas.drawText("系统设定分类: ${item.category}", 80f, 620f, paint)

                paint.color = 0xFFF59E0B.toInt() // Amber accent code info
                paint.textSize = 34f
                canvas.drawText("系统底层 UID: ${item.uid}", 80f, 740f, paint)

                paint.textSize = 32f
                paint.color = 0xFF10B981.toInt() // Emerald accent time format
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
                val timestampStr = sdf.format(java.util.Date())
                canvas.drawText("数字签章时间戳: $timestampStr", 80f, 820f, paint)

                paint.textSize = 28f
                paint.color = 0xFF64748B.toInt()
                canvas.drawText("AI Studio Built in Remote Emulator Simulation", 80f, 920f, paint)

                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                }
                bitmap.recycle()

                // Save physical EXIF metadata to the mock capture!
                com.example.util.PhotoMetadataUtils.writePhysicalMetadata(context, targetFile, item)

                withContext(Dispatchers.Main) {
                    refreshActiveSessionPhotos(item.uid)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Imports xlsx or csv by SAF Uri
     */
    fun importFile(uri: Uri, projectId: String, replace: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val fileName = getFileName(context, uri)?.lowercase() ?: ""
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val success = if (fileName.endsWith(".xlsx")) {
                        repository.parseAndImportXlsx(context, inputStream, projectId, replace)
                    } else {
                        repository.parseAndImportCsv(context, inputStream, projectId, replace)
                    }
                    withContext(Dispatchers.Main) {
                        onResult(success)
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        onResult(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false)
                }
            } finally {
                _isImporting.value = false
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private val _wifiPort = MutableStateFlow(9090)
    val wifiPort = _wifiPort.asStateFlow()

    fun updateWifiPort(port: Int) {
        _wifiPort.value = port
    }

    /**
     * Toggles the WiFi local network files transfer server on the specified port.
     */
    fun toggleWifiTransfer(enable: Boolean, portVal: Int = _wifiPort.value) {
        _wifiTransferEnabled.value = enable
        if (enable) {
            _wifiPort.value = portVal
            val ip = getDeviceIpAddress()
            _deviceIpAddress.value = ip
            
            wifiServer = WifiTransferServer(
                context = context,
                repository = repository,
                onProjectChanged = { projId ->
                    viewModelScope.launch(Dispatchers.Main) {
                        selectProject(projId)
                        val proj = repository.getProjectById(projId)
                        if (proj != null) {
                            Toast.makeText(context, "局域网传送：已同步并选用分类项目「${proj.name}」！", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) { success, count ->
                if (success) {
                    viewModelScope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "通过局域网无线成功导入「$count」条外部资产单据！", Toast.LENGTH_LONG).show()
                    }
                }
            }
            wifiServer?.start(portVal)
        } else {
            wifiServer?.stop()
            wifiServer = null
            _deviceIpAddress.value = null
        }
    }

    fun dismissBackgroundPdfMessage() {
        _backgroundPdfMessage.value = null
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val list = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val iFace = interfaces.nextElement()
                val name = iFace.name.lowercase()
                val addresses = iFace.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && ip != "127.0.0.1") {
                            list.add(name to ip)
                        }
                    }
                }
            }
            
            // 1. Prioritize wlan (WiFi client connections)
            val wlanIp = list.firstOrNull { it.first.contains("wlan") }?.second
            if (wlanIp != null) return wlanIp
            
            // 2. Prioritize softap / hotspot
            val apIp = list.firstOrNull { it.first.contains("ap") || it.first.contains("softap") }?.second
            if (apIp != null) return apIp
            
            // 3. Fallback to any found valid IPv4
            if (list.isNotEmpty()) return list[0].second
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    fun exportToZip(destinationUri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val tempZip = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.zip")
                val itemsList = stockItems.value
                val success = repository.createExportZip(context, itemsList, tempZip)
                
                if (success && tempZip.exists()) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                        tempZip.inputStream().use { input ->
                            input.copyTo(outStream)
                        }
                    }
                    tempZip.delete()
                    withContext(Dispatchers.Main) {
                        onResult(true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false)
                }
            } finally {
                _isExporting.value = false
            }
        }
    }

    /**
     * Simple manual build controller.
     */
    fun manualGeneratePdf(item: StockItem) {
        viewModelScope.launch {
            val freshPdf = repository.generatePdfForItem(context, item)
            withContext(Dispatchers.Main) {
                if (freshPdf != null && freshPdf.exists()) {
                    Toast.makeText(context, "PDF 合并生成成功！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "生成 PDF 失败（请先拍照）", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importSampleData() {
        var pid = _activeProjectId.value
        viewModelScope.launch(Dispatchers.IO) {
            if (pid.isEmpty()) {
                val defaultProj = Project(id = "default_project", name = "默认项目")
                repository.insertProject(defaultProj)
                pid = "default_project"
                withContext(Dispatchers.Main) {
                    _activeProjectId.value = "default_project"
                }
            }

            val headers = listOf("序号", "设备编号", "设备名称", "资产分类", "规格型号", "生产厂家", "计量单位", "数量", "存放位置", "购置日期", "启用日期", "账面原值", "账面净值", "是否盘点", "备注", "UUID")
            val headersJson = repository.toJsonList(headers)

            val proj = repository.getProjectById(pid)
            if (proj != null && proj.columnHeadersJson.isEmpty()) {
                repository.insertProject(proj.copy(
                    columnHeadersJson = headersJson
                ))
            }

            val samples = listOf(
                StockItem(
                    name = "大隈数控立式加工中心",
                    category = "机器设备类",
                    location = "二号机加工车间A2",
                    originalCode = "MC-OKUMA-08",
                    projectId = pid,
                    shouldCheck = true,
                    originalRowJson = repository.toJsonList(listOf("1", "MC-OKUMA-08", "大隈数控立式加工中心", "机器设备类", "GENOS M460V-5G", "大隈机械", "台", "1", "二号机加工车间A2", "2022-03", "2022-04", "850000.00", "520000.00", "是", "高精度加工", "")),
                    rowOrder = 1
                ),
                StockItem(
                    name = "戴尔超算物理刀片服务器",
                    category = "电子设备类",
                    location = "3号算力机房14架",
                    originalCode = "HPC-DELL-12",
                    projectId = pid,
                    shouldCheck = true,
                    originalRowJson = repository.toJsonList(listOf("2", "HPC-DELL-12", "戴尔超算物理刀片服务器", "电子设备类", "PowerEdge MX750c", "戴尔中国", "精", "1", "3号算力机房14架", "2024-01", "2024-02", "320000.00", "260000.00", "是", "核心科学计算", "")),
                    rowOrder = 2
                ),
                StockItem(
                    name = "研发总装中心主厂房",
                    category = "房屋建筑物类",
                    location = "园区西北角一号地",
                    originalCode = "BLDG-HQ-01",
                    projectId = pid,
                    shouldCheck = false,
                    originalRowJson = repository.toJsonList(listOf("3", "BLDG-HQ-01", "研发总装中心主厂房", "房屋建筑物类", "钢混框架架构(地上三层)", "中铁建设", "栋", "1", "园区西北角一号地", "2018-06", "2018-12", "45000000.00", "38000000.00", "否", "资产自用红线内", "")),
                    rowOrder = 3
                ),
                StockItem(
                    name = "特斯拉一秒干线物流重卡",
                    category = "运输设备类",
                    location = "园区物流调度室C区",
                    originalCode = "EV-SEMI-05",
                    projectId = pid,
                    shouldCheck = true,
                    originalRowJson = repository.toJsonList(listOf("4", "EV-SEMI-05", "特斯拉一秒干线物流重卡", "运输设备类", "Semi Type-Class 8", "特斯拉", "辆", "1", "园区物流调度室C区", "2023-08", "2023-09", "1200000.00", "980000.00", "是", "干线低碳干线运输", "")),
                    rowOrder = 4
                )
            )
            repository.insertAll(samples)
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAll()
            File(context.filesDir, "photos").deleteRecursively()
            File(context.filesDir, "pdfs").deleteRecursively()
        }
    }
}
