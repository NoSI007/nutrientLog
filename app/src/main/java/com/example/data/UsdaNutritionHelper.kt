package com.example.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mapped nutritional profile data parsed and normalized from USDA FoodData Central.
 */
data class UsdaNutrientProfile(
    val fdcId: String,
    val description: String,
    val brandOwner: String?,
    val servingSize: Double,
    val servingSizeUnit: String,
    val nutrients: Map<String, Double> // Mapped by our standardized nutrient keys
)

object UsdaNutritionHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private const val USDA_SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search"
    private const val USDA_DETAIL_URL = "https://api.nal.usda.gov/fdc/v1/food/"

    private fun cleanApiKey(key: String): String {
        var k = key.trim()
        if (k.startsWith("\"") && k.endsWith("\"") && k.length >= 2) {
            k = k.substring(1, k.length - 1).trim()
        } else if (k.startsWith("'") && k.endsWith("'") && k.length >= 2) {
            k = k.substring(1, k.length - 1).trim()
        }
        return k
    }

    /**
     * Resolves the active USDA API key from BuildConfig.
     * Respects the configured key or falls back gracefully to the standard "DEMO_KEY" placeholder behavior.
     */
    fun getActiveApiKey(): String {
        val buildKey = try {
            com.example.BuildConfig.USDA_API_KEY
        } catch (e: Exception) {
            null
        }
        return if (buildKey.isNullOrBlank() || buildKey == "USDA_API_KEY" || buildKey == "DEMO_KEY" || buildKey == "USDA_API_KEY_DEFAULT_VALUE") {
            "DEMO_KEY"
        } else {
            cleanApiKey(buildKey)
        }
    }

    /**
     * Search USDA FoodData Central and return list of food items with normalized nutritional profiles.
     */
    suspend fun searchFoodNutritionalProfile(query: String): List<UsdaNutrientProfile> = withContext(Dispatchers.IO) {
        var activeKey = getActiveApiKey()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            var url = "$USDA_SEARCH_URL?query=$encodedQuery&api_key=$activeKey&pageSize=15"
            var request = Request.Builder()
                .url(url)
                .addHeader("X-Api-Key", activeKey)
                .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            var response = client.newCall(request).execute()
            if (response.code == 403 && activeKey != "DEMO_KEY") {
                Log.w("UsdaNutritionHelper", "USDA search returned 403. Retrying with DEMO_KEY...")
                activeKey = "DEMO_KEY"
                url = "$USDA_SEARCH_URL?query=$encodedQuery&api_key=$activeKey&pageSize=15"
                request = Request.Builder()
                    .url(url)
                    .addHeader("X-Api-Key", activeKey)
                    .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
                response = client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                Log.e("UsdaNutritionHelper", "USDA HTTP search failed with status: ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(body)
            val foodsArray = root.optJSONArray("foods") ?: return@withContext emptyList()
            val results = mutableListOf<UsdaNutrientProfile>()

            for (i in 0 until foodsArray.length()) {
                val foodObj = foodsArray.getJSONObject(i)
                val id = foodObj.optLong("fdcId", 0L).toString()
                val description = foodObj.optString("description", "Unknown Food")
                val brandName = foodObj.optString("brandOwner", "").takeIf { it.isNotEmpty() }
                val servingSize = foodObj.optDouble("servingSize", 100.0)
                val servingUnit = foodObj.optString("servingSizeUnit", "g")

                val rawNutrientsArray = foodObj.optJSONArray("foodNutrients")
                val nutrientMap = mutableMapOf<String, Double>()

                if (rawNutrientsArray != null) {
                    for (j in 0 until rawNutrientsArray.length()) {
                        val nutObj = rawNutrientsArray.getJSONObject(j)
                        val name = nutObj.optString("nutrientName", "").lowercase()
                        val number = nutObj.optString("nutrientNumber", "")
                        val valuePer100g = nutObj.optDouble("value", 0.0)

                        // USDA values are given per 100g. We scale according to the serving size.
                        val scale = if (servingSize > 0) servingSize / 100.0 else 1.0
                        val valuePerServing = valuePer100g * scale

                        val matchedKey = mapNutrientNameToLocalKey(name, number)
                        if (matchedKey != null && !valuePerServing.isNaN()) {
                            nutrientMap[matchedKey] = (nutrientMap[matchedKey] ?: 0.0) + valuePerServing
                        }
                    }
                }

                results.add(
                    UsdaNutrientProfile(
                        fdcId = id,
                        description = formatRawDescription(description, brandName),
                        brandOwner = brandName,
                        servingSize = servingSize,
                        servingSizeUnit = servingUnit,
                        nutrients = nutrientMap
                    )
                )
            }
            return@withContext results
        } catch (e: Exception) {
            Log.e("UsdaNutritionHelper", "Exception during USDA food search", e)
            return@withContext emptyList()
        }
    }

    /**
     * Retrieve deep, comprehensive nutrient details for a specific Food by its FDC ID.
     */
    suspend fun getFoodDetailsProfile(fdcId: String): UsdaNutrientProfile? = withContext(Dispatchers.IO) {
        var activeKey = getActiveApiKey()
        try {
            var url = "$USDA_DETAIL_URL$fdcId?api_key=$activeKey"
            var request = Request.Builder()
                .url(url)
                .addHeader("X-Api-Key", activeKey)
                .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            var response = client.newCall(request).execute()
            if (response.code == 403 && activeKey != "DEMO_KEY") {
                Log.w("UsdaNutritionHelper", "USDA details returned 403. Retrying with DEMO_KEY...")
                activeKey = "DEMO_KEY"
                url = "$USDA_DETAIL_URL$fdcId?api_key=$activeKey"
                request = Request.Builder()
                    .url(url)
                    .addHeader("X-Api-Key", activeKey)
                    .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
                response = client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                Log.e("UsdaNutritionHelper", "USDA HTTP details request failed with status: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val foodObj = JSONObject(body)

            val id = foodObj.optLong("fdcId", 0L).toString()
            val description = foodObj.optString("description", "Unknown Food")
            val brandName = foodObj.optString("brandOwner", "").takeIf { it.isNotEmpty() }
            val servingSize = foodObj.optDouble("servingSize", 100.0)
            val servingUnit = foodObj.optString("servingSizeUnit", "g")

            val rawNutrientsArray = foodObj.optJSONArray("foodNutrients")
            val nutrientMap = mutableMapOf<String, Double>()

            if (rawNutrientsArray != null) {
                for (j in 0 until rawNutrientsArray.length()) {
                    val wrapperObj = rawNutrientsArray.getJSONObject(j)
                    val valuePer100g = wrapperObj.optDouble("amount", 0.0)

                    val nutrientDetailObj = wrapperObj.optJSONObject("nutrient")
                    if (nutrientDetailObj != null) {
                        val name = nutrientDetailObj.optString("name", "").lowercase()
                        val number = nutrientDetailObj.optString("number", "")

                        // Standardize to portion size
                        val scale = if (servingSize > 0) servingSize / 100.0 else 1.0
                        val valuePerServing = valuePer100g * scale

                        val matchedKey = mapNutrientNameToLocalKey(name, number)
                        if (matchedKey != null && !valuePerServing.isNaN()) {
                            nutrientMap[matchedKey] = (nutrientMap[matchedKey] ?: 0.0) + valuePerServing
                        }
                    }
                }
            }

            return@withContext UsdaNutrientProfile(
                fdcId = id,
                description = formatRawDescription(description, brandName),
                brandOwner = brandName,
                servingSize = servingSize,
                servingSizeUnit = servingUnit,
                nutrients = nutrientMap
            )
        } catch (e: Exception) {
            Log.e("UsdaNutritionHelper", "Exception during USDA details lookup", e)
            return@withContext null
        }
    }

    /**
     * Maps USDA's nutrientName or nutrientNumber to our unified local nutrient keys (covering macros, micros, minerals).
     */
    private fun mapNutrientNameToLocalKey(name: String, number: String): String? {
        return when {
            number == "208" || number == "1008" || name.contains("energy") || name.contains("calories") -> "calories"
            number == "203" || number == "1003" || name == "protein" -> "protein"
            number == "205" || number == "1005" || name.contains("carbohydrate") -> "carbohydrates"
            number == "204" || number == "1004" || name.contains("total lipid") || name == "fat" || name.contains("total fat") -> "fat"
            number == "291" || number == "1079" || name.contains("fiber") -> "fiber"
            number == "269" || number == "2000" || number == "1010" || name.contains("sugars, total") || name == "sugars" || name == "sugar" -> "sugars"
            number == "307" || number == "1093" || name.contains("sodium") -> "sodium"
            number == "306" || number == "1092" || name.contains("potassium") -> "potassium"
            number == "301" || number == "1087" || name.contains("calcium") -> "calcium"
            number == "303" || number == "1089" || name.contains("iron") -> "iron"
            number == "304" || number == "1090" || name.contains("magnesium") -> "magnesium"
            number == "309" || number == "1095" || name.contains("zinc") -> "zinc"
            number == "305" || number == "1091" || name.contains("phosphorus") -> "phosphorus"
            number == "312" || name.contains("copper") -> "copper"
            number == "315" || name.contains("manganese") -> "manganese"
            number == "317" || name.contains("selenium") -> "selenium"
            number == "606" || number == "1258" || name.contains("saturated fat") || name.contains("fatty acids, total saturated") -> "saturated_fat"
            number == "605" || number == "1257" || name.contains("trans fat") || name.contains("fatty acids, total trans") -> "trans_fat"
            number == "645" || name.contains("monounsaturated") -> "monounsaturated_fat"
            number == "646" || name.contains("polyunsaturated") -> "polyunsaturated_fat"
            number == "601" || number == "1253" || name.contains("cholesterol") -> "cholesterol"
            number == "320" || number == "1104" || name.contains("vitamin a") -> "vitamin_a"
            number == "401" || number == "1162" || name.contains("vitamin c") -> "vitamin_c"
            number == "328" || name.contains("vitamin d") -> "vitamin_d"
            number == "323" || name.contains("vitamin e") -> "vitamin_e"
            number == "430" || name.contains("vitamin k") -> "vitamin_k"
            number == "404" || name.contains("thiamin") -> "thiamin"
            number == "405" || name.contains("riboflavin") -> "riboflavin"
            number == "406" || name.contains("niacin") -> "niacin"
            number == "410" || name.contains("pantothenic") -> "pantothenic_acid"
            number == "415" || name.contains("vitamin b-6") || name.contains("vitamin b6") -> "vitamin_b6"
            number == "417" || name.contains("folate") || name.contains("folic") -> "folate"
            number == "418" || name.contains("vitamin b-12") || name.contains("vitamin b12") -> "vitamin_b12"
            name.contains("biotin") -> "biotin"
            name.contains("choline") -> "choline"
            name.contains("iodine") -> "iodine"
            name.contains("chromium") -> "chromium"
            name.contains("molybdenum") -> "molybdenum"
            else -> null
        }
    }

    private fun formatRawDescription(desc: String, brand: String?): String {
        val cleanDesc = desc.lowercase()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        
        return if (!brand.isNullOrBlank()) {
            "[$brand] $cleanDesc"
        } else {
            cleanDesc
        }
    }
}
