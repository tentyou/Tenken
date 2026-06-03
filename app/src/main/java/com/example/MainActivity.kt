package com.example

import android.Manifest
import android.net.Uri
import androidx.compose.ui.draw.scale
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.InventoryConstants
import com.example.data.InventoryTemplate
import com.example.data.StockItem
import com.example.ui.StockViewModel
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: StockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppContent(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: StockViewModel) {
    val activeItem by viewModel.activeItemForPhoto.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = activeItem,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "ScreenTransition"
    ) { currentActiveItem ->
        if (currentActiveItem != null) {
            CameraCaptureScreen(viewModel = viewModel, activeItem = currentActiveItem)
        } else {
            DashboardScreen(viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: StockViewModel) {
    val context = LocalContext.current
    val allProjects by viewModel.allProjects.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()
    val stockItems by viewModel.stockItems.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val isWatermarking by viewModel.isWatermarking.collectAsStateWithLifecycle()

    val currentProject = allProjects.find { it.id == activeProjectId }
    val currentProjectName = currentProject?.name ?: InventoryConstants.DEFAULT_PROJECT_NAME
    val currentTemplateHeadersJson by rememberUpdatedState(currentProject?.columnHeadersJson)

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showAddProjectDialog by remember { mutableStateOf(false) }
    var showDeleteProjectDialog by remember { mutableStateOf<com.example.data.Project?>(null) }
    var showRenameProjectDialog by remember { mutableStateOf<com.example.data.Project?>(null) }
    var showWatermarkConfirmDialog by remember { mutableStateOf<Boolean?>(null) }
    var showEditMetaDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showWatermarkSettingsPage by remember { mutableStateOf(false) }

    // Drawer state configuration
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // Tab Index: 0 = 待盘点设备 (Only shouldCheck == true), 1 = 台账管理 (All items with shouldCheck toggles)
    var activeTab by remember { mutableStateOf(0) }

    // Document Import Launcher. XLSX is the primary import format; CSV is kept for compatibility.
    val documentImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    // XLSX Template Export Launcher
    val xlsxTemplateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(InventoryTemplate.XLSX_MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(InventoryTemplate.createXlsxBytes(currentTemplateHeadersJson))
                }
                Toast.makeText(context, "盘点表模板已保存。", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "保存模板失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ZIP Export Launcher
    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportToZip(uri) { success ->
                if (success) {
                    Toast.makeText(context, "项目资料包已保存。", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "导出失败，请确认项目内已有资产记录及现场照片。", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Display background PDF compilation notification
    val backgroundMessage by viewModel.backgroundPdfMessage.collectAsStateWithLifecycle()
    if (backgroundMessage != null) {
        androidx.compose.runtime.LaunchedEffect(backgroundMessage) {
            Toast.makeText(context, backgroundMessage ?: "资产记录 PDF 已生成。", Toast.LENGTH_LONG).show()
            viewModel.dismissBackgroundPdfMessage()
        }
    }

    // Safe direct file import options dialog
    if (pendingImportUri != null) {
        var selectedImportProjId by remember(allProjects, activeProjectId) { mutableStateOf(activeProjectId) }
        var replaceMode by remember { mutableStateOf(false) } // false = 追加, true = 替换
        
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    Text("资产台账导入设置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("1. 选择导入目标项目", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        allProjects.forEach { proj ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedImportProjId == proj.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedImportProjId = proj.id }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    RadioButton(
                                        selected = (selectedImportProjId == proj.id),
                                        onClick = { selectedImportProjId = proj.id }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = proj.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selectedImportProjId == proj.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Text("2. 选择导入方式", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!replaceMode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { replaceMode = false }
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                RadioButton(selected = !replaceMode, onClick = { replaceMode = false })
                                Text("追加记录", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (replaceMode) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { replaceMode = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                RadioButton(selected = replaceMode, onClick = { replaceMode = true })
                                Text("替换现有台账", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (replaceMode) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "替换模式将删除当前项目已有资产记录及其关联照片、PDF 文件。执行前请确认已完成必要备份。",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri!!
                        viewModel.importFile(uri, selectedImportProjId, replaceMode) { success ->
                            if (success) {
                                Toast.makeText(context, "资产台账已导入。", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "导入失败，请核对模板表头及是否盘点列。", Toast.LENGTH_LONG).show()
                            }
                        }
                        pendingImportUri = null
                    }
                ) {
                    Text("执行映射导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Modal Drawer wrapper
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                        .padding(16.dp)
                ) {
                    // Drawer Brand Head
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "项目列表",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Wi-Fi Local File Transfer Portal embedded inside Drawer
                    val wifiEnabled by viewModel.wifiTransferEnabled.collectAsStateWithLifecycle()
                    val ipAddress by viewModel.deviceIpAddress.collectAsStateWithLifecycle()
                    val wifiPort by viewModel.wifiPort.collectAsStateWithLifecycle()

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (wifiEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = if (wifiEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                                        contentDescription = null,
                                        tint = if (wifiEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "局域网资料传输",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (wifiEnabled) "局域网传输已开启" else "电脑端访问地址导入",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Switch(
                                    checked = wifiEnabled,
                                    onCheckedChange = { viewModel.toggleWifiTransfer(it, wifiPort) },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                            
                            if (wifiEnabled && ipAddress != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "IP地址:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(6.dp)) {
                                        Text(
                                            text = "http://$ipAddress:$wifiPort",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                // Port input in Sidebar
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("传输端口:", fontSize = 11.sp, color = Color.Gray)
                                    var portInput by remember { mutableStateOf(wifiPort.toString()) }
                                    OutlinedTextField(
                                        value = portInput,
                                        onValueChange = { input ->
                                            val filtered = input.filter { it.isDigit() }
                                            portInput = filtered
                                            filtered.toIntOrNull()?.let { viewModel.updateWifiPort(it) }
                                        },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .width(90.dp),
                                        singleLine = true,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(
                                        onClick = { showWatermarkSettingsPage = true },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("类别前缀", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Project selection row headers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "分类项目组列表",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        IconButton(
                            onClick = { showAddProjectDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "新建项目分类",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Side list of all projects
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allProjects.forEach { proj ->
                            val isSelected = (proj.id == activeProjectId)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectProject(proj.id)
                                            coroutineScope.launch { drawerState.close() }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.FolderOpen else Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = proj.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    // Rename project option
                                    IconButton(
                                        onClick = { showRenameProjectDialog = proj },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "重命名项目分类",
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))

                                    // Deletion warning options
                                    IconButton(
                                        onClick = { showDeleteProjectDialog = proj },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "删除该分类项目",
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Divider()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "物理数据独立存储隔离机理",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                val wifiEnabled by viewModel.wifiTransferEnabled.collectAsStateWithLifecycle()
                val ipAddress by viewModel.deviceIpAddress.collectAsStateWithLifecycle()
                val wifiPort by viewModel.wifiPort.collectAsStateWithLifecycle()

                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "打开项目菜单"
                            )
                        }
                    },
                    title = {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = currentProjectName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .basicMarquee(iterations = Int.MAX_VALUE)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            // WiFi active dynamic indicator pill on topBar (stacked under title name to avoid changing bar width)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (wifiEnabled) Color(0xFFE8F5E9) else Color(0xFFECEFF1),
                                modifier = Modifier
                                    .clickable {
                                        coroutineScope.launch {
                                            val targetState = !wifiEnabled
                                            viewModel.toggleWifiTransfer(targetState, wifiPort)
                                            Toast.makeText(
                                                context,
                                                "WiFi已${if (targetState) "点击开启中" else "点击关闭"}，3秒后提示端口...",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            delay(3000)
                                            if (targetState) {
                                                val freshIp = viewModel.deviceIpAddress.value ?: "127.0.0.1"
                                                Toast.makeText(
                                                    context,
                                                    "局域网传输地址：http://${freshIp}:${wifiPort}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "局域网传输服务已关闭。",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(if (wifiEnabled) Color(0xFF4CAF50) else Color.Gray)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (wifiEnabled) "WiFi传输: 开 (${ipAddress ?: "获取中"}:${wifiPort})" else "WiFi传输: 关",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (wifiEnabled) Color(0xFF2E7D32) else Color.DarkGray
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.startTutorial() },
                            modifier = Modifier.testTag("help_tutorial_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = "新手指引",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.testTag("clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "清空数据",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val showTutorial by viewModel.showTutorial.collectAsStateWithLifecycle()
                val watermarkEnabled by viewModel.watermarkEnabled.collectAsStateWithLifecycle()

                if (allProjects.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "尚未创建任何评估项目",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "请先新建资产评估项目，或通过局域网端添加项目后导入资产台账。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { showAddProjectDialog = true },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("立即新建分类项目", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                    if (showTutorial) {
                        item {
                            TutorialGuideCard(
                                onLoadSample = { viewModel.importSampleData() },
                                onCloseTutorial = { viewModel.completeTutorial() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                     // Stats Category Card dynamically mapped
                    item {
                        StatsCategoryCard(
                            onImportClick = { documentImportLauncher.launch(arrayOf("*/*")) },
                            onTemplateClick = {
                                xlsxTemplateLauncher.launch("盘点表模板.xlsx")
                            }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Project Assessment Metadata Setup Card
                    item {
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Style,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "设置信息",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    val baseDateVal = currentProject?.baseDate ?: "未设定"
                                    val companyVal = currentProject?.companyName ?: "未设定"
                                    val rTypeVal = currentProject?.reportType ?: InventoryConstants.REPORT_TYPE_EVALUATION

                                    Text(
                                        text = "评估基准日：$baseDateVal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                    Text(
                                        text = "产权持有单位：$companyVal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "报告类型：$rTypeVal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }

                                Button(
                                    onClick = { showEditMetaDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .testTag("edit_meta_btn")
                                        .padding(start = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "修改",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("修改", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Camera Watermarks Configuration & Live Preview Card
                    item {
                        val watermarkEnabled by viewModel.watermarkEnabled.collectAsStateWithLifecycle()
                        val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()

                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                // NEW CARD STRUCTURE INTRODUCED HERE
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.PhotoCamera,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "图片水印",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    
                                    val isWatermarking by viewModel.isWatermarking.collectAsStateWithLifecycle()
                                    if (isWatermarking) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(10.dp),
                                                strokeWidth = 1.5.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("正在重构PDF...", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "为「${activeProject?.name ?: InventoryConstants.DEFAULT_PROJECT_NAME}」启用自动水印",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "启用后在照片 PDF 中记录资产分类编号及现场核验信息；关闭后生成不含水印信息的 PDF。",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Switch(
                                        checked = watermarkEnabled,
                                        onCheckedChange = { targetState ->
                                            showWatermarkConfirmDialog = targetState
                                        },
                                        modifier = Modifier.testTag("watermark_switch").scale(0.85f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val subCountText = remember(watermarkEnabled) {
                                            if (watermarkEnabled) "右上角流水 + 左下角物理元数据" else "水印功能已关闭"
                                        }
                                        Text(
                                            text = subCountText,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Button(
                                        onClick = { showWatermarkSettingsPage = true },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("detailed_watermark_settings_button")
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("详细设置", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }


                    val mainCheckList = stockItems.filter { it.shouldCheck }
                    val checkedCount = mainCheckList.size
                    val totalCount = stockItems.size

                    // Tab switching rows: 0 = 待盘点设备(filtered shouldCheck == true), 1 = 全量台账(show checkboxes)
                    item {
                        TabRow(
                            selectedTabIndex = activeTab,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            containerColor = Color.Transparent,
                            divider = {}
                        ) {
                            Tab(
                                selected = (activeTab == 0),
                                onClick = { activeTab = 0 }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("待盘点清单", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            Tab(
                                selected = (activeTab == 1),
                                onClick = { activeTab = 1 }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ListAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("台账预览 (${checkedCount}项/${totalCount}项)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (activeTab == 1) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "台账预览 (${checkedCount}项/${totalCount}项)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "勾选即纳入盘点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (stockItems.isEmpty()) {
                            item {
                                EmptyStateView(
                                    onImportClick = { documentImportLauncher.launch(arrayOf("*/*")) },
                                    onSampleClick = { viewModel.importSampleData() }
                                )
                            }
                        } else {
                            items(stockItems, key = { it.uid }) { item ->
                                val seq = try {
                                    if (item.originalRowJson.isNotEmpty()) {
                                        val m = Regex("\"([^\"]*)\"").find(item.originalRowJson)
                                        m?.groupValues?.get(1) ?: "1"
                                    } else "1"
                                } catch (e: Exception) { "1" }
                                val formattedName = "${item.name}（${item.category}-$seq）"

                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = item.shouldCheck,
                                            onCheckedChange = { isChecked ->
                                                viewModel.updateItem(item.copy(shouldCheck = isChecked))
                                            },
                                            modifier = Modifier.testTag("checkbox_${item.uid}")
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = formattedName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            CollapsibleMetadataSection(item = item, modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "需要盘点的资产",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "共计 ${checkedCount} 项需盘点设备/台账共 ${totalCount} 项",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (mainCheckList.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.AssignmentLate, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "当前项目暂无待盘点资产",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Gray
                                        )
                                        Text(
                                            text = "可在「全部台账管理」中勾选需盘点资产，或导入资产台账。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.LightGray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            items(mainCheckList, key = { it.uid }) { item ->
                                StockItemRow(
                                    item = item,
                                    onCameraClick = { viewModel.startPhotoCapture(item) },
                                    onPdfClick = { viewModel.manualGeneratePdf(item) },
                                    onDeletePdfClick = { viewModel.deleteItemPdf(item) }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }

                // Export Actions Sticky Bar (Floating Bottom Drawer Style)
                val activeListForExport = stockItems.filter { it.shouldCheck }
                if (activeListForExport.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        tonalElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val isBaseDateEmpty = currentProject?.baseDate?.trim()?.isEmpty() ?: true
                                    val isCompanyNameEmpty = currentProject?.companyName?.trim()?.isEmpty() ?: true
                                    if (isBaseDateEmpty || isCompanyNameEmpty) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "导出失败：请先在“设置信息”中填写评估基准日和产权持有单位。",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault())
                                        val timestampStr = sdf.format(java.util.Date())
                                        val proposedZipName = "${currentProjectName}-盘点表-${timestampStr}.zip"
                                        zipExportLauncher.launch(proposedZipName)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("export_zip_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderZip,
                                    contentDescription = "ZIP",
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = "导出项目文件",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
                }

                // Global Loading Indicator for Streams
                if (isImporting || isExporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable(enabled = false) {},
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (isImporting) "正在读取资产台账并写入本地数据库..." else "正在生成资产记录 PDF 并整理项目资料包...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(220.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Quick purge validation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(text = "清空全部数据？") },
            text = { Text(text = "系统将删除全部资产台账记录，并清理本地照片及已生成的 PDF 文件。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = "确认清空", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    // Project Assessment Metadata Setup Dialog
    if (showEditMetaDialog) {
        var baseDateState by remember { mutableStateOf(currentProject?.baseDate ?: "") }
        var companyNameState by remember { mutableStateOf(currentProject?.companyName ?: "") }
        var selectedReportTypeOption by remember {
            mutableStateOf(
                when (currentProject?.reportType) {
                    InventoryConstants.REPORT_TYPE_EVALUATION, InventoryConstants.REPORT_TYPE_CONSULTING -> currentProject.reportType
                    null, "" -> InventoryConstants.REPORT_TYPE_EVALUATION
                    else -> "自定义"
                }
            )
        }
        var customReportTypeState by remember {
            mutableStateOf(
                if (currentProject?.reportType == InventoryConstants.REPORT_TYPE_EVALUATION || currentProject?.reportType == InventoryConstants.REPORT_TYPE_CONSULTING || currentProject?.reportType.isNullOrEmpty()) ""
                else currentProject?.reportType ?: ""
            )
        }

        val context = LocalContext.current
        val calendar = remember(baseDateState) {
            java.util.Calendar.getInstance().apply {
                try {
                    if (baseDateState.isNotEmpty()) {
                        val sdf = java.text.SimpleDateFormat("yyyy年MM月dd日", java.util.Locale.CHINA)
                        val date = sdf.parse(baseDateState)
                        if (date != null) {
                            time = date
                        }
                    }
                } catch (e: Exception) {
                    // Ignore, use current date
                }
            }
        }

        val datePickerDialog = remember(context, baseDateState) {
            android.app.DatePickerDialog(
                context,
                { _, selectedYear, selectedMonth, selectedDay ->
                    val formattedDate = String.format(
                        java.util.Locale.CHINA,
                        "%d年%02d月%02d日",
                        selectedYear,
                        selectedMonth + 1,
                        selectedDay
                    )
                    baseDateState = formattedDate
                },
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }

        AlertDialog(
            onDismissRequest = { showEditMetaDialog = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("设定设置信息", fontWeight = FontWeight.Bold) 
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        OutlinedTextField(
                            value = baseDateState,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("评估基准日", fontSize = 12.sp) },
                            placeholder = { Text("点击选择日期", color = Color.Gray, fontSize = 12.sp) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = "选择日期",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dialog_input_base_date"),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp)
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { datePickerDialog.show() }
                        )
                    }

                    OutlinedTextField(
                        value = companyNameState,
                        onValueChange = { companyNameState = it },
                        label = { Text("被评估/产权持有单位名称", fontSize = 12.sp) },
                        placeholder = { Text("如：华东科技集团有限公司", color = Color.Gray, fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("dialog_input_company_name"),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "报告分类选项",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Column {
                        listOf(InventoryConstants.REPORT_TYPE_EVALUATION, InventoryConstants.REPORT_TYPE_CONSULTING, "自定义").forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { selectedReportTypeOption = option }
                                    .padding(vertical = 4.dp)
                                    .fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = (selectedReportTypeOption == option),
                                    onClick = { selectedReportTypeOption = option },
                                    modifier = Modifier.testTag("dialog_radio_$option")
                                )
                                Text(
                                    text = option,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (selectedReportTypeOption == "自定义") {
                        OutlinedTextField(
                            value = customReportTypeState,
                            onValueChange = { customReportTypeState = it },
                            label = { Text("输入自定义分类名称", fontSize = 11.sp) },
                            placeholder = { Text("如：资产核查报告", color = Color.Gray, fontSize = 11.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .testTag("dialog_input_custom_report_type"),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalReportType = if (selectedReportTypeOption == "自定义") {
                            customReportTypeState.trim().ifEmpty { "自定义报告" }
                        } else {
                            selectedReportTypeOption
                        }
                        viewModel.updateProjectMeta(activeProjectId, baseDateState.trim(), companyNameState.trim(), finalReportType)
                        showEditMetaDialog = false
                    },
                    modifier = Modifier.testTag("dialog_save_meta_btn")
                ) {
                    Text("保存设定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditMetaDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Project creation Dialog
    if (showAddProjectDialog) {
        var baseDateState by remember { mutableStateOf("") }
        var companyNameState by remember { mutableStateOf("") }
        var selectedReportTypeOption by remember { mutableStateOf(InventoryConstants.REPORT_TYPE_EVALUATION) }
        var customReportTypeState by remember { mutableStateOf("") }

        val context = LocalContext.current
        val calendar = remember { java.util.Calendar.getInstance() }

        val datePickerDialog = remember(context) {
            android.app.DatePickerDialog(
                context,
                { _, selectedYear, selectedMonth, selectedDay ->
                    val formattedDate = String.format(
                        java.util.Locale.CHINA,
                        "%d年%02d月%02d日",
                        selectedYear,
                        selectedMonth + 1,
                        selectedDay
                    )
                    baseDateState = formattedDate
                },
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }

        AlertDialog(
            onDismissRequest = { showAddProjectDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("创建评估项目", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        OutlinedTextField(
                            value = baseDateState,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("评估基准日", fontSize = 12.sp) },
                            placeholder = { Text("点击选择日期", color = Color.Gray, fontSize = 12.sp) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = "选择日期",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp)
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { datePickerDialog.show() }
                        )
                    }

                    OutlinedTextField(
                        value = companyNameState,
                        onValueChange = { companyNameState = it },
                        label = { Text("被评估/产权持有单位名称", fontSize = 12.sp) },
                        placeholder = { Text("如：华东科技集团有限公司", color = Color.Gray, fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "报告分类选项",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Column {
                        listOf(InventoryConstants.REPORT_TYPE_EVALUATION, InventoryConstants.REPORT_TYPE_CONSULTING, "自定义").forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { selectedReportTypeOption = option }
                                    .padding(vertical = 4.dp)
                                    .fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = (selectedReportTypeOption == option),
                                    onClick = { selectedReportTypeOption = option }
                                )
                                Text(
                                    text = option,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (selectedReportTypeOption == "自定义") {
                        OutlinedTextField(
                            value = customReportTypeState,
                            onValueChange = { customReportTypeState = it },
                            label = { Text("输入自定义分类名称", fontSize = 11.sp) },
                            placeholder = { Text("如：资产核查报告", color = Color.Gray, fontSize = 11.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp)
                        )
                    }

                    // Show live generated default project name
                    val digits = baseDateState.filter { it.isDigit() }
                    val defaultName = if (companyNameState.trim().isNotEmpty() && digits.isNotEmpty()) {
                        "${companyNameState.trim()}-$digits"
                    } else {
                        "（信息补齐后自动生成项目名称）"
                    }
                    Text(
                        text = "预拟项目名称: $defaultName",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val digits = baseDateState.filter { it.isDigit() }
                        if (baseDateState.isEmpty() || companyNameState.trim().isEmpty() || (selectedReportTypeOption == "自定义" && customReportTypeState.trim().isEmpty())) {
                            Toast.makeText(context, "请填写项目必填信息后再创建。", Toast.LENGTH_LONG).show()
                        } else {
                            val finalReportType = if (selectedReportTypeOption == "自定义") {
                                customReportTypeState.trim()
                            } else {
                                selectedReportTypeOption
                            }
                            val autoProjectName = "${companyNameState.trim()}-$digits"
                            viewModel.addProject(
                                name = autoProjectName,
                                baseDate = baseDateState.trim(),
                                companyName = companyNameState.trim(),
                                reportType = finalReportType
                            )
                            showAddProjectDialog = false
                        }
                    }
                ) {
                    Text("创建项目")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProjectDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Watermark confirmation Dialog
    if (showWatermarkConfirmDialog != null) {
        val targetState = showWatermarkConfirmDialog!!
        AlertDialog(
            onDismissRequest = { showWatermarkConfirmDialog = null },
            title = { 
                Text(
                    text = if (targetState) "确认要开启照片水印吗？" else "确认要关闭照片水印吗？",
                    fontWeight = FontWeight.Bold
                ) 
            },
            text = {
                Text(
                    text = if (targetState) {
                        "开启后，系统将为已生成的 PDF 照片补充资产分类编号及现场核验信息，并重新生成相关 PDF。"
                    } else {
                        "关闭后，系统将重新生成相关 PDF，并移除照片中的水印标识。"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWatermarkConfirmDialog = null
                        viewModel.setWatermarkEnabled(targetState) { count ->
                            val msg = if (targetState) {
                                "已为 $count 个 PDF 文件更新照片水印。"
                            } else {
                                "已从 $count 个 PDF 文件中移除照片水印。"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.testTag("watermark_confirm_btn")
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWatermarkConfirmDialog = null },
                    modifier = Modifier.testTag("watermark_cancel_btn")
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Watermarking Progress loader overlay
    if (isWatermarking) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {} // Not dismissable
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "正在更新照片水印...",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在重新生成资产记录，请勿关闭应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Project renaming Dialog
    if (showRenameProjectDialog != null) {
        val proj = showRenameProjectDialog!!
        var editProjectName by remember(proj.id) { mutableStateOf(proj.name) }
        AlertDialog(
            onDismissRequest = { showRenameProjectDialog = null },
            title = { Text("重命名分类项目", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editProjectName,
                    onValueChange = { editProjectName = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_project_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editProjectName.isNotBlank()) {
                            viewModel.renameProject(proj.id, editProjectName.trim())
                            showRenameProjectDialog = null
                            Toast.makeText(context, "项目已被重命名为: ${editProjectName.trim()}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("rename_project_confirm")
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameProjectDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Project deleting warnings dialog
    if (showDeleteProjectDialog != null) {
        val projToDelete = showDeleteProjectDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteProjectDialog = null },
            modifier = Modifier.testTag("delete_project_dialog"),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
                    Text("删除项目确认", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            },
            text = {
                Text(
                    text = "确认删除项目「${projToDelete.name}」？\n\n系统将删除该项目的资产台账记录、现场照片及已生成的 PDF 文件。其他项目不受影响。此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProject(projToDelete) {
                            Toast.makeText(context, "项目「${projToDelete.name}」已删除。", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteProjectDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProjectDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showWatermarkSettingsPage) {
        WatermarkSettingsPage(
            viewModel = viewModel,
            onBackClick = { showWatermarkSettingsPage = false }
        )
    }
}

@Composable
fun TutorialGuideCard(
    onLoadSample: () -> Unit,
    onCloseTutorial: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("tutorial_guide_card"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "资产盘点流程指引",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(
                    onClick = onCloseTutorial,
                    modifier = Modifier.size(28.dp).testTag("close_tutorial_icon_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭引导说明",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = "请按以下流程完成资产台账导入、现场拍照、记录生成与资料导出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("1. ", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Text("点击下方按钮「加载示范数据」", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("或通过右上角导入您自己的台账 Excel 清单文件。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text("2. ", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Text("在资产记录中进入拍照", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("采集资产现场照片，必要时记录条码或二维码信息。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text("3. ", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Text("生成资产记录 PDF", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("系统根据现场照片生成资产记录 PDF，并可按资产分类配置编号前缀。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text("4. ", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Text("通过局域网导入导出资料", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("开启局域网传输后，可在电脑端浏览器上传资产台账或下载项目资料包。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onLoadSample,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .testTag("load_sample_tutorial_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("加载示范数据", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onCloseTutorial,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("close_tutorial_button")
                ) {
                    Text("我知道了", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun StatsCategoryCard(
    onImportClick: () -> Unit,
    onTemplateClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onImportClick,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("import_xlsx_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(imageVector = Icons.Default.Attachment, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("导入盘点表", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onTemplateClick,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("download_template_button"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("盘点表模板", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StockItemRow(
    item: StockItem,
    onCameraClick: () -> Unit,
    onPdfClick: () -> Unit,
    onDeletePdfClick: () -> Unit
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stock_item_${item.uid}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (item.photoCount > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (item.photoCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Dynamic decorative icon indicating type
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (item.photoCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.photoCount > 0) Icons.Default.CameraAlt else Icons.Default.Inventory2,
                        contentDescription = null,
                        tint = if (item.photoCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Asset texts
                val seq = try {
                    if (item.originalRowJson.isNotEmpty()) {
                        val m = Regex("\"([^\"]*)\"").find(item.originalRowJson)
                        m?.groupValues?.get(1) ?: "1"
                    } else "1"
                } catch (e: Exception) { "1" }
                val formattedName = "${item.name}（${item.category}-$seq）"

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formattedName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Action panel right aligned
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick stats pill
                    if (item.photoCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE8F5E9),
                            contentColor = Color(0xFF2E7D32)
                        ) {
                            Text(
                                text = "已拍${item.photoCount}张",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Shutter button on the list right side
                    Button(
                        onClick = onCameraClick,
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("snap_button_${item.uid}"),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (item.photoCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "拍照",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (item.photoCount > 0) "续拍" else "拍照",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            // Sub-row containing details like Category and UUID, and the PDF generation triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "详细信息",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // PDF compiling triggers
                if (item.photoCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = onPdfClick,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (item.pdfStatus == InventoryConstants.PDF_STATUS_GENERATED) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("generate_pdf_${item.uid}"),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = if (item.pdfStatus == InventoryConstants.PDF_STATUS_GENERATED) Icons.Default.PictureAsPdf else Icons.Default.Refresh,
                                contentDescription = "PDF",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (item.pdfStatus == InventoryConstants.PDF_STATUS_GENERATED) "合并PDF已绪" else "归并生成PDF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (item.pdfStatus == InventoryConstants.PDF_STATUS_GENERATED) {
                            var showPdfPreview by remember { mutableStateOf(false) }

                            TextButton(
                                onClick = { showPdfPreview = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.secondary
                                ),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("preview_pdf_${item.uid}"),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "预览PDF",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "预览",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (showPdfPreview) {
                                val pdfFile = File(context.filesDir, "pdfs/${item.uid}/照片.pdf")
                                if (pdfFile.exists()) {
                                    PdfPreviewDialog(
                                        file = pdfFile,
                                        onDismiss = { showPdfPreview = false }
                                    )
                                } else {
                                    Toast.makeText(context, "未找到生成的PDF预览文件", Toast.LENGTH_SHORT).show()
                                    showPdfPreview = false
                                }
                            }
                        }
                        
                        IconButton(
                            onClick = onDeletePdfClick,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("delete_pdf_${item.uid}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "清除该项全部照片及PDF",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "暂无照片文件",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    )
                }
            }

            if (isExpanded) {
                val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val itemsToDisplay = listOf(
                        Pair("设备编号", item.originalCode),
                        Pair("资产分类", item.category),
                        Pair("存放位置", item.location),
                        Pair("设备 UID", item.uid)
                    )

                    itemsToDisplay.forEach { (label, value) ->
                        val displayValue = value.ifEmpty { "无" }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(displayValue) {
                                    detectTapGestures(
                                        onLongPress = {
                                            if (value.isNotEmpty()) {
                                                val clip = android.content.ClipData.newPlainText("metadata", value)
                                                clipboardManager.setPrimaryClip(clip)
                                                android.widget.Toast.makeText(context, "已复制元数据: $value", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$label: ",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = displayValue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(
    onImportClick: () -> Unit,
    onSampleClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无盘点台账资产数据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请导入资产台账表格后开展现场盘点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onImportClick,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("从手机存储导入台账清单", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CameraCaptureScreen(viewModel: StockViewModel, activeItem: StockItem) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasCameraPermission = perms[Manifest.permission.CAMERA] ?: hasCameraPermission
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (hasCameraPermission) {
        CameraPreviewWidget(viewModel = viewModel, activeItem = activeItem)
    } else {
        CameraPermissionDeniedWidget(
            onRequestClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onBackClick = { viewModel.endPhotoCapture {} }
        )
    }
}

@Composable
fun CameraPermissionDeniedWidget(onRequestClick: () -> Unit, onBackClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "未获得相机授权",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "现场照片采集需要使用手机相机权限。应用仅在拍照流程中调用相机。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("申请开启相机权限", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onBackClick) {
                Text("取消并返回盘点列表", color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun CameraPreviewWidget(viewModel: StockViewModel, activeItem: StockItem) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Load active item session photo files
    val sessionPhotos by viewModel.activeSessionPhotos.collectAsStateWithLifecycle()
    var selectedImageForFilter by remember { mutableStateOf<File?>(null) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var showFlashOverlay by remember { mutableStateOf(false) }

    // Shutter animation state
    val flashAlpha by animateFloatAsState(
        targetValue = if (showFlashOverlay) 0.8f else 0.0f,
        animationSpec = tween(durationMillis = 80),
        label = "ShutterFlashAlpha"
    )

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // Coroutine scope
    val scope = rememberCoroutineScope()

    // Key effect to rebind camera on Lens toggles
    LaunchedEffect(lensFacing) {
        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Fullscreen dynamic camera viewfinder
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize(),
            update = { /* Update preview overlay if dynamic state fluctuates */ }
        )

        // Simulated shutter mechanical flash overlay
        if (flashAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha))
            )
        }

        // Camera Header Bar Overlay
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = {
                    viewModel.endPhotoCapture {}
                },
                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = activeItem.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                Text(
                    text = "连续拍照关联模式",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "反转镜头",
                    tint = Color.White
                )
            }
        }

        // Camera Footer Controls Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(vertical = 16.dp)
        ) {
            // Part A: LazyRow thumbnail gallery of currently snapped session photos
            AnimatedVisibility(
                visible = sessionPhotos.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "本次拍摄记录 (已拍摄 ${sessionPhotos.size} 张照片)",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.Red.copy(alpha = 0.8f),
                            modifier = Modifier.clickable {
                                // Delete current captures
                                File(context.filesDir, "photos/${activeItem.uid}").deleteRecursively()
                                viewModel.refreshActiveSessionPhotos(activeItem.uid)
                            }
                        ) {
                            Text(
                                text = "重拍",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(sessionPhotos) { imageFile ->
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedImageForFilter = imageFile
                                    }
                            ) {
                                AsyncImage(
                                    model = imageFile,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    if (selectedImageForFilter != null) {
                        DocumentEnhancingDialog(
                            file = selectedImageForFilter!!,
                            viewModel = viewModel,
                            onFilterSelected = { filter ->
                                viewModel.applyFilterToPhoto(selectedImageForFilter!!, filter)
                            },
                            onApplyCrop = { top, bottom, left, right ->
                                viewModel.applyCropToPhoto(selectedImageForFilter!!, top, bottom, left, right)
                                selectedImageForFilter = null
                            },
                            onDeletePhoto = {
                                viewModel.deletePhoto(selectedImageForFilter!!)
                                selectedImageForFilter = null
                            },
                            onDismiss = { selectedImageForFilter = null }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (sessionPhotos.isEmpty()) {
                        "提示：可连续采集多张现场照片，完成后生成资产记录 PDF。"
                    } else {
                        "已拍摄 ${sessionPhotos.size} 张多角度物理卡片"
                    },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.simulateCapture(activeItem)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .testTag("simulate_capture_button")
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn, 
                        contentDescription = null, 
                        modifier = Modifier.size(14.dp),
                        tint = Color.Yellow
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "生成模拟现场照片记录", 
                        fontSize = 11.sp, 
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Part B: Large physical-shutter buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Secondary Dismiss buttons
                TextButton(
                    onClick = { viewModel.endPhotoCapture {} }
                ) {
                    Text("取消", color = Color.White, fontSize = 16.sp)
                }

                // Shutter Outer Target Frame
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .clickable {
                            val imgCapture = imageCapture
                            if (imgCapture == null) {
                                Toast
                                    .makeText(context, "相机正在初始化，请稍候。", Toast.LENGTH_SHORT)
                                    .show()
                                return@clickable
                            }

                            // Build unique photo path under filesDir/photos/{item.uid}/
                            val targetFile = File(
                                context.filesDir,
                                "photos/${activeItem.uid}/photo_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}.jpg"
                            )
                            targetFile.parentFile?.mkdirs()

                            val outputOptions = ImageCapture.OutputFileOptions
                                .Builder(targetFile)
                                .build()
                            val executor = ContextCompat.getMainExecutor(context)

                            // Quick shutter flash
                            showFlashOverlay = true
                            scope.launch {
                                delay(100)
                                showFlashOverlay = false
                            }

                            imgCapture.takePicture(
                                outputOptions,
                                executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            com.example.util.PhotoMetadataUtils.writePhysicalMetadata(context, targetFile, activeItem)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                viewModel.refreshActiveSessionPhotos(activeItem.uid)
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        exception.printStackTrace()
                                        Toast
                                            .makeText(
                                                context,
                                                "照片采集失败，请检查相机权限或设备状态：${exception.message}",
                                                Toast.LENGTH_LONG
                                            )
                                            .show()
                                    }
                                }
                            )
                        }
                        .testTag("camera_shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                // Finish and Close Shutter button
                Button(
                    onClick = {
                        viewModel.endPhotoCapture {
                            Toast.makeText(context, "正在生成资产记录 PDF，完成后将提示。", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sessionPhotos.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.DarkGray
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("camera_done_button")
                ) {
                    Text("完成", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentEnhancingDialog(
    file: File,
    viewModel: com.example.ui.StockViewModel,
    onFilterSelected: (String) -> Unit,
    onApplyCrop: (Float, Float, Float, Float) -> Unit,
    onDeletePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    val watermarkEnabled by viewModel.watermarkEnabled.collectAsStateWithLifecycle()
    val watermarkTrEnabled by viewModel.watermarkTrEnabled.collectAsStateWithLifecycle()
    val blEnabled by viewModel.watermarkBlEnabled.collectAsStateWithLifecycle()
    val showDate by viewModel.watermarkBlShowDate.collectAsStateWithLifecycle()
    val showTime by viewModel.watermarkBlShowTime.collectAsStateWithLifecycle()
    val showGps by viewModel.watermarkBlShowGps.collectAsStateWithLifecycle()
    val showAddress by viewModel.watermarkBlShowAddress.collectAsStateWithLifecycle()
    
    val activeItem by viewModel.activeItemForPhoto.collectAsStateWithLifecycle()
    val stockItems by viewModel.stockItems.collectAsStateWithLifecycle()
    
    val watermarkText = remember(activeItem, stockItems) {
        val item = activeItem
        if (item != null) {
            val prefix = viewModel.getCategoryPrefix(item.category)
            val filtered = stockItems.filter { it.category == item.category }
            val sorted = filtered.sortedBy { it.originalCode.ifEmpty { it.uid } }
            val idx = sorted.indexOfFirst { it.uid == item.uid }
            val seq = String.format(java.util.Locale.CHINA, "%04d", if (idx != -1) idx + 1 else 1)
            "$prefix-$seq"
        } else {
            "C-0001"
        }
    }

    // Read actual physical metadata of the photographed file!
    val photoMeta = remember(file) { com.example.util.PhotoMetadataUtils.readPhysicalMetadata(file) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "实地勘测存证照片预览",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Photo container with real-time watermark overlay!
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                ) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Top Right Serial Overlay
                    if (watermarkEnabled && watermarkTrEnabled) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Red, RoundedCornerShape(2.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = watermarkText,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Bottom-Left dynamic watermarking overlay on the ACTUAL photo preview!
                    if (blEnabled) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(6.dp)
                        ) {
                            if (showDate) {
                                Text(
                                    text = "拍摄日期：${photoMeta.dateStr}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f))
                                )
                            }
                            if (showTime) {
                                Text(
                                    text = "时间：${photoMeta.timeStr}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f))
                                )
                            }
                            if (showGps) {
                                Text(
                                    text = String.format(java.util.Locale.CHINA, "经度：%.2f  纬度：%.2f", photoMeta.longitude, photoMeta.latitude),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f))
                                )
                            }
                            if (showAddress) {
                                Text(
                                    text = "位置：${photoMeta.address}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f)),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Watermark settings togglers directly inside preview screen for rich user interactivity!
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "现场核验水印",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Switch(
                                checked = blEnabled,
                                onCheckedChange = { viewModel.updateWatermarkBlSettings(enabled = it) },
                                modifier = Modifier.testTag("dialog_bl_switch").scale(0.7f)
                            )
                        }

                        if (blEnabled) {
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showDate,
                                        onCheckedChange = { viewModel.updateWatermarkBlSettings(showDate = it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                    Text("显示日期", fontSize = 11.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showTime,
                                        onCheckedChange = { viewModel.updateWatermarkBlSettings(showTime = it) },
                                        modifier = Modifier.scale(0.8f)
                                        )
                                    Text("显示时间", fontSize = 11.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showGps,
                                        onCheckedChange = { viewModel.updateWatermarkBlSettings(showGps = it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                    Text("显示经纬", fontSize = 11.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showAddress,
                                        onCheckedChange = { viewModel.updateWatermarkBlSettings(showAddress = it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                    Text("显示位置", fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp), RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "物理元数据 (直接源于照片硬件写入)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(String.format(java.util.Locale.CHINA, "经度：%.2f", photoMeta.longitude), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(String.format(java.util.Locale.CHINA, "纬度：%.2f", photoMeta.latitude), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("参考位置：${photoMeta.address}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDeletePhoto,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除照片")
                }
                
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

// ============================================
// PROJECT-SPECIFIC WATERMARK CONFIGURATION SURFACE
// ============================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkSettingsPage(
    viewModel: com.example.ui.StockViewModel,
    onBackClick: () -> Unit
) {
    val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
    val watermarkEnabled by viewModel.watermarkEnabled.collectAsStateWithLifecycle()
    val watermarkTrEnabled by viewModel.watermarkTrEnabled.collectAsStateWithLifecycle()
    val blEnabled by viewModel.watermarkBlEnabled.collectAsStateWithLifecycle()
    val showDate by viewModel.watermarkBlShowDate.collectAsStateWithLifecycle()
    val showTime by viewModel.watermarkBlShowTime.collectAsStateWithLifecycle()
    val showGps by viewModel.watermarkBlShowGps.collectAsStateWithLifecycle()
    val showAddress by viewModel.watermarkBlShowAddress.collectAsStateWithLifecycle()
    val blAddress by viewModel.watermarkBlAddress.collectAsStateWithLifecycle()
    val blLat by viewModel.watermarkBlLat.collectAsStateWithLifecycle()
    val blLng by viewModel.watermarkBlLng.collectAsStateWithLifecycle()
    val isWatermarking by viewModel.isWatermarking.collectAsStateWithLifecycle()
    val stockItems by viewModel.stockItems.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "专属水印相机参数配置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "当前项目: ${activeProject?.name ?: InventoryConstants.DEFAULT_PROJECT_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回主页"
                        )
                    }
                },
                actions = {
                    if (isWatermarking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = {
                            // Force-trigger refresh/validation of PDF cache on this project
                            viewModel.setWatermarkEnabled(watermarkEnabled)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重构PDF缓存",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(10.dp))
                
                // Card 1: Main Toggle
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用本项专属自动水印",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "关闭后生成不含水印信息的 PDF；开启后记录现场核验信息。",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Switch(
                                checked = watermarkEnabled,
                                onCheckedChange = { viewModel.setWatermarkEnabled(it) },
                                modifier = Modifier.testTag("full_page_watermark_main_switch").scale(0.9f)
                            )
                        }
                    }
                }
            }

            if (watermarkEnabled) {
                // Widget 2: Top-right sequence watermarks
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "右上角分类流水号标签",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "附加红色独立实勘流水标签",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "根据台账前缀与设备序列自动生成类似于 [C4-6-4-0001] 的红色序列贴纸",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = watermarkTrEnabled,
                                    onCheckedChange = { viewModel.updateWatermarkTrSetting(it) },
                                    modifier = Modifier.testTag("tr_setting_switch").scale(0.85f)
                                )
                            }

                            if (watermarkTrEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "资产分类编号前缀对照表（可编辑）",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                val categories = stockItems.map { it.category }.distinct().filter { it.isNotEmpty() }
                                if (categories.isEmpty()) {
                                    Text(
                                        text = "当前清单中暂无资产分类。请先导入资产台账。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else {
                                    categories.forEach { cat ->
                                        var prefixValue by remember { mutableStateOf(viewModel.getCategoryPrefix(cat)) }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = cat,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = prefixValue,
                                                onValueChange = { newVal ->
                                                    prefixValue = newVal
                                                    viewModel.saveCategoryPrefix(cat, newVal)
                                                },
                                                placeholder = { Text("例如 C-1-1", fontSize = 11.sp) },
                                                singleLine = true,
                                                textStyle = TextStyle(fontSize = 12.sp),
                                                modifier = Modifier
                                                    .width(130.dp)
                                                    .height(46.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Widget 3: Bottom-left coordinates and references watermarks
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "左下角相机实地定位水印",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "附加硬件环境实拍参考水印",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "展示高精度时间刻度戳与经纬度参考背板",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = blEnabled,
                                    onCheckedChange = { viewModel.updateWatermarkBlSettings(enabled = it) },
                                    modifier = Modifier.testTag("bl_setting_switch").scale(0.85f)
                                )
                            }

                            if (blEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "选择要显示的水印字段内容:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Perfect Grid alignment using equal weights
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = showDate,
                                            onCheckedChange = { viewModel.updateWatermarkBlSettings(showDate = it) },
                                            modifier = Modifier.testTag("chk_detail_date").scale(0.85f)
                                        )
                                        Text("拍摄日期", fontSize = 12.sp)
                                    }
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = showTime,
                                            onCheckedChange = { viewModel.updateWatermarkBlSettings(showTime = it) },
                                            modifier = Modifier.testTag("chk_detail_time").scale(0.85f)
                                        )
                                        Text("拍摄时刻", fontSize = 12.sp)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = showGps,
                                            onCheckedChange = { viewModel.updateWatermarkBlSettings(showGps = it) },
                                            modifier = Modifier.testTag("chk_detail_gps").scale(0.85f)
                                        )
                                        Text("传感器GPS经纬度", fontSize = 12.sp)
                                    }
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = showAddress,
                                            onCheckedChange = { viewModel.updateWatermarkBlSettings(showAddress = it) },
                                            modifier = Modifier.testTag("chk_detail_address").scale(0.85f)
                                        )
                                        Text("物理存放参考位置说明", fontSize = 12.sp)
                                    }
                                }

                                // Informational Shield Box
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "物理数据自动采集保障声明",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "系统可根据设备定位信息和照片 EXIF 元数据记录拍摄日期、时间、经纬度及参考位置，用于辅助现场核验记录。",
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Widget 4: Dynamic preview mockup render
                item {
                    Text(
                        text = "水印效果预览",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(
                                color = Color(0xFF1E293B),
                                size = size
                            )
                            val gridColor = Color(0xFF334155)
                            val spacingValue = 30f
                            var offsetValue = 0f
                            while (offsetValue < size.width + size.height) {
                                drawLine(
                                    color = gridColor,
                                    start = androidx.compose.ui.geometry.Offset(offsetValue, 0f),
                                    end = androidx.compose.ui.geometry.Offset(offsetValue - size.height, size.height),
                                    strokeWidth = 2f
                                )
                                offsetValue += spacingValue
                            }
                        }

                        // Top-Right Sticker Preview
                        if (watermarkTrEnabled) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Red, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                val sampleCategory = stockItems.firstOrNull()?.category ?: "默认分类"
                                val prefix = viewModel.getCategoryPrefix(sampleCategory).ifEmpty { "C-1" }
                                Text(
                                    text = "$prefix-0001",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Bottom-Left Canvas Overlay
                        if (blEnabled) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(10.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                    .padding(6.dp)
                            ) {
                                if (showDate) {
                                    Text(
                                        text = "拍摄日期：2026年06月01日",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f))
                                    )
                                }
                                if (showTime) {
                                    Text(
                                        text = "时间：14:38:58",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f))
                                    )
                                }
                                if (showGps) {
                                    val dLat = blLat.toDoubleOrNull() ?: 31.23
                                    val dLng = blLng.toDoubleOrNull() ?: 121.47
                                    Text(
                                        text = String.format(java.util.Locale.CHINA, "经度：%.2f  纬度：%.2f", dLng, dLat),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f))
                                    )
                                }
                                if (showAddress) {
                                    Text(
                                        text = "位置：$blAddress",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black, blurRadius = 1f)),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "专属水印功能已关闭",
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "关闭水印后，后续生成的 PDF 将不包含照片水印信息。",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewDialog(
    file: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmaps by remember(file) { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val list = mutableListOf<android.graphics.Bitmap>()
                val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(parcelFileDescriptor)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    // High quality scaling
                    val scaleFactor = 2
                    val width = page.width * scaleFactor
                    val height = page.height * scaleFactor
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    list.add(bitmap)
                    page.close()
                }
                renderer.close()
                parcelFileDescriptor.close()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    bitmaps = list
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    errorMessage = e.message ?: "PDF 解析失败"
                }
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PDF预览: ${file.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider()

                    if (errorMessage != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (bitmaps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "正在渲染，请稍候。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Gray.copy(alpha = 0.15f)),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(bitmaps) { bitmap ->
                                Card(
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "PDF Page",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
fun CollapsibleMetadataSection(
    item: com.example.data.StockItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "详细信息",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = if (isExpanded) "收起" else "展开",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (isExpanded) {
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val itemsToDisplay = listOf(
                    Pair("设备编号", item.originalCode),
                    Pair("资产分类", item.category),
                    Pair("存放位置", item.location),
                    Pair("设备 UID", item.uid)
                )

                itemsToDisplay.forEach { (label, value) ->
                    val displayValue = value.ifEmpty { "无" }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(displayValue) {
                                detectTapGestures(
                                    onLongPress = {
                                        if (value.isNotEmpty()) {
                                            val clip = android.content.ClipData.newPlainText("metadata", value)
                                            clipboardManager.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, "已复制元数据: $value", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$label: ",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = displayValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
