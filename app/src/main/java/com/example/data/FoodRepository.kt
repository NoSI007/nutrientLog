package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val supplementDao = database.supplementDao()
    private val favoriteMealDao = database.favoriteMealDao()
    private val favoriteFoodDao = database.favoriteFoodDao()
    private val nutrientDao = database.nutrientDao()
    private val usdaCacheService = UsdaCacheService(context)
    private val autoLogMutex = Mutex()

    init {
        FirestoreService.initialize(context)
    }

    suspend fun deleteAllEntriesLocalOnly() = withContext(Dispatchers.IO) {
        dao.deleteAllEntries()
    }

    suspend fun insertEntriesLocalOnly(entries: List<FoodLogEntry>) = withContext(Dispatchers.IO) {
        dao.insertEntries(entries)
    }

    val allEntries: Flow<List<FoodLogEntry>> = dao.getAllEntries()
    val allSupplements: Flow<List<Supplement>> = supplementDao.getAllSupplements()
    val allFavoriteMeals: Flow<List<FavoriteMeal>> = favoriteMealDao.getAllFavoriteMeals()
    val allFavoriteFoods: Flow<List<FavoriteFood>> = favoriteFoodDao.getAllFavoriteFoods()
    val allNutrients: Flow<List<NutrientEntity>> = nutrientDao.getAllNutrientsFlow()

    suspend fun insertFavoriteFood(food: FavoriteFood) = withContext(Dispatchers.IO) {
        favoriteFoodDao.insertFavoriteFood(food)
    }

    suspend fun deleteFavoriteFood(food: FavoriteFood) = withContext(Dispatchers.IO) {
        favoriteFoodDao.deleteFavoriteFood(food)
    }

    suspend fun deleteFavoriteFoodById(id: Int) = withContext(Dispatchers.IO) {
        favoriteFoodDao.deleteFavoriteFoodById(id)
    }

    suspend fun getFavoriteFoodByName(name: String): FavoriteFood? = withContext(Dispatchers.IO) {
        favoriteFoodDao.getFavoriteFoodByName(name)
    }

    suspend fun getNutrientsDirect(): List<NutrientEntity> = withContext(Dispatchers.IO) {
        nutrientDao.getAllNutrients()
    }

    suspend fun ensureNutrientsSeeded() = withContext(Dispatchers.IO) {
        val existing = nutrientDao.getAllNutrients()
        if (existing.isEmpty()) {
            val entities = Nutrients.DEFAULT_DEFINITIONS.map { def ->
                NutrientEntity(
                    key = def.key,
                    name = def.name,
                    group = def.group,
                    rda = def.rda,
                    unit = def.unit,
                    isMaxLimit = def.isMaxLimit,
                    description = def.description
                )
            }
            nutrientDao.insertNutrients(entities)
            Log.i("FoodRepository", "Seeded 41 essential nutrients into standard schema table successfully.")
        }
    }

    suspend fun updateNutrientRda(key: String, newRda: Double) = withContext(Dispatchers.IO) {
        nutrientDao.updateRda(key, newRda)
    }

    suspend fun resetNutrientsToDefault() = withContext(Dispatchers.IO) {
        nutrientDao.deleteAll()
        ensureNutrientsSeeded()
    }

    suspend fun insertFavoriteMeal(meal: FavoriteMeal) = withContext(Dispatchers.IO) {
        favoriteMealDao.insertFavoriteMeal(meal)
    }

    suspend fun deleteFavoriteMeal(meal: FavoriteMeal) = withContext(Dispatchers.IO) {
        favoriteMealDao.deleteFavoriteMeal(meal)
    }

    suspend fun deleteFavoriteMealById(id: Int) = withContext(Dispatchers.IO) {
        favoriteMealDao.deleteFavoriteMealById(id)
    }

    suspend fun logFavoriteMeal(mealToLog: FavoriteMeal, dateString: String, selectedMealType: String) = withContext(Dispatchers.IO) {
        mealToLog.foods.forEach { food ->
            val logEntry = FoodLogEntry(
                date = dateString,
                foodName = food.foodName,
                mealType = selectedMealType,
                quantity = food.quantity,
                unit = food.unit,
                nutrients = food.nutrients
            )
            dao.insertEntry(logEntry)
        }
    }

    suspend fun getAllEntriesDirect(): List<FoodLogEntry> = withContext(Dispatchers.IO) {
        dao.getAllEntriesDirect()
    }

    suspend fun exportEntriesToJson(entries: List<FoodLogEntry>): String = withContext(Dispatchers.IO) {
        try {
            val moshi = Moshi.Builder()
                .add(Double::class.java, ResilientDoubleAdapter)
                .add(Double::class.javaObjectType, ResilientDoubleAdapter)
                .add(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, FoodLogEntry::class.java)
            val adapter = moshi.adapter<List<FoodLogEntry>>(listType)
            adapter.toJson(entries)
        } catch (e: Exception) {
            Log.e("FoodRepository", "Failed to export entries JSONString", e)
            ""
        }
    }

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        try {
            val entries = dao.getAllEntriesDirect()
            val moshi = Moshi.Builder()
                .add(Double::class.java, ResilientDoubleAdapter)
                .add(Double::class.javaObjectType, ResilientDoubleAdapter)
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
            val cleanJson = jsonString.trim().removePrefix("\uFEFF")
            val moshi = Moshi.Builder()
                .add(Double::class.java, ResilientDoubleAdapter)
                .add(Double::class.javaObjectType, ResilientDoubleAdapter)
                .add(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, FoodLogEntry::class.java)
            val adapter = moshi.adapter<List<FoodLogEntry>>(listType)
            val entries = adapter.fromJson(cleanJson)
            if (entries != null) {
                var count = 0
                val insertedEntries = mutableListOf<FoodLogEntry>()
                for (entry in entries) {
                    val cleanEntry = entry.copy(id = 0)
                    val newId = dao.insertEntry(cleanEntry)
                    insertedEntries.add(cleanEntry.copy(id = newId.toInt()))
                    count++
                }
                try {
                    FirestoreService.saveAllFoodLogEntries(insertedEntries)
                } catch (e: Exception) {
                    Log.e("FoodRepository", "Firestore import batch save failed", e)
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

    suspend fun insertEntry(entry: FoodLogEntry): FoodLogEntry = withContext(Dispatchers.IO) {
        val newId = dao.insertEntry(entry)
        val entryWithId = if (entry.id == 0) entry.copy(id = newId.toInt()) else entry
        try {
            FirestoreService.saveFoodLogEntry(entryWithId)
        } catch (e: Exception) {
            Log.e("FoodRepository", "Firestore sync failed on insertEntry", e)
        }
        entryWithId
    }

    suspend fun deleteEntry(entry: FoodLogEntry) = withContext(Dispatchers.IO) {
        dao.deleteEntry(entry)
        try {
            FirestoreService.deleteFoodLogEntry(entry.id)
        } catch (e: Exception) {
            Log.e("FoodRepository", "Firestore sync failed on deleteEntry", e)
        }
    }

    suspend fun deleteEntryById(id: Int) = withContext(Dispatchers.IO) {
        dao.deleteEntryById(id)
        try {
            FirestoreService.deleteFoodLogEntry(id)
        } catch (e: Exception) {
            Log.e("FoodRepository", "Firestore sync failed on deleteEntryById", e)
        }
    }

    suspend fun deleteEntriesForDate(dateString: String) = withContext(Dispatchers.IO) {
        dao.deleteEntriesForDate(dateString)
        try {
            FirestoreService.deleteEntriesForDate(dateString)
        } catch (e: Exception) {
            Log.e("FoodRepository", "Firestore sync failed on deleteEntriesForDate", e)
        }
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
        val cleanInput = foodInput.trim()
        val isNumeric = cleanInput.all { it.isDigit() }
        val isLikelyBarcode = (isNumeric && cleanInput.length >= 6) || getProductByBarcode(cleanInput) != null
        if (isLikelyBarcode) {
            return@withContext parseAndLogBarcode(cleanInput, date, mealType)
        }

        val sharedPrefs = context.getSharedPreferences("nutrition_targets", Context.MODE_PRIVATE)
        val customApiKey = sharedPrefs.getString("usda_api_key", null)?.takeIf { it.isNotBlank() }

        val apiKey = GeminiAuthHandler.getApiKey()

        if (apiKey.isEmpty()) {
            Log.w("FoodRepository", "No valid Gemini API key found, attempting USDA API automatic lookup.")
            try {
                val searchResults = usdaCacheService.searchFoods(cleanInput, customApiKey)
                if (searchResults.isNotEmpty()) {
                    val bestMatch = searchResults.first()
                    val entry = FoodLogEntry(
                        date = date,
                        foodName = bestMatch.name,
                        mealType = mealType,
                        quantity = 1.0,
                        unit = bestMatch.servingSizeUnit,
                        nutrients = bestMatch.nutrients
                    )
                    dao.insertEntry(entry)
                    Log.i("FoodRepository", "Automatically resolved '$cleanInput' nutrients via USDA FoodData Central!")
                    return@withContext Result.success(entry)
                }
            } catch (e: Exception) {
                Log.e("FoodRepository", "Auto USDA API lookup failed: ${e.message}")
            }

            Log.w("FoodRepository", "Utilizing local fallback analyzer.")
            val fallbackEntry = createFallbackEntry(foodInput, date, mealType)
            dao.insertEntry(fallbackEntry)
            return@withContext Result.success(fallbackEntry)
        }

        val prompt = compilePrompt(foodInput)
        val jsonRequest = buildRequestBodyJson(prompt)

        val request = GeminiAuthHandler.buildRequest(
            "gemini-3.5-flash:generateContent",
            jsonRequest
        )

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("FoodRepository", "Gemini API error code: ${response.code}. Attempting USDA API lookup fallback.")
                try {
                    val searchResults = usdaCacheService.searchFoods(cleanInput, customApiKey)
                    if (searchResults.isNotEmpty()) {
                        val bestMatch = searchResults.first()
                        val entry = FoodLogEntry(
                            date = date,
                            foodName = bestMatch.name,
                            mealType = mealType,
                            quantity = 1.0,
                            unit = bestMatch.servingSizeUnit,
                            nutrients = bestMatch.nutrients
                        )
                        dao.insertEntry(entry)
                        return@withContext Result.success(entry)
                    }
                } catch (ex: Exception) {
                    Log.e("FoodRepository", "USDA API lookup fallback failed", ex)
                }

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
                Log.e("FoodRepository", "Payload parsing error. Attempting USDA API lookup fallback.")
                try {
                    val searchResults = usdaCacheService.searchFoods(cleanInput, customApiKey)
                    if (searchResults.isNotEmpty()) {
                        val bestMatch = searchResults.first()
                        val entry = FoodLogEntry(
                            date = date,
                            foodName = bestMatch.name,
                            mealType = mealType,
                            quantity = 1.0,
                            unit = bestMatch.servingSizeUnit,
                            nutrients = bestMatch.nutrients
                        )
                        dao.insertEntry(entry)
                        return@withContext Result.success(entry)
                    }
                } catch (ex: Exception) {
                    Log.e("FoodRepository", "USDA API lookup fallback failed", ex)
                }

                val fallbackEntry = createFallbackEntry(foodInput, date, mealType)
                dao.insertEntry(fallbackEntry)
                return@withContext Result.success(fallbackEntry)
            }
        } catch (e: Exception) {
            Log.e("FoodRepository", "Gemini API query execution failed: ${e.message}. Attempting USDA API lookup fallback.", e)
            try {
                val searchResults = usdaCacheService.searchFoods(cleanInput, customApiKey)
                if (searchResults.isNotEmpty()) {
                    val bestMatch = searchResults.first()
                    val entry = FoodLogEntry(
                        date = date,
                        foodName = bestMatch.name,
                        mealType = mealType,
                        quantity = 1.0,
                        unit = bestMatch.servingSizeUnit,
                        nutrients = bestMatch.nutrients
                    )
                    dao.insertEntry(entry)
                    return@withContext Result.success(entry)
                }
            } catch (ex: Exception) {
                Log.e("FoodRepository", "USDA API lookup fallback failed", ex)
            }

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

        val matchedLookup = FoodLookupDatabase.findMatch(foodInput)
        if (matchedLookup != null) {
            val accumulated = mutableMapOf<String, Double>()
            for (definition in Nutrients.DEFINITIONS) {
                accumulated[definition.key] = matchedLookup.getNutrientValue(definition.key)
            }
            return FoodLogEntry(
                date = date,
                foodName = matchedLookup.name,
                mealType = mealType,
                quantity = 1.0,
                unit = matchedLookup.servingSize,
                nutrients = accumulated
            )
        }

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
        val apiKey = GeminiAuthHandler.getApiKey()

        if (apiKey.isEmpty()) {
            Log.w("FoodRepository", "No valid Gemini API key found, utilizing local fallback multi-day analyzer.")
            val fallbackEntries = parseMultiDayOffline(bulkInput, anchorDate)
            for (entry in fallbackEntries) {
                dao.insertEntry(entry)
            }
            return@withContext Result.success(fallbackEntries)
        }

        val prompt = compileMultiDayPrompt(bulkInput, anchorDate)
        val jsonRequest = buildRequestBodyJson(prompt)

        val request = GeminiAuthHandler.buildRequest(
            "gemini-3.5-flash:generateContent",
            jsonRequest
        )

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
            3. "mealType": One of: "Breakfast", "Lunch", "Dinner", "Snack", "Supplement". If indeterminate, infer based on common composition or context.
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
                lower.startsWith("supplement:") || lower.equals("supplement") || lower.startsWith("supplements:") || lower.equals("supplements") -> {
                    currentMeal = "Supplement"
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

    suspend fun getAllSupplementsDirect(): List<Supplement> = withContext(Dispatchers.IO) {
        supplementDao.getAllSupplementsDirect()
    }

    suspend fun getSupplementById(id: Int): Supplement? = withContext(Dispatchers.IO) {
        supplementDao.getSupplementById(id)
    }

    suspend fun insertSupplement(supplement: Supplement) = withContext(Dispatchers.IO) {
        val standardized = supplement.copy(
            nutrients = standardizeNutrientKeys(supplement.nutrients)
        )
        supplementDao.insertSupplement(standardized)
    }

    suspend fun deleteSupplement(supplement: Supplement) = withContext(Dispatchers.IO) {
        supplementDao.deleteSupplement(supplement)
    }

    suspend fun deleteSupplementById(id: Int) = withContext(Dispatchers.IO) {
        supplementDao.deleteSupplementById(id)
    }

    suspend fun exportSupplementsToJson(): String = withContext(Dispatchers.IO) {
        try {
            val supplements = supplementDao.getAllSupplementsDirect()
            val moshi = Moshi.Builder()
                .add(Double::class.java, ResilientDoubleAdapter)
                .add(Double::class.javaObjectType, ResilientDoubleAdapter)
                .add(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, Supplement::class.java)
            val adapter = moshi.adapter<List<Supplement>>(listType)
            adapter.toJson(supplements)
        } catch (e: java.lang.Exception) {
            Log.e("FoodRepository", "Failed to export supplements JSONString", e)
            ""
        }
    }

    suspend fun importSupplementsFromJson(jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val cleanJson = jsonString.trim().removePrefix("\uFEFF")
            val moshi = Moshi.Builder()
                .add(Double::class.java, ResilientDoubleAdapter)
                .add(Double::class.javaObjectType, ResilientDoubleAdapter)
                .add(KotlinJsonAdapterFactory())
                .build()
            val listType = Types.newParameterizedType(List::class.java, Supplement::class.java)
            val adapter = moshi.adapter<List<Supplement>>(listType)
            val supplements = adapter.fromJson(cleanJson)
            if (supplements != null) {
                var count = 0
                for (supp in supplements) {
                    val cleanSupp = supp.copy(id = 0)
                    insertSupplement(cleanSupp)
                    count++
                }
                Result.success(count)
            } else {
                Result.failure(java.lang.Exception("Parsed supplements list is empty or null"))
            }
        } catch (e: java.lang.Exception) {
            Log.e("FoodRepository", "Failed to import supplements JSONString", e)
            Result.failure(e)
        }
    }

    suspend fun autoLogSupplementsForDate(dateString: String): Int = autoLogMutex.withLock {
        withContext(Dispatchers.IO) {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = try {
                format.parse(dateString)
            } catch (e: Exception) {
                null
            } ?: return@withContext 0

            val calendar = Calendar.getInstance().apply { time = date }
            val dayOfWeekName = SimpleDateFormat("EEEE", Locale.US).format(date) // e.g. "Monday"

            val supplements = supplementDao.getAllSupplementsDirect()
            if (supplements.isEmpty()) return@withContext 0

            val existingEntries = dao.getAllEntriesDirect().filter { it.date == dateString }
            val supplementLogsForDay = existingEntries.filter { it.mealType == "Supplement" }

            var countAdded = 0

            for (supplement in supplements) {
                val isScheduledToday = when (supplement.frequency) {
                    "Once Daily", "Twice Daily" -> true
                    "Weekly" -> {
                        supplement.daysOfWeek.split(",")
                            .any { it.trim().equals(dayOfWeekName, ignoreCase = true) }
                    }
                    "Alternate Days" -> {
                        (calendar.timeInMillis / (24L * 60L * 60L * 1000L)) % 2L == 0L
                    }
                    else -> true
                }

                if (!isScheduledToday) continue

                val expectedLogNames = if (supplement.frequency == "Twice Daily") {
                    listOf(
                        "${supplement.name} (${supplement.dosage}) - Morning",
                        "${supplement.name} (${supplement.dosage}) - Evening"
                    )
                } else {
                    listOf("${supplement.name} (${supplement.dosage})")
                }

                for (expectedName in expectedLogNames) {
                    val alreadyLogged = supplementLogsForDay.any {
                        it.foodName.equals(expectedName, ignoreCase = true) ||
                        (it.foodName.startsWith(supplement.name, ignoreCase = true) && 
                         it.foodName.contains(supplement.dosage) && 
                         (expectedLogNames.size == 1 || it.foodName.contains(expectedName.substringAfter("- "))))
                    }

                    if (!alreadyLogged) {
                        val calculatedNutrients = if (supplement.nutrients.isNotEmpty()) {
                            standardizeNutrientKeys(supplement.nutrients)
                        } else {
                            estimateSupplementNutrients(supplement.name, supplement.dosage)
                        }

                        val newLog = FoodLogEntry(
                            id = 0,
                            date = dateString,
                            foodName = expectedName,
                            mealType = "Supplement",
                            quantity = 1.0,
                            unit = "serving",
                            nutrients = standardizeNutrientKeys(calculatedNutrients)
                        )

                        dao.insertEntry(newLog)
                        countAdded++
                    }
                }
            }

            countAdded
        }
    }

    fun estimateSupplementNutrients(name: String, dosage: String): Map<String, Double> {
        val lower = name.lowercase()
        val lowerDosage = dosage.lowercase()
        val accumulated = mutableMapOf<String, Double>()
        for (definition in Nutrients.DEFINITIONS) {
            accumulated[definition.key] = 0.0
        }

        var parsedValueNumeric = 0.0
        try {
            val match = Regex("([0-9.,]+)").find(lowerDosage)
            if (match != null) {
                parsedValueNumeric = match.groupValues[1].replace(",", "").toDouble()
            }
        } catch (e: Exception) {
            // Ignore
        }

        if (lower.contains("vitamin d") || lower.contains("d3")) {
            if (parsedValueNumeric > 0.0) {
                if (lowerDosage.contains("iu")) {
                    accumulated["vitamin_d"] = parsedValueNumeric * 0.025
                } else if (lowerDosage.contains("mcg") || lowerDosage.contains("µg")) {
                    accumulated["vitamin_d"] = parsedValueNumeric
                } else {
                    accumulated["vitamin_d"] = 15.0
                }
            } else {
                accumulated["vitamin_d"] = 25.0
            }
        } else if (lower.contains("vitamin c") || lower.contains("ascorbic")) {
            if (parsedValueNumeric > 0.0 && (lowerDosage.contains("mg") || lowerDosage.contains("g"))) {
                val factor = if (lowerDosage.contains(" g") || lowerDosage.endsWith("g") && !lowerDosage.endsWith("mg")) 1000.0 else 1.0
                accumulated["vitamin_c"] = parsedValueNumeric * factor
            } else {
                accumulated["vitamin_c"] = 500.0
            }
        } else if (lower.contains("magnesium")) {
            if (parsedValueNumeric > 0.0 && lowerDosage.contains("mg")) {
                accumulated["magnesium"] = parsedValueNumeric
            } else {
                accumulated["magnesium"] = 250.0
            }
        } else if (lower.contains("iron")) {
            if (parsedValueNumeric > 0.0 && lowerDosage.contains("mg")) {
                accumulated["iron"] = parsedValueNumeric
            } else {
                accumulated["iron"] = 18.0
            }
        } else if (lower.contains("calcium")) {
            if (parsedValueNumeric > 0.0 && lowerDosage.contains("mg")) {
                accumulated["calcium"] = parsedValueNumeric
            } else {
                accumulated["calcium"] = 500.0
            }
        } else if (lower.contains("zinc")) {
            if (parsedValueNumeric > 0.0 && lowerDosage.contains("mg")) {
                accumulated["zinc"] = parsedValueNumeric
            } else {
                accumulated["zinc"] = 15.0
            }
        } else if (lower.contains("omega") || lower.contains("fish oil")) {
            accumulated["polyunsaturated_fat"] = 1.0
            accumulated["omega3"] = 1.0
            accumulated["calories"] = 10.0
            accumulated["fat"] = 1.0
        } else if (lower.contains("b12") || lower.contains("methylcobalamin") || lower.contains("b-12")) {
            accumulated["vitamin_b12"] = if (parsedValueNumeric > 0.0) parsedValueNumeric else 1000.0
        } else if (lower.contains("folate") || lower.contains("folic")) {
            accumulated["folate"] = if (parsedValueNumeric > 0.0) parsedValueNumeric else 400.0
        } else if (lower.contains("multivitamin") || lower.contains("multi") || lower.contains("max") || lower.contains("supp")) {
            accumulated["vitamin_a"] = 900.0
            accumulated["vitamin_c"] = 90.0
            accumulated["vitamin_d"] = 15.0
            accumulated["vitamin_e"] = 15.0
            accumulated["vitamin_b12"] = 2.4
            accumulated["folate"] = 400.0
            accumulated["zinc"] = 11.0
            accumulated["iron"] = 18.0
            accumulated["magnesium"] = 50.0
        }

        return accumulated
    }

    fun getProductByBarcode(barcode: String): BarcodeProduct? {
        val clean = barcode.trim()
        val products = mapOf(
            "885101234567" to BarcodeProduct(
                name = "Premium Whey Protein",
                dosage = "1 scoop (30g)",
                isSupplement = true,
                nutrients = mapOf("calories" to 120.0, "protein" to 25.0, "calcium" to 150.0, "potassium" to 160.0)
            ),
            "8851012345678" to BarcodeProduct(
                name = "Premium Whey Protein",
                dosage = "1 scoop (30g)",
                isSupplement = true,
                nutrients = mapOf("calories" to 120.0, "protein" to 25.0, "calcium" to 150.0, "potassium" to 160.0)
            ),
            "190111222333" to BarcodeProduct(
                name = "Daily Multivitamin Tablet",
                dosage = "1 tablet",
                isSupplement = true,
                nutrients = mapOf("calories" to 0.0, "vitamin_c" to 90.0, "vitamin_d" to 25.0, "zinc" to 15.0, "iron" to 18.0)
            ),
            "1901112223334" to BarcodeProduct(
                name = "Daily Multivitamin Tablet",
                dosage = "1 tablet",
                isSupplement = true,
                nutrients = mapOf("calories" to 0.0, "vitamin_c" to 90.0, "vitamin_d" to 25.0, "zinc" to 15.0, "iron" to 18.0)
            ),
            "041500000251" to BarcodeProduct(
                name = "Greek Yogurt with Chia & Honey",
                dosage = "1 container (150g)",
                isSupplement = false,
                nutrients = mapOf("calories" to 150.0, "protein" to 12.0, "carbohydrates" to 18.0, "calcium" to 200.0)
            ),
            "0415000002511" to BarcodeProduct(
                name = "Greek Yogurt with Chia & Honey",
                dosage = "1 container (150g)",
                isSupplement = false,
                nutrients = mapOf("calories" to 150.0, "protein" to 12.0, "carbohydrates" to 18.0, "calcium" to 200.0)
            ),
            "012000000133" to BarcodeProduct(
                name = "Organic Rolled Oats with Banana",
                dosage = "1 cup (40g cooked)",
                isSupplement = false,
                nutrients = mapOf("calories" to 190.0, "protein" to 6.0, "carbohydrates" to 34.0, "fiber" to 5.0, "potassium" to 150.0)
            ),
            "0120000001335" to BarcodeProduct(
                name = "Organic Rolled Oats with Banana",
                dosage = "1 cup (40g cooked)",
                isSupplement = false,
                nutrients = mapOf("calories" to 190.0, "protein" to 6.0, "carbohydrates" to 34.0, "fiber" to 5.0, "potassium" to 150.0)
            ),
            "490000004433" to BarcodeProduct(
                name = "Matcha Green Tea",
                dosage = "1 serving",
                isSupplement = false,
                nutrients = mapOf("calories" to 5.0, "vitamin_c" to 10.0, "potassium" to 30.0)
            ),
            "4900000044331" to BarcodeProduct(
                name = "Matcha Green Tea",
                dosage = "1 serving",
                isSupplement = false,
                nutrients = mapOf("calories" to 5.0, "vitamin_c" to 10.0, "potassium" to 30.0)
            ),
            "301234567890" to BarcodeProduct(
                name = "Pure Magnesium Glycinate",
                dosage = "2 capsules",
                isSupplement = true,
                nutrients = mapOf("magnesium" to 200.0)
            ),
            "3012345678901" to BarcodeProduct(
                name = "Pure Magnesium Glycinate",
                dosage = "2 capsules",
                isSupplement = true,
                nutrients = mapOf("magnesium" to 200.0)
            )
        )
        return products[clean]
    }

    suspend fun parseAndLogBarcode(
        barcode: String,
        date: String,
        mealType: String,
        quantity: Double = 1.0
    ): Result<FoodLogEntry> = withContext(Dispatchers.IO) {
        val localProduct = getProductByBarcode(barcode)
        if (localProduct != null) {
            val entry = FoodLogEntry(
                id = 0,
                date = date,
                foodName = "${localProduct.name} (Scanned)",
                mealType = mealType,
                quantity = quantity,
                unit = if (localProduct.isSupplement) "tablet" else "serving",
                nutrients = localProduct.nutrients
            )
            dao.insertEntry(entry)
            return@withContext Result.success(entry)
        }

        val isNumeric = barcode.trim().all { it.isDigit() }
        if (isNumeric) {
            try {
                val usdaResults = usdaCacheService.searchFoods(barcode)
                if (usdaResults.isNotEmpty()) {
                    val result = usdaResults.first()
                    val entry = FoodLogEntry(
                        id = 0,
                        date = date,
                        foodName = "${result.name} (Scanned)",
                        mealType = mealType,
                        quantity = quantity,
                        unit = result.servingSizeUnit.ifBlank { "serving" },
                        nutrients = result.nutrients
                    )
                    dao.insertEntry(entry)
                    return@withContext Result.success(entry)
                }
            } catch (e: Exception) {
                android.util.Log.e("FoodRepository", "USDA Barcode database lookup failed: ${e.message}", e)
            }
        }

        if (!isNumeric) {
            return@withContext parseAndLogFood(barcode, date, mealType)
        }

        val apiKey = GeminiAuthHandler.getApiKey()

        if (apiKey.isEmpty()) {
            val entryName = "Scanned Product: $barcode"
            val fallbackEntry = createFallbackEntry(entryName, date, mealType).copy(quantity = quantity)
            dao.insertEntry(fallbackEntry)
            return@withContext Result.success(fallbackEntry)
        }

        val prompt = compilePrompt("scanned food or supplement product UPC barcode $barcode")
        val jsonRequest = buildRequestBodyJson(prompt)

        val request = GeminiAuthHandler.buildRequest(
            "gemini-3.5-flash:generateContent",
            jsonRequest
        )

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val fallbackEntry = createFallbackEntry("Scanned Barcode $barcode", date, mealType).copy(quantity = quantity)
                dao.insertEntry(fallbackEntry)
                return@withContext Result.success(fallbackEntry)
            }

            val bodyString = response.body?.string() ?: ""
            val parsedResult = extractNutrientsFromJson(bodyString, "Scanned Barcode: $barcode", date, mealType)
            if (parsedResult != null) {
                val entryToInsert = parsedResult.copy(quantity = quantity)
                dao.insertEntry(entryToInsert)
                return@withContext Result.success(entryToInsert)
            } else {
                val fallbackEntry = createFallbackEntry("Scanned Barcode $barcode", date, mealType).copy(quantity = quantity)
                dao.insertEntry(fallbackEntry)
                return@withContext Result.success(fallbackEntry)
            }
        } catch (e: Exception) {
            val fallbackEntry = createFallbackEntry("Scanned Barcode $barcode", date, mealType).copy(quantity = quantity)
            dao.insertEntry(fallbackEntry)
            return@withContext Result.success(fallbackEntry)
        }
    }

    suspend fun parseAndRegisterScannedSupplement(
        barcode: String
    ): Result<Supplement> = withContext(Dispatchers.IO) {
        val localProduct = getProductByBarcode(barcode)
        if (localProduct != null) {
            val supplement = Supplement(
                name = localProduct.name,
                dosage = localProduct.dosage,
                frequency = "Once Daily",
                timeOfDay = "Morning",
                notes = "Scanned Barcode: $barcode",
                nutrients = localProduct.nutrients
            )
            supplementDao.insertSupplement(supplement)
            return@withContext Result.success(supplement)
        }

        val isNumeric = barcode.trim().all { it.isDigit() }
        if (!isNumeric) {
            val lower = barcode.lowercase()
            val splitParts = barcode.trim().split(",", ";").map { it.trim() }
            val name = if (splitParts.isNotEmpty() && splitParts[0].isNotBlank()) splitParts[0] else "Scanned Supplement"
            val dosage = if (splitParts.size > 1 && splitParts[1].isNotBlank()) splitParts[1] else "1 capsule"
            val frequency = when {
                lower.contains("twice") || lower.contains("2x") -> "Twice Daily"
                lower.contains("weekly") -> "Weekly"
                lower.contains("alternate") -> "Alternate Days"
                else -> "Once Daily"
            }
            val notes = "Scanned QR Guideline"
            
            val supplement = Supplement(
                name = name,
                dosage = dosage,
                frequency = frequency,
                timeOfDay = "Morning",
                notes = notes,
                nutrients = estimateSupplementNutrients(name, dosage)
            )
            supplementDao.insertSupplement(supplement)
            return@withContext Result.success(supplement)
        }

        val apiKey = GeminiAuthHandler.getApiKey()
        if (apiKey.isEmpty()) {
            val supplement = Supplement(
                name = "Multivitamin ($barcode)",
                dosage = "1 tablet",
                frequency = "Once Daily",
                timeOfDay = "Morning",
                notes = "Scanned offline barcode",
                nutrients = estimateSupplementNutrients("multivitamin", "1 tablet")
            )
            supplementDao.insertSupplement(supplement)
            return@withContext Result.success(supplement)
        }

        // Prepare Prompt for Supplement details query
        val templatePrompt = """
            Identify the product UPC barcode '$barcode'. Return a valid JSON content of a clinical supplement with fields:
            name: "Full Brand Product Name Description",
            dosage: "Dosage (e.g. '1 pill' or '500 mg')"
            Output only raw JSON content, no formatting wrappers.
        """.trimIndent()

        val jsonRequest = buildRequestBodyJson(templatePrompt)
        val request = GeminiAuthHandler.buildRequest(
            "gemini-3.5-flash:generateContent",
            jsonRequest
        )

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val supp = Supplement(
                    name = "Supplement ($barcode)",
                    dosage = "1 capsule",
                    frequency = "Once Daily",
                    notes = "Scanned fallback profile",
                    nutrients = estimateSupplementNutrients("multivitamin", "1 capsule")
                )
                supplementDao.insertSupplement(supp)
                return@withContext Result.success(supp)
            }

            val bodyString = response.body?.string() ?: ""
            var finalName = "Supplement ($barcode)"
            var finalDosage = "1 capsule"
            try {
                val root = org.json.JSONObject(bodyString)
                val candidates = root.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    if (content != null) {
                        val parts = content.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val textPart = parts.getJSONObject(0).optString("text", "")
                            val cleanJsonString = if (textPart.contains("```json")) {
                                textPart.substringAfter("```json").substringBefore("```").trim()
                            } else if (textPart.contains("```")) {
                                textPart.substringAfter("```").substringBefore("```").trim()
                            } else {
                                textPart.trim()
                            }
                            val innerJson = org.json.JSONObject(cleanJsonString)
                            finalName = innerJson.optString("name", "Supplement ($barcode)")
                            finalDosage = innerJson.optString("dosage", "1 capsule")
                        }
                    }
                }
            } catch (jsonEx: Exception) {
                Log.e("FoodRepository", "Manual JSON parsing failed, trying simple extraction", jsonEx)
                val cleanedJson = bodyString.substringAfter("{").substringBeforeLast("}")
                val nameParsed = cleanedJson.substringAfter("\"name\"").substringAfter("\"").substringBefore("\"").trim()
                val dosageParsed = cleanedJson.substringAfter("\"dosage\"").substringAfter("\"").substringBefore("\"").trim()
                if (nameParsed.isNotEmpty() && !nameParsed.contains("{")) finalName = nameParsed
                if (dosageParsed.isNotEmpty() && !dosageParsed.contains("{")) finalDosage = dosageParsed
            }

            val finalNutrients = estimateSupplementNutrients(finalName, finalDosage)
            val supp = Supplement(
                name = finalName,
                dosage = finalDosage,
                frequency = "Once Daily",
                timeOfDay = "Morning",
                notes = "Auto-scanned $barcode",
                nutrients = finalNutrients
            )
            supplementDao.insertSupplement(supp)
            return@withContext Result.success(supp)
        } catch (e: Exception) {
            val supp = Supplement(
                name = "Vitamins ($barcode)",
                dosage = "1 capsule",
                frequency = "Once Daily",
                notes = "Scanned backup profile",
                nutrients = estimateSupplementNutrients("multivitamin", "1 capsule")
            )
            supplementDao.insertSupplement(supp)
            return@withContext Result.success(supp)
        }
    }

    fun standardizeNutrientKey(rawKey: String): String {
        val normalized = rawKey.lowercase().replace("_", " ").replace("-", " ").trim()
        
        return when {
            // Calories
            normalized.startsWith("calorie") || normalized == "kcal" || normalized == "energy" -> "calories"
            
            // Macronutrients
            normalized.startsWith("carb") || normalized == "carbohydrate" -> "carbohydrates"
            normalized == "protein" || normalized.startsWith("protein") -> "protein"
            normalized == "fat" || normalized == "total fat" || normalized.startsWith("total fat") || normalized.startsWith("fat") -> "fat"
            normalized == "fiber" || normalized.startsWith("dietary fiber") || normalized.startsWith("fiber") -> "fiber"
            normalized == "water" || normalized.startsWith("hydration") || normalized.startsWith("water") -> "water"
            
            // Lipids
            normalized.startsWith("saturated fat") || normalized.contains("saturated fat") -> "saturated_fat"
            normalized.startsWith("trans fat") || normalized.contains("trans fat") -> "trans_fat"
            normalized.startsWith("monounsaturated fat") || normalized.contains("monounsaturated") -> "monounsaturated_fat"
            normalized.startsWith("polyunsaturated fat") || normalized.contains("polyunsaturated") -> "polyunsaturated_fat"
            normalized.contains("omega 3") || normalized.contains("omega3") || normalized.contains("n 3") -> "omega3"
            normalized.contains("omega 6") || normalized.contains("omega6") || normalized.contains("n 6") -> "omega6"
            normalized == "cholesterol" || normalized.startsWith("cholesterol") -> "cholesterol"
            
            // Vitamins
            normalized.startsWith("vitamin a") || normalized.startsWith("vit a") || normalized.startsWith("retinol") -> "vitamin_a"
            normalized.startsWith("vitamin c") || normalized.startsWith("vit c") || normalized.startsWith("ascorbic") -> "vitamin_c"
            normalized.startsWith("vitamin d") || normalized.startsWith("vit d") || normalized.contains("d3") || normalized.startsWith("calciferol") || normalized.contains("cholecalciferol") -> "vitamin_d"
            normalized.startsWith("vitamin e") || normalized.startsWith("vit e") || normalized.contains("tocopherol") -> "vitamin_e"
            normalized.startsWith("vitamin k") || normalized.startsWith("vit k") || normalized.contains("k1") || normalized.contains("k2") || normalized.startsWith("phylloquinone") || normalized.startsWith("menaquinone") -> "vitamin_k"
            normalized.startsWith("vitamin b12") || normalized.contains("b12") || normalized.contains("cobalamin") || normalized.contains("cyanocobalamin") || normalized.contains("methylcobalamin") -> "vitamin_b12"
            normalized.startsWith("thiamin") || normalized.contains("vitamin b1") || normalized.contains("b1") -> "thiamin"
            normalized.startsWith("riboflavin") || normalized.contains("vitamin b2") || normalized.contains("b2") -> "riboflavin"
            normalized.startsWith("niacin") || normalized.contains("vitamin b3") || normalized.contains("b3") || normalized.contains("nicotinic") || normalized.contains("niacinamide") -> "niacin"
            normalized.startsWith("pantothenic") || normalized.contains("vitamin b5") || normalized.contains("b5") || normalized.contains("pantothenate") -> "pantothenic_acid"
            normalized.startsWith("vitamin b6") || normalized.contains("b6") || normalized.contains("pyridoxine") || normalized.contains("pyridoxal") -> "vitamin_b6"
            normalized.startsWith("biotin") || normalized.contains("b7") || normalized.contains("vitamin h") -> "biotin"
            normalized.startsWith("folic") || normalized.contains("folate") || normalized.contains("b9") -> "folate"
            normalized == "choline" || normalized.startsWith("choline") -> "choline"
            
            // Minerals
            normalized.startsWith("calcium") -> "calcium"
            normalized.startsWith("iron") -> "iron"
            normalized.startsWith("magnesium") -> "magnesium"
            normalized.startsWith("phosphorus") -> "phosphorus"
            normalized.startsWith("potassium") -> "potassium"
            normalized.startsWith("sodium") -> "sodium"
            normalized.startsWith("zinc") -> "zinc"
            normalized.startsWith("copper") -> "copper"
            normalized.startsWith("manganese") -> "manganese"
            normalized.startsWith("selenium") -> "selenium"
            normalized.startsWith("chromium") -> "chromium"
            normalized.startsWith("molybdenum") -> "molybdenum"
            normalized.startsWith("iodine") -> "iodine"
            
            // Others
            normalized.startsWith("sugar") -> "sugars"
            
            // Fallback: keep original clean lower key if it matches any standard key directly
            else -> {
                val dbMatch = com.example.data.Nutrients.DEFAULT_DEFINITIONS.find { it.key == rawKey }
                dbMatch?.key ?: rawKey
            }
        }
    }

    fun standardizeNutrientKeys(rawNutrients: Map<String, Double>): Map<String, Double> {
        val isMultiMax = rawNutrients.keys.any { key ->
            key.any { it.isUpperCase() } && (key.contains("µg") || key.contains("mg") || key.contains("mcg") || key.contains("("))
        }
        if (isMultiMax) {
            val logMsg = "Multi-Max data flow detected: Raw keys: ${rawNutrients.keys}"
            android.util.Log.d("NutritionRepository", logMsg)
            println("[NutritionRepository] $logMsg")
        }
        val standardized = mutableMapOf<String, Double>()
        for ((key, value) in rawNutrients) {
            val standardizedKey = standardizeNutrientKey(key)
            if (isMultiMax) {
                val logItem = "Standardizing Multi-Max key: '$key' -> '$standardizedKey' (Value: $value)"
                android.util.Log.d("NutritionRepository", logItem)
                println("[NutritionRepository] $logItem")
            }
            standardized[standardizedKey] = (standardized[standardizedKey] ?: 0.0) + value
        }
        if (isMultiMax) {
            val finalLogMsg = "Multi-Max standardization result: $standardized"
            android.util.Log.d("NutritionRepository", finalLogMsg)
            println("[NutritionRepository] $finalLogMsg")
        }
        return standardized
    }
}

data class BarcodeProduct(
    val name: String,
    val dosage: String,
    val isSupplement: Boolean,
    val nutrients: Map<String, Double>
)
