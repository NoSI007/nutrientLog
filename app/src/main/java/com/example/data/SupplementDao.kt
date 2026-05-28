package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplementDao {
    @Query("SELECT * FROM supplements ORDER BY name ASC")
    fun getAllSupplements(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements ORDER BY name ASC")
    suspend fun getAllSupplementsDirect(): List<Supplement>

    @Query("SELECT * FROM supplements WHERE id = :id")
    suspend fun getSupplementById(id: Int): Supplement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupplement(supplement: Supplement)

    @Delete
    suspend fun deleteSupplement(supplement: Supplement)

    @Query("DELETE FROM supplements WHERE id = :id")
    suspend fun deleteSupplementById(id: Int)
}
