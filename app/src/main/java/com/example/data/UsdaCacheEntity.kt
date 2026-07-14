package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for caching raw USDA API query responses and food details locally.
 * Improves search latency and reduces external API rate-limiting/token usage.
 */
@Entity(tableName = "usda_cache")
data class UsdaCacheEntity(
    @PrimaryKey val cacheKey: String, // format: "search:<query>" or "detail:<fdcId>"
    val cachedResponseJson: String,  // Serialized JSON array of results or details profile
    val timestamp: Long = System.currentTimeMillis()
)
