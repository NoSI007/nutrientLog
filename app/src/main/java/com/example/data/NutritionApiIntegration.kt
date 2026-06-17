package com.example.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OnlineFoodResult(
    val id: String,
    val name: String,
    val brandName: String?,
    val servingSize: Double,
    val servingSizeUnit: String,
    val source: String,
    val nutrients: Map<String, Double> // Normalized nutrient map
)

object NutritionApiIntegration {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private const val USDA_SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search"
    private const val NUTRITIONIX_SEARCH_URL = "https://trackapi.nutritionix.com/v2/search/instant"
    private const val NUTRITIONIX_NATURAL_URL = "https://trackapi.nutritionix.com/v2/natural/nutrients"

    /**
     * Search USDA FoodData Central
     */
    suspend fun searchUsda(query: String, customApiKey: String? = null): List<OnlineFoodResult> = withContext(Dispatchers.IO) {
        val apiKey = if (!customApiKey.isNullOrBlank()) {
            customApiKey
        } else {
            try {
                com.example.BuildConfig.USDA_API_KEY
            } catch (e: Exception) {
                null
            }
        }

        val activeKey = if (apiKey.isNullOrBlank() || apiKey == "USDA_API_KEY" || apiKey == "DEMO_KEY" || apiKey == "USDA_API_KEY_DEFAULT_VALUE") {
            "DEMO_KEY"
        } else {
            apiKey
        }

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$USDA_SEARCH_URL?query=$encodedQuery&api_key=$activeKey&pageSize=10"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("NutritionApi", "USDA API Error: Code ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(body)
            val foodsArray = root.optJSONArray("foods") ?: return@withContext emptyList()
            val results = mutableListOf<OnlineFoodResult>()

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

                        // USDA values are given per 100g. We must scale to the serving size.
                        val coef = if (servingSize > 0) servingSize / 100.0 else 1.0
                        val valPerServing = valuePer100g * coef

                        val matchedKey = mapNutrientNameToKey(name, number)
                        if (matchedKey != null && !valPerServing.isNaN()) {
                            nutrientMap[matchedKey] = (nutrientMap[matchedKey] ?: 0.0) + valPerServing
                        }
                    }
                }

                results.add(
                    OnlineFoodResult(
                        id = id,
                        name = formatDescription(description, brandName),
                        brandName = brandName,
                        servingSize = servingSize,
                        servingSizeUnit = servingUnit,
                        source = "USDA",
                        nutrients = nutrientMap
                    )
                )
            }
            return@withContext results
        } catch (e: Exception) {
            Log.e("NutritionApi", "Exception in USDA Search", e)
            return@withContext emptyList()
        }
    }

    /**
     * Search Nutritionix
     */
    suspend fun searchNutritionix(query: String, customAppId: String? = null, customApiKey: String? = null): List<OnlineFoodResult> = withContext(Dispatchers.IO) {
        val appId = if (!customAppId.isNullOrBlank()) {
            customAppId
        } else {
            try {
                com.example.BuildConfig.NUTRITIONIX_APP_ID
            } catch (e: Exception) {
                ""
            }
        }
        
        val apiKey = if (!customApiKey.isNullOrBlank()) {
            customApiKey
        } else {
            try {
                com.example.BuildConfig.NUTRITIONIX_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        if (appId.isBlank() || apiKey.isBlank() || 
            appId == "NUTRITIONIX_APP_ID" || apiKey == "NUTRITIONIX_API_KEY" ||
            appId == "NUTRITIONIX_APP_ID_DEFAULT_VALUE" || apiKey == "NUTRITIONIX_API_KEY_DEFAULT_VALUE") {
            Log.i("NutritionApi", "Nutritionix API Keys not configured. Skipping Nutritionix search.")
            return@withContext emptyList()
        }

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$NUTRITIONIX_SEARCH_URL?query=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-app-id", appId)
                .addHeader("x-app-key", apiKey)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("NutritionApi", "Nutritionix search failed: ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(body)
            val brandedArray = root.optJSONArray("branded") ?: JSONArray()
            val results = mutableListOf<OnlineFoodResult>()

            // Process branded options first as they contain nutrient shortcuts
            for (i in 0 until Math.min(brandedArray.length(), 6)) {
                val item = brandedArray.getJSONObject(i)
                val id = item.optString("nix_item_id", "")
                val foodName = item.optString("food_name", "Unknown Item")
                val brandName = item.optString("brand_name", "").takeIf { it.isNotEmpty() }
                val servingQty = item.optDouble("serving_qty", 1.0)
                val servingUnit = item.optString("serving_unit", "serving")

                val nutrientMap = mutableMapOf<String, Double>()
                // Extract directly provided basic nutrients
                item.optDouble("nf_calories", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["calories"] = it }
                item.optDouble("nf_total_fat", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["fat"] = it }
                item.optDouble("nf_saturated_fat", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["saturated_fat"] = it }
                item.optDouble("nf_cholesterol", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["cholesterol"] = it }
                item.optDouble("nf_sodium", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["sodium"] = it }
                item.optDouble("nf_total_carbohydrate", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["carbohydrates"] = it }
                item.optDouble("nf_dietary_fiber", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["fiber"] = it }
                item.optDouble("nf_sugars", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["sugars"] = it }
                item.optDouble("nf_protein", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["protein"] = it }
                item.optDouble("nf_potassium", Double.NaN).takeIf { !it.isNaN() }?.let { nutrientMap["potassium"] = it }

                results.add(
                    OnlineFoodResult(
                        id = id,
                        name = foodName,
                        brandName = brandName,
                        servingSize = servingQty,
                        servingSizeUnit = servingUnit,
                        source = "Nutritionix",
                        nutrients = nutrientMap
                    )
                )
            }
            return@withContext results
        } catch (e: Exception) {
            Log.e("NutritionApi", "Nutritionix search exception", e)
            return@withContext emptyList()
        }
    }

    /**
     * Map database labels / nutrientNumbers to local app nutrient keys.
     */
    private fun mapNutrientNameToKey(name: String, number: String): String? {
        return when {
            // Calories
            number == "208" || number == "1008" || name.contains("energy") || name.contains("calories") -> "calories"
            // Macros
            number == "203" || number == "1003" || name == "protein" -> "protein"
            number == "205" || number == "1005" || name.contains("carbohydrate") -> "carbohydrates"
            number == "204" || number == "1004" || name.contains("total lipid") || name == "fat" || name.contains("total fat") -> "fat"
            number == "291" || number == "1079" || name.contains("fiber") -> "fiber"
            number == "269" || number == "2000" || number == "1010" || name.contains("sugars, total") || name == "sugars" || name == "sugar" -> "sugars"
            
            // Sodium & Minerals
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
            
            // Fats/Lipids
            number == "606" || number == "1258" || name.contains("saturated fat") || name.contains("fatty acids, total saturated") -> "saturated_fat"
            number == "605" || number == "1257" || name.contains("trans fat") || name.contains("fatty acids, total trans") -> "trans_fat"
            number == "645" || name.contains("monounsaturated") -> "monounsaturated_fat"
            number == "646" || name.contains("polyunsaturated") -> "polyunsaturated_fat"
            number == "601" || number == "1253" || name.contains("cholesterol") -> "cholesterol"
            
            // Vitamins
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

    private fun formatDescription(desc: String, brand: String?): String {
        val cleanDesc = desc.lowercase()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        
        return if (!brand.isNullOrBlank()) {
            val cleanBrand = brand.uppercase()
            "[$cleanBrand] $cleanDesc"
        } else {
            cleanDesc
        }
    }
}
