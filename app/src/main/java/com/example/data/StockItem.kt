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
    val pdfStatus: String = InventoryConstants.PDF_STATUS_PENDING,
    val projectId: String = InventoryConstants.DEFAULT_PROJECT_ID,
    val shouldCheck: Boolean = true,
    val originalRowJson: String = "",
    val rowOrder: Int = 0
)
