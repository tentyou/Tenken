package com.example.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.example.data.InventoryConstants
import com.example.data.InventorySampling
import com.example.data.InventoryTemplate
import com.example.data.StockRepository
import com.example.data.Project
import com.example.data.SamplingMethod
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

class WifiTransferServer(
    private val context: Context,
    private val repository: StockRepository,
    private val onProjectChanged: (String) -> Unit,
    private val onImportCompleted: (Boolean, Int) -> Unit
) {
    private fun readLineBytes(inputStream: java.io.InputStream): String? {
        val bos = java.io.ByteArrayOutputStream()
        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                if (bos.size() == 0) return null else break
            }
            if (b == '\n'.code) {
                break
            }
            if (b == '\r'.code) {
                continue
            }
            bos.write(b)
        }
        return bos.toString("UTF-8")
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start(port: Int = 8080) {
        if (isRunning) return
        isRunning = true
        acquireTransferLocks()
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        handleClient(socket)
                    }.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isRunning) {
                    isRunning = false
                    releaseTransferLocks()
                }
            }
        }.apply { start() }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        serverThread = null
        releaseTransferLocks()
    }

    private fun acquireTransferLocks() {
        try {
            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tenken:WifiTransferServer"
            ).apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Tenken:WifiTransferServer"
            ).apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseTransferLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            wakeLock = null
        }

        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            wifiLock = null
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val headerLine = readLineBytes(input) ?: return socket.close()
            
            val parts = headerLine.split(" ")
            if (parts.size < 2) return socket.close()
            
            val method = parts[0]
            val pathWithQuery = parts[1]
            
            var filename = "upload.xlsx"
            var queryProjectId = ""
            var queryMode = "append" // append or replace

            val queryStart = pathWithQuery.indexOf("?")
            val path = if (queryStart != -1) {
                val query = pathWithQuery.substring(queryStart + 1)
                for (param in query.split("&")) {
                    val kv = param.split("=")
                    if (kv.size == 2) {
                        val key = kv[0]
                        val value = URLDecoder.decode(kv[1], "UTF-8")
                        when (key) {
                            "filename" -> filename = value
                            "projectId", "queryProjectId" -> queryProjectId = value
                            "mode" -> queryMode = value
                        }
                    }
                }
                pathWithQuery.substring(0, queryStart)
            } else {
                pathWithQuery
            }

            // Read all headers to skip them
            var contentLength = 0
            var rangeHeader: String? = null
            var line: String? = readLineBytes(input)
            while (line != null && line.isNotEmpty()) {
                val lowerLine = line.lowercase()
                if (lowerLine.startsWith("content-length:")) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        contentLength = parts[1].trim().toIntOrNull() ?: 0
                    }
                } else if (lowerLine.startsWith("range:")) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        rangeHeader = parts[1].trim()
                    }
                }
                line = readLineBytes(input)
            }

            val out = socket.getOutputStream()

            if (method == "GET" && path == "/") {
                // Send beautiful web portal Page with dynamic projects injected
                val html = getHtmlPage()
                val htmlBytes = html.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${htmlBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(htmlBytes)
                out.flush()
            } else if (method == "GET" && path == "/download-template") {
                val project = kotlinx.coroutines.runBlocking {
                    repository.getProjectById(queryProjectId)
                }
                val projectName = project?.name ?: InventoryConstants.DEFAULT_PROJECT_NAME
                val xlsxBytes = InventoryTemplate.createXlsxBytes(project?.columnHeadersJson)
                
                val safeFilename = URLEncoder.encode("${projectName}-盘点模板.xlsx", "UTF-8").replace("+", "%20")
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: ${InventoryTemplate.XLSX_MIME_TYPE}\r\n" +
                        "Content-Disposition: attachment; filename*=UTF-8''$safeFilename\r\n" +
                        "Content-Length: ${xlsxBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(xlsxBytes)
                out.flush()
            } else if (method == "POST" && path == "/upload" && contentLength > 0) {
                // Read binary body content of exact Content-Length
                val bodyBos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val toRead = Math.min(4096, contentLength - totalRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    bodyBos.write(buffer, 0, read)
                    totalRead += read
                }
                val bodyBytes = bodyBos.toByteArray()
                
                val defaultProject = kotlinx.coroutines.runBlocking {
                    val list = repository.listProjectsSync()
                    if (list.isNotEmpty()) list[0].id else InventoryConstants.DEFAULT_PROJECT_ID
                }
                val targetProjId = queryProjectId.ifEmpty { defaultProject }
                val isReplace = queryMode == "replace"

                // Save and import the records
                val recordsCountBefore = getItemsCount(targetProjId)
                val tempFile = java.io.File(context.cacheDir, "wifi_upload_${UUID.randomUUID()}.$filename")
                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(bodyBytes)
                
                var success = false
                try {
                    val inputStream = ByteArrayInputStream(bodyBytes)
                    success = kotlinx.coroutines.runBlocking {
                        if (filename.endsWith(".xlsx", ignoreCase = true)) {
                            repository.parseAndImportXlsx(context, inputStream, targetProjId, isReplace)
                        } else {
                            repository.parseAndImportCsv(context, inputStream, targetProjId, isReplace)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempFile.delete()
                }

                val recordsCountAfter = getItemsCount(targetProjId)
                val recordsImported = if (isReplace) {
                    recordsCountAfter
                } else {
                    recordsCountAfter - recordsCountBefore
                }

                val responseJson = if (success && recordsImported > 0) {
                    onImportCompleted(true, recordsImported)
                    "{\"success\": true, \"count\": $recordsImported}"
                } else {
                    onImportCompleted(false, 0)
                    "{\"success\": false, \"error\": \"格式不正确或未找到有效的数据列\"}"
                }
                val jsonBytes = responseJson.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            } else if (method == "GET" && path == "/api/project") {
                val project = kotlinx.coroutines.runBlocking {
                    repository.getProjectById(queryProjectId)
                }
                if (project != null) {
                    onProjectChanged(project.id)
                }
                val responseJson = if (project != null) {
                    val escapedBaseDate = project.baseDate.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    val escapedCompanyName = project.companyName.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    val escapedReportType = project.reportType.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    "{\"id\": \"${project.id}\", \"name\": \"${project.name}\", \"baseDate\": \"$escapedBaseDate\", \"companyName\": \"$escapedCompanyName\", \"reportType\": \"$escapedReportType\"}"
                } else {
                    "{}"
                }
                val jsonBytes = responseJson.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/save-project-meta" && contentLength > 0) {
                val bodyBos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val toRead = Math.min(4096, contentLength - totalRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    bodyBos.write(buffer, 0, read)
                    totalRead += read
                }
                val bodyBytes = bodyBos.toByteArray()
                val bodyStr = String(bodyBytes, Charsets.UTF_8)

                val json = org.json.JSONObject(bodyStr)
                val pId = json.optString("id", "")
                val pBaseDate = json.optString("baseDate", "")
                val pCompanyName = json.optString("companyName", "")
                val pReportType = json.optString("reportType", "")

                var success = false
                if (pId.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        val proj = repository.getProjectById(pId)
                        if (proj != null) {
                            repository.insertProject(proj.copy(
                                baseDate = pBaseDate,
                                companyName = pCompanyName,
                                reportType = pReportType
                            ))
                            onProjectChanged(proj.id)
                            success = true
                        }
                    }
                }

                val responseJson = "{\"success\": $success}"
                val jsonBytes = responseJson.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            } else if (method == "GET" && path == "/api/projects") {
                val list = kotlinx.coroutines.runBlocking {
                    repository.listProjectsSync()
                }
                val arr = org.json.JSONArray()
                list.forEach { p ->
                    val obj = org.json.JSONObject()
                    obj.put("id", p.id)
                    obj.put("name", p.name)
                    obj.put("baseDate", p.baseDate)
                    obj.put("companyName", p.companyName)
                    obj.put("reportType", p.reportType)
                    arr.put(obj)
                }
                val rBytes = arr.toString().toByteArray(Charsets.UTF_8)
                val rHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${rBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(rHeaders.toByteArray(Charsets.UTF_8))
                out.write(rBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/project/add" && contentLength > 0) {
                val bodyBos = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val toRead = Math.min(4096, contentLength - totalRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    bodyBos.write(buffer, 0, read)
                    totalRead += read
                }
                val bodyStr = String(bodyBos.toByteArray(), Charsets.UTF_8)

                val json = org.json.JSONObject(bodyStr)
                val pBaseDate = json.optString("baseDate", "")
                val pCompanyName = json.optString("companyName", "")
                val pReportType = json.optString("reportType", InventoryConstants.REPORT_TYPE_EVALUATION)
                var pName = json.optString("name", "")

                if (pName.trim().isEmpty()) {
                    if (pCompanyName.trim().isNotEmpty() && pBaseDate.isNotEmpty()) {
                        val formattedDate = pBaseDate.filter { it.isDigit() }
                        pName = "${pCompanyName.trim()}-$formattedDate"
                    } else {
                        pName = "新建项目"
                    }
                }

                val newProj = Project(
                    name = pName,
                    baseDate = pBaseDate,
                    companyName = pCompanyName.trim(),
                    reportType = pReportType
                )
                kotlinx.coroutines.runBlocking {
                    repository.insertProject(newProj)
                }
                onProjectChanged(newProj.id)

                val responseJson = "{\"success\": true, \"id\": \"${newProj.id}\", \"name\": \"${newProj.name}\"}"
                val jsonBytes = responseJson.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/project/delete" && contentLength > 0) {
                val bodyBos = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val toRead = Math.min(4096, contentLength - totalRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    bodyBos.write(buffer, 0, read)
                    totalRead += read
                }
                val bodyStr = String(bodyBos.toByteArray(), Charsets.UTF_8)

                val json = org.json.JSONObject(bodyStr)
                val pId = json.optString("id", "")

                var success = false
                if (pId.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        val proj = repository.getProjectById(pId)
                        if (proj != null) {
                            repository.deleteProject(context, proj)
                            val remaining = repository.listProjectsSync()
                            val nextActive = if (remaining.isNotEmpty()) remaining[0].id else ""
                            onProjectChanged(nextActive)
                            success = true
                        }
                    }
                }

                val responseJson = "{\"success\": $success}"
                val jsonBytes = responseJson.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/project/update" && contentLength > 0) {
                val bodyBos = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val toRead = Math.min(4096, contentLength - totalRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    bodyBos.write(buffer, 0, read)
                    totalRead += read
                }
                val bodyStr = String(bodyBos.toByteArray(), Charsets.UTF_8)

                val json = org.json.JSONObject(bodyStr)
                val pId = json.optString("id", "")
                val pName = json.optString("name", "")
                val pBaseDate = json.optString("baseDate", "")
                val pCompanyName = json.optString("companyName", "")
                val pReportType = json.optString("reportType", "")

                var success = false
                if (pId.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        val proj = repository.getProjectById(pId)
                        if (proj != null) {
                            var updatedName = pName.trim()
                            if (pName.trim().isEmpty() && pCompanyName.trim().isNotEmpty() && pBaseDate.isNotEmpty()) {
                                val formattedDate = pBaseDate.filter { it.isDigit() }
                                updatedName = "${pCompanyName.trim()}-$formattedDate"
                            }
                            repository.insertProject(proj.copy(
                                name = if (updatedName.isNotEmpty()) updatedName else proj.name,
                                baseDate = pBaseDate,
                                companyName = pCompanyName.trim(),
                                reportType = pReportType
                            ))
                            onProjectChanged(proj.id)
                            success = true
                        }
                    }
                }

                val responseJson = "{\"success\": $success}"
                val jsonBytes = responseJson.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${jsonBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            } else if (method == "GET" && path == "/api/items") {
                val responseJson = buildItemsResponseJson(queryProjectId)
                val rBytes = responseJson.toByteArray(Charsets.UTF_8)
                val rHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${rBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(rHeaders.toByteArray(Charsets.UTF_8))
                out.write(rBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/items/check" && contentLength > 0) {
                val bodyStr = readBodyAsString(input, contentLength)
                val json = org.json.JSONObject(bodyStr)
                val uid = json.optString("uid", "")
                val shouldCheck = json.optBoolean("shouldCheck", false)

                var success = false
                var projectId = ""
                if (uid.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        val item = repository.getItemByUid(uid)
                        if (item != null) {
                            repository.insertItem(item.copy(shouldCheck = shouldCheck))
                            projectId = item.projectId
                            success = true
                        }
                    }
                }
                if (projectId.isNotEmpty()) {
                    onProjectChanged(projectId)
                }

                val responseJson = "{\"success\": $success}"
                val rBytes = responseJson.toByteArray(Charsets.UTF_8)
                val rHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${rBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(rHeaders.toByteArray(Charsets.UTF_8))
                out.write(rBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/items/select-all" && contentLength > 0) {
                val bodyStr = readBodyAsString(input, contentLength)
                val json = org.json.JSONObject(bodyStr)
                val pId = json.optString("projectId", queryProjectId)
                val shouldCheck = json.optBoolean("shouldCheck", false)

                var count = 0
                var success = false
                if (pId.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        val items = repository.getItemsByProjectSync(pId)
                        count = items.size
                        repository.insertAll(items.map { it.copy(shouldCheck = shouldCheck) })
                        success = true
                    }
                    onProjectChanged(pId)
                }

                val responseJson = "{\"success\": $success, \"count\": $count}"
                val rBytes = responseJson.toByteArray(Charsets.UTF_8)
                val rHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${rBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(rHeaders.toByteArray(Charsets.UTF_8))
                out.write(rBytes)
                out.flush()
            } else if (method == "POST" && path == "/api/items/sample" && contentLength > 0) {
                val bodyStr = readBodyAsString(input, contentLength)
                val json = org.json.JSONObject(bodyStr)
                val pId = json.optString("projectId", queryProjectId)
                val category = json.optString("category", "")
                val methodId = json.optString("method", SamplingMethod.ORIGINAL_VALUE_TOP_N.id)
                val requestedCount = json.optInt("count", 0)
                val targetRatio = json.optDouble("ratio", 0.0)

                var responseJson = "{\"success\": false, \"error\": \"参数不完整\"}"
                if (pId.isNotEmpty() && category.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        val project = repository.getProjectById(pId)
                        val items = repository.getItemsByProjectSync(pId)
                        val samplingMethod = SamplingMethod.fromId(methodId)
                        if (items.any { it.category.trim() == category.trim() }) {
                            val result = InventorySampling.sample(
                                allItems = items,
                                columnHeadersJson = project?.columnHeadersJson,
                                category = category,
                                method = samplingMethod,
                                requestedCount = requestedCount,
                                targetRatioPercent = targetRatio
                            )
                            repository.insertAll(InventorySampling.applyResultToSelectedCategory(items, result))
                            val obj = org.json.JSONObject()
                            obj.put("success", true)
                            obj.put("summary", result.summaryText())
                            obj.put("selectedCount", result.selectedCount)
                            obj.put("categoryCount", result.categoryCount)
                            responseJson = obj.toString()
                        } else {
                            responseJson = "{\"success\": false, \"error\": \"未找到所选资产分类\"}"
                        }
                    }
                    onProjectChanged(pId)
                }

                val rBytes = responseJson.toByteArray(Charsets.UTF_8)
                val rHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${rBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(rHeaders.toByteArray(Charsets.UTF_8))
                out.write(rBytes)
                out.flush()
            } else if (method == "GET" && path == "/api/prepare-zip") {
                val project = kotlinx.coroutines.runBlocking {
                    repository.getProjectById(queryProjectId)
                }
                val responseJson = if (project != null) {
                    if (project.baseDate.trim().isEmpty() || project.companyName.trim().isEmpty()) {
                        "{\"success\": false, \"error\": \"生成项目资料包失败：请先填写评估基准日和产权持有单位。\"}"
                    } else {
                        val items = kotlinx.coroutines.runBlocking {
                            repository.getItemsByProjectSync(queryProjectId)
                        }
                        val destFile = java.io.File(context.cacheDir, "project_export_${queryProjectId}.zip")
                        val success = kotlinx.coroutines.runBlocking {
                            repository.createExportZip(context, items, destFile)
                        }
                        if (success && destFile.exists()) {
                            val timestampStr = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                            "{\"success\": true, \"size\": ${destFile.length()}, \"filename\": \"${URLEncoder.encode("${project.name}-盘点表-${timestampStr}.zip", "UTF-8").replace("+", "%20")}\"}"
                        } else {
                            "{\"success\": false, \"error\": \"生成项目资料包失败：项目暂无可导出的资产记录或 PDF 文件。\"}"
                        }
                    }
                } else {
                    "{\"success\": false, \"error\": \"未找到对应项目\"}"
                }
                val rBytes = responseJson.toByteArray(Charsets.UTF_8)
                val rHeaders = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${rBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(rHeaders.toByteArray(Charsets.UTF_8))
                out.write(rBytes)
                out.flush()
            } else if (method == "GET" && path == "/download-zip") {
                val project = kotlinx.coroutines.runBlocking {
                    repository.getProjectById(queryProjectId)
                }
                val destFile = java.io.File(context.cacheDir, "project_export_${queryProjectId}.zip")
                if (project != null && (project.baseDate.trim().isEmpty() || project.companyName.trim().isEmpty())) {
                    val errorMsg = "Export zip failed: baseDate and companyName are empty. Please fill them first!"
                    val errorBytes = errorMsg.toByteArray(Charsets.UTF_8)
                    val rHeaders = "HTTP/1.1 400 Bad Request\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: ${errorBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(rHeaders.toByteArray(Charsets.UTF_8))
                    out.write(errorBytes)
                    out.flush()
                } else if (project != null && destFile.exists()) {
                    val fileLength = destFile.length()
                    val timestampStr = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                    val safeFilename = URLEncoder.encode("${project.name}-盘点表-${timestampStr}.zip", "UTF-8").replace("+", "%20")

                    var rangeStart: Long = -1
                    var rangeEnd: Long = -1

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        val rangesStr = rangeHeader.substring(6).trim()
                        val dashIdx = rangesStr.indexOf('-')
                        if (dashIdx != -1) {
                            val startStr = rangesStr.substring(0, dashIdx).trim()
                            val endStr = rangesStr.substring(dashIdx + 1).trim()
                            try {
                                if (startStr.isNotEmpty()) {
                                    rangeStart = startStr.toLong()
                                }
                                if (endStr.isNotEmpty()) {
                                    rangeEnd = endStr.toLong()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    if (rangeHeader != null && (rangeStart >= 0L || rangeEnd >= 0L)) {
                        if (rangeStart == -1L) rangeStart = 0L
                        if (rangeEnd == -1L || rangeEnd >= fileLength) rangeEnd = fileLength - 1L

                        if (rangeStart >= fileLength) {
                            val rHeaders = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                                    "Content-Range: bytes */$fileLength\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(rHeaders.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } else {
                            val chunkLength = rangeEnd - rangeStart + 1L
                            val rHeaders = "HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Type: application/zip\r\n" +
                                    "Content-Range: bytes $rangeStart-$rangeEnd/$fileLength\r\n" +
                                    "Content-Disposition: attachment; filename*=UTF-8''$safeFilename\r\n" +
                                    "Content-Length: $chunkLength\r\n" +
                                    "Accept-Ranges: bytes\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(rHeaders.toByteArray(Charsets.UTF_8))
                            
                            destFile.inputStream().use { fileInput ->
                                fileInput.skip(rangeStart)
                                var bytesToRead = chunkLength
                                val buffer = ByteArray(8192)
                                while (bytesToRead > 0) {
                                    val toRead = Math.min(buffer.size.toLong(), bytesToRead).toInt()
                                    val read = fileInput.read(buffer, 0, toRead)
                                    if (read == -1) break
                                    out.write(buffer, 0, read)
                                    bytesToRead -= read
                                }
                            }
                            out.flush()
                        }
                    } else {
                        val rHeaders = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/zip\r\n" +
                                "Content-Disposition: attachment; filename*=UTF-8''$safeFilename\r\n" +
                                "Content-Length: $fileLength\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(rHeaders.toByteArray(Charsets.UTF_8))
                        
                        destFile.inputStream().use { fileInput ->
                            fileInput.copyTo(out, bufferSize = 8192)
                        }
                        out.flush()
                    }
                } else {
                    val errorMsg = "ZIP file not generated or project not found"
                    val errorBytes = errorMsg.toByteArray(Charsets.UTF_8)
                    val rHeaders = "HTTP/1.1 404 Not Found\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: ${errorBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(rHeaders.toByteArray(Charsets.UTF_8))
                    out.write(errorBytes)
                    out.flush()
                }
            } else {
                // Not Found
                val errorMsg = "Not Found"
                val errorBytes = errorMsg.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${errorBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(responseHeaders.toByteArray(Charsets.UTF_8))
                out.write(errorBytes)
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun getItemsCount(projectId: String): Int {
        return try {
            val db = com.example.data.AppDatabase.getDatabase(context)
            db.stockItemDao().getSyncItemsCountByProject(projectId)
        } catch (e: Exception) {
            0
        }
    }

    private fun readBodyAsString(input: java.io.InputStream, contentLength: Int): String {
        val bodyBos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var totalRead = 0
        while (totalRead < contentLength) {
            val toRead = Math.min(4096, contentLength - totalRead)
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            bodyBos.write(buffer, 0, read)
            totalRead += read
        }
        return String(bodyBos.toByteArray(), Charsets.UTF_8)
    }

    private fun buildItemsResponseJson(projectId: String): String {
        val project = kotlinx.coroutines.runBlocking {
            repository.getProjectById(projectId)
        }
        val items = kotlinx.coroutines.runBlocking {
            repository.getItemsByProjectSync(projectId)
        }

        val root = org.json.JSONObject()
        root.put("success", true)
        root.put("projectId", projectId)
        root.put("totalCount", items.size)
        root.put("checkedCount", items.count { it.shouldCheck })

        val headersArr = org.json.JSONArray()
        InventorySampling.parseJsonStringList(project?.columnHeadersJson).forEach { headersArr.put(it) }
        root.put("headers", headersArr)

        val categoriesArr = org.json.JSONArray()
        InventorySampling.categories(items).forEach { categoriesArr.put(it) }
        root.put("categories", categoriesArr)

        val methodsArr = org.json.JSONArray()
        InventorySampling.methods.forEach { method ->
            val methodObj = org.json.JSONObject()
            methodObj.put("id", method.id)
            methodObj.put("name", method.displayName)
            methodObj.put("requiresCount", method.requiresCount)
            methodObj.put("requiresRatio", method.requiresRatio)
            methodsArr.put(methodObj)
        }
        root.put("methods", methodsArr)

        val itemsArr = org.json.JSONArray()
        items.forEach { item ->
            val obj = org.json.JSONObject()
            obj.put("uid", item.uid)
            obj.put("name", item.name)
            obj.put("category", item.category)
            obj.put("location", item.location)
            obj.put("originalCode", item.originalCode)
            obj.put("shouldCheck", item.shouldCheck)
            obj.put("photoCount", item.photoCount)
            obj.put("rowOrder", item.rowOrder)
            obj.put("originalValue", InventorySampling.originalValue(item, project?.columnHeadersJson))
            obj.put("netValue", InventorySampling.netValue(item, project?.columnHeadersJson))
            obj.put("quantity", InventorySampling.quantity(item, project?.columnHeadersJson))

            val metadataArr = org.json.JSONArray()
            InventorySampling.metadataPairs(item, project?.columnHeadersJson).forEach { (label, value) ->
                val pairObj = org.json.JSONObject()
                pairObj.put("label", label)
                pairObj.put("value", value)
                metadataArr.put(pairObj)
            }
            obj.put("metadata", metadataArr)
            itemsArr.put(obj)
        }
        root.put("items", itemsArr)

        return root.toString()
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#27;")
    }

    private fun decodeJsonString(s: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                if (next == 'u' && i + 5 < s.length) {
                    try {
                        val hex = s.substring(i + 2, i + 6)
                        val charVal = hex.toInt(16).toChar()
                        result.append(charVal)
                        i += 6
                        continue
                    } catch (e: Exception) {
                        // ignore and fall through
                    }
                } else if (next == '\"' || next == '\\' || next == '/') {
                    result.append(next)
                    i += 2
                    continue
                } else if (next == 'n') {
                    result.append('\n')
                    i += 2
                    continue
                } else if (next == 'r') {
                    result.append('\r')
                    i += 2
                    continue
                } else if (next == 't') {
                    result.append('\t')
                    i += 2
                    continue
                }
            }
            result.append(c)
            i++
        }
        return result.toString()
    }

    private fun getHtmlPage(): String {
        val projects = kotlinx.coroutines.runBlocking {
            repository.listProjectsSync()
        }
        val optionsHtml = projects.joinToString("") { 
            "<option value='${it.id}'>${escapeHtml(it.name)}</option>"
        }

        val projectsListHtml = projects.joinToString("") {
            val baseDateText = it.baseDate.ifEmpty { "未设定" }
            val companyNameText = it.companyName.ifEmpty { "未设定" }
            val reportTypeText = it.reportType.ifEmpty { "未设定" }
            "<div class='project-item' id='item_${it.id}' onclick=\"selectProject('${it.id}')\">" +
                "<div class='proj-name'>${escapeHtml(it.name)}</div>" +
                "<div class='proj-meta'>日期: $baseDateText | 单位: $companyNameText</div>" +
                "<div style='margin-top: 8px; display: flex; justify-content: space-between; align-items: center;'>" +
                  "<span class='badge'>$reportTypeText</span>" +
                  "<button class='delete-btn' onclick=\"deleteProject(event, '${it.id}')\">删除</button>" +
                "</div>" +
            "</div>"
        }

        val projectsArray = org.json.JSONArray()
        projects.forEach { p ->
            val obj = org.json.JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("baseDate", p.baseDate)
            obj.put("companyName", p.companyName)
            obj.put("reportType", p.reportType)
            projectsArray.put(obj)
        }
        val knownProjectsJson = projectsArray.toString()

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>项目盘点工作台</title>
              <style>
                body {
                  background-color: #0b0f19;
                  color: #e2e8f0;
                  font-family: system-ui, -apple-system, sans-serif;
                  margin: 0;
                  padding: 0;
                  min-height: 100vh;
                  display: flex;
                  flex-direction: column;
                }
                header {
                  background-color: #111827;
                  border-bottom: 1px solid #1f2937;
                  padding: 16px 24px;
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                }
                header h1 {
                  font-size: 18px;
                  margin: 0;
                  font-weight: 700;
                  color: #38bdf8;
                  display: flex;
                  align-items: center;
                  gap: 8px;
                }
                .container {
                  display: flex;
                  flex: 1;
                  width: 100%;
                  box-sizing: border-box;
                }
                .sidebar {
                  width: 320px;
                  background-color: #111827;
                  border-right: 1px solid #1f2937;
                  display: flex;
                  flex-direction: column;
                  padding: 20px;
                  box-sizing: border-box;
                  gap: 16px;
                }
                .main-content {
                  flex: 1;
                  padding: 30px;
                  box-sizing: border-box;
                  overflow-y: auto;
                  max-width: 1180px;
                }
                .section-title {
                  font-size: 15px;
                  font-weight: bold;
                  text-transform: uppercase;
                  letter-spacing: 0.05em;
                  color: #94a3b8;
                  margin-bottom: 12px;
                  border-left: 3px solid #38bdf8;
                  padding-left: 8px;
                }
                .project-list {
                  display: flex;
                  flex-direction: column;
                  gap: 10px;
                  overflow-y: auto;
                  max-height: 320px;
                  padding-right: 4px;
                }
                .project-item {
                  background-color: #1e293b;
                  border: 1px solid #334155;
                  border-radius: 8px;
                  padding: 12px;
                  cursor: pointer;
                  transition: all 0.2s;
                  position: relative;
                }
                .project-item:hover {
                  border-color: #38bdf8;
                  background-color: #0f172a;
                }
                .project-item.active {
                  border-color: #10b981;
                  background-color: #022c22;
                  box-shadow: 0 0 10px rgba(16,185,129,0.15);
                }
                .proj-name {
                  font-weight: bold;
                  font-size: 14px;
                  color: #f1f5f9;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                }
                .proj-meta {
                  font-size: 11px;
                  color: #94a3b8;
                  margin-top: 4px;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                }
                .badge {
                  background-color: #0f172a;
                  color: #38bdf8;
                  font-size: 10px;
                  font-weight: 600;
                  padding: 2px 6px;
                  border-radius: 4px;
                  border: 1px solid #1e293b;
                }
                .delete-btn {
                  background-color: transparent;
                  color: #ef4444;
                  border: none;
                  font-size: 11px;
                  cursor: pointer;
                  padding: 2px 4px;
                  border-radius: 4px;
                  transition: background-color 0.2s;
                }
                .delete-btn:hover {
                  background-color: rgba(239, 68, 68, 0.1);
                  text-decoration: underline;
                }
                .btn {
                  background-color: #38bdf8;
                  color: #0f172a;
                  border: none;
                  border-radius: 6px;
                  padding: 10px 14px;
                  font-weight: 600;
                  font-size: 13px;
                  cursor: pointer;
                  transition: all 0.2s;
                  text-align: center;
                }
                .btn:hover {
                  background-color: #0ea5e9;
                  transform: translateY(-1px);
                }
                .btn-green {
                  background-color: #10b981;
                  color: white;
                }
                .btn-green:hover {
                  background-color: #059669;
                }
                .btn-amber {
                  background-color: #f59e0b;
                  color: white;
                }
                .btn-amber:hover {
                  background-color: #d97706;
                }
                .form-group {
                  display: flex;
                  flex-direction: column;
                  gap: 6px;
                }
                label {
                  font-size: 12px;
                  font-weight: 600;
                  color: #94a3b8;
                }
                input[type="text"], input[type="date"], select {
                  background-color: #1e293b;
                  color: #f1f5f9;
                  padding: 8px 12px;
                  border-radius: 6px;
                  border: 1px solid #334155;
                  font-size: 13px;
                  outline: none;
                  box-sizing: border-box;
                }
                input:focus, select:focus {
                  border-color: #38bdf8;
                }
                .radio-group {
                  display: flex;
                  gap: 12px;
                  align-items: center;
                }
                .radio-item {
                  display: inline-flex;
                  align-items: center;
                  gap: 4px;
                  font-size: 13px;
                  cursor: pointer;
                }
                .divider {
                  height: 1px;
                  background-color: #1f2937;
                  margin: 8px 0;
                }
                .card-main {
                  background-color: #111827;
                  border: 1px solid #1f2937;
                  border-radius: 12px;
                  padding: 24px;
                  display: flex;
                  flex-direction: column;
                  gap: 20px;
                }
                .upload-zone {
                  border: 2px dashed #38bdf8;
                  background-color: #0f172a;
                  border-radius: 12px;
                  padding: 30px;
                  text-align: center;
                  cursor: pointer;
                  transition: all 0.2s;
                }
                .upload-zone:hover {
                  background-color: #1e293b;
                  border-color: #10b981;
                }
                .upload-zone .icon {
                  font-size: 32px;
                  margin-bottom: 8px;
                }
                .upload-zone .title {
                  font-size: 14px;
                  font-weight: bold;
                  color: #f1f5f9;
                }
                .upload-zone .subtitle {
                  font-size: 12px;
                  color: #94a3b8;
                  margin-top: 4px;
                }
                .warning-box {
                  background-color: rgba(245, 158, 11, 0.1);
                  border: 1px solid rgba(245, 158, 11, 0.2);
                  color: #f59e0b;
                  border-radius: 8px;
                  padding: 12px;
                  font-size: 12px;
                  line-height: 1.5;
                  display: none;
                }
                .status-box {
                  padding: 12px;
                  border-radius: 8px;
                  font-size: 13px;
                  line-height: 1.5;
                  border: 1px solid transparent;
                  display: none;
                  margin-top: 10px;
                }
                .status-success {
                  background-color: rgba(16, 185, 129, 0.1);
                  border-color: rgba(16, 185, 129, 0.2);
                  color: #10b981;
                }
                .status-error {
                  background-color: rgba(239, 68, 68, 0.1);
                  border-color: rgba(239, 68, 68, 0.2);
                  color: #ef4444;
                }
                .template-box {
                  background-color: #1e293b;
                  border-radius: 8px;
                  padding: 12px;
                  font-size: 12px;
                  color: #94a3b8;
                  line-height: 1.5;
                }
                .ledger-toolbar {
                  display: grid;
                  grid-template-columns: repeat(4, minmax(150px, 1fr));
                  gap: 12px;
                  align-items: end;
                }
                .ledger-actions {
                  display: flex;
                  gap: 8px;
                  flex-wrap: wrap;
                  align-items: center;
                }
                .table-wrap {
                  overflow-x: auto;
                  border: 1px solid #1f2937;
                  border-radius: 8px;
                }
                table.ledger-table {
                  width: 100%;
                  min-width: 980px;
                  border-collapse: collapse;
                  font-size: 12px;
                }
                .ledger-table th, .ledger-table td {
                  border-bottom: 1px solid #1f2937;
                  padding: 8px 10px;
                  text-align: left;
                  vertical-align: top;
                }
                .ledger-table th {
                  color: #94a3b8;
                  background-color: #0f172a;
                  font-weight: 700;
                }
                .ledger-table tr:hover td {
                  background-color: rgba(56, 189, 248, 0.05);
                }
                .ledger-name {
                  font-weight: 700;
                  color: #f8fafc;
                  max-width: 220px;
                }
                .ledger-muted {
                  color: #94a3b8;
                }
                .ledger-summary {
                  white-space: pre-line;
                }
                @media (max-width: 980px) {
                  .container {
                    flex-direction: column;
                  }
                  .sidebar {
                    width: 100%;
                    border-right: none;
                    border-bottom: 1px solid #1f2937;
                  }
                  .ledger-toolbar {
                    grid-template-columns: 1fr;
                  }
                }
              </style>
            </head>
            <body>
              <header>
                <h1>项目盘点工作台</h1>
                <div style="font-size: 13px; color: #94a3b8;">
                  局域网联通机制 · 数据双向同步
                </div>
              </header>

              <div class="container">
                <!-- 左列：项目管理侧边栏 -->
                <div class="sidebar">
                  <div class="section-title">项目选择与管理</div>
                  <div class="project-list" id="projectList">
                    $projectsListHtml
                  </div>

                  <div class="divider"></div>

                  <div class="section-title">新建项目</div>
                  <div class="form-group">
                    <label>持有单位/被评估单位</label>
                    <input type="text" id="newProjCompany" placeholder="请输入单位或公司名称">
                  </div>
                  <div class="form-group">
                    <label>评估基准日</label>
                    <input type="date" id="newProjBaseDate">
                  </div>
                  <div class="form-group">
                    <label>报告类型</label>
                    <div class="radio-group" style="margin-top: 4px;">
                      <label class="radio-item">
                        <input type="radio" name="newProjReportType" value="评估报告" checked id="newRadioEval"> 评估项目
                      </label>
                      <label class="radio-item">
                        <input type="radio" name="newProjReportType" value="咨询报告" id="newRadioCons"> 咨询项目
                      </label>
                      <label class="radio-item">
                        <input type="radio" name="newProjReportType" value="自定义" id="newRadioCust" onchange="toggleNewCustomBox()"> 其他类型
                      </label>
                    </div>
                  </div>
                  <div id="newCustomTypeBox" style="display: none; margin-top: 4px; margin-bottom: 4px;">
                    <input type="text" id="newProjCustomType" placeholder="输入自定义类型名称" style="width: 100%;">
                  </div>
                  <button type="button" class="btn btn-amber" onclick="createProject()" style="width: 100%; margin-top: 10px;">立即新建项目</button>
                </div>

                <!-- 右列：主工作面板 -->
                <div class="main-content">
                  <!-- 参数编辑 (双向同步) -->
                  <div style="background-color: #111827; border-radius: 12px; padding: 24px; display: flex; flex-direction: column; gap: 16px; border: 1px solid #1f2937; margin-bottom: 24px;">
                    <div style="font-weight: bold; font-size: 15px; color: #38bdf8;">选定项目设置信息</div>
                    
                    <div class="form-group">
                      <label>当前目标项目分类</label>
                      <select id="projectSelect" onchange="onSelectDropdownChanged()" style="width: 100%; font-weight: bold; border-color: #38bdf8;">
                        $optionsHtml
                      </select>
                    </div>

                    <div class="form-group">
                      <label>持有单位 / 被评估单位</label>
                      <input type="text" id="companyName" placeholder="如：XX技术集团有限公司">
                    </div>

                    <div style="display: flex; gap: 16px; flex-wrap: wrap;">
                      <div class="form-group" style="flex: 1; min-width: 160px;">
                        <label>评估基准日</label>
                        <input type="date" id="baseDate">
                      </div>
                      <div class="form-group" style="flex: 1; min-width: 200px;">
                        <label>报告类型</label>
                        <div class="radio-group" style="margin-top: 8px;">
                          <label class="radio-item">
                            <input type="radio" name="reportType" value="评估报告" checked id="editRadioEval" onchange="toggleCustomReportType()"> 评估报告
                          </label>
                          <label class="radio-item">
                            <input type="radio" name="reportType" value="咨询报告" id="editRadioCons" onchange="toggleCustomReportType()"> 咨询报告
                          </label>
                          <label class="radio-item">
                            <input type="radio" name="reportType" value="自定义" id="reportTypeCustomRadio" onchange="toggleCustomReportType()"> 自定义
                          </label>
                        </div>
                      </div>
                    </div>

                    <div id="customReportTypeBox" style="display: none;">
                      <input type="text" id="customReportType" placeholder="输入自定义类型名称" style="width: 100%;">
                    </div>

                    <button type="button" class="btn btn-green" onclick="saveProjectMeta()" style="margin-top: 6px;">保存设置并同步至手机</button>
                    <div id="metaStatus" style="font-size: 12px; text-align: center; margin-top: 4px; font-weight: bold; display: none;"></div>
                  </div>

                  <!-- 导入与导出 -->
                  <div style="background-color: #111827; border-radius: 12px; padding: 24px; display: flex; flex-direction: column; gap: 16px; border: 1px solid #1f2937;">
                    <div style="font-weight: bold; font-size: 15px; color: #38bdf8;">资产清单导入与导出</div>

                    <!-- 文件下载与导出 -->
                    <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap;">
                      <a id="templateLink" href="/download-template?projectId=" class="btn" download style="text-decoration: none;">下载 Excel 盘点模板 (.xlsx)</a>
                      <button type="button" class="btn btn-amber" onclick="exportProjectZip()">导出盘点资料包</button>
                    </div>
                    <div id="exportStatus" class="status-box"></div>

                    <div class="divider"></div>

                    <!-- 导入模式 -->
                    <div class="form-group">
                      <label>导入模式</label>
                      <div class="radio-group" style="margin-top: 4px;">
                        <label class="radio-item">
                          <input type="radio" name="importMode" value="append" checked onchange="toggleWarning()"> 追加到当前项目
                        </label>
                        <label class="radio-item">
                          <input type="radio" name="importMode" value="replace" onchange="toggleWarning()"> 替换项目台账 
                        </label>
                      </div>
                    </div>

                    <div id="warningBox" class="warning-box">
                      提示：替换模式将清空选中项目的现有台账记录（包括照片及生成的报告），操作前请做好备份。
                    </div>

                    <!-- 拖拽载入 -->
                    <div class="form-group">
                      <label>载入资产台账列表</label>
                      <div class="upload-zone" id="dropzone" onclick="document.getElementById('fileInput').click()">
                        <div class="icon">文件</div>
                        <div class="title">点击或拖拽表格文件到这里</div>
                        <div class="subtitle">默认使用 Excel (.xlsx)，CSV (.csv) 仅作兼容导入</div>
                        <input type="file" id="fileInput" accept=".xlsx,.csv" style="display:none" onchange="performUpload(this.files[0])">
                      </div>
                    </div>

                    <div class="template-box">
                      <b>表格标准列须知：</b><br>
                      Excel 台账必须包含“设备名称”(Name/AssetName) 和“资产分类”(Category) 列，且每一条资产记录这两项均不得为空。<br>
                      建议同时提供“设备编号”(Code)、“存放位置”(Location)、“账面原值”和“是否盘点”(ShouldCheck) 等列；“是否盘点”设为“否”或“0”的资产进入台账预览，设为“是”或“1”的资产进入待盘点清单。
                    </div>

                    <div id="importStatus" class="status-box"></div>
                  </div>

                  <div style="background-color: #111827; border-radius: 12px; padding: 24px; display: flex; flex-direction: column; gap: 16px; border: 1px solid #1f2937; margin-top: 24px;">
                    <div style="display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: center;">
                      <div>
                        <div style="font-weight: bold; font-size: 15px; color: #38bdf8;">台账预览与分类抽样</div>
                        <div id="ledgerCountText" class="ledger-muted" style="font-size: 12px; margin-top: 4px;">正在读取当前项目台账。</div>
                      </div>
                      <div class="ledger-actions">
                        <button type="button" class="btn" onclick="loadLedger()">刷新台账</button>
                        <button type="button" class="btn btn-green" onclick="setAllChecks(true)">全选</button>
                        <button type="button" class="btn btn-amber" onclick="setAllChecks(false)">取消全选</button>
                      </div>
                    </div>

                    <div class="ledger-toolbar">
                      <div class="form-group">
                        <label>设备分类</label>
                        <select id="samplingCategory"></select>
                      </div>
                      <div class="form-group">
                        <label>抽样方式</label>
                        <select id="samplingMethod" onchange="onSamplingMethodChanged()"></select>
                      </div>
                      <div class="form-group" id="samplingCountBox">
                        <label>抽样数量</label>
                        <input type="text" id="samplingCount" value="10" placeholder="10 / 50 / 100 / 自定义">
                      </div>
                      <div class="form-group" id="samplingRatioBox" style="display: none;">
                        <label>目标占比（%）</label>
                        <input type="text" id="samplingRatio" value="70" placeholder="如：70">
                      </div>
                    </div>

                    <div class="ledger-actions">
                      <button type="button" class="btn btn-green" onclick="applySampling()">应用分类抽样</button>
                      <span class="ledger-muted" style="font-size: 12px;">抽样只替换所选分类内的待盘点状态，其他分类保持不变。</span>
                    </div>

                    <div id="samplingStatus" class="status-box ledger-summary"></div>
                    <div class="table-wrap">
                      <table class="ledger-table">
                        <thead>
                          <tr>
                            <th style="width: 70px;">盘点</th>
                            <th>设备编号</th>
                            <th>设备名称</th>
                            <th>资产分类</th>
                            <th>存放位置</th>
                            <th>账面原值</th>
                            <th>账面净值</th>
                            <th>数量</th>
                            <th>照片</th>
                          </tr>
                        </thead>
                        <tbody id="ledgerBody">
                          <tr><td colspan="9" class="ledger-muted">暂无台账数据。</td></tr>
                        </tbody>
                      </table>
                    </div>
                  </div>
                </div>
              </div>

              <script>
                let knownProjects = $knownProjectsJson;
                let ledgerData = { items: [], categories: [], methods: [] };

                function pollProjects() {
                  fetch('/api/projects')
                    .then(r => r.json())
                    .then(data => {
                      if (Array.isArray(data)) {
                        const localProjMap = JSON.stringify(knownProjects.map(p => p.id + ":" + p.name + ":" + p.baseDate + ":" + p.companyName));
                        const remoteProjMap = JSON.stringify(data.map(p => p.id + ":" + p.name + ":" + p.baseDate + ":" + p.companyName));
                        if (localProjMap !== remoteProjMap) {
                          window.location.reload();
                        }
                      }
                    })
                    .catch(e => {});
                }
                setInterval(pollProjects, 3500);

                function cnToHtmlDate(cnStr) {
                  if (!cnStr) return "";
                  const match = cnStr.match(/(\d+)年(\d+)月(\d+)日/);
                  if (match) {
                    const y = match[1];
                    const m = match[2].padStart(2, '0');
                    const d = match[3].padStart(2, '0');
                    return y + "-" + m + "-" + d;
                  }
                  const parts = cnStr.split('-');
                  if (parts.length === 3) return cnStr;
                  return "";
                }

                function htmlToCnDate(htmlStr) {
                  if (!htmlStr) return "";
                  const parts = htmlStr.split('-');
                  if (parts.length === 3) {
                    return parseInt(parts[0], 10) + "年" + parts[1] + "月" + parts[2] + "日";
                  }
                  return htmlStr;
                }

                function toggleNewCustomBox() {
                  const cust = document.getElementById('newRadioCust').checked;
                  document.getElementById('newCustomTypeBox').style.display = cust ? 'block' : 'none';
                }

                function toggleCustomReportType() {
                  const customRadio = document.getElementById('reportTypeCustomRadio');
                  const customBox = document.getElementById('customReportTypeBox');
                  if (customBox) {
                    customBox.style.display = (customRadio && customRadio.checked) ? 'block' : 'none';
                  }
                }

                function onSelectDropdownChanged() {
                  updateTemplateLink();
                  loadProjectMeta();
                  loadLedger();
                }

                function selectProject(id) {
                  const sel = document.getElementById('projectSelect');
                  if (sel) {
                    sel.value = id;
                    updateTemplateLink();
                  }
                  document.querySelectorAll('.project-item').forEach(el => el.classList.remove('active'));
                  const activeEl = document.getElementById('item_' + id);
                  if (activeEl) {
                    activeEl.classList.add('active');
                  }
                  loadProjectMeta();
                  loadLedger();
                }

                function createProject() {
                  const company = document.getElementById('newProjCompany').value.trim();
                  const baseDate = document.getElementById('newProjBaseDate').value;
                  if (!company || !baseDate) {
                    alert("请填写产权持有单位名称与评估基准日。");
                    return;
                  }

                  let reportType = "评估报告";
                  if (document.getElementById('newRadioCons').checked) {
                    reportType = "咨询报告";
                  } else if (document.getElementById('newRadioCust').checked) {
                    reportType = document.getElementById('newProjCustomType').value.trim() || "自定义报告";
                  }

                  const cnDate = htmlToCnDate(baseDate);
                  const dateDigits = baseDate.replace(/-/g, '');
                  const defaultProjName = company + "-" + dateDigits;

                  fetch('/api/project/add', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                      name: defaultProjName,
                      baseDate: cnDate,
                      companyName: company,
                      reportType: reportType
                    })
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (data.success) {
                      alert("项目 [" + data.name + "] 已创建并同步。");
                      window.location.reload();
                    } else {
                      alert("新建失败");
                    }
                  })
                  .catch(e => {
                    alert("网络传输超时，新建失败。");
                  });
                }

                function deleteProject(event, id) {
                  event.stopPropagation();
                  if (!confirm("确认删除该项目及其全部台账、照片和 PDF 文件？此操作不可撤销。")) {
                    return;
                  }

                  fetch('/api/project/delete', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ id: id })
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (data.success) {
                      alert("项目已删除。");
                      window.location.reload();
                    } else {
                      alert("删除失败");
                    }
                  })
                  .catch(e => {
                    alert("网络传输请求出错。");
                  });
                }

                function loadProjectMeta() {
                  const sel = document.getElementById('projectSelect');
                  if (!sel || !sel.value) return;
                  
                  fetch('/api/project?queryProjectId=' + encodeURIComponent(sel.value))
                    .then(r => r.json())
                    .then(data => {
                      if (data.id) {
                        document.getElementById('baseDate').value = cnToHtmlDate(data.baseDate || "");
                        document.getElementById('companyName').value = data.companyName || "";
                        
                        const rType = data.reportType || "评估报告";
                        if (rType === "评估报告" || rType === "咨询报告") {
                          const rad = document.querySelector('input[name="reportType"][value="' + rType + '"]');
                          if (rad) rad.checked = true;
                          document.getElementById('customReportTypeBox').style.display = 'none';
                          document.getElementById('customReportType').value = "";
                        } else {
                          document.getElementById('reportTypeCustomRadio').checked = true;
                          document.getElementById('customReportTypeBox').style.display = 'block';
                          document.getElementById('customReportType').value = rType;
                        }
                      }
                    });
                }

                function saveProjectMeta() {
                  const sel = document.getElementById('projectSelect');
                  if (!sel || !sel.value) {
                    alert("请先选择一个目标项目。");
                    return;
                  }
                  
                  const baseDateVal = document.getElementById('baseDate').value;
                  const companyName = document.getElementById('companyName').value.trim();
                  
                  if (!baseDateVal || !companyName) {
                    alert("请填写持有单位和评估基准日。");
                    return;
                  }

                  const baseDate = htmlToCnDate(baseDateVal);
                  
                  let reportType = "评估报告";
                  const checkedType = document.querySelector('input[name="reportType"]:checked');
                  if (checkedType) {
                    reportType = checkedType.value;
                  }
                  if (reportType === "自定义") {
                    reportType = document.getElementById('customReportType').value.trim() || "自定义报告";
                  }

                  const metaStatus = document.getElementById('metaStatus');
                  metaStatus.style.display = 'block';
                  metaStatus.style.color = '#38bdf8';
                  metaStatus.innerText = "正在保存项目参数...";

                  const dateDigits = baseDateVal.replace(/-/g, '');
                  const updatedName = companyName + "-" + dateDigits;

                  fetch('/api/project/update', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                      id: sel.value,
                      name: updatedName,
                      baseDate: baseDate,
                      companyName: companyName,
                      reportType: reportType
                    })
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (data.success) {
                      metaStatus.style.color = '#34d399';
                      metaStatus.innerText = "项目参数已保存，项目名称更新为：" + updatedName;
                      const actItem = document.getElementById('item_' + sel.value);
                      if (actItem) {
                        const nameEl = actItem.querySelector('.proj-name');
                        if (nameEl) nameEl.innerText = updatedName;
                        const metaEl = actItem.querySelector('.proj-meta');
                        if (metaEl) metaEl.innerText = "评估基准日：" + baseDate + " | 产权持有单位：" + companyName;
                      }
                      setTimeout(() => { metaStatus.style.display = 'none'; }, 3500);
                    } else {
                      metaStatus.style.color = '#fca5a5';
                      metaStatus.innerText = "保存失败，请检查连接状态。";
                    }
                  })
                  .catch(e => {
                    metaStatus.style.color = '#fca5a5';
                    metaStatus.innerText = "网络传输出错，请重试。";
                  });
                }

                function escapeHtmlText(value) {
                  return String(value || "")
                    .replace(/&/g, "&amp;")
                    .replace(/</g, "&lt;")
                    .replace(/>/g, "&gt;")
                    .replace(/"/g, "&quot;")
                    .replace(/'/g, "&#39;");
                }

                function formatNumber(value) {
                  const n = Number(value || 0);
                  if (!Number.isFinite(n) || n === 0) return "";
                  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
                }

                function loadLedger() {
                  const sel = document.getElementById('projectSelect');
                  if (!sel || !sel.value) return;
                  fetch('/api/items?projectId=' + encodeURIComponent(sel.value))
                    .then(r => r.json())
                    .then(data => {
                      ledgerData = data || { items: [], categories: [], methods: [] };
                      renderLedgerControls();
                      renderLedgerTable();
                    })
                    .catch(e => {
                      const countText = document.getElementById('ledgerCountText');
                      if (countText) countText.innerText = "台账读取失败，请确认局域网连接状态。";
                    });
                }

                function renderLedgerControls() {
                  const countText = document.getElementById('ledgerCountText');
                  if (countText) {
                    countText.innerText = "台账共 " + (ledgerData.totalCount || 0) + " 项，待盘点 " + (ledgerData.checkedCount || 0) + " 项。";
                  }

                  const categorySelect = document.getElementById('samplingCategory');
                  const previousCategory = categorySelect ? categorySelect.value : "";
                  if (categorySelect) {
                    categorySelect.innerHTML = "";
                    (ledgerData.categories || []).forEach(cat => {
                      const opt = document.createElement('option');
                      opt.value = cat;
                      opt.textContent = cat;
                      categorySelect.appendChild(opt);
                    });
                    if (previousCategory && (ledgerData.categories || []).indexOf(previousCategory) >= 0) {
                      categorySelect.value = previousCategory;
                    }
                  }

                  const methodSelect = document.getElementById('samplingMethod');
                  const previousMethod = methodSelect ? methodSelect.value : "";
                  if (methodSelect) {
                    methodSelect.innerHTML = "";
                    (ledgerData.methods || []).forEach(method => {
                      const opt = document.createElement('option');
                      opt.value = method.id;
                      opt.textContent = method.name;
                      opt.dataset.requiresCount = method.requiresCount ? "true" : "false";
                      opt.dataset.requiresRatio = method.requiresRatio ? "true" : "false";
                      methodSelect.appendChild(opt);
                    });
                    if (previousMethod && (ledgerData.methods || []).some(m => m.id === previousMethod)) {
                      methodSelect.value = previousMethod;
                    }
                  }
                  onSamplingMethodChanged();
                }

                function renderLedgerTable() {
                  const body = document.getElementById('ledgerBody');
                  if (!body) return;
                  const items = ledgerData.items || [];
                  if (items.length === 0) {
                    body.innerHTML = "<tr><td colspan='9' class='ledger-muted'>暂无台账数据。请先导入 Excel 台账。</td></tr>";
                    return;
                  }
                  body.innerHTML = items.map(item => {
                    const checked = item.shouldCheck ? "checked" : "";
                    return "<tr>" +
                      "<td><input type='checkbox' " + checked + " onchange=\"setItemCheck('" + escapeHtmlText(item.uid) + "', this.checked)\"></td>" +
                      "<td>" + escapeHtmlText(item.originalCode) + "</td>" +
                      "<td class='ledger-name'>" + escapeHtmlText(item.name) + "</td>" +
                      "<td>" + escapeHtmlText(item.category) + "</td>" +
                      "<td>" + escapeHtmlText(item.location) + "</td>" +
                      "<td>" + escapeHtmlText(formatNumber(item.originalValue)) + "</td>" +
                      "<td>" + escapeHtmlText(formatNumber(item.netValue)) + "</td>" +
                      "<td>" + escapeHtmlText(formatNumber(item.quantity)) + "</td>" +
                      "<td>" + Number(item.photoCount || 0) + "</td>" +
                      "</tr>";
                  }).join("");
                }

                function onSamplingMethodChanged() {
                  const methodSelect = document.getElementById('samplingMethod');
                  const opt = methodSelect && methodSelect.options[methodSelect.selectedIndex];
                  const requiresCount = opt ? opt.dataset.requiresCount === "true" : true;
                  const requiresRatio = opt ? opt.dataset.requiresRatio === "true" : false;
                  document.getElementById('samplingCountBox').style.display = requiresCount ? 'flex' : 'none';
                  document.getElementById('samplingRatioBox').style.display = requiresRatio ? 'flex' : 'none';
                }

                function setItemCheck(uid, shouldCheck) {
                  fetch('/api/items/check', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ uid: uid, shouldCheck: shouldCheck })
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (!data.success) alert("更新盘点状态失败。");
                    loadLedger();
                  })
                  .catch(e => alert("网络传输失败，盘点状态未更新。"));
                }

                function setAllChecks(shouldCheck) {
                  const sel = document.getElementById('projectSelect');
                  if (!sel || !sel.value) return;
                  fetch('/api/items/select-all', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ projectId: sel.value, shouldCheck: shouldCheck })
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (!data.success) alert("批量更新失败。");
                    loadLedger();
                  })
                  .catch(e => alert("网络传输失败，批量更新未完成。"));
                }

                function applySampling() {
                  const sel = document.getElementById('projectSelect');
                  const category = document.getElementById('samplingCategory').value;
                  const methodId = document.getElementById('samplingMethod').value;
                  const count = parseInt(document.getElementById('samplingCount').value || "0", 10);
                  const ratio = parseFloat(document.getElementById('samplingRatio').value || "0");
                  const status = document.getElementById('samplingStatus');
                  if (!sel || !sel.value || !category) {
                    alert("请先选择项目和设备分类。");
                    return;
                  }
                  status.className = 'status-box active';
                  status.style.display = 'block';
                  status.style.backgroundColor = '#1e3a5f';
                  status.style.color = '#60a5fa';
                  status.style.borderColor = '#2563eb';
                  status.innerText = "正在执行分类抽样。";

                  fetch('/api/items/sample', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                      projectId: sel.value,
                      category: category,
                      method: methodId,
                      count: Number.isFinite(count) ? count : 0,
                      ratio: Number.isFinite(ratio) ? ratio : 0
                    })
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (data.success) {
                      status.className = 'status-box active status-success ledger-summary';
                      status.style.display = 'block';
                      status.innerText = data.summary || "分类抽样已完成。";
                      loadLedger();
                    } else {
                      status.className = 'status-box active status-error';
                      status.style.display = 'block';
                      status.innerText = "抽样失败: " + (data.error || "未知错误");
                    }
                  })
                  .catch(e => {
                    status.className = 'status-box active status-error';
                    status.style.display = 'block';
                    status.innerText = "网络传输失败，抽样未完成。";
                  });
                }

                function exportProjectZip() {
                  const sel = document.getElementById('projectSelect');
                  if (!sel || !sel.value) {
                    alert("请先选择一个目标项目。");
                    return;
                  }
                  
                  const status = document.getElementById('exportStatus');
                  status.className = 'status-box active';
                  status.style.backgroundColor = '#1e3a5f';
                  status.style.color = '#60a5fa';
                  status.style.borderColor = '#2563eb';
                  status.style.display = 'block';
                  status.innerText = "正在生成项目资料包，请稍候。";

                  fetch('/api/prepare-zip?projectId=' + encodeURIComponent(sel.value))
                    .then(r => r.json())
                    .then(data => {
                      if (data.success) {
                        status.className = 'status-box active status-success';
                        status.style.display = 'block';
                        status.innerHTML = "项目资料包已生成。(大小: " + (data.size / 1024 / 1024).toFixed(2) + " MB)<br>" +
                                           "<a href='/download-zip?projectId=" + encodeURIComponent(sel.value) + "' style='display: inline-block; margin-top: 12px; background-color: #10b981; color: white; padding: 10px 20px; border-radius: 6px; text-decoration: none; font-weight: bold; border: 1px solid #059669; transition: background-color 0.2s;'>下载项目资料包（含分类盘点表及资产记录 PDF）</a>";
                      } else {
                        status.className = 'status-box active status-error';
                        status.style.display = 'block';
                        status.innerText = "导出失败: " + data.error;
                      }
                    })
                    .catch(e => {
                      status.className = 'status-box active status-error';
                      status.style.display = 'block';
                      status.innerText = "局域网连接超时，项目资料包生成失败。";
                    });
                }

                function updateTemplateLink() {
                  const sel = document.getElementById('projectSelect');
                  const link = document.getElementById('templateLink');
                  if(sel) {
                    link.href = '/download-template?projectId=' + sel.value;
                  }
                  loadProjectMeta();
                }

                function toggleWarning() {
                  const warning = document.getElementById('warningBox');
                  const val = document.querySelector('input[name="importMode"]:checked').value;
                  warning.style.display = (val === 'replace') ? 'block' : 'none';
                }

                const zone = document.getElementById('dropzone');
                zone.ondragover = (e) => { e.preventDefault(); zone.style.borderColor = '#10b981'; };
                zone.ondragleave = () => { zone.style.borderColor = '#38bdf8'; };
                zone.ondrop = (e) => {
                  e.preventDefault();
                  zone.style.borderColor = '#38bdf8';
                  if (e.dataTransfer.files.length > 0) {
                    performUpload(e.dataTransfer.files[0]);
                  }
                };

                function performUpload(file) {
                  if (!file) return;
                  const sel = document.getElementById('projectSelect');
                  if (!sel || !sel.value) {
                    alert("请先选择或新建一个项目。");
                    return;
                  }
                  
                  const mode = document.querySelector('input[name="importMode"]:checked').value;
                  const status = document.getElementById('importStatus');
                  status.className = 'status-box active';
                  status.style.backgroundColor = '#1e3a5f';
                  status.style.color = '#60a5fa';
                  status.style.borderColor = '#2563eb';
                  status.style.display = 'block';
                  status.innerText = "正在上传并解析 [" + file.name + "]，请稍候。";

                  fetch('/upload?projectId=' + encodeURIComponent(sel.value) + '&mode=' + mode + '&filename=' + encodeURIComponent(file.name), {
                    method: 'POST',
                    body: file
                  })
                  .then(r => r.json())
                  .then(data => {
                    if (data.success) {
                      status.className = 'status-box active status-success';
                      const modeText = mode === 'replace' ? '替换' : '追加';
                      status.innerText = "资产台账已导入，" + modeText + " " + data.count + " 条资产记录。手机端盘点列表已同步更新。";
                      loadLedger();
                    } else {
                      status.className = 'status-box active status-error';
                      status.innerText = "导入失败: " + data.error;
                    }
                  })
                  .catch(e => {
                    status.className = 'status-box active status-error';
                    status.innerText = "网络传输失败，请确认手机与电脑处于同一局域网。";
                  });
                }

                if (document.getElementById('projectSelect') && document.getElementById('projectSelect').value) {
                  selectProject(document.getElementById('projectSelect').value);
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
