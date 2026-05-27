package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodLogDao {
    @Query("SELECT * FROM food_log_entries ORDER BY date DESC, id DESC")
    fun getAllEntries(): Flow<List<FoodLogEntry>>

    @Query("SELECT * FROM food_log_entries ORDER BY date DESC, id DESC")
    suspend fun getAllEntriesDirect(): List<FoodLogEntry>

    @Query("SELECT * FROM food_log_entries WHERE date = :dateString")
    fun getEntriesForDate(dateString: String): Flow<List<FoodLogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: FoodLogEntry)

    @Delete
    suspend fun deleteEntry(entry: FoodLogEntry)

    @Query("DELETE FROM food_log_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Int)

    @Query("DELETE FROM food_log_entries WHERE date = :dateString")
    suspend fun deleteEntriesForDate(dateString: String)
}
