package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class FoodRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.foodLogDao()

    val allEntries: Flow<List<FoodLogEntry>> = dao.getAllEntries()

    suspend fun getAllEntriesDirect(): List<FoodLogEntry> = withContext(Dispatchers.IO) {
        dao.getAllEntriesDirect()
    }

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        try {
            val entries = dao.getAllEntriesDirect()
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, FoodLogEntry::class.java)
            val adapter = moshi.adapter<List<FoodLogEntry>>(listType)
            adapter.toJson(entries)
        } catch (e: Exception) {
            Log.e("FoodRepository", "Failed to export JSONString", e)
            ""
        }
    }

    suspend fun importFromJson(jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, FoodLogEntry::class.java)
            val adapter = moshi.adapter<List<FoodLogEntry>>(listType)
            val entries = adapter.fromJson(jsonString)
            if (entries != null) {
                var count = 0
                for (entry in entries) {
                    val cleanEntry = entry.copy(id = 0)
                    dao.insertEntry(cleanEntry)
                    count++
                }
                Result.success(count)
            } else {
                Result.failure(Exception("Parsed entries list is empty or null"))
            }
        } catch (e: Exception) {
            Log.e("FoodRepository", "Failed to import JSONString", e)
            Result.failure(e)
        }
    }

    fun getEntriesForDate(dateString: String): Flow<List<FoodLogEntry>> {
        return dao.getEntriesForDate(dateString)
    }

    suspend fun insertEntry(entry: FoodLogEntry) = withContext(Dispatchers.IO) {
        dao.insertEntry(entry)
    }

    suspend fun deleteEntry(entry: FoodLogEntry) = withContext(Dispatchers.IO) {
        dao.deleteEntry(entry)
    }

    suspend fun deleteEntryById(id: Int) = withContext(Dispatchers.IO) {
        dao.deleteEntryById(id)
    }

    suspend fun deleteEntriesForDate(dateString: String) = withContext(Dispatchers.IO) {
        dao.deleteEntriesForDate(dateString)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun parseAndLogFood(
        foodInput: String,
        date: String,
        mealType: String
    ): Result<FoodLogEntry> = withContext(Dispatchers.IO) {
        val apiKey = try {
            com.example.BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "GEMINI_API_KEY") {
            Log.w("FoodRepository", "No valid Gemini API key found, utilizing local fallback analyzer.")
            val fallbackEntry = createFallbackEntry(foodInput, date, mealType)
            dao.insertEntry(fallbackEntry)
            return@withContext Result.success(fallbackEntry)
        }

        val prompt = compilePrompt(foodInput)
        val jsonRequest = buildRequestBodyJson(prompt)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(jsonRequest.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("FoodRepository", "Gemini API error code: ${response.code}. Spawning fallback data model.")
                val fallbackEntry = createFallbackEntry(foodInput, date, mealType)
                dao.insertEntry(fallbackEntry)
                return@withContext Result.success(fallbackEntry)
            }

            val bodyString = response.body?.string() ?: ""
            val parsedResult = extractNutrientsFromJson(bodyString, foodInput, date, mealType)
            if (parsedResult != null) {
                dao.insertEntry(parsedResult)
                return@withContext Result.success(parsedResult)
            } else {
                Log.e("FoodRepository", "Payload parsing error. Moving to fallback profile.")
                val fallbackEntry = createFallbackEntry(foodInput, date, mealType)
                dao.insertEntry(fallbackEntry)
                return@withContext Result.success(fallbackEntry)
            }
        } catch (e: Exception) {
            Log.e("FoodRepository", "Gemini API query execution failed: ${e.message}. Using backup solver.", e)
            val fallbackEntry = createFallbackEntry(foodInput, date, mealType)
            dao.insertEntry(fallbackEntry)
            return@withContext Result.success(fallbackEntry)
        }
    }

    private fun extractNutrientsFromJson(
        responseJsonString: String,
        originalInput: String,
        date: String,
        mealType: String
    ): FoodLogEntry? {
        try {
            val root = org.json.JSONObject(responseJsonString)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) return null
            val candidate = candidates.getJSONObject(0)
            val content = candidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            if (parts.length() == 0) return null
            val textPart = parts.getJSONObject(0).getString("text")

            val cleanJsonString = if (textPart.contains("```json")) {
                textPart
                    .substringAfter("```json")
                    .substringBefore("```")
                    .trim()
            } else if (textPart.contains("```")) {
                textPart
                    .substringAfter("```")
                    .substringBefore("```")
                    .trim()
            } else {
                textPart.trim()
            }

            val innerJson = org.json.JSONObject(cleanJsonString)
            val foodName = innerJson.optString("foodName", originalInput)
            val nutrientsJson = innerJson.getJSONObject("nutrients")

            val finalNutrientsMap = mutableMapOf<String, Double>()
            for (definition in Nutrients.DEFINITIONS) {
                val value = nutrientsJson.optDouble(definition.key, 0.0)
                finalNutrientsMap[definition.key] = if (value.isFinite() && value >= 0.0) value else 0.0
            }

            return FoodLogEntry(
                date = date,
                foodName = foodName,
                mealType = mealType,
                quantity = 1.0,
                unit = "serving",
                nutrients = finalNutrientsMap
            )
        } catch (e: Exception) {
            Log.e("FoodRepository", "Error parsing nutritional values in response payload", e)
            return null
        }
    }

    private fun buildRequestBodyJson(prompt: String): String {
        return """
            {
              "contents": [
                {
                  "parts": [
                    {
                      "text": ${org.json.JSONObject.quote(prompt)}
                    }
                  ]
                }
              ],
              "generationConfig": {
                "responseMimeType": "application/json"
              }
            }
        """.trimIndent()
    }

    fun createFallbackEntry(
        foodInput: String,
        date: String,
        mealType: String
    ): FoodLogEntry {
        val lower = foodInput.lowercase()
        val accumulated = mutableMapOf<String, Double>()

        for (definition in Nutrients.DEFINITIONS) {
            accumulated[definition.key] = 0.0
        }

        var matchCount = 0

        fun addNutrient(key: String, value: Double) {
            accumulated[key] = (accumulated[key] ?: 0.0) + value
        }

        if (lower.contains("egg")) {
            matchCount++
            addNutrient("calories", 140.0)
            addNutrient("protein", 12.0)
            addNutrient("fat", 10.0)
            addNutrient("saturated_fat", 3.0)
            addNutrient("cholesterol", 370.0)
            addNutrient("sodium", 140.0)
            addNutrient("vitamin_d", 2.0)
            addNutrient("choline", 290.0)
            addNutrient("vitamin_a", 160.0)
            addNutrient("iron", 1.8)
            addNutrient("calcium", 50.0)
            addNutrient("water", 150.0)
        }
        if (lower.contains("banana")) {
            matchCount++
            addNutrient("calories", 105.0)
            addNutrient("carbohydrates", 27.0)
            addNutrient("fiber", 3.0)
            addNutrient("sugars", 14.0)
            addNutrient("potassium", 422.0)
            addNutrient("vitamin_c", 10.0)
            addNutrient("vitamin_b6", 0.4)
            addNutrient("magnesium", 32.0)
            addNutrient("water", 90.0)
        }
        if (lower.contains("chicken") || lower.contains("poultry") || lower.contains("turkey") || lower.contains("breast")) {
            matchCount++
            addNutrient("calories", 220.0)
            addNutrient("protein", 35.0)
            addNutrient("fat", 8.0)
            addNutrient("saturated_fat", 2.2)
            addNutrient("cholesterol", 95.0)
            addNutrient("sodium", 80.0)
            addNutrient("potassium", 300.0)
            addNutrient("niacin", 12.0)
            addNutrient("vitamin_b6", 0.6)
            addNutrient("zinc", 2.0)
            addNutrient("selenium", 25.0)
            addNutrient("choline", 90.0)
            addNutrient("water", 65.0)
        }
        if (lower.contains("beef") || lower.contains("steak") || lower.contains("meat") || lower.contains("burger")) {
            matchCount++
            addNutrient("calories", 280.0)
            addNutrient("protein", 28.0)
            addNutrient("fat", 18.0)
            addNutrient("saturated_fat", 7.0)
            addNutrient("cholesterol", 85.0)
            addNutrient("sodium", 70.0)
            addNutrient("potassium", 350.0)
            addNutrient("iron", 3.0)
            addNutrient("zinc", 6.0)
            addNutrient("vitamin_b12", 2.5)
            addNutrient("phosphorus", 220.0)
            addNutrient("choline", 80.0)
            addNutrient("water", 60.0)
        }
        if (lower.contains("apple")) {
            matchCount++
            addNutrient("calories", 95.0)
            addNutrient("carbohydrates", 25.0)
            addNutrient("fiber", 4.4)
            addNutrient("sugars", 19.0)
            addNutrient("potassium", 195.0)
            addNutrient("vitamin_c", 8.4)
            addNutrient("water", 150.0)
        }
        if (lower.contains("bread") || lower.contains("toast") || lower.contains("sandwich") || lower.contains("bun")) {
            matchCount++
            addNutrient("calories", 150.0)
            addNutrient("carbohydrates", 28.0)
            addNutrient("fiber", 2.5)
            addNutrient("protein", 5.0)
            addNutrient("fat", 2.0)
            addNutrient("sodium", 300.0)
            addNutrient("folate", 45.0)
            addNutrient("iron", 1.5)
            addNutrient("thiamin", 0.2)
            addNutrient("riboflavin", 0.15)
            addNutrient("water", 35.0)
        }
        if (lower.contains("milk") || lower.contains("cheese") || lower.contains("yogurt") || lower.contains("dairy") || lower.contains("butter")) {
            matchCount++
            addNutrient("calories", 180.0)
            addNutrient("carbohydrates", 12.0)
            addNutrient("sugars", 12.0)
            addNutrient("protein", 9.0)
            addNutrient("fat", 9.0)
            addNutrient("saturated_fat", 6.0)
            addNutrient("cholesterol", 30.0)
            addNutrient("calcium", 300.0)
            addNutrient("phosphorus", 250.0)
            addNutrient("potassium", 380.0)
            addNutrient("sodium", 150.0)
            addNutrient("vitamin_b12", 1.2)
            addNutrient("riboflavin", 0.4)
            addNutrient("vitamin_d", 2.5)
            addNutrient("water", 200.0)
        }
        if (lower.contains("rice") || lower.contains("grain") || lower.contains("oat") || lower.contains("cereal") || lower.contains("pasta")) {
            matchCount++
            addNutrient("calories", 220.0)
            addNutrient("carbohydrates", 45.0)
            addNutrient("protein", 5.0)
            addNutrient("fiber", 1.5)
            addNutrient("fat", 1.0)
            addNutrient("sodium", 5.0)
            addNutrient("thiamin", 0.25)
            addNutrient("niacin", 2.5)
            addNutrient("iron", 1.2)
            addNutrient("potassium", 60.0)
            addNutrient("water", 130.0)
        }
        if (lower.contains("salmon") || lower.contains("fish") || lower.contains("tuna") || lower.contains("seafood")) {
            matchCount++
            addNutrient("calories", 210.0)
            addNutrient("protein", 25.0)
            addNutrient("fat", 11.0)
            addNutrient("saturated_fat", 2.0)
            addNutrient("polyunsaturated_fat", 4.0)
            addNutrient("omega3", 2.3)
            addNutrient("cholesterol", 60.0)
            addNutrient("vitamin_d", 12.0)
            addNutrient("vitamin_b12", 4.5)
            addNutrient("selenium", 35.0)
            addNutrient("sodium", 60.0)
            addNutrient("potassium", 400.0)
            addNutrient("niacin", 9.0)
            addNutrient("phosphorus", 250.0)
            addNutrient("water", 70.0)
        }
        if (lower.contains("spinach") || lower.contains("salad") || lower.contains("lettuce") || lower.contains("broccoli") || lower.contains("vegetable") || lower.contains("tomato") || lower.contains("carrot")) {
            matchCount++
            addNutrient("calories", 45.0)
            addNutrient("carbohydrates", 8.0)
            addNutrient("fiber", 3.0)
            addNutrient("sugars", 3.0)
            addNutrient("protein", 2.0)
            addNutrient("vitamin_a", 500.0)
            addNutrient("vitamin_c", 45.0)
            addNutrient("vitamin_k", 150.0)
            addNutrient("folate", 90.0)
            addNutrient("potassium", 320.0)
            addNutrient("iron", 1.5)
            addNutrient("magnesium", 35.0)
            addNutrient("manganese", 0.5)
            addNutrient("water", 180.0)
        }
        if (lower.contains("soda") || lower.contains("cola") || lower.contains("drink") || lower.contains("coke") || lower.contains("juice") || lower.contains("beer") || lower.contains("wine")) {
            matchCount++
            addNutrient("calories", 150.0)
            addNutrient("carbohydrates", 35.0)
            addNutrient("sugars", 35.0)
            addNutrient("sodium", 45.0)
            addNutrient("water", 330.0)
        }
        if (lower.contains("oil") || lower.contains("margarine") || lower.contains("fat") || lower.contains("mayo") || lower.contains("dressing")) {
            matchCount++
            addNutrient("calories", 120.0)
            addNutrient("fat", 14.0)
            addNutrient("saturated_fat", 3.2)
            addNutrient("monounsaturated_fat", 7.0)
            addNutrient("polyunsaturated_fat", 3.0)
            addNutrient("vitamin_e", 2.5)
            addNutrient("vitamin_k", 8.0)
        }
        if (lower.contains("nut") || lower.contains("almond") || lower.contains("peanut") || lower.contains("seed") || lower.contains("chia")) {
            matchCount++
            addNutrient("calories", 180.0)
            addNutrient("carbohydrates", 6.0)
            addNutrient("fiber", 3.5)
            addNutrient("protein", 6.0)
            addNutrient("fat", 16.0)
            addNutrient("saturated_fat", 1.8)
            addNutrient("polyunsaturated_fat", 6.0)
            addNutrient("omega6", 4.2)
            addNutrient("magnesium", 75.0)
            addNutrient("zinc", 1.2)
            addNutrient("copper", 0.3)
            addNutrient("manganese", 0.6)
            addNutrient("vitamin_e", 4.0)
            addNutrient("water", 10.0)
        }

        if (lower.contains("kcl") || lower.contains("potassium chloride") || lower.contains("salt replacement") || lower.contains("kcl replacement") || lower.contains("no salt replacement")) {
            matchCount++
            addNutrient("potassium", 1360.0)
            addNutrient("sodium", 0.0)
        }

        if (matchCount == 0) {
            addNutrient("calories", 250.0)
            addNutrient("carbohydrates", 30.0)
            addNutrient("protein", 10.0)
            addNutrient("fat", 8.0)
            addNutrient("saturated_fat", 2.0)
            addNutrient("fiber", 2.5)
            addNutrient("sugars", 5.0)
            addNutrient("sodium", 200.0)
            addNutrient("potassium", 250.0)
            addNutrient("calcium", 50.0)
            addNutrient("iron", 1.0)
            addNutrient("vitamin_c", 5.0)
            addNutrient("vitamin_a", 40.0)
            addNutrient("water", 120.0)
        }

        if (lower.contains("no salt") || lower.contains("zero salt") || lower.contains("unsalted") || lower.contains("kcl") || lower.contains("salt replacement") || lower.contains("without salt")) {
            accumulated["sodium"] = 0.0
        }

        return FoodLogEntry(
            date = date,
            foodName = foodInput,
            mealType = mealType,
            quantity = 1.0,
            unit = "serving",
            nutrients = accumulated
        )
    }

    private fun compilePrompt(food: String): String {
        return """
            You are a highly precise nutrition database system. Analyze the following food input and estimate its corresponding content across our exact list of 41 nutrients.
            Food Input: "$food"
            
            Special User Dietary Preference Context:
            - The user is on a STRICT ZERO-SALT diet and is not taking any table salt (NaCl/sodium). Any seasonal/home/restaurant food analyzed should assume **no salted seasoning used (minimal to zero added sodium)**.
            - The user uses **KCl (Potassium Chloride) salt replacement / potassium replacement** instead of table salt. If the food input contains "kcl", "potassium chloride", "salt replacement", or "kcl replacement", it contains **0.0 mg Sodium** but is rich in **Potassium** (estimate potassium at around 1360 mg per teaspoon/serving or appropriate based on food context).
            
            Return ONLY a single, well-formed JSON object enclosing:
            1. "foodName": A refined, polished description of the parsed food item (or group of items, e.g., "Egg salad sandwich with mayonnaise")
            2. "nutrients": A dictionary matching exactly the keys specified below with their corresponding numerical values. Units are absolute (DO NOT include the unit character in the value, e.g. "calories": 150.0).
            
            Nutrient Keys, display names, and exact units required:
            - calories: (Energy in kcal)
            - carbohydrates: (Carbon totals in g)
            - protein: (Protein in g)
            - fat: (Total lipids in g)
            - fiber: (Dietary fiber in g)
            - water: (Moisture in ml or g)
            - saturated_fat: (Saturated fat in g)
            - trans_fat: (Trans fats in g)
            - monounsaturated_fat: (Monounsaturated fat in g)
            - polyunsaturated_fat: (Polyunsaturated fat in g)
            - omega3: (Omega 3 fatty acids in g)
            - omega6: (Omega 6 fatty acids in g)
            - cholesterol: (Cholesterol in mg)
            - vitamin_a: (Vitamin A in mcg RAE)
            - vitamin_c: (Vitamin C in mg)
            - vitamin_d: (Vitamin D in mcg)
            - vitamin_e: (Vitamin E in mg)
            - vitamin_k: (Vitamin K in mcg)
            - thiamin: (Vitamin B1 in mg)
            - riboflavin: (Vitamin B2 in mg)
            - niacin: (Vitamin B3 in mg)
            - pantothenic_acid: (Vitamin B5 in mg)
            - vitamin_b6: (Vitamin B6 in mg)
            - biotin: (Vitamin B7 in mcg)
            - folate: (Vitamin B9 in mcg DFE)
            - vitamin_b12: (Vitamin B12 in mcg)
            - choline: (Choline in mg)
            - calcium: (Calcium in mg)
            - iron: (Iron in mg)
            - magnesium: (Magnesium in mg)
            - phosphorus: (Phosphorus in mg)
            - potassium: (Potassium in mg)
            - sodium: (Sodium in mg)
            - zinc: (Zinc in mg)
            - copper: (Copper in mg)
            - manganese: (Manganese in mg)
            - selenium: (Selenium in mcg)
            - chromium: (Chromium in mcg)
            - molybdenum: (Molybdenum in mcg)
            - sugars: (Total sugars in g)
            - iodine: (Iodine in mcg)

            Strict Formatting Rules:
            - All 41 keys must be included in the "nutrients" object, with numeric floating-point values.
            - Ensure there are no markdown annotations outside of the JSON block (return only the raw JSON, or JSON inside ```json ... ```).
            - Do not return default placeholder text, descriptions, or explanations. Just return the structured object.
        """.trimIndent()
    }

    suspend fun parseAndLogMultiDayFood(
        bulkInput: String,
        anchorDate: String
    ): Result<List<FoodLogEntry>> = withContext(Dispatchers.IO) {
        val apiKey = try {
            com.example.BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "GEMINI_API_KEY") {
            Log.w("FoodRepository", "No valid Gemini API key found, utilizing local fallback multi-day analyzer.")
            val fallbackEntries = parseMultiDayOffline(bulkInput, anchorDate)
            for (entry in fallbackEntries) {
                dao.insertEntry(entry)
            }
            return@withContext Result.success(fallbackEntries)
        }

        val prompt = compileMultiDayPrompt(bulkInput, anchorDate)
        val jsonRequest = buildRequestBodyJson(prompt)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(jsonRequest.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("FoodRepository", "Gemini API error code: ${response.code}. Spawning fallback data model.")
                val fallbackEntries = parseMultiDayOffline(bulkInput, anchorDate)
                for (entry in fallbackEntries) {
                    dao.insertEntry(entry)
                }
                return@withContext Result.success(fallbackEntries)
            }

            val bodyString = response.body?.string() ?: ""
            val parsedEntries = extractMultiDayNutrientsFromJson(bodyString, bulkInput, anchorDate)
            if (parsedEntries.isNotEmpty()) {
                for (entry in parsedEntries) {
                    dao.insertEntry(entry)
                }
                return@withContext Result.success(parsedEntries)
            } else {
                Log.e("FoodRepository", "Payload parsing error for multi-day logs. Moving to fallback profile.")
                val fallbackEntries = parseMultiDayOffline(bulkInput, anchorDate)
                for (entry in fallbackEntries) {
                    dao.insertEntry(entry)
                }
                return@withContext Result.success(fallbackEntries)
            }
        } catch (e: Exception) {
            Log.e("FoodRepository", "Gemini API multi-day query execution failed: ${e.message}. Using backup solver.", e)
            val fallbackEntries = parseMultiDayOffline(bulkInput, anchorDate)
            for (entry in fallbackEntries) {
                dao.insertEntry(entry)
            }
            return@withContext Result.success(fallbackEntries)
        }
    }

    private fun extractMultiDayNutrientsFromJson(
        responseJsonString: String,
        bulkInput: String,
        anchorDate: String
    ): List<FoodLogEntry> {
        val entries = mutableListOf<FoodLogEntry>()
        try {
            val root = org.json.JSONObject(responseJsonString)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) return emptyList()
            val candidate = candidates.getJSONObject(0)
            val content = candidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            if (parts.length() == 0) return emptyList()
            val textPart = parts.getJSONObject(0).getString("text")

            val cleanJsonString = if (textPart.contains("```json")) {
                textPart
                    .substringAfter("```json")
                    .substringBefore("```")
                    .trim()
            } else if (textPart.contains("```")) {
                textPart
                    .substringAfter("```")
                    .substringBefore("```")
                    .trim()
            } else {
                textPart.trim()
            }

            val innerJson = org.json.JSONObject(cleanJsonString)
            val jsonEntries = innerJson.getJSONArray("entries")

            for (i in 0 until jsonEntries.length()) {
                val item = jsonEntries.getJSONObject(i)
                val date = item.optString("date", anchorDate)
                val foodName = item.optString("foodName", "Parsed Food")
                val mealType = item.optString("mealType", "Lunch")
                val nutrientsJson = item.getJSONObject("nutrients")

                val finalNutrientsMap = mutableMapOf<String, Double>()
                for (definition in Nutrients.DEFINITIONS) {
                    val value = nutrientsJson.optDouble(definition.key, 0.0)
                    finalNutrientsMap[definition.key] = if (value.isFinite() && value >= 0.0) value else 0.0
                }

                entries.add(
                    FoodLogEntry(
                        date = date,
                        foodName = foodName,
                        mealType = mealType,
                        quantity = 1.0,
                        unit = "serving",
                        nutrients = finalNutrientsMap
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("FoodRepository", "Error parsing bulk multi-day nutritional response payload", e)
        }
        return entries
    }

    private fun compileMultiDayPrompt(bulkInput: String, anchorDate: String): String {
        return """
            You are a highly precise nutrition database system. Analyze the following bulk food log input which may contain multiple foods logged across many days.
            Estimate their corresponding compositions across our exact list of 41 nutrients and separate them into individual daily entries.
            
            Special User Dietary Preference Context:
            - The user is on a STRICT ZERO-SALT diet and is not taking any table salt (NaCl/sodium). Any seasonal/home/restaurant food analyzed should assume **no salted seasoning used (minimal to zero added sodium)**.
            - The user uses **KCl (Potassium Chloride) salt replacement / potassium replacement** instead of table salt. If the food input contains "kcl", "potassium chloride", "salt replacement", or "kcl replacement", it contains **0.0 mg Sodium** but is rich in **Potassium** (estimate potassium at around 1360 mg per teaspoon/serving or appropriate based on food context).

            Current Anchor Date (Today): $anchorDate (Monday, May 25, 2026).
            Resolve relative time phrases like "yesterday", "today", "two days ago", "last Tuesday" using this anchor.
            If no day or date is specified, default to the anchor date: "$anchorDate".
            
            Bulk Input: "$bulkInput"
            
            Return ONLY a single, well-formed JSON object containing a list under "entries".
            Each entry must contain:
            1. "date": The calculated date in "YYYY-MM-DD" format (e.g. "2026-05-24").
            2. "foodName": A refined, polished description of the parsed food item (e.g. "Grilled chicken breast with brown rice")
            3. "mealType": One of: "Breakfast", "Lunch", "Dinner", "Snack". If indeterminate, infer based on common composition or context.
            4. "nutrients": A dictionary matching exactly the 41 key keys specified below. Units are absolute values (DO NOT include unit characters, e.g., "calories": 150.0).
            
            Nutrient Keys:
            - calories
            - carbohydrates
            - protein
            - fat
            - fiber
            - water
            - saturated_fat
            - trans_fat
            - monounsaturated_fat
            - polyunsaturated_fat
            - omega3
            - omega6
            - cholesterol
            - vitamin_a
            - vitamin_c
            - vitamin_d
            - vitamin_e
            - vitamin_k
            - thiamin
            - riboflavin
            - niacin
            - pantothenic_acid
            - vitamin_b6
            - biotin
            - folate
            - vitamin_b12
            - choline
            - calcium
            - iron
            - magnesium
            - phosphorus
            - potassium
            - sodium
            - zinc
            - copper
            - manganese
            - selenium
            - chromium
            - molybdenum
            - sugars
            - iodine
            
            Strict Formatting Rules:
            - All 41 keys must be included in the "nutrients" object for EVERY entry, with numeric floating-point values.
            - Ensure there are no markdown annotations outside of the JSON block. Return ONLY raw JSON, or JSON inside ```json ... ```.
        """.trimIndent()
    }

    fun parseMultiDayOffline(bulkInput: String, anchorDate: String): List<FoodLogEntry> {
        val entries = mutableListOf<FoodLogEntry>()
        val lines = bulkInput.lines()
        var currentDate = anchorDate
        var currentMeal = "Lunch"

        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun getRelativeDate(offset: Int): String {
            return try {
                val cal = Calendar.getInstance()
                val parsed = format.parse(anchorDate)
                if (parsed != null) {
                    cal.time = parsed
                    cal.add(Calendar.DATE, offset)
                    format.format(cal.time)
                } else {
                    anchorDate
                }
            } catch (e: Exception) {
                anchorDate
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val lower = trimmed.lowercase()
            // Check if line indicates a date change
            when {
                lower.contains("yesterday") -> {
                    currentDate = getRelativeDate(-1)
                    continue
                }
                lower.contains("2 days ago") -> {
                    currentDate = getRelativeDate(-2)
                    continue
                }
                lower.contains("3 days ago") -> {
                    currentDate = getRelativeDate(-3)
                    continue
                }
                lower.contains("today") -> {
                    currentDate = anchorDate
                    continue
                }
            }

            // Check if line indicates a meal type change
            when {
                lower.startsWith("breakfast:") || lower.equals("breakfast") -> {
                    currentMeal = "Breakfast"
                    val foodPart = trimmed.substringAfter(":", "").trim()
                    if (foodPart.isNotEmpty()) {
                        entries.add(createFallbackEntry(foodPart, currentDate, currentMeal))
                    }
                    continue
                }
                lower.startsWith("lunch:") || lower.equals("lunch") -> {
                    currentMeal = "Lunch"
                    val foodPart = trimmed.substringAfter(":", "").trim()
                    if (foodPart.isNotEmpty()) {
                        entries.add(createFallbackEntry(foodPart, currentDate, currentMeal))
                    }
                    continue
                }
                lower.startsWith("dinner:") || lower.equals("dinner") -> {
                    currentMeal = "Dinner"
                    val foodPart = trimmed.substringAfter(":", "").trim()
                    if (foodPart.isNotEmpty()) {
                        entries.add(createFallbackEntry(foodPart, currentDate, currentMeal))
                    }
                    continue
                }
                lower.startsWith("snack:") || lower.equals("snack") -> {
                    currentMeal = "Snack"
                    val foodPart = trimmed.substringAfter(":", "").trim()
                    if (foodPart.isNotEmpty()) {
                        entries.add(createFallbackEntry(foodPart, currentDate, currentMeal))
                    }
                    continue
                }
            }

            val foodInput = trimmed.removeSuffix(";").trim()
            if (foodInput.isNotEmpty()) {
                entries.add(createFallbackEntry(foodInput, currentDate, currentMeal))
            }
        }

        return entries
    }
}
