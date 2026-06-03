# Tenken 资产盘点辅助工具

Tenken 是一个 Android 端资产盘点辅助应用，用于资产评估、现场核验和盘点资料整理场景。应用支持导入 Excel 台账、按资产分类抽样、现场拍照、生成资产记录 PDF，并通过局域网 Wi-Fi 页面完成电脑端台账导入、预览、抽样和资料包下载。

本项目主要由 AI 辅助生成和持续维护。代码、界面文案和业务流程已经按当前需求做过多轮人工确认和调整，但仍建议在正式评估项目中由具备资质的专业人员复核数据口径、抽样规则、导出资料和最终结论。

## 主要功能

- **项目管理**
  - 创建、切换、重命名和删除评估项目。
  - 维护评估基准日、产权持有单位、报告类型等项目元数据。

- **Excel 台账导入**
  - 默认使用 `.xlsx` 台账模板。
  - CSV 仅保留兼容导入，不再作为推荐格式。
  - 每条资产记录必须包含 `设备名称` 和 `资产分类`。
  - 可选元数据为空时保持为空，例如 `存放位置`、`设备编号` 不会被自动填充默认值。

- **台账预览与盘点范围管理**
  - 在手机端查看全量台账。
  - 通过 checkbox 将资产纳入或移出待盘点清单。
  - 支持全选/取消全选。
  - 设备卡片详情展示导入台账中的非空元数据。

- **分类抽样**
  - 抽样前必须选择设备分类。
  - 抽样结果只替换所选分类内的待盘点状态，其他分类保持不变。
  - 支持以下抽样方式：
    - 账面原值前 N 项
    - 账面净值前 N 项
    - 数量前 N 项
    - 随机抽样 N 项
    - 累计原值占比抽样
    - 累计净值占比抽样
  - 抽样完成后提示资产数量占比、账面原值占比、账面净值占比和数量占比。

- **现场拍照与 PDF 记录**
  - 对待盘点资产连续拍摄现场照片。
  - 支持图片预览、裁剪和删除。
  - 生成资产记录 PDF。
  - 可配置照片水印，包括资产分类流水号和现场核验信息。

- **Wi-Fi 局域网传输页面**
  - 手机开启局域网传输后，电脑浏览器访问手机提供的 HTTP 页面。
  - 支持电脑端上传 Excel 台账、下载模板、编辑项目元数据。
  - 支持电脑端台账预览、单项勾选、全选/取消全选和分类抽样。
  - 所有 Wi-Fi 页面操作直接写入手机本地数据库，手机端界面同步刷新。

- **资料包导出**
  - 导出项目资料包 ZIP。
  - 资料包包含分类盘点表和资产记录 PDF。

## 表格导入要求

推荐使用应用导出的 Excel 模板。导入台账时请至少包含以下列：

| 列名 | 是否必填 | 说明 |
| --- | --- | --- |
| 设备名称 / Name / AssetName | 必填 | 每条资产记录必须填写 |
| 资产分类 / Category | 必填 | 每条资产记录必须填写，抽样功能基于该字段 |
| 设备编号 / Code | 可选 | 为空时保持为空 |
| 存放位置 / Location | 可选 | 为空时保持为空 |
| 账面原值 | 可选 | 用于“账面原值前 N 项”和“累计原值占比抽样” |
| 账面净值 | 可选 | 用于“账面净值前 N 项”和“累计净值占比抽样” |
| 数量 / Quantity / Qty | 可选 | 用于“数量前 N 项”和数量占比统计 |
| 是否盘点 / ShouldCheck | 可选 | `是/1/true` 进入待盘点清单，`否/0/false` 进入台账预览 |
| 备注 | 可选 | 导出盘点表时保留 |
| UUID / UID | 可选 | 为空时由系统生成 |

说明：

- `设备名称` 或 `资产分类` 缺失时，导入失败。
- 空白可选字段不会被系统写入“默认位置”“默认区域”等占位值。
- 无法识别的数值字段按 0 处理，仅影响对应抽样排序和占比统计。

## 使用流程

1. 在手机端新建评估项目，填写评估基准日和产权持有单位。
2. 下载 Excel 模板，整理资产台账。
3. 在手机端或 Wi-Fi 页面导入台账。
4. 在台账预览中勾选待盘点资产，或按设备分类执行抽样。
5. 对待盘点资产逐项拍摄现场照片。
6. 生成资产记录 PDF。
7. 导出项目资料包 ZIP。

## Wi-Fi 页面使用

1. 在手机端开启“局域网资料传输”。
2. 在同一局域网内的电脑浏览器访问手机显示的地址，例如：

   ```text
   http://192.168.1.10:9090
   ```

3. 在电脑端完成项目选择、台账上传、台账预览、分类抽样和资料包下载。

注意：

- Wi-Fi 服务仅用于局域网内临时传输。
- 传输期间请保持手机应用运行，并确认手机和电脑处于同一网络。
- 当前实现是轻量级 HTTP 服务，不提供公网访问鉴权能力，不建议暴露到公网。

## 本地构建

### 环境要求

- Android Studio 或命令行 Android SDK
- JDK 17 或更高版本；当前本地维护环境使用 JDK 21
- Gradle Wrapper 已包含在仓库中

### 常用命令

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME='C:\Tenken\.tools\jdk21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --console=plain --no-configuration-cache testDebugUnitTest
.\gradlew.bat --console=plain --no-configuration-cache assembleDebug
```

生成的 debug APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

安装到已连接的 Android 设备：

```powershell
.\.tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 技术栈

- Kotlin
- Jetpack Compose
- Room
- CameraX
- Android PdfDocument
- Apache POI
- 手写轻量级局域网 HTTP 服务
- Gradle / Android Gradle Plugin

## 项目结构

```text
app/src/main/java/com/example/
├── MainActivity.kt                 # Compose UI、拍照流程、台账预览和手机端抽样
├── data/
│   ├── AppDatabase.kt              # Room 数据库
│   ├── InventorySampling.kt        # 共享分类抽样算法
│   ├── InventoryTemplate.kt        # Excel 模板
│   ├── Project.kt                  # 项目实体
│   ├── StockItem.kt                # 资产实体
│   └── StockRepository.kt          # 导入、导出、PDF、数据库业务逻辑
├── ui/
│   ├── StockViewModel.kt           # UI 状态和业务协调
│   └── WifiTransferServer.kt       # 局域网 Web 页面和接口
└── util/
    └── PhotoMetadataUtils.kt       # 照片元数据处理
```

## AI 辅助开发声明

本项目由 AI 工具辅助生成、修改和维护。AI 参与了代码编写、界面文案调整、测试命令执行和维护说明整理。

使用或二次开发本项目时请注意：

- AI 生成代码可能存在边界条件遗漏、平台兼容性问题或业务理解偏差。
- 资产评估、盘点抽样和资料归档属于专业业务流程，实际项目中应由相关专业人员复核。
- 本项目不构成资产评估意见、审计意见或法律意见。

## 许可证

本项目以 MIT License 发布，详见 [LICENSE](LICENSE)。

如果你在商业项目、评估项目或内部系统中使用本项目，请结合自身业务要求补充权限控制、数据备份、日志审计、隐私保护和网络安全措施。
