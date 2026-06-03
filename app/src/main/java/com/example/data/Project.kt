package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseDate: String = "",
    val companyName: String = "",
    val reportType: String = InventoryConstants.REPORT_TYPE_EVALUATION,
    val columnHeadersJson: String = "",
    val watermarkEnabled: Boolean = false,
    val watermarkBlEnabled: Boolean = true,
    val watermarkBlShowDate: Boolean = true,
    val watermarkBlShowTime: Boolean = true,
    val watermarkBlShowGps: Boolean = true,
    val watermarkBlShowAddress: Boolean = true,
    val watermarkTrEnabled: Boolean = true
)
