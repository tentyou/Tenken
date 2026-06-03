package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StockItemDao {
    @Query("SELECT * FROM stock_items ORDER BY rowOrder ASC, uid ASC")
    fun getAllItems(): Flow<List<StockItem>>

    @Query("SELECT * FROM stock_items")
    suspend fun getAllItemsSync(): List<StockItem>

    @Query("SELECT * FROM stock_items WHERE projectId = :projectId ORDER BY rowOrder ASC, uid ASC")
    fun getAllItemsByProject(projectId: String): Flow<List<StockItem>>

    @Query("SELECT * FROM stock_items WHERE projectId = :projectId ORDER BY rowOrder ASC, uid ASC")
    suspend fun getItemsByProjectSync(projectId: String): List<StockItem>

    @Query("SELECT * FROM stock_items WHERE uid = :uid LIMIT 1")
    suspend fun getItemByUid(uid: String): StockItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: StockItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<StockItem>)

    @Update
    suspend fun updateItem(item: StockItem)

    @Query("UPDATE stock_items SET photoCount = :photoCount, pdfStatus = :pdfStatus WHERE uid = :uid")
    suspend fun updatePhotoState(uid: String, photoCount: Int, pdfStatus: String)

    @Delete
    suspend fun deleteItem(item: StockItem)

    @Query("DELETE FROM stock_items")
    suspend fun deleteAll()

    @Query("DELETE FROM stock_items WHERE projectId = :projectId")
    suspend fun deleteItemsByProject(projectId: String)

    @Query("SELECT COUNT(*) FROM stock_items")
    fun getSyncItemsCount(): Int

    @Query("SELECT COUNT(*) FROM stock_items WHERE projectId = :projectId")
    fun getSyncItemsCountByProject(projectId: String): Int
}

