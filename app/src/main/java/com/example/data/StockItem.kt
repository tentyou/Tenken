package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "stock_items")
data class StockItem(
    @PrimaryKey
    val uid: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "",
    val location: String = "",
    val originalCode: String = "",
    val photoCount: Int = 0,
    val pdfStatus: String = "未生成", // "未生成" / "已生成"
    val projectId: String = "default_project",
    val shouldCheck: Boolean = true,
    val originalRowJson: String = "",
    val rowOrder: Int = 0
)

