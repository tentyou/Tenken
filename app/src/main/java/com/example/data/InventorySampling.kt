package com.example.data

import java.text.DecimalFormat
import kotlin.random.Random

private fun formatSamplingPercent(numerator: Double, denominator: Double): String {
    if (denominator <= 0.0) return "0.00%"
    return DecimalFormat("0.00%").format(numerator / denominator)
}

enum class SamplingMethod(
    val id: String,
    val displayName: String,
    val requiresCount: Boolean,
    val requiresRatio: Boolean
) {
    ORIGINAL_VALUE_TOP_N("original_top_n", "账面原值前 N 项", true, false),
    NET_VALUE_TOP_N("net_top_n", "账面净值前 N 项", true, false),
    QUANTITY_TOP_N("quantity_top_n", "数量前 N 项", true, false),
    RANDOM_N("random_n", "随机抽样 N 项", true, false),
    ORIGINAL_VALUE_COVERAGE("original_coverage", "累计原值占比抽样", false, true),
    NET_VALUE_COVERAGE("net_coverage", "累计净值占比抽样", false, true);

    companion object {
        fun fromId(id: String?): SamplingMethod {
            return values().firstOrNull { it.id == id } ?: ORIGINAL_VALUE_TOP_N
        }
    }
}

data class SamplingResult(
    val method: SamplingMethod,
    val category: String,
    val selectedUids: Set<String>,
    val selectedCount: Int,
    val categoryCount: Int,
    val selectedOriginalValue: Double,
    val totalOriginalValue: Double,
    val selectedNetValue: Double,
    val totalNetValue: Double,
    val selectedQuantity: Double,
    val totalQuantity: Double
) {
    fun summaryText(): String {
        val amountFormat = DecimalFormat("#,##0.##")
        val lines = mutableListOf<String>()
        lines.add("抽样分类：$category")
        lines.add("抽样方式：${method.displayName}")
        lines.add("已抽取 $selectedCount 项资产纳入盘点。")
        lines.add("资产数量占比：$selectedCount / $categoryCount，占比 ${formatSamplingPercent(selectedCount.toDouble(), categoryCount.toDouble())}。")
        lines.add(metricLine("账面原值", selectedOriginalValue, totalOriginalValue, amountFormat))
        lines.add(metricLine("账面净值", selectedNetValue, totalNetValue, amountFormat))
        lines.add(metricLine("数量", selectedQuantity, totalQuantity, amountFormat))
        return lines.joinToString("\n")
    }

    private fun metricLine(label: String, selected: Double, total: Double, amountFormat: DecimalFormat): String {
        return if (total > 0.0) {
            "$label：${amountFormat.format(selected)} / ${amountFormat.format(total)}，占比 ${formatSamplingPercent(selected, total)}。"
        } else {
            "$label：未识别到有效数据。"
        }
    }
}

object InventorySampling {
    val methods: List<SamplingMethod> = SamplingMethod.values().toList()

    fun parseJsonStringList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json).map { match ->
                match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun categories(items: List<StockItem>): List<String> {
        return items.map { it.category.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun metadataPairs(item: StockItem, columnHeadersJson: String?): List<Pair<String, String>> {
        val headers = parseJsonStringList(columnHeadersJson)
        val values = parseJsonStringList(item.originalRowJson)
        val pairs = mutableListOf<Pair<String, String>>()

        headers.zip(values).forEach { (label, value) ->
            val cleanLabel = label.trim()
            val cleanValue = value.trim()
            if (cleanLabel.isNotBlank() && cleanValue.isNotBlank()) {
                pairs.add(cleanLabel to cleanValue)
            }
        }

        fun hasLabelMatching(predicate: (String) -> Boolean): Boolean {
            return pairs.any { (label, _) -> predicate(label.trim().lowercase()) }
        }

        fun addFallback(label: String, value: String, predicate: (String) -> Boolean) {
            val cleanValue = value.trim()
            if (cleanValue.isNotBlank() && !hasLabelMatching(predicate)) {
                pairs.add(label to cleanValue)
            }
        }

        addFallback("设备编号", item.originalCode) { it.contains("编号") || it.contains("code") }
        addFallback("设备名称", item.name) { it.contains("名称") || it.contains("name") }
        addFallback("资产分类", item.category) { it.contains("分类") || it.contains("类别") || it.contains("category") }
        addFallback("存放位置", item.location) { it.contains("位置") || it.contains("地点") || it.contains("location") }
        addFallback("设备 UID", item.uid) { it == "uid" || it == "uuid" || it.contains("唯一") }

        return pairs
    }

    fun originalValue(item: StockItem, columnHeadersJson: String?): Double {
        return metricValue(item, columnHeadersJson, Metric.ORIGINAL_VALUE)
    }

    fun netValue(item: StockItem, columnHeadersJson: String?): Double {
        return metricValue(item, columnHeadersJson, Metric.NET_VALUE)
    }

    fun quantity(item: StockItem, columnHeadersJson: String?): Double {
        return metricValue(item, columnHeadersJson, Metric.QUANTITY)
    }

    fun sample(
        allItems: List<StockItem>,
        columnHeadersJson: String?,
        category: String,
        method: SamplingMethod,
        requestedCount: Int,
        targetRatioPercent: Double
    ): SamplingResult {
        val categoryItems = allItems.filter { it.category.trim() == category.trim() }
        val selectedItems = when (method) {
            SamplingMethod.ORIGINAL_VALUE_TOP_N -> topN(categoryItems, columnHeadersJson, requestedCount, Metric.ORIGINAL_VALUE)
            SamplingMethod.NET_VALUE_TOP_N -> topN(categoryItems, columnHeadersJson, requestedCount, Metric.NET_VALUE)
            SamplingMethod.QUANTITY_TOP_N -> topN(categoryItems, columnHeadersJson, requestedCount, Metric.QUANTITY)
            SamplingMethod.RANDOM_N -> categoryItems.shuffled(Random(System.currentTimeMillis())).take(requestedCount.coerceIn(0, categoryItems.size))
            SamplingMethod.ORIGINAL_VALUE_COVERAGE -> coverage(categoryItems, columnHeadersJson, targetRatioPercent, Metric.ORIGINAL_VALUE)
            SamplingMethod.NET_VALUE_COVERAGE -> coverage(categoryItems, columnHeadersJson, targetRatioPercent, Metric.NET_VALUE)
        }
        val selectedUids = selectedItems.map { it.uid }.toSet()

        return SamplingResult(
            method = method,
            category = category,
            selectedUids = selectedUids,
            selectedCount = selectedItems.size,
            categoryCount = categoryItems.size,
            selectedOriginalValue = selectedItems.sumOf { originalValue(it, columnHeadersJson) },
            totalOriginalValue = categoryItems.sumOf { originalValue(it, columnHeadersJson) },
            selectedNetValue = selectedItems.sumOf { netValue(it, columnHeadersJson) },
            totalNetValue = categoryItems.sumOf { netValue(it, columnHeadersJson) },
            selectedQuantity = selectedItems.sumOf { quantity(it, columnHeadersJson) },
            totalQuantity = categoryItems.sumOf { quantity(it, columnHeadersJson) }
        )
    }

    fun applyResultToSelectedCategory(allItems: List<StockItem>, result: SamplingResult): List<StockItem> {
        return allItems.map { item ->
            if (item.category.trim() == result.category.trim()) {
                item.copy(shouldCheck = item.uid in result.selectedUids)
            } else {
                item
            }
        }
    }

    private fun topN(items: List<StockItem>, columnHeadersJson: String?, requestedCount: Int, metric: Metric): List<StockItem> {
        if (requestedCount <= 0) return emptyList()
        return items.sortedWith(
            compareByDescending<StockItem> { metricValue(it, columnHeadersJson, metric) }
                .thenBy { it.rowOrder }
                .thenBy { it.uid }
        ).take(requestedCount.coerceAtMost(items.size))
    }

    private fun coverage(items: List<StockItem>, columnHeadersJson: String?, targetRatioPercent: Double, metric: Metric): List<StockItem> {
        val targetRatio = (targetRatioPercent / 100.0).coerceIn(0.0, 1.0)
        if (targetRatio <= 0.0) return emptyList()
        val total = items.sumOf { metricValue(it, columnHeadersJson, metric) }
        if (total <= 0.0) return emptyList()

        val selected = mutableListOf<StockItem>()
        var runningTotal = 0.0
        for (item in topN(items, columnHeadersJson, items.size, metric)) {
            selected.add(item)
            runningTotal += metricValue(item, columnHeadersJson, metric)
            if (runningTotal / total >= targetRatio) break
        }
        return selected
    }

    private fun metricValue(item: StockItem, columnHeadersJson: String?, metric: Metric): Double {
        val headers = parseJsonStringList(columnHeadersJson)
        val values = parseJsonStringList(item.originalRowJson)
        val candidate = headers.zip(values).firstOrNull { (label, _) ->
            metric.matches(label)
        }?.second ?: return 0.0
        return parseNumber(candidate)
    }

    private fun parseNumber(rawValue: String): Double {
        val normalizedNumber = rawValue
            .replace(",", "")
            .replace("，", "")
            .replace("￥", "")
            .replace("元", "")
            .trim()
        return Regex("-?\\d+(?:\\.\\d+)?")
            .find(normalizedNumber)
            ?.value
            ?.toDoubleOrNull() ?: 0.0
    }

    private enum class Metric {
        ORIGINAL_VALUE,
        NET_VALUE,
        QUANTITY;

        fun matches(label: String): Boolean {
            val normalized = label.trim().lowercase()
            return when (this) {
                ORIGINAL_VALUE -> normalized.contains("账面原值") ||
                    normalized.contains("设备原值") ||
                    normalized.contains("资产原值") ||
                    normalized == "原值" ||
                    normalized.contains("original value") ||
                    normalized.contains("originalvalue")
                NET_VALUE -> normalized.contains("账面净值") ||
                    normalized.contains("设备净值") ||
                    normalized.contains("资产净值") ||
                    normalized == "净值" ||
                    normalized.contains("net value") ||
                    normalized.contains("netvalue")
                QUANTITY -> normalized == "数量" ||
                    normalized == "台数" ||
                    normalized == "件数" ||
                    normalized.contains("quantity") ||
                    normalized == "qty"
            }
        }
    }
}
