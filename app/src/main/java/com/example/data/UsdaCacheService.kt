package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service layer coordinating cached retrieval of USDA food search queries and food profiles.
 * Minimizes unnecessary network calls, improves UI snappiness, and safeguards against API rate limits.
 */
class UsdaCacheService(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val cacheDao = database.usdaCacheDao()

    // 24 hours of cache lifetime (86400000 ms) before fetching updated profiles
    private val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L

    /**
     * Searches USDA FoodData Central with database-level caching.
     * Checks the cache first. If a fresh entry is present, it returns it;
     * otherwise, performs the network query, saves the results, and returns.
     */
    suspend fun searchFoods(query: String, customApiKey: String? = null): List<OnlineFoodResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val cacheKey = "search:$cleanQuery"
        try {
            val cachedEntry = cacheDao.getCacheByKey(cacheKey)
            if (cachedEntry != null) {
                if (System.currentTimeMillis() - cachedEntry.timestamp < CACHE_EXPIRATION_MS) {
                    Log.i("UsdaCacheService", "USDA search cache HIT for query: '$cleanQuery'")
                    val results = deserializeSearchResults(cachedEntry.cachedResponseJson)
                    if (results.isNotEmpty()) {
                        return@withContext results
                    }
                } else {
                    Log.i("UsdaCacheService", "USDA search cache EXPIRED for query: '$cleanQuery'. Invalidating...")
                    cacheDao.deleteCacheByKey(cacheKey)
                }
            }
        } catch (e: Exception) {
            Log.e("UsdaCacheService", "Failed to retrieve cached search results for '$cleanQuery'", e)
        }

        Log.i("UsdaCacheService", "USDA search cache MISS for query: '$cleanQuery'. Querying FoodData Central API.")
        val results = NutritionApiIntegration.searchUsda(query, customApiKey)

        if (results.isNotEmpty()) {
            try {
                val jsonString = serializeSearchResults(results)
                cacheDao.insertCache(
                    UsdaCacheEntity(
                        cacheKey = cacheKey,
                        cachedResponseJson = jsonString,
                        timestamp = System.currentTimeMillis()
                    )
                )
                Log.i("UsdaCacheService", "Cached ${results.size} online results for query: '$cleanQuery'")
            } catch (e: Exception) {
                Log.e("UsdaCacheService", "Failed to persist USDA search query cache for '$cleanQuery'", e)
            }
        }

        return@withContext results
    }

    /**
     * Obtains a detailed USDA Nutrient Profile by FDC ID with caching support.
     * Returns the cached profile if fresh, otherwise fetches detail from USDA API and saves it.
     */
    suspend fun getFoodDetails(fdcId: String): UsdaNutrientProfile? = withContext(Dispatchers.IO) {
        if (fdcId.isBlank()) return@withContext null

        val cacheKey = "detail:$fdcId"
        try {
            val cachedEntry = cacheDao.getCacheByKey(cacheKey)
            if (cachedEntry != null) {
                if (System.currentTimeMillis() - cachedEntry.timestamp < CACHE_EXPIRATION_MS) {
                    Log.i("UsdaCacheService", "USDA detail cache HIT for FDC ID: $fdcId")
                    val profile = deserializeDetailProfile(cachedEntry.cachedResponseJson)
                    if (profile != null) {
                        return@withContext profile
                    }
                } else {
                    Log.i("UsdaCacheService", "USDA detail cache EXPIRED for FDC ID: $fdcId. Invalidating...")
                    cacheDao.deleteCacheByKey(cacheKey)
                }
            }
        } catch (e: Exception) {
            Log.e("UsdaCacheService", "Failed to retrieve cached detail profile for FDC ID $fdcId", e)
        }

        Log.i("UsdaCacheService", "USDA detail cache MISS for FDC ID: $fdcId. Querying FoodData Central API.")
        val profile = UsdaNutritionHelper.getFoodDetailsProfile(fdcId)

        if (profile != null) {
            try {
                val jsonString = serializeDetailProfile(profile)
                cacheDao.insertCache(
                    UsdaCacheEntity(
                        cacheKey = cacheKey,
                        cachedResponseJson = jsonString,
                        timestamp = System.currentTimeMillis()
                    )
                )
                Log.i("UsdaCacheService", "Cached detailed profile for FDC ID: $fdcId")
            } catch (e: Exception) {
                Log.e("UsdaCacheService", "Failed to persist USDA food detail cache for FDC ID $fdcId", e)
            }
        }

        return@withContext profile
    }

    /**
     * Clears all cached USDA entries.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            cacheDao.clearAllCache()
            Log.i("UsdaCacheService", "Successfully evicted all cached USDA search and detail records.")
        } catch (e: Exception) {
            Log.e("UsdaCacheService", "Failed to clear USDA cache database.", e)
        }
    }

    /**
     * Clears all expired cached entries older than 24 hours.
     */
    suspend fun clearExpiredCache() = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - CACHE_EXPIRATION_MS
            cacheDao.clearExpiredCache(cutoff)
            Log.i("UsdaCacheService", "Successfully swept expired USDA cache records.")
        } catch (e: Exception) {
            Log.e("UsdaCacheService", "Failed to sweep expired USDA cache records.", e)
        }
    }

    private fun serializeSearchResults(results: List<OnlineFoodResult>): String {
        val array = JSONArray()
        for (item in results) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("brandName", item.brandName ?: JSONObject.NULL)
            obj.put("servingSize", item.servingSize)
            obj.put("servingSizeUnit", item.servingSizeUnit)
            obj.put("source", item.source)

            val nutrientsObj = JSONObject()
            for ((key, value) in item.nutrients) {
                nutrientsObj.put(key, value)
            }
            obj.put("nutrients", nutrientsObj)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeSearchResults(jsonString: String): List<OnlineFoodResult> {
        val results = mutableListOf<OnlineFoodResult>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val brandName = if (obj.isNull("brandName")) null else obj.getString("brandName")
                val servingSize = obj.getDouble("servingSize")
                val servingSizeUnit = obj.getString("servingSizeUnit")
                val source = obj.getString("source")

                val nutrientsObj = obj.getJSONObject("nutrients")
                val nutrientsMap = mutableMapOf<String, Double>()
                val keys = nutrientsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    nutrientsMap[key] = nutrientsObj.getDouble(key)
                }

                results.add(
                    OnlineFoodResult(
                        id = id,
                        name = name,
                        brandName = brandName,
                        servingSize = servingSize,
                        servingSizeUnit = servingSizeUnit,
                        source = source,
                        nutrients = nutrientsMap
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("UsdaCacheService", "Serialization Error: Corrupted search result payload in database cache.", e)
        }
        return results
    }

    private fun serializeDetailProfile(profile: UsdaNutrientProfile): String {
        val obj = JSONObject()
        obj.put("fdcId", profile.fdcId)
        obj.put("description", profile.description)
        obj.put("brandOwner", profile.brandOwner ?: JSONObject.NULL)
        obj.put("servingSize", profile.servingSize)
        obj.put("servingSizeUnit", profile.servingSizeUnit)

        val nutrientsObj = JSONObject()
        for ((key, value) in profile.nutrients) {
            nutrientsObj.put(key, value)
        }
        obj.put("nutrients", nutrientsObj)
        return obj.toString()
    }

    private fun deserializeDetailProfile(jsonString: String): UsdaNutrientProfile? {
        return try {
            val obj = JSONObject(jsonString)
            val fdcId = obj.getString("fdcId")
            val description = obj.getString("description")
            val brandOwner = if (obj.isNull("brandOwner")) null else obj.getString("brandOwner")
            val servingSize = obj.getDouble("servingSize")
            val servingSizeUnit = obj.getString("servingSizeUnit")

            val nutrientsObj = obj.getJSONObject("nutrients")
            val nutrientsMap = mutableMapOf<String, Double>()
            val keys = nutrientsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                nutrientsMap[key] = nutrientsObj.getDouble(key)
            }

            UsdaNutrientProfile(
                fdcId = fdcId,
                description = description,
                brandOwner = brandOwner,
                servingSize = servingSize,
                servingSizeUnit = servingSizeUnit,
                nutrients = nutrientsMap
            )
        } catch (e: Exception) {
            Log.e("UsdaCacheService", "Serialization Error: Corrupted food detail payload in database cache.", e)
            null
        }
    }
}
