# Tenken 项目 Wiki

## 1. 项目概览

Tenken 是一个 Android/Kotlin 单模块应用，当前应用名在资源文件中为“点检”（源码中部分中文字符串出现乱码）。项目主要面向资产盘点、现场点检和交付归档场景，核心能力包括：

- 管理多个盘点项目。
- 导入 CSV 或 XLSX 台账。
- 按项目、分类、资产条目维护盘点状态。
- 对资产进行连续拍照。
- 将照片自动合成为 PDF。
- 为照片/PDF 添加编号、水印、拍摄时间、GPS 和地址信息。
- 导出包含分类 PDF 和盘点表的 ZIP 档案包。
- 通过局域网 Wi-Fi 启动一个简易 HTTP 门户，实现电脑端上传台账、编辑项目元数据和下载导出包。

项目看起来来自 Google AI Studio 生成模板，README 中仍保留 AI Studio 的运行说明和 Gemini API Key 占位配置。不过从当前源码看，主要业务是 Android 本地盘点工具，Gemini/Firebase AI 相关依赖处于未启用或注释状态。

## 2. 技术栈

- 语言：Kotlin
- 平台：Android
- UI：Jetpack Compose、Material 3
- 架构组件：ViewModel、StateFlow、Lifecycle Compose
- 数据库：Room
- 异步：Kotlin Coroutines、Flow
- 拍照：CameraX
- 图片加载：Coil
- PDF：Android `PdfDocument`、`PdfRenderer`
- Excel：Apache POI
- 网络：手写 `ServerSocket` HTTP 服务
- 测试：JUnit、Robolectric、Roborazzi
- 构建：Gradle Kotlin DSL、Version Catalog、KSP、Secrets Gradle Plugin

主要版本：

- Android Gradle Plugin：9.1.1
- Kotlin：2.2.10
- compileSdk：36.1
- minSdk：26
- targetSdk：36
- Room：2.7.0
- CameraX：1.5.0
- Robolectric：4.16.1

## 3. 目录结构

```text
.
├── README.md
├── wiki.md
├── metadata.json
├── .env.example
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/
        │   │   ├── MainActivity.kt
        │   │   ├── data/
        │   │   │   ├── AppDatabase.kt
        │   │   │   ├── Project.kt
        │   │   │   ├── ProjectDao.kt
        │   │   │   ├── StockItem.kt
        │   │   │   ├── StockItemDao.kt
        │   │   │   └── StockRepository.kt
        │   │   ├── ui/
        │   │   │   ├── StockViewModel.kt
        │   │   │   ├── WifiTransferServer.kt
        │   │   │   └── theme/
        │   │   └── util/
        │   │       └── PhotoMetadataUtils.kt
        │   └── res/
        ├── test/
        └── androidTest/
```

## 4. 核心模块说明

### 4.1 `MainActivity.kt`

主界面和大部分 Compose UI 都集中在这个文件中。

主要职责：

- 启动应用并挂载 Compose 内容。
- 在仪表盘和拍照界面之间切换。
- 展示项目列表、资产列表、统计卡片、空状态和引导卡片。
- 触发导入、导出、清空、项目新增/重命名/删除等操作。
- 调用 CameraX 拍照。
- 展示照片增强、裁剪、水印设置和 PDF 预览界面。

关键 Composable：

- `MainAppContent`
- `DashboardScreen`
- `TutorialGuideCard`
- `StatsCategoryCard`
- `StockItemRow`
- `EmptyStateView`
- `CameraCaptureScreen`
- `CameraPreviewWidget`
- `DocumentEnhancingDialog`
- `WatermarkSettingsPage`
- `PdfPreviewDialog`
- `CollapsibleMetadataSection`

### 4.2 `StockViewModel.kt`

应用状态和业务操作的协调层。它连接 UI、Room 数据库、仓库层和 Wi-Fi 传输服务。

主要状态：

- `allProjects`：全部项目。
- `activeProjectId`：当前选中项目 ID。
- `activeProject`：当前项目详情。
- `stockItems`：当前项目下的资产条目。
- `isImporting` / `isExporting`：导入导出状态。
- `activeItemForPhoto`：当前正在拍照的资产。
- `activeSessionPhotos`：当前拍照会话照片列表。
- `wifiTransferEnabled` / `deviceIpAddress` / `wifiPort`：局域网传输状态。
- `watermarkEnabled` 及多项水印开关。

主要操作：

- 项目：新增、选择、重命名、更新元数据、删除。
- 台账：导入 CSV/XLSX、导入样例数据、清空。
- 拍照：开始/结束拍照、模拟拍照、删除照片、刷新照片列表。
- PDF：后台生成、手动生成、删除某资产 PDF。
- 图片处理：滤镜、裁剪。
- 水印：开启/关闭、更新左下角/右上角水印设置，并重新生成已有 PDF。
- 导出：生成 ZIP 并写入用户选择的目标 URI。
- Wi-Fi：启动/停止局域网 HTTP 服务。

### 4.3 `StockRepository.kt`

仓库层包含大部分数据处理、文件处理和导入导出逻辑。

主要职责：

- 封装 `StockItemDao` 和 `ProjectDao`。
- CSV/XLSX 解析并导入 Room。
- 解析和保存原始行数据。
- 生成 XLSX 盘点报表。
- 读取照片并合成 PDF。
- 添加图片水印。
- 图片滤镜处理。
- 照片裁剪和重编号。
- 生成导出 ZIP。
- 管理分类前缀配置。

重要函数：

- `parseAndImportCsv(...)`
- `parseAndImportXlsx(...)`
- `generateXlsxReport(...)`
- `generatePdfForItem(...)`
- `createExportZip(...)`
- `applyImageFilter(...)`
- `cropImageFile(...)`
- `renumberPhotos(...)`
- `getCategoryPrefix(...)`

文件存储约定：

- 照片目录：`context.filesDir/photos/<itemUid>/`
- PDF 目录：`context.filesDir/pdfs/<itemUid>/`
- PDF 文件名：源码中使用类似 `照片.pdf` 的名称，但当前源码显示为乱码。
- 导出临时文件：`context.cacheDir`

### 4.4 `WifiTransferServer.kt`

局域网文件传输服务器。它没有使用成熟 HTTP 框架，而是基于 `ServerSocket` 手写 HTTP 解析、路由和响应。

主要能力：

- `GET /`：返回电脑端 HTML 管理门户。
- `GET /download-template`：下载 CSV 空模板。
- `POST /upload`：上传 CSV/XLSX 并导入指定项目。
- `GET /api/projects`：获取项目列表。
- `GET /api/project`：获取并切换项目。
- `POST /api/project/add`：新增项目。
- `POST /api/project/update`：更新项目元数据。
- `POST /api/project/delete`：删除项目。
- `POST /api/save-project-meta`：保存项目元数据。
- `GET /api/prepare-zip`：准备导出 ZIP。
- `GET /download-zip`：下载准备好的 ZIP。

实现特点：

- 支持 `Content-Length` 和 chunked body。
- HTML、CSS、JavaScript 都内嵌在 Kotlin 字符串中。
- JSON 解析使用正则手写处理。
- 下载和上传逻辑直接调用 `StockRepository`。

### 4.5 `PhotoMetadataUtils.kt`

照片物理元数据工具。

主要职责：

- 从设备获取 GPS 或网络定位。
- 使用 `Geocoder` 反查地址。
- 写入 EXIF GPS、拍摄时间、地址描述。
- 读取 EXIF 中的日期、时间、经纬度和地址。
- 在无定位时使用上海附近的模拟坐标作为回退值。

数据类：

- `PhotoPhysicalMetadata`

### 4.6 Room 数据层

数据库定义在 `AppDatabase.kt`：

- 数据库名：`stocktake_database`
- 实体：`StockItem`、`Project`
- 当前版本：4
- 迁移策略：`fallbackToDestructiveMigration()`

这意味着数据库 schema 变化时会销毁旧数据并重建数据库，不适合生产环境保留历史数据。

#### `Project`

字段：

- `id`：项目 UUID。
- `name`：项目名称。
- `baseDate`：基准日。
- `companyName`：公司/单位名称。
- `reportType`：报告类型。
- `columnHeadersJson`：导入台账列头。
- `watermarkEnabled`：总水印开关。
- `watermarkBlEnabled`：左下角水印开关。
- `watermarkBlShowDate`：左下角显示日期。
- `watermarkBlShowTime`：左下角显示时间。
- `watermarkBlShowGps`：左下角显示 GPS。
- `watermarkBlShowAddress`：左下角显示地址。
- `watermarkTrEnabled`：右上角编号水印开关。

#### `StockItem`

字段：

- `uid`：资产 UUID。
- `name`：资产名称。
- `category`：资产分类。
- `location`：存放位置。
- `originalCode`：原始资产编号。
- `photoCount`：照片数量。
- `pdfStatus`：PDF 生成状态。
- `projectId`：所属项目。
- `shouldCheck`：是否需要盘点。
- `originalRowJson`：导入台账原始行数据。

## 5. 主要业务流程

### 5.1 新建项目

1. 用户在 App 内或 Wi-Fi 门户中新建项目。
2. 输入单位名称、基准日、报告类型等元数据。
3. `StockViewModel.addProject(...)` 或 Wi-Fi API 创建 `Project`。
4. 项目写入 Room。
5. 新项目成为当前活跃项目。

### 5.2 导入台账

支持两种导入入口：

- App 内通过系统文件选择器导入。
- 电脑端通过 Wi-Fi 门户上传。

流程：

1. 用户选择 CSV 或 XLSX。
2. 选择导入项目。
3. 选择追加或替换模式。
4. `StockRepository.parseAndImportCsv(...)` 或 `parseAndImportXlsx(...)` 解析数据。
5. 根据列名识别编号、名称、分类、位置、是否盘点等字段。
6. 生成或保留 UUID。
7. 写入 `stock_items` 表。
8. 如果是替换模式，会清理该项目旧条目及相关照片/PDF。

### 5.3 拍照与 PDF 生成

1. 用户在资产行点击拍照。
2. `StockViewModel.startPhotoCapture(item)` 设置当前拍照资产。
3. UI 切换到 CameraX 拍照界面。
4. 拍照后图片保存到 `filesDir/photos/<uid>/`。
5. 写入 EXIF 元数据。
6. 用户结束拍照后，后台调用 `generatePdfForItem(...)`。
7. 照片被压缩、旋转校正、可选水印处理，并合成为 PDF。
8. 更新 Room 中 `photoCount` 和 `pdfStatus`。

### 5.4 水印处理

水印分为两类：

- 右上角编号水印：通常使用分类前缀和序号。
- 左下角信息水印：可包含拍摄日期、时间、GPS、地址。

开启或更新水印设置后，ViewModel 会遍历当前项目中已生成 PDF 的资产，并重新生成 PDF。

### 5.5 导出 ZIP

1. 用户选择导出位置，或通过 Wi-Fi 门户请求导出。
2. `createExportZip(...)` 按资产分类分组。
3. 对有照片但未生成 PDF 的条目尝试即时生成 PDF。
4. 将 PDF 放入分类目录。
5. 生成 XLSX 盘点表并放到 ZIP 根目录。
6. 输出 ZIP 到用户指定位置或 Wi-Fi 下载缓存。

ZIP 结构大致为：

```text
导出包.zip
├── 分类A/
│   ├── 前缀 0001 资产名.pdf
│   └── 前缀 0002 资产名.pdf
├── 分类B/
│   └── 前缀 0001 资产名.pdf
└── 盘点表.xlsx
```

## 6. 构建与运行

README 中给出的运行方式：

1. 安装 Android Studio。
2. 用 Android Studio 打开项目根目录。
3. 等待 Gradle 同步。
4. 在项目根目录创建 `.env`。
5. 根据 `.env.example` 写入 `GEMINI_API_KEY=...`。
6. README 建议移除 `app/build.gradle.kts` 中 debug 签名配置行：`signingConfig = signingConfigs.getByName("debugConfig")`。
7. 在模拟器或真机运行 App。

补充说明：

- 当前仓库没有发现 `gradlew` 或 `gradlew.bat`，因此命令行构建需要本机已安装 Gradle，或先补齐 Gradle Wrapper。
- `app/build.gradle.kts` 配置了 `debug.keystore`，但项目根目录当前未看到该文件。如果 Android Studio 导入时报签名文件缺失，可以按 README 删除 debug 签名配置，或提供对应 keystore。
- `release` 签名依赖环境变量 `KEYSTORE_PATH`、`STORE_PASSWORD`、`KEY_PASSWORD`。

## 7. 权限

`AndroidManifest.xml` 申请了：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CAMERA`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

摄像头声明为非必需：

```xml
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

应用配置了：

- `android:usesCleartextTraffic="true"`：允许明文 HTTP，便于局域网门户访问。
- `android:allowBackup="true"`：允许备份。

## 8. 测试现状

测试目录包含：

- `ExampleUnitTest.kt`：基础加法测试。
- `ExampleRobolectricTest.kt`：读取 `app_name` 字符串。
- `GreetingScreenshotTest.kt`：使用 Roborazzi 截取空状态视图截图。
- `ExampleInstrumentedTest.kt`：默认 Android instrumentation 示例。

当前测试覆盖偏示例性质，尚未覆盖核心业务：

- CSV/XLSX 导入解析。
- UUID 保留和替换模式行为。
- Room 数据写入。
- PDF 生成。
- ZIP 结构。
- Wi-Fi API。
- 水印和 EXIF 元数据。

另外，`ExampleRobolectricTest.kt` 期望的 `app_name` 为乱码形式的“盘点辅助”，但 `strings.xml` 当前值为乱码形式的“点检”。该测试很可能失败。

## 9. 当前风险与问题

### 9.1 中文字符串乱码

大量 Kotlin 源码、资源文件和 metadata 中的中文显示为 mojibake，例如：

- `鐐规`
- `璇勪及鎶ュ憡`
- `宸茬敓鎴?`
- `鏈敓鎴?`

这说明源文件可能经历过错误编码转换。影响包括：

- UI 文案不可读。
- 业务状态字符串可读性差。
- 测试断言不稳定。
- CSV/XLSX 列名匹配逻辑难以维护。
- 如果某些字符串缺失引号或被破坏，可能导致 Kotlin 编译失败。

建议优先做一次统一编码修复，并将状态值替换为稳定枚举或英文内部常量，UI 层再做中文展示。

### 9.2 业务逻辑集中在少数大文件

`MainActivity.kt`、`StockRepository.kt`、`WifiTransferServer.kt` 都非常大，分别承担了 UI、业务处理、文件导出、HTTP 服务和 HTML 生成等大量职责。

建议后续拆分：

- UI 组件按页面/弹窗拆分。
- 导入解析拆成 `ImportService`。
- PDF/XLSX/ZIP 拆成独立 exporter。
- Wi-Fi 服务拆分路由、请求解析、HTML 模板。

### 9.3 手写 JSON、CSV 和 HTTP 解析

当前存在多处手写解析：

- `StockRepository` 手写 JSON list 字符串。
- `WifiTransferServer` 用正则解析 JSON。
- `WifiTransferServer` 手写 HTTP 请求解析。
- CSV 解析也由自定义逻辑完成。

风险：

- 转义字符、换行、引号、中文、特殊字符容易出错。
- 文件名和项目名可能造成响应或 ZIP 路径问题。
- HTTP 边界场景覆盖不足。

建议：

- JSON 使用 Moshi。
- CSV 使用成熟 CSV parser。
- 局域网 HTTP 服务可考虑 Ktor、NanoHTTPD 或 Android 端轻量 HTTP 库。

### 9.4 数据库迁移策略会删除数据

`fallbackToDestructiveMigration()` 会在 schema 变化时删除旧数据库。对于盘点类应用，这会带来真实数据丢失风险。

建议：

- 建立 Room migration。
- 保留导出备份入口。
- 在 destructive migration 仅用于开发构建。

### 9.5 状态值使用字符串

`pdfStatus` 使用字符串表达“已生成/未生成”。当前字符串又存在乱码，业务判断依赖完全匹配：

```kotlin
item.pdfStatus == "..."
```

建议改为：

- Room 中保存稳定英文值：`GENERATED` / `NOT_GENERATED`
- Kotlin 使用 enum 或常量对象。
- UI 展示层再映射为中文。

### 9.6 缺少 Gradle Wrapper

当前目录未发现 `gradlew`/`gradlew.bat`。这会降低命令行构建和 CI 可复现性。

建议补充 Gradle Wrapper，并在 README 中提供命令：

```bash
./gradlew test
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

## 10. 推荐后续维护路线

优先级建议：

1. 修复源码和资源中的中文编码问题。
2. 补齐 Gradle Wrapper，确保可命令行构建。
3. 跑通 `test` 和 `assembleDebug`。
4. 将 PDF 状态、报告类型、是否盘点等内部状态改为稳定常量/枚举。
5. 为导入解析、导出 ZIP、PDF 生成建立单元测试或集成测试。
6. 拆分 `MainActivity.kt`、`StockRepository.kt`、`WifiTransferServer.kt`。
7. 用 Moshi 替代手写 JSON 解析。
8. 为 Room schema 建立 migration。
9. 明确 Wi-Fi 门户的安全边界，例如仅局域网、端口配置、访问提示和关闭策略。

## 11. 快速索引

- 应用入口：`app/src/main/java/com/example/MainActivity.kt`
- ViewModel：`app/src/main/java/com/example/ui/StockViewModel.kt`
- 仓库层：`app/src/main/java/com/example/data/StockRepository.kt`
- 数据库：`app/src/main/java/com/example/data/AppDatabase.kt`
- 项目实体：`app/src/main/java/com/example/data/Project.kt`
- 资产实体：`app/src/main/java/com/example/data/StockItem.kt`
- Wi-Fi 服务：`app/src/main/java/com/example/ui/WifiTransferServer.kt`
- 照片元数据：`app/src/main/java/com/example/util/PhotoMetadataUtils.kt`
- 构建配置：`app/build.gradle.kts`
- 依赖版本：`gradle/libs.versions.toml`
- Android Manifest：`app/src/main/AndroidManifest.xml`

