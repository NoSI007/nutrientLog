package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object (DAO) for managing local USDA API cache records.
 */
@Dao
interface UsdaCacheDao {
    @Query("SELECT * FROM usda_cache WHERE cacheKey = :key")
    suspend fun getCacheByKey(key: String): UsdaCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(cache: UsdaCacheEntity)

    @Query("DELETE FROM usda_cache WHERE cacheKey = :key")
    suspend fun deleteCacheByKey(key: String)

    @Query("DELETE FROM usda_cache WHERE timestamp < :expirationTime")
    suspend fun clearExpiredCache(expirationTime: Long)

    @Query("DELETE FROM usda_cache")
    suspend fun clearAllCache()
}
