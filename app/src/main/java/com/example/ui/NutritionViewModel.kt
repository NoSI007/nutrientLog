package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FoodLogEntry
import com.example.data.FoodRepository
import com.example.data.NutrientDefinition
import com.example.data.NutrientGroup
import com.example.data.Nutrients
import com.example.data.FavoriteMeal
import com.example.data.FavoriteMealFoodItem
import com.example.data.OnlineFoodResult
import com.example.data.NutritionApiIntegration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class NutrientStatus(
    val definition: NutrientDefinition,
    val intake: Double,
    val percentage: Double, // intake / rda * 100
    val status: StatusColor // RED, YELLOW, GREEN
)

enum class StatusColor {
    RED,    // Critical (deficient or limit exceeded)
    YELLOW, // Sub-optimal (50-99% for goals)
    GREEN   // Healthy (Met or within healthy limit)
}

data class DayTrend(
    val dateString: String, // "YYYY-MM-DD"
    val displayDate: String, // "Mon", "Tue" etc.
    val calories: Double,
    val carbsGrams: Double,
    val proteinGrams: Double,
    val fatGrams: Double
)

class NutritionViewModel(application: Application) : AndroidViewModel(application) {
    val repository = FoodRepository(application)
    val usdaCacheService = com.example.data.UsdaCacheService(application)
    private val sharedPrefs = application.getSharedPreferences("nutrition_targets", Context.MODE_PRIVATE)
    private val localStorage = application.getSharedPreferences("local_storage", Context.MODE_PRIVATE)

    private val _manualFoodLogs = MutableStateFlow<List<ManualFoodLog>>(emptyList())
    val manualFoodLogs: StateFlow<List<ManualFoodLog>> = _manualFoodLogs.asStateFlow()

    private val _onlineSearchQuery = MutableStateFlow("")
    val onlineSearchQuery: StateFlow<String> = _onlineSearchQuery.asStateFlow()

    private val _onlineSearchResults = MutableStateFlow<List<OnlineFoodResult>>(emptyList())
    val onlineSearchResults: StateFlow<List<OnlineFoodResult>> = _onlineSearchResults.asStateFlow()

    private val _isOnlineSearching = MutableStateFlow(false)
    val isOnlineSearching: StateFlow<Boolean> = _isOnlineSearching.asStateFlow()

    private val _usdaApiKey = MutableStateFlow(sharedPrefs.getString("usda_api_key", "") ?: "")
    val usdaApiKey: StateFlow<String> = _usdaApiKey.asStateFlow()

    private val _nutritionixAppId = MutableStateFlow(sharedPrefs.getString("nutritionix_app_id", "") ?: "")
    val nutritionixAppId: StateFlow<String> = _nutritionixAppId.asStateFlow()

    private val _nutritionixApiKey = MutableStateFlow(sharedPrefs.getString("nutritionix_api_key", "") ?: "")
    val nutritionixApiKey: StateFlow<String> = _nutritionixApiKey.asStateFlow()

    fun saveApiCredentials(usdaKey: String, ixAppId: String, ixApiKey: String) {
        sharedPrefs.edit()
            .putString("usda_api_key", usdaKey.trim())
            .putString("nutritionix_app_id", ixAppId.trim())
            .putString("nutritionix_api_key", ixApiKey.trim())
            .apply()
        _usdaApiKey.value = usdaKey.trim()
        _nutritionixAppId.value = ixAppId.trim()
        _nutritionixApiKey.value = ixApiKey.trim()
        _operationMessage.value = "Updated API Integration credentials!"
    }

    private val _themeMode = MutableStateFlow(sharedPrefs.getString("theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        sharedPrefs.edit().putString("theme_mode", mode).apply()
    }

    private val _currentDate = MutableStateFlow(getTodayDateString())
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _customRdaOverrides = MutableStateFlow<Map<String, Double>>(emptyMap())
    val customRdaOverrides: StateFlow<Map<String, Double>> = _customRdaOverrides.asStateFlow()

    val allLogEntries: StateFlow<List<FoodLogEntry>> = repository.allEntries
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allSupplements: StateFlow<List<com.example.data.Supplement>> = repository.allSupplements
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allFavoriteMeals: StateFlow<List<com.example.data.FavoriteMeal>> = repository.allFavoriteMeals
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allFavoriteFoods: StateFlow<List<com.example.data.FavoriteFood>> = repository.allFavoriteFoods
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val consecutiveMissedAlerts: StateFlow<List<ConsecutiveMissedAlert>> = combine(allLogEntries, customRdaOverrides) { entries, overrides ->
        if (entries.isEmpty()) return@combine emptyList()
        
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        // Find earliest log entry
        val earliestDateStr = entries.minOfOrNull { it.date } ?: return@combine emptyList()
        
        val alerts = mutableListOf<ConsecutiveMissedAlert>()
        
        // Group entries by date
        val groupedEntries = entries.groupBy { it.date }
        
        // Get today and yesterday dates
        val todayCal = Calendar.getInstance()
        val todayStr = format.format(todayCal.time)
        todayCal.add(Calendar.DATE, -1)
        val yesterdayStr = format.format(todayCal.time)
        
        // Helper to compute backward streak starting from a specified date
        fun getBackwardStreak(startDateStr: String, key: String, rdaValue: Double): Int {
            var streak = 0
            val cal = Calendar.getInstance()
            val parsedStart = try { format.parse(startDateStr) } catch(e: Exception) { null } ?: return 0
            cal.time = parsedStart
            
            while (true) {
                val curDateStr = format.format(cal.time)
                if (curDateStr < earliestDateStr) break
                
                // Get all logs on curDateStr
                val dayLogs = groupedEntries[curDateStr] ?: emptyList()
                val totalIntake = dayLogs.sumOf { (it.nutrients[key] ?: 0.0) * it.quantity }
                
                if (totalIntake < rdaValue) {
                    streak++
                    cal.add(Calendar.DATE, -1)
                } else {
                    break
                }
            }
            return streak
        }
        
        for (def in com.example.data.Nutrients.DEFINITIONS) {
            // Only apply to goals (non max-limits) and strictly positive rdas
            if (def.isMaxLimit || def.rda <= 0.0) continue
            
            val rdaValue = overrides[def.key] ?: def.rda
            
            // Calculate streak ending today or ending yesterday
            val todayStreak = getBackwardStreak(todayStr, def.key, rdaValue)
            val yesterdayStreak = getBackwardStreak(yesterdayStr, def.key, rdaValue)
            
            val bestStreak = maxOf(todayStreak, yesterdayStreak)
            val endType = if (todayStreak >= yesterdayStreak) "Today" else "Yesterday"
            
            // "missed for more than three consecutive days" -> streak > 3 (4 or more days)
            if (bestStreak > 3) {
                val endPhrase = if (endType == "Today") "through today" else "through yesterday"
                val desc = "You missed your daily target of ${rdaValue.toInt()} ${def.unit} for $bestStreak consecutive days ($endPhrase)."
                alerts.add(
                    ConsecutiveMissedAlert(
                        nutrientKey = def.key,
                        nutrientName = def.name,
                        group = def.group,
                        unit = def.unit,
                        consecutiveDays = bestStreak,
                        rdaValue = rdaValue,
                        streakEndType = endPhrase,
                        description = desc
                    )
                )
            }
        }
        // Group or sort alerts by severity (highest consecutiveDays first)
        alerts.sortedByDescending { it.consecutiveDays }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _observationDaysLimit = MutableStateFlow(30)
    val observationDaysLimit: StateFlow<Int> = _observationDaysLimit.asStateFlow()

    private val _selectedObservationNutrientKey = MutableStateFlow("calories")
    val selectedObservationNutrientKey: StateFlow<String> = _selectedObservationNutrientKey.asStateFlow()

    private val _profileAge = MutableStateFlow(30)
    val profileAge: StateFlow<Int> = _profileAge.asStateFlow()

    private val _profileActivity = MutableStateFlow("Lightly Active")
    val profileActivity: StateFlow<String> = _profileActivity.asStateFlow()

    private val _profileSex = MutableStateFlow("Female")
    val profileSex: StateFlow<String> = _profileSex.asStateFlow()

    private val _profileWeight = MutableStateFlow(70.0)
    val profileWeight: StateFlow<Double> = _profileWeight.asStateFlow()

    private val _profileGoal = MutableStateFlow("Balanced / Maintenance")
    val profileGoal: StateFlow<String> = _profileGoal.asStateFlow()

    private val _activeHealthPreset = MutableStateFlow("None")
    val activeHealthPreset: StateFlow<String> = _activeHealthPreset.asStateFlow()

    private val _aiNutritionalTip = MutableStateFlow<String?>(null)
    val aiNutritionalTip: StateFlow<String?> = _aiNutritionalTip.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    private val _isGeminiResponseActive = MutableStateFlow(false)
    val isGeminiResponseActive: StateFlow<Boolean> = _isGeminiResponseActive.asStateFlow()

    private val _targetSuggestedFoods = MutableStateFlow<List<FoodSuggestion>>(emptyList())
    val targetSuggestedFoods: StateFlow<List<FoodSuggestion>> = _targetSuggestedFoods.asStateFlow()

    private val _isSuggestionsLoading = MutableStateFlow(false)
    val isSuggestionsLoading: StateFlow<Boolean> = _isSuggestionsLoading.asStateFlow()

    private val _suggestionsError = MutableStateFlow<String?>(null)
    val suggestionsError: StateFlow<String?> = _suggestionsError.asStateFlow()

    private val _dailySummaryNotificationsEnabled = MutableStateFlow(false)
    val dailySummaryNotificationsEnabled: StateFlow<Boolean> = _dailySummaryNotificationsEnabled.asStateFlow()

    private val _localCustomFoods = MutableStateFlow<List<CustomStateFoodItem>>(emptyList())
    val localCustomFoods: StateFlow<List<CustomStateFoodItem>> = _localCustomFoods.asStateFlow()

    private val _aiSuggestedRecipes = MutableStateFlow<List<AiRecipeSuggestion>>(emptyList())
    val aiSuggestedRecipes: StateFlow<List<AiRecipeSuggestion>> = _aiSuggestedRecipes.asStateFlow()

    private val _isRecipesLoading = MutableStateFlow(false)
    val isRecipesLoading: StateFlow<Boolean> = _isRecipesLoading.asStateFlow()

    private val _recipesError = MutableStateFlow<String?>(null)
    val recipesError: StateFlow<String?> = _recipesError.asStateFlow()

    private val _plannedMenu = MutableStateFlow<List<PlannedMeal>>(emptyList())
    val plannedMenu: StateFlow<List<PlannedMeal>> = _plannedMenu.asStateFlow()

    private val _isPlanningLoading = MutableStateFlow(false)
    val isPlanningLoading: StateFlow<Boolean> = _isPlanningLoading.asStateFlow()

    private val _planningError = MutableStateFlow<String?>(null)
    val planningError: StateFlow<String?> = _planningError.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // --- Gemini Diagnostics & Settings states ---
    private val _geminiActiveModel = MutableStateFlow(com.example.data.GeminiAuthHandler.getActiveModel())
    val geminiActiveModel: StateFlow<String> = _geminiActiveModel.asStateFlow()

    private val _geminiCustomApiKey = MutableStateFlow(com.example.data.GeminiAuthHandler.getCustomSavedKey())
    val geminiCustomApiKey: StateFlow<String> = _geminiCustomApiKey.asStateFlow()

    private val _geminiDiagnosticResult = MutableStateFlow<String?>(null)
    val geminiDiagnosticResult: StateFlow<String?> = _geminiDiagnosticResult.asStateFlow()

    private val _geminiDiagnosticLoading = MutableStateFlow(false)
    val geminiDiagnosticLoading: StateFlow<Boolean> = _geminiDiagnosticLoading.asStateFlow()

    fun updateGeminiModel(model: String) {
        com.example.data.GeminiAuthHandler.saveActiveModel(model)
        _geminiActiveModel.value = model
        _operationMessage.value = "Gemini Engine set to: $model"
    }

    fun updateGeminiCustomApiKey(key: String) {
        com.example.data.GeminiAuthHandler.saveCustomKey(key)
        _geminiCustomApiKey.value = key
        _operationMessage.value = "Saved Custom Gemini API Key!"
    }

    fun runGeminiDiagnostics() {
        _geminiDiagnosticLoading.value = true
        _geminiDiagnosticResult.value = "Running API diagnostics... Connecting to Google AI Registry..."
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val key = com.example.data.GeminiAuthHandler.getApiKey()
            if (key.isEmpty()) {
                _geminiDiagnosticResult.value = "❌ ERROR: No API Key found!\n\nReason: The key resolves to an empty string. Please generate a new key in Google AI Studio (https://aistudio.google.com/app/apikey) and configure it in the Secrets panel or enter it above as a custom key."
                _geminiDiagnosticLoading.value = false
                return@launch
            }
            
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
                .addHeader("Accept", "application/json")
                .get()
                .build()
                
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    
                    val resultText = buildString {
                        append("=== DIAGNOSTIC REPORT ===\n")
                        append("Connection status: ${if (response.isSuccessful) "CONNECTED (HTTP 200)" else "FAILED (HTTP $code)"}\n\n")
                        
                        if (response.isSuccessful) {
                            append("✅ SUCCESS! Your API Key is active and authorized.\n")
                            append("The model registry list was fetched successfully.\n\n")
                            if (body.contains("\"name\"")) {
                                val modelCount = body.split("\"name\"").size - 1
                                append("Found $modelCount available Gemini models in registry.\n")
                            } else {
                                append("No models returned but registry responded successfully.\n")
                            }
                        } else {
                            append("❌ AUTHORIZATION FAILURE!\n")
                            append("HTTP Status: $code ${response.message}\n\n")
                            
                            if (body.contains("leaked") || body.contains("Leaked") || body.contains("PERMISSION_DENIED")) {
                                append("Critical Alert: Google has marked your API key as LEAKED or INVALID!\n")
                                append("Google's security system automatically disables leaked keys if they are found in public code repositories, public builds, or log dumps. To protect your billing or resource limits, this key CANNOT be used.\n\n")
                                append("👉 ACTION REQUIRED:\n")
                                append("1. Go to Google AI Studio: https://aistudio.google.com/app/apikey\n")
                                append("2. Generate a brand new API key.\n")
                                append("3. Enter it securely into the Secrets panel or as a Custom Key above.\n")
                            } else {
                                append("Response Details:\n$body\n")
                            }
                        }
                    }
                    
                    _geminiDiagnosticResult.value = resultText
                }
            } catch (e: Exception) {
                _geminiDiagnosticResult.value = "❌ NETWORK ERROR: ${e.localizedMessage}\n\nPlease check your internet connection and try again."
            } finally {
                _geminiDiagnosticLoading.value = false
            }
        }
    }

    init {
        // Initialize Gemini Auth Handler
        com.example.data.GeminiAuthHandler.initialize(application)

        // Load the local JSON-based nutrient database structure
        com.example.data.Nutrients.loadFromDatabase(application)

        // Seed 41 essential nutrients to Room database schema asynchronously
        viewModelScope.launch {
            repository.ensureNutrientsSeeded()
        }

        // Load custom RDA overrides
        val saved = mutableMapOf<String, Double>()
        val excludedKeys = setOf("profile_age", "profile_activity", "profile_sex", "profile_weight", "profile_goal", "observation_days_limit", "daily_summary_notifications_enabled", "active_health_preset")
        sharedPrefs.all.forEach { (key, value) ->
            if (key !in excludedKeys) {
                when (value) {
                    is Float -> saved[key] = value.toDouble()
                    is Double -> saved[key] = value
                    is Int -> saved[key] = value.toDouble()
                    is Long -> saved[key] = value.toDouble()
                    is String -> value.toDoubleOrNull()?.let { saved[key] = it }
                }
            }
        }
        _customRdaOverrides.value = saved
        _observationDaysLimit.value = sharedPrefs.getInt("observation_days_limit", 30)
        _profileAge.value = sharedPrefs.getInt("profile_age", 30)
        _profileWeight.value = sharedPrefs.getFloat("profile_weight", 70.0f).toDouble()
        _profileActivity.value = sharedPrefs.getString("profile_activity", "Lightly Active") ?: "Lightly Active"
        _profileSex.value = sharedPrefs.getString("profile_sex", "Female") ?: "Female"
        _profileGoal.value = sharedPrefs.getString("profile_goal", "Balanced / Maintenance") ?: "Balanced / Maintenance"
        _activeHealthPreset.value = sharedPrefs.getString("active_health_preset", "None") ?: "None"
        _dailySummaryNotificationsEnabled.value = sharedPrefs.getBoolean("daily_summary_notifications_enabled", false)

        var customFoodsJson = sharedPrefs.getString("local_custom_foods", null)
        if (customFoodsJson.isNullOrBlank()) {
            val internalFile = java.io.File(application.filesDir, "local_custom_foods_backup.json")
            if (internalFile.exists()) {
                customFoodsJson = internalFile.readText()
            }
        }
        if (customFoodsJson.isNullOrBlank()) {
            application.getExternalFilesDir(null)?.let { dir ->
                val externalFile = java.io.File(dir, "local_custom_foods_backup.json")
                if (externalFile.exists()) {
                    customFoodsJson = externalFile.readText()
                }
            }
        }

        val loadedCustomFoods = mutableListOf<CustomStateFoodItem>()
        if (customFoodsJson != null) {
            try {
                val array = org.json.JSONArray(customFoodsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    loadedCustomFoods.add(
                        CustomStateFoodItem(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.getString("name"),
                            portionSize = obj.getDouble("portionSize"),
                            unit = obj.getString("unit")
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _localCustomFoods.value = loadedCustomFoods

        loadManualFoodLogs()

        viewModelScope.launch {
            _currentDate.collect { dateStr ->
                try {
                    repository.autoLogSupplementsForDate(dateStr)
                } catch (e: Exception) {
                    android.util.Log.e("NutritionViewModel", "Auto-logging supplements failed", e)
                }
            }
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val existing = repository.allEntries.first()
                if (existing.isNotEmpty()) {
                    android.util.Log.i("NutritionViewModel", "Local Room database contains existing data. Preserving local state as source of truth.")
                } else {
                    android.util.Log.i("NutritionViewModel", "Local Room database is empty. Attempting to restore from Firestore...")
                    // Prioritize loading from Firestore primary storage backend when local is completely empty
                    val firestoreEntries = com.example.data.FirestoreService.getAllFoodLogEntries()
                    if (firestoreEntries.isNotEmpty()) {
                        android.util.Log.i("NutritionViewModel", "Found ${firestoreEntries.size} food log entries in Firestore. Synchronizing to local Room database.")
                        repository.deleteAllEntriesLocalOnly()
                        repository.insertEntriesLocalOnly(firestoreEntries)
                    } else {
                        android.util.Log.i("NutritionViewModel", "No entries found in Firestore (or offline). Checking local backups.")
                        // Restore from persistent JSON backup files or SharedPreferences if available
                        var backupJson = sharedPrefs.getString("backup_food_logs", null)
                        if (backupJson.isNullOrBlank()) {
                            val internalFile = java.io.File(application.filesDir, "backup_food_logs.json")
                            if (internalFile.exists()) {
                                backupJson = internalFile.readText()
                            }
                        }
                        if (backupJson.isNullOrBlank()) {
                            application.getExternalFilesDir(null)?.let { dir ->
                                val externalFile = java.io.File(dir, "backup_food_logs.json")
                                if (externalFile.exists()) {
                                    backupJson = externalFile.readText()
                                }
                            }
                        }

                        if (!backupJson.isNullOrBlank()) {
                            repository.importFromJson(backupJson)
                        } else {
                            // If completely empty, prepopulate sample logs (which will sync to Firestore on insertion)
                            prepopulateSampleLogs()
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NutritionViewModel", "Firestore initialization or sync failed, falling back to local prepopulation check", t)
                try {
                    val existing = repository.allEntries.first()
                    if (existing.isEmpty()) {
                        prepopulateSampleLogs()
                    }
                } catch (inner: Throwable) {
                    android.util.Log.e("NutritionViewModel", "Local check failed", inner)
                }
            }
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Keep the SharedPreferences and local storage files backup in sync with Room database
            allLogEntries.collect { entries ->
                try {
                    val json = repository.exportToJson()
                    sharedPrefs.edit().putString("backup_food_logs", json).apply()
                    
                    // Backup to internal filesDir
                    val internalFile = java.io.File(application.filesDir, "backup_food_logs.json")
                    internalFile.writeText(json)
                    
                    // Backup to external storage (persists across uninstalls and rebuilds)
                    application.getExternalFilesDir(null)?.let { dir ->
                        val externalFile = java.io.File(dir, "backup_food_logs.json")
                        externalFile.writeText(json)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NutritionViewModel", "Auto-saving logs to local storage backup failed", e)
                }
            }
        }
    }

    fun updateCustomRda(key: String, value: Double) {
        val current = _customRdaOverrides.value.toMutableMap()
        if (value <= 0.0) {
            current.remove(key)
            sharedPrefs.edit().remove(key).apply()
            // Reset to default on remove
            Nutrients.getByKey(key)?.let { def ->
                val standardLabelDef = Nutrients.DEFAULT_DEFINITIONS.find { it.key == key }
                if (standardLabelDef != null) {
                    com.example.data.Nutrients.updateRda(getApplication(), key, standardLabelDef.rda)
                    viewModelScope.launch {
                        repository.updateNutrientRda(key, standardLabelDef.rda)
                    }
                }
            }
        } else {
            current[key] = value
            sharedPrefs.edit().putFloat(key, value.toFloat()).apply()
            // Persist the updated RDA value back to the local JSON-based database structure
            com.example.data.Nutrients.updateRda(getApplication(), key, value)
            viewModelScope.launch {
                repository.updateNutrientRda(key, value)
            }
        }
        _customRdaOverrides.value = current
        _operationMessage.value = "Updated target for ${Nutrients.getByKey(key)?.name ?: key} to ${value.toInt()} ${Nutrients.getByKey(key)?.unit ?: ""}"
    }

    fun applyRdaProfile(
        age: Int,
        weight: Double,
        activityLevel: String,
        sex: String,
        fitnessGoal: String = _profileGoal.value
    ) {
        // Calculate base BMR according to Mifflin-St Jeor Equation
        val bmr = if (sex == "Male") {
            (10.0 * weight) + (6.25 * 175.0) - (5.0 * age) + 5.0
        } else {
            (10.0 * weight) + (6.25 * 162.0) - (5.0 * age) - 161.0
        }

        // Apply activity multipliers
        val multiplier = when (activityLevel) {
            "Sedentary" -> 1.2
            "Lightly Active" -> 1.375
            "Moderately Active" -> 1.55
            "Very Active" -> 1.725
            "Super Active" -> 1.9
            else -> 1.375
        }

        val baseCalories = bmr * multiplier
        val calculatedCalories = when (fitnessGoal) {
            "Weight Loss / Fat Burn / Cutting" -> (baseCalories - 500.0).coerceAtLeast(1200.0)
            "Muscle Gain / Bulking" -> baseCalories + 400.0
            "Athletic Performance" -> baseCalories + 200.0
            "Low Carb / Keto" -> baseCalories
            else -> baseCalories // "Balanced / Maintenance" or default
        }
        val finalCalories = Math.round(calculatedCalories / 50.0) * 50.0

        // Calculate Macronutrient distribution based on Fitness Goal
        val proteinPerKg = when (fitnessGoal) {
            "Weight Loss / Fat Burn / Cutting" -> 1.8
            "Muscle Gain / Bulking" -> 2.2
            "Athletic Performance" -> 1.6
            "Low Carb / Keto" -> 1.6
            else -> if (age >= 60 || activityLevel == "Very Active" || activityLevel == "Super Active") 1.6 else 1.0
        }
        val finalProtein = Math.round((weight * proteinPerKg) / 5.0) * 5.0

        val finalCarbs = when (fitnessGoal) {
            "Weight Loss / Fat Burn / Cutting" -> Math.round((finalCalories * 0.35 / 4.0) / 5.0) * 5.0
            "Muscle Gain / Bulking" -> Math.round((finalCalories * 0.55 / 4.0) / 5.0) * 5.0
            "Athletic Performance" -> Math.round((finalCalories * 0.60 / 4.0) / 5.0) * 5.0
            "Low Carb / Keto" -> Math.max(20.0, Math.round((finalCalories * 0.05 / 4.0) / 5.0) * 5.0)
            else -> Math.round((finalCalories * 0.50 / 4.0) / 5.0) * 5.0
        }

        val finalFat = when (fitnessGoal) {
            "Weight Loss / Fat Burn / Cutting" -> Math.round((finalCalories * 0.25 / 9.0) / 5.0) * 5.0
            "Muscle Gain / Bulking" -> Math.round((finalCalories * 0.25 / 9.0) / 5.0) * 5.0
            "Athletic Performance" -> Math.round((finalCalories * 0.25 / 9.0) / 5.0) * 5.0
            "Low Carb / Keto" -> Math.round((finalCalories * 0.70 / 9.0) / 5.0) * 5.0
            else -> Math.round((finalCalories * 0.30 / 9.0) / 5.0) * 5.0
        }

        // Calculate Water intake adjusted by weight, activity, and goals
        val baseWater = weight * 35.0
        val waterAdjustment = when (activityLevel) {
            "Sedentary" -> 0.0
            "Lightly Active" -> 250.0
            "Moderately Active" -> 500.0
            "Very Active" -> 1000.0
            "Super Active" -> 1500.0
            else -> 0.0
        }
        val goalWaterAdjustment = when (fitnessGoal) {
            "Athletic Performance" -> 1000.0
            "Low Carb / Keto" -> 500.0
            else -> 0.0
        }
        val finalWater = baseWater + waterAdjustment + goalWaterAdjustment

        // Calculate fiber
        val finalFiber = if (sex == "Male") {
            if (age < 51) 38.0 else 30.0
        } else {
            if (age < 51) 25.0 else 21.0
        }

        // Lipids/Fats
        val finalSatFat = Math.round((finalCalories * 0.10 / 9.0) / 1.0) * 1.0
        val finalTransFat = 2.0
        val finalMonoFat = Math.round((finalCalories * 0.15 / 9.0) / 1.0) * 1.0
        val finalPolyFat = Math.round((finalCalories * 0.08 / 9.0) / 1.0) * 1.0
        val finalOmega3 = if (sex == "Male") 1.6 else 1.1
        val finalOmega6 = if (sex == "Male") (if (age < 51) 17.0 else 14.0) else (if (age < 51) 12.0 else 11.0)
        val finalCholesterol = if (activityLevel == "Sedentary" || age >= 60) 200.0 else 300.0

        // Vitamins
        val finalVitA = if (sex == "Male") 900.0 else 700.0
        val finalVitC = if (sex == "Male") 90.0 else 75.0
        val finalVitD = if (age >= 70) 20.0 else 15.0
        val finalVitE = 15.0
        val finalVitK = if (sex == "Male") 120.0 else 90.0
        val finalThiamin = if (sex == "Male") 1.2 else 1.1
        val finalRiboflavin = if (sex == "Male") 1.3 else 1.1
        val finalNiacin = if (sex == "Male") 16.0 else 14.0
        val finalPantothenic = 5.0
        val finalVitB6 = if (age < 51) 1.3 else (if (sex == "Male") 1.7 else 1.5)
        val finalBiotin = 30.0
        val finalFolate = 400.0
        val finalVitB12 = if (age >= 51) 2.8 else 2.4
        val finalCholine = if (sex == "Male") 550.0 else 425.0

        // Minerals
        val finalCalcium = if (sex == "Female" && age >= 51) 1200.0
                           else if (sex == "Male" && age >= 71) 1200.0
                           else 1000.0
        val finalIron = if (sex == "Female" && age >= 18 && age < 51) 18.0 else 8.0
        val finalMagnesium = if (sex == "Male") (if (age < 31) 400.0 else 420.0) else (if (age < 31) 310.0 else 320.0)
        val finalPhosphorus = 700.0
        val finalPotassium = if (sex == "Male") 3400.0 else 2600.0
        val finalSodium = when (fitnessGoal) {
            "Athletic Performance", "Low Carb / Keto" -> 2300.0
            else -> if (activityLevel == "Very Active" || activityLevel == "Super Active") 2300.0 else 2000.0
        }
        val finalZinc = if (sex == "Male") 11.0 else 8.0
        val finalCopper = 0.9
        val finalManganese = if (sex == "Male") 2.3 else 1.8
        val finalSelenium = 55.0
        val finalChromium = if (sex == "Male") (if (age < 51) 35.0 else 30.0) else (if (age < 51) 25.0 else 20.0)
        val finalMolybdenum = 45.0

        // Others
        val finalSugars = when (fitnessGoal) {
            "Weight Loss / Fat Burn / Cutting" -> Math.round((finalCalories * 0.05 / 4.0) / 5.0) * 5.0
            "Low Carb / Keto" -> Math.max(15.0, Math.round((finalCalories * 0.02 / 4.0) / 5.0) * 5.0)
            else -> Math.round((finalCalories * 0.10 / 4.0) / 5.0) * 5.0
        }
        val finalIodine = 150.0

        // Write profiles to SharedPreferences
        sharedPrefs.edit()
            .putInt("profile_age", age)
            .putFloat("profile_weight", weight.toFloat())
            .putString("profile_activity", activityLevel)
            .putString("profile_sex", sex)
            .putString("profile_goal", fitnessGoal)
            .apply()

        _profileAge.value = age
        _profileWeight.value = weight
        _profileActivity.value = activityLevel
        _profileSex.value = sex
        _profileGoal.value = fitnessGoal

        // Write custom targets sequentially for all 41 nutrients
        val currentOverrides = _customRdaOverrides.value.toMutableMap()
        val rdaMap = mapOf(
            "calories" to finalCalories,
            "carbohydrates" to finalCarbs.toDouble(),
            "protein" to finalProtein.toDouble(),
            "fat" to finalFat.toDouble(),
            "fiber" to finalFiber,
            "water" to finalWater,
            "saturated_fat" to finalSatFat,
            "trans_fat" to finalTransFat,
            "monounsaturated_fat" to finalMonoFat,
            "polyunsaturated_fat" to finalPolyFat,
            "omega3" to finalOmega3,
            "omega6" to finalOmega6,
            "cholesterol" to finalCholesterol,
            "vitamin_a" to finalVitA,
            "vitamin_c" to finalVitC,
            "vitamin_d" to finalVitD,
            "vitamin_e" to finalVitE,
            "vitamin_k" to finalVitK,
            "thiamin" to finalThiamin,
            "riboflavin" to finalRiboflavin,
            "niacin" to finalNiacin,
            "pantothenic_acid" to finalPantothenic,
            "vitamin_b6" to finalVitB6,
            "biotin" to finalBiotin,
            "folate" to finalFolate,
            "vitamin_b12" to finalVitB12,
            "choline" to finalCholine,
            "calcium" to finalCalcium,
            "iron" to finalIron,
            "magnesium" to finalMagnesium,
            "phosphorus" to finalPhosphorus,
            "potassium" to finalPotassium,
            "sodium" to finalSodium,
            "zinc" to finalZinc,
            "copper" to finalCopper,
            "manganese" to finalManganese,
            "selenium" to finalSelenium,
            "chromium" to finalChromium,
            "molybdenum" to finalMolybdenum,
            "sugars" to finalSugars,
            "iodine" to finalIodine
        )

        val editor = sharedPrefs.edit()
        rdaMap.forEach { (k, v) ->
            currentOverrides[k] = v
            editor.putFloat(k, v.toFloat())
        }
        editor.apply()
        _customRdaOverrides.value = currentOverrides

        // Also persist all newly customized RDAs to our dynamic local JSON-based database structure
        val newList = com.example.data.Nutrients.DEFINITIONS.map { def ->
            val updatedRda = rdaMap[def.key] ?: def.rda
            def.copy(rda = updatedRda)
        }
        com.example.data.Nutrients.saveListToDatabase(getApplication(), newList)

        _operationMessage.value = "Tailored RDA Profile calculated & applied successfully based on biological metrics!"
    }

    fun resetRdaToDefaults() {
        val current = _customRdaOverrides.value.toMutableMap()
        val keysToReset = com.example.data.Nutrients.DEFINITIONS.map { it.key }
        
        val editor = sharedPrefs.edit()
        keysToReset.forEach { key ->
            current.remove(key)
            editor.remove(key)
        }
        editor.remove("profile_age")
        editor.remove("profile_weight")
        editor.remove("profile_activity")
        editor.remove("profile_sex")
        editor.remove("profile_goal")
        editor.remove("active_health_preset")
        editor.apply()

        // Reset the dynamic local JSON-based database structure to defaults as well
        com.example.data.Nutrients.resetToDefaults(getApplication())
        com.example.data.Nutrients.loadFromDatabase(getApplication())

        _customRdaOverrides.value = current
        _profileAge.value = 30
        _profileWeight.value = 70.0
        _profileActivity.value = "Lightly Active"
        _profileSex.value = "Female"
        _profileGoal.value = "Balanced / Maintenance"
        _activeHealthPreset.value = "None"

        _operationMessage.value = "All 41 tracked RDA targets reset to standard reference values."
    }

    fun applyHealthGoalPreset(presetKey: String) {
        _activeHealthPreset.value = presetKey
        sharedPrefs.edit().putString("active_health_preset", presetKey).apply()

        val rdaMap = when (presetKey) {
            "DASH Diet" -> mapOf(
                "sodium" to 1500.0,
                "potassium" to 4700.0,
                "magnesium" to 500.0,
                "calcium" to 1200.0
            )
            "Diabetes Care" -> mapOf(
                "sugars" to 25.0,
                "carbohydrates" to 130.0,
                "fiber" to 35.0
            )
            "Prenatal Support" -> mapOf(
                "folate" to 600.0,
                "iron" to 27.0,
                "choline" to 450.0,
                "calcium" to 1200.0,
                "vitamin_d" to 20.0,
                "vitamin_b12" to 2.8
            )
            "Bone Health" -> mapOf(
                "calcium" to 1300.0,
                "vitamin_d" to 25.0,
                "vitamin_k" to 150.0,
                "magnesium" to 420.0
            )
            "Anemia Support" -> mapOf(
                "iron" to 25.0,
                "vitamin_c" to 200.0,
                "folate" to 500.0,
                "vitamin_b12" to 3.0
            )
            "Heart Healthy" -> mapOf(
                "sodium" to 1800.0,
                "saturated_fat" to 15.0,
                "omega3" to 2.5,
                "fiber" to 35.0,
                "cholesterol" to 150.0
            )
            "Vegan Balance" -> mapOf(
                "iron" to 15.0,
                "zinc" to 12.0,
                "vitamin_b12" to 5.0,
                "vitamin_d" to 20.0,
                "calcium" to 1100.0
            )
            "Immune Booster" -> mapOf(
                "vitamin_c" to 250.0,
                "vitamin_d" to 25.0,
                "zinc" to 15.0,
                "selenium" to 100.0
            )
            else -> emptyMap()
        }

        if (rdaMap.isNotEmpty()) {
            val currentOverrides = _customRdaOverrides.value.toMutableMap()
            val editor = sharedPrefs.edit()
            
            rdaMap.forEach { (k, v) ->
                currentOverrides[k] = v
                editor.putFloat(k, v.toFloat())
                
                // Persist back to local JSON-based database
                com.example.data.Nutrients.updateRda(getApplication(), k, v)
                
                // Update Room database
                viewModelScope.launch {
                    repository.updateNutrientRda(k, v)
                }
            }
            
            editor.apply()
            _customRdaOverrides.value = currentOverrides
            _operationMessage.value = "Applied targets for $presetKey protocol!"
        } else if (presetKey == "None") {
            _operationMessage.value = "Health goal preset cleared. Custom targets remain active."
        }
    }

    val currentDateEntries: StateFlow<List<FoodLogEntry>> = combine(allLogEntries, currentDate) { entries, date ->
        entries.filter { it.date == date }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 1. Nutrient summary list for the currently selected date
    val dailyNutrients: StateFlow<List<NutrientStatus>> = currentDateEntries.mapToNutrientStatus()

    // 2. Macro spreads calculations
    val macroSpread: StateFlow<MacroSpreadRatio> = currentDateEntries.mapToMacroSpread()

    // 3. Deficiency and risk warnings
    val warnings: StateFlow<List<DeficiencyWarning>> = dailyNutrients.mapToWarnings()

    // 4. Overarching 7-day trend values
    val sevenDayTrends: StateFlow<List<DayTrend>> = allLogEntries.mapToTrends()

    init {
        viewModelScope.launch {
            combine(currentDate, dailyNutrients) { date, statusList ->
                Pair(date, statusList)
            }.collect { (date, statusList) ->
                val deficiencies = statusList.filter { !it.definition.isMaxLimit && it.percentage < 50.0 && it.definition.rda > 0.0 }
                val excesses = statusList.filter { it.definition.isMaxLimit && it.intake > it.definition.rda }
                val fallbackTip = generateLocalFallbackTips(deficiencies, excesses)
                
                val apiKey = com.example.data.GeminiAuthHandler.getApiKey()
                val hasValidKey = apiKey.isNotEmpty()
                
                if (hasValidKey) {
                    fetchAiPersonalizedTips(forceGemini = false)
                    fetchSuggestedFoodsForDeficiencies(forceGemini = false)
                } else {
                    _aiNutritionalTip.value = fallbackTip
                    _isGeminiResponseActive.value = false
                    val allDeficiencies = statusList.filter { !it.definition.isMaxLimit && it.percentage < 100.0 && it.definition.rda > 0.0 }
                    _targetSuggestedFoods.value = generateOfflineFoodSuggestions(allDeficiencies)
                }
            }
        }
    }


    // 5. Nutrient observation and fasting/feasting details
    fun updateObservationDaysLimit(limit: Int) {
        val validLimit = limit.coerceIn(1, 120)
        _observationDaysLimit.value = validLimit
        sharedPrefs.edit().putInt("observation_days_limit", validLimit).apply()
    }

    fun selectObservationNutrient(key: String) {
        _selectedObservationNutrientKey.value = key
    }

    val nutrientObservations: StateFlow<List<NutrientObservationDay>> = combine(
        allLogEntries,
        customRdaOverrides,
        observationDaysLimit,
        selectedObservationNutrientKey,
        currentDate
    ) { entries, overrides, limit, key, anchorDate ->
        val definition = Nutrients.getByKey(key) ?: return@combine emptyList()
        val expected = overrides[key] ?: definition.rda

        val groupedByDate = entries.groupBy { it.date }
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val viewFormat = SimpleDateFormat("MMM d (E)", Locale.US)

        val calendar = Calendar.getInstance()
        try {
            val parsedAnchor = format.parse(anchorDate)
            if (parsedAnchor != null) {
                calendar.time = parsedAnchor
            }
        } catch (e: Exception) {
            // fallback
        }

        val datesList = mutableListOf<String>()
        for (i in 0 until limit) {
            val dateStr = format.format(calendar.time)
            datesList.add(dateStr)
            calendar.add(Calendar.DATE, -1)
        }
        datesList.reverse()

        var runningTotal = 0.0
        datesList.map { dateStr ->
            val dayEntries = groupedByDate[dateStr] ?: emptyList()
            val achieved = dayEntries.sumOf { (it.nutrients[key] ?: 0.0) * it.quantity }
            val diff = expected - achieved
            runningTotal += diff

            val dispDate = try {
                val parsedDate = format.parse(dateStr)
                if (parsedDate != null) viewFormat.format(parsedDate) else dateStr
            } catch (e: Exception) {
                dateStr
            }

            NutrientObservationDay(
                dateString = dateStr,
                displayDate = dispDate,
                expected = expected,
                achieved = achieved,
                difference = diff,
                isFasting = achieved < expected,
                runningTotalDiff = runningTotal
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectDate(dateString: String) {
        _currentDate.value = dateString
    }

    fun insertSupplement(name: String, dosage: String, frequency: String, daysOfWeek: String = "", timeOfDay: String = "Morning", notes: String = "", nutrients: Map<String, Double> = emptyMap()) {
        viewModelScope.launch {
            val supplement = com.example.data.Supplement(
                name = name,
                dosage = dosage,
                frequency = frequency,
                daysOfWeek = daysOfWeek,
                timeOfDay = timeOfDay,
                notes = notes,
                nutrients = if (nutrients.isNotEmpty()) nutrients else repository.estimateSupplementNutrients(name, dosage)
            )
            repository.insertSupplement(supplement)
            _operationMessage.value = "Registered supplement: $name"
            try {
                repository.autoLogSupplementsForDate(_currentDate.value)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun deleteSupplement(supplement: com.example.data.Supplement) {
        viewModelScope.launch {
            repository.deleteSupplement(supplement)
            _operationMessage.value = "Removed supplement: ${supplement.name}"
        }
    }

    fun triggerAutoLog() {
        viewModelScope.launch {
            _isLoading.value = true
            val added = repository.autoLogSupplementsForDate(_currentDate.value)
            _isLoading.value = false
            if (added > 0) {
                _operationMessage.value = "Added $added scheduled supplement(s)!"
            } else {
                _operationMessage.value = "All active supplements already logged."
            }
        }
    }

    fun logSupplementManual(supplement: com.example.data.Supplement) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dateStr = _currentDate.value
            val expectedName = if (supplement.dosage.isNotEmpty()) {
                "${supplement.name} (${supplement.dosage})"
            } else {
                supplement.name
            }
            
            // Check if already logged
            val existing = repository.getAllEntriesDirect().filter { 
                it.date == dateStr && 
                it.mealType == "Supplement" && 
                (it.foodName.equals(expectedName, ignoreCase = true) || 
                 it.foodName.equals(supplement.name, ignoreCase = true))
            }
            if (existing.isEmpty()) {
                val calculatedNutrients = if (supplement.nutrients.isNotEmpty()) {
                    repository.standardizeNutrientKeys(supplement.nutrients)
                } else {
                    repository.estimateSupplementNutrients(supplement.name, supplement.dosage)
                }

                val newLog = com.example.data.FoodLogEntry(
                    id = 0,
                    date = dateStr,
                    foodName = expectedName,
                    mealType = "Supplement",
                    quantity = 1.0,
                    unit = "serving",
                    nutrients = repository.standardizeNutrientKeys(calculatedNutrients)
                )
                repository.insertEntry(newLog)
                _operationMessage.value = "Logged supplement: ${supplement.name}"
            }
        }
    }

    fun unlogSupplementManual(supplement: com.example.data.Supplement) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dateStr = _currentDate.value
            val expectedName = if (supplement.dosage.isNotEmpty()) {
                "${supplement.name} (${supplement.dosage})"
            } else {
                supplement.name
            }
            
            val existing = repository.getAllEntriesDirect().filter { 
                it.date == dateStr && 
                it.mealType == "Supplement" && 
                (it.foodName.equals(expectedName, ignoreCase = true) || 
                 it.foodName.equals(supplement.name, ignoreCase = true))
            }
            for (entry in existing) {
                repository.deleteEntry(entry)
            }
            if (existing.isNotEmpty()) {
                _operationMessage.value = "Removed log for: ${supplement.name}"
            }
        }
    }

    fun clearMessage() {
        _operationMessage.value = null
    }

    fun setOperationMessage(msg: String) {
        _operationMessage.value = msg
    }

    private var lastDeletedEntry: FoodLogEntry? = null
    private var lastDeletedBatch: List<FoodLogEntry> = emptyList()
    private var lastAddedEntry: FoodLogEntry? = null

    fun deleteLog(entry: FoodLogEntry) {
        viewModelScope.launch {
            lastDeletedEntry = entry
            lastDeletedBatch = emptyList()
            repository.deleteEntry(entry)
            _operationMessage.value = "Deleted ${entry.foodName}"
        }
    }

    fun undoDelete() {
        viewModelScope.launch {
            val single = lastDeletedEntry
            val batch = lastDeletedBatch
            if (single != null) {
                repository.insertEntry(single)
                lastDeletedEntry = null
                _operationMessage.value = "Restored: ${single.foodName}"
            } else if (batch.isNotEmpty()) {
                batch.forEach { repository.insertEntry(it) }
                lastDeletedBatch = emptyList()
                _operationMessage.value = "Restored ${batch.size} entries"
            }
        }
    }

    fun undoLastAddedEntry() {
        viewModelScope.launch {
            val entry = lastAddedEntry
            if (entry != null) {
                repository.deleteEntry(entry)
                lastAddedEntry = null
                _operationMessage.value = "Removed: ${entry.foodName}"
            } else {
                _operationMessage.value = "No added entry found to undo"
            }
        }
    }

    fun clearAllForDate(dateString: String) {
        viewModelScope.launch {
            val currentEntries = allLogEntries.value.filter { it.date == dateString }
            if (currentEntries.isNotEmpty()) {
                lastDeletedBatch = currentEntries
                lastDeletedEntry = null
                repository.deleteEntriesForDate(dateString)
                _operationMessage.value = "Cleared all ${currentEntries.size} entries for this day"
            }
        }
    }

    fun updateFoodLogEntry(entry: FoodLogEntry) {
        viewModelScope.launch {
            try {
                repository.insertEntry(entry)
                _operationMessage.value = "Updated entry: ${entry.foodName}"
            } catch (e: Exception) {
                _operationMessage.value = "Failed to update entry"
            }
        }
    }

    fun reanalyzeAndReplaceEntry(entryId: Int, newDescription: String, mealType: String, date: String) {
        if (newDescription.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteEntryById(entryId)
                val result = repository.parseAndLogFood(newDescription, date, mealType)
                if (result.isSuccess) {
                    _operationMessage.value = "Re-analyzed & logged: ${result.getOrNull()?.foodName}"
                } else {
                    _operationMessage.value = "Updated with default fallback composition."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error re-analyzing item."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addFoodLog(input: String, mealType: String) {
        if (input.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.parseAndLogFood(input, _currentDate.value, mealType)
                if (result.isSuccess) {
                    val entry = result.getOrNull()
                    if (entry != null) {
                        lastAddedEntry = entry
                    }
                    _operationMessage.value = "Logged: ${entry?.foodName}"
                } else {
                    _operationMessage.value = "Failed to parse. Logged standard estimate."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error: Use offline fallback estimates."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveFavoriteMeal(name: String, entries: List<FoodLogEntry>) {
        if (name.isBlank() || entries.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val foods = entries.map { entry ->
                    FavoriteMealFoodItem(
                        foodName = entry.foodName,
                        quantity = entry.quantity,
                        unit = entry.unit,
                        nutrients = entry.nutrients
                    )
                }
                val favoriteMeal = FavoriteMeal(name = name, foods = foods)
                repository.insertFavoriteMeal(favoriteMeal)
                _operationMessage.value = "Saved favorite meal: '$name'"
            } catch (e: Exception) {
                _operationMessage.value = "Error saving preset: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logFavoriteMeal(meal: FavoriteMeal, selectedMealType: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.logFavoriteMeal(meal, _currentDate.value, selectedMealType)
                _operationMessage.value = "Logged preset meal: '${meal.name}'"
            } catch (e: Exception) {
                _operationMessage.value = "Error logging preset: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteFavoriteMeal(meal: FavoriteMeal) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteFavoriteMeal(meal)
                _operationMessage.value = "Deleted favorite meal: '${meal.name}'"
            } catch (e: Exception) {
                _operationMessage.value = "Error deleting: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleFavoriteFood(foodName: String, quantity: Double, unit: String, nutrients: Map<String, Double>) {
        viewModelScope.launch {
            try {
                val existing = repository.getFavoriteFoodByName(foodName)
                if (existing != null) {
                    repository.deleteFavoriteFood(existing)
                    _operationMessage.value = "Removed from Favorites: $foodName"
                } else {
                    val favorite = com.example.data.FavoriteFood(
                        foodName = foodName,
                        quantity = quantity,
                        unit = unit,
                        nutrients = nutrients
                    )
                    repository.insertFavoriteFood(favorite)
                    _operationMessage.value = "Added to Favorites: $foodName"
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error favoriting food: ${e.localizedMessage}"
            }
        }
    }

    fun addFavoriteFoodToLog(food: com.example.data.FavoriteFood, mealType: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val entry = FoodLogEntry(
                    foodName = food.foodName,
                    quantity = food.quantity,
                    unit = food.unit,
                    mealType = mealType,
                    date = _currentDate.value,
                    nutrients = food.nutrients
                )
                val saved = repository.insertEntry(entry)
                lastAddedEntry = saved
                _operationMessage.value = "Logged: ${food.foodName}!"
            } catch (e: Exception) {
                _operationMessage.value = "Failed to log favorite: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteFavoriteFood(food: com.example.data.FavoriteFood) {
        viewModelScope.launch {
            try {
                repository.deleteFavoriteFood(food)
                _operationMessage.value = "Deleted favorite food: ${food.foodName}"
            } catch (e: Exception) {
                _operationMessage.value = "Error deleting: ${e.localizedMessage}"
            }
        }
    }

    fun addManualFoodLog(foodName: String, servingSize: Double, mealType: String, customTimestamp: Long? = null) {
        if (foodName.isBlank()) return
        val timestamp = customTimestamp ?: System.currentTimeMillis()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateStr = if (customTimestamp != null) {
            format.format(java.util.Date(timestamp))
        } else {
            _currentDate.value
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Log to Room Database using our precise fallback/AI parser scaled by servingSize
                val result = repository.parseAndLogFood("$foodName ($servingSize servings)", dateStr, mealType)
                if (result.isSuccess) {
                    val entry = result.getOrNull()
                    if (entry != null) {
                        lastAddedEntry = entry
                    }
                }
                
                val id = java.util.UUID.randomUUID().toString()
                val newLog = ManualFoodLog(
                    id = id,
                    foodName = foodName,
                    servingSize = servingSize,
                    mealType = mealType,
                    date = dateStr,
                    timestamp = timestamp
                )
                
                val updatedList = listOf(newLog) + _manualFoodLogs.value
                saveManualFoodLogsToStorage(updatedList)
                _operationMessage.value = "Logged: $foodName!"
            } catch (e: Exception) {
                _operationMessage.value = "Local Storage writing error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteManualFoodLog(id: String) {
        val updatedList = _manualFoodLogs.value.filter { it.id != id }
        saveManualFoodLogsToStorage(updatedList)
        _operationMessage.value = "Deleted log item from localStorage!"
    }

    fun setOnlineSearchQuery(query: String) {
        _onlineSearchQuery.value = query
    }

    fun performOnlineFoodSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _onlineSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isOnlineSearching.value = true
            _onlineSearchQuery.value = trimmed
            try {
                val usdaResults = usdaCacheService.searchFoods(
                    query = trimmed,
                    customApiKey = _usdaApiKey.value.takeIf { it.isNotBlank() }
                )
                val nutritionixResults = com.example.data.NutritionApiIntegration.searchNutritionix(
                    query = trimmed,
                    customAppId = _nutritionixAppId.value.takeIf { it.isNotBlank() },
                    customApiKey = _nutritionixApiKey.value.takeIf { it.isNotBlank() }
                )
                _onlineSearchResults.value = usdaResults + nutritionixResults
            } catch (e: Exception) {
                android.util.Log.e("NutritionViewModel", "Error searching online", e)
                _onlineSearchResults.value = emptyList()
            } finally {
                _isOnlineSearching.value = false
            }
        }
    }

    fun logOnlineFoodItem(
        foodName: String,
        mealType: String,
        quantity: Double,
        unit: String,
        nutrients: Map<String, Double>,
        source: String
    ) {
        if (foodName.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 1. Insert directly to Room DB
                val entry = FoodLogEntry(
                    date = _currentDate.value,
                    foodName = "$foodName [$source]",
                    mealType = mealType,
                    quantity = quantity,
                    unit = unit,
                    nutrients = nutrients
                )
                val savedEntry = repository.insertEntry(entry)
                lastAddedEntry = savedEntry

                // 2. Add to manual logs in storage so they can view/export it
                val timestamp = System.currentTimeMillis()
                val logId = java.util.UUID.randomUUID().toString()
                val newLog = ManualFoodLog(
                    id = logId,
                    foodName = "$foodName [$source]",
                    servingSize = quantity,
                    mealType = mealType,
                    date = _currentDate.value,
                    timestamp = timestamp
                )
                val updatedList = listOf(newLog) + _manualFoodLogs.value
                saveManualFoodLogsToStorage(updatedList)

                _operationMessage.value = "Successfully fetched nutrients from $source and logged: $foodName!"
            } catch (e: Exception) {
                _operationMessage.value = "Failed to log online food: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun insertDirectFoodLogEntry(foodName: String, mealType: String, quantity: Double, unit: String, nutrients: Map<String, Double>) {
        if (foodName.isBlank()) return
        viewModelScope.launch {
            try {
                val entry = FoodLogEntry(
                    date = _currentDate.value,
                    foodName = foodName,
                    mealType = mealType,
                    quantity = quantity,
                    unit = unit,
                    nutrients = nutrients
                )
                val savedEntry = repository.insertEntry(entry)
                lastAddedEntry = savedEntry
                _operationMessage.value = "Successfully logged custom food: $foodName!"
            } catch (e: Exception) {
                _operationMessage.value = "Failed to log custom food: ${e.localizedMessage}"
            }
        }
    }

    fun updateManualFoodLog(id: String, newFoodName: String, newServingSize: Double, newMealType: String) {
        if (newFoodName.isBlank()) return
        val updatedList = _manualFoodLogs.value.map { log ->
            if (log.id == id) {
                log.copy(
                    foodName = newFoodName,
                    servingSize = newServingSize,
                    mealType = newMealType
                )
            } else {
                log
            }
        }
        saveManualFoodLogsToStorage(updatedList)
        _operationMessage.value = "Updated log item in localStorage!"
    }

    private fun saveManualFoodLogsToStorage(list: List<ManualFoodLog>) {
        _manualFoodLogs.value = list
        try {
            val jsonArray = org.json.JSONArray()
            list.forEach { log ->
                val obj = org.json.JSONObject().apply {
                    put("id", log.id)
                    put("foodName", log.foodName)
                    put("servingSize", log.servingSize)
                    put("mealType", log.mealType)
                    put("date", log.date)
                    put("timestamp", log.timestamp)
                }
                jsonArray.put(obj)
            }
            val json = jsonArray.toString()
            localStorage.edit().putString("manual_logs", json).apply()
            
            // Backup to internal filesDir
            val internalFile = java.io.File(getApplication<Application>().filesDir, "manual_logs_backup.json")
            internalFile.writeText(json)
            
            // Backup to external storage (persists across browser/emulator reinstalls)
            getApplication<Application>().getExternalFilesDir(null)?.let { dir ->
                val externalFile = java.io.File(dir, "manual_logs_backup.json")
                externalFile.writeText(json)
            }
        } catch (e: Exception) {
            android.util.Log.e("NutritionViewModel", "Failed to persist manual_logs backup", e)
        }
    }

    private fun loadManualFoodLogs() {
        try {
            var jsonString = localStorage.getString("manual_logs", null)
            if (jsonString.isNullOrBlank()) {
                val internalFile = java.io.File(getApplication<Application>().filesDir, "manual_logs_backup.json")
                if (internalFile.exists()) {
                    jsonString = internalFile.readText()
                }
            }
            if (jsonString.isNullOrBlank()) {
                getApplication<Application>().getExternalFilesDir(null)?.let { dir ->
                    val externalFile = java.io.File(dir, "manual_logs_backup.json")
                    if (externalFile.exists()) {
                        jsonString = externalFile.readText()
                    }
                }
            }
            if (!jsonString.isNullOrBlank()) {
                val array = org.json.JSONArray(jsonString)
                val list = mutableListOf<ManualFoodLog>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ManualFoodLog(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            foodName = obj.optString("foodName", ""),
                            servingSize = obj.optDouble("servingSize", 1.0),
                            mealType = obj.optString("mealType", "Lunch"),
                            date = obj.optString("date", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                _manualFoodLogs.value = list.sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            android.util.Log.e("NutritionViewModel", "Failed to deserialize localStorage manual logs", e)
        }
    }

    fun getManualFoodLogsAsJson(): String {
        val list = _manualFoodLogs.value
        val jsonArray = org.json.JSONArray()
        list.forEach { log ->
            val obj = org.json.JSONObject().apply {
                put("id", log.id)
                put("foodName", log.foodName)
                put("servingSize", log.servingSize)
                put("mealType", log.mealType)
                put("date", log.date)
                put("timestamp", log.timestamp)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString(4)
    }

    fun getManualFoodLogsAsCsv(): String {
        val list = _manualFoodLogs.value
        val sb = java.lang.StringBuilder()
        sb.append("ID,Food Name,Serving Size,Meal Type,Date,Timestamp,Readable Time\n")
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        list.forEach { log ->
            val readableTime = df.format(java.util.Date(log.timestamp))
            val escapedName = log.foodName.replace("\"", "\"\"")
            sb.append("${log.id},\"${escapedName}\",${log.servingSize},${log.mealType},${log.date},${log.timestamp},\"$readableTime\"\n")
        }
        return sb.toString()
    }

    fun addBarcodeFoodLog(barcode: String, mealType: String, quantity: Double = 1.0) {
        if (barcode.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.parseAndLogBarcode(barcode, _currentDate.value, mealType, quantity)
                if (result.isSuccess) {
                    val entry = result.getOrNull()
                    if (entry != null) {
                        lastAddedEntry = entry
                    }
                    _operationMessage.value = "Scanned & Logged: ${entry?.foodName} (${quantity} serving(s))"
                } else {
                    _operationMessage.value = "Failed to parse barcode. Logged backup estimate."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Scanning error. Used offline profile estimates."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addScannedSupplement(barcode: String) {
        if (barcode.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.parseAndRegisterScannedSupplement(barcode)
                if (result.isSuccess) {
                    val supplement = result.getOrNull()
                    _operationMessage.value = "Scanned & Registered: ${supplement?.name}"
                    try {
                        repository.autoLogSupplementsForDate(_currentDate.value)
                    } catch (e: Exception) {
                        // ignore
                    }
                } else {
                    _operationMessage.value = "Failed to register scanned supplement."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error parsing scanned supplement."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun copyDayLog(sourceDate: String, targetDate: String) {
        viewModelScope.launch {
            val sourceEntries = allLogEntries.value.filter { it.date == sourceDate }
            if (sourceEntries.isNotEmpty()) {
                sourceEntries.forEach { entry ->
                    repository.insertEntry(entry.copy(id = 0, date = targetDate))
                }
                _operationMessage.value = "Copied ${sourceEntries.size} entries to target date."
            } else {
                _operationMessage.value = "No entries to copy from source date."
            }
        }
    }

    fun addBulkFoodLog(input: String) {
        if (input.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.parseAndLogMultiDayFood(input, getTodayDateString())
                if (result.isSuccess) {
                    val entries = result.getOrNull() ?: emptyList()
                    if (entries.isNotEmpty()) {
                        _operationMessage.value = "Successfully logged ${entries.size} items across your journal!"
                    } else {
                        _operationMessage.value = "No food items parsed from your input."
                    }
                } else {
                    _operationMessage.value = "Fallback parser extracted standard estimates."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error parsing multi-day logs."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportLogs(onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportToJson()
            localStorage.edit().putLong("last_backup_timestamp", System.currentTimeMillis()).apply()
            onSuccess(json)
        }
    }

    fun exportSpecificEntries(entries: List<FoodLogEntry>, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportEntriesToJson(entries)
            localStorage.edit().putLong("last_backup_timestamp", System.currentTimeMillis()).apply()
            onSuccess(json)
        }
    }

    suspend fun getExportString(): String {
        return repository.exportToJson()
    }

    fun generateDailyReportJson(date: String, entries: List<FoodLogEntry>, nutrients: List<NutrientStatus>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"date\": \"").append(date).append("\",\n")
        
        sb.append("  \"food_logs\": [\n")
        entries.forEachIndexed { idx, entry ->
            sb.append("    {\n")
            sb.append("      \"meal\": \"").append(entry.mealType.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"food_name\": \"").append(entry.foodName.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"quantity\": ").append(entry.quantity).append(",\n")
            sb.append("      \"unit\": \"").append(entry.unit.replace("\"", "\\\"")).append("\"\n")
            sb.append("    }")
            if (idx < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")
        
        sb.append("  \"nutrient_analysis\": [\n")
        nutrients.forEachIndexed { idx, status ->
            sb.append("    {\n")
            sb.append("      \"key\": \"").append(status.definition.key).append("\",\n")
            sb.append("      \"name\": \"").append(status.definition.name).append("\",\n")
            sb.append("      \"group\": \"").append(status.definition.group.name).append("\",\n")
            sb.append("      \"intake\": ").append(status.intake).append(",\n")
            sb.append("      \"rda_target\": ").append(status.definition.rda).append(",\n")
            sb.append("      \"unit\": \"").append(status.definition.unit).append("\",\n")
            sb.append("      \"percentage\": ").append(status.percentage).append("\n")
            sb.append("    }")
            if (idx < nutrients.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    fun generateDailyReportText(date: String, entries: List<FoodLogEntry>, nutrients: List<NutrientStatus>): String {
        val sb = StringBuilder()
        sb.append("==================================================\n")
        sb.append("        NUTRISCRIBE DAILY NUTRITIONAL JOURNAL\n")
        sb.append("        Date: $date\n")
        sb.append("==================================================\n\n")

        // 1. MEAL & FOOD JOURNAL
        sb.append("--------------------------------------------------\n")
        sb.append("1. MEAL & FOOD JOURNAL LOGS\n")
        sb.append("--------------------------------------------------\n")
        if (entries.isEmpty()) {
            sb.append("No active foods or meals logged for this day.\n")
        } else {
            val grouped = entries.groupBy { it.mealType }
            grouped.forEach { (meal, items) ->
                sb.append("\n[$meal]\n")
                items.forEach { item ->
                    val multiplierStr = if (item.quantity != 1.0) " (x${String.format(java.util.Locale.US, "%.1f", item.quantity)})" else ""
                    sb.append("  • ${item.foodName}$multiplierStr - ${item.quantity} ${item.unit}\n")
                    // Brief nutrient breakdown for the food
                    val kcal = item.nutrients["calories"] ?: 0.0
                    val protein = item.nutrients["protein"] ?: 0.0
                    val carbs = item.nutrients["carbohydrates"] ?: 0.0
                    val fat = item.nutrients["fat"] ?: 0.0
                    val fiber = item.nutrients["fiber"] ?: 0.0
                    sb.append("    Energy: ${String.format(java.util.Locale.US, "%.1f", kcal)} kcal | Protein: ${String.format(java.util.Locale.US, "%.1f", protein)}g | Carbs: ${String.format(java.util.Locale.US, "%.1f", carbs)}g | Fat: ${String.format(java.util.Locale.US, "%.1f", fat)}g | Fiber: ${String.format(java.util.Locale.US, "%.1f", fiber)}g\n")
                }
            }
        }
        sb.append("\n\n")

        // 2. DAILY ENERGY & MACRONUTRIENT OUTLOOK
        sb.append("--------------------------------------------------\n")
        sb.append("2. ENERGY & MACRONUTRIENT BALANCE METRICS\n")
        sb.append("--------------------------------------------------\n")
        val calStatus = nutrients.find { it.definition.key == "calories" }
        if (calStatus != null) {
            sb.append("Energy / Calories:\n")
            sb.append("  - Consumed: ${String.format(java.util.Locale.US, "%.1f", calStatus.intake)} kcal\n")
            sb.append("  - Daily Target RDA: ${calStatus.definition.rda.toInt()} kcal\n")
            sb.append("  - Status: ${String.format(java.util.Locale.US, "%.1f", calStatus.percentage)}% met\n\n")
        }

        val macros = listOf("protein", "carbohydrates", "fat", "fiber", "water")
        macros.forEach { key ->
            val status = nutrients.find { it.definition.key == key }
            if (status != null) {
                sb.append("${status.definition.name}:\n")
                sb.append("  - Consumed: ${String.format(java.util.Locale.US, "%.1f", status.intake)}${status.definition.unit}\n")
                sb.append("  - Daily Target RDA: ${status.definition.rda.toInt()}${status.definition.unit}\n")
                sb.append("  - Status: ${String.format(java.util.Locale.US, "%.1f", status.percentage)}% met\n\n")
            }
        }
        sb.append("\n")

        // 3. COMPLETE 41 NUTRIENTS & WELL-BEING SAFEGUARDS
        sb.append("--------------------------------------------------\n")
        sb.append("3. COMPLETE 41 COREGULATED VITAMINS & MINERALS STATUS\n")
        sb.append("--------------------------------------------------\n")
        
        val vitamins = nutrients.filter { it.definition.group.name == "VITAMINS" || it.definition.name.startsWith("Vitamin") || it.definition.name.startsWith("Vit") }
        val minerals = nutrients.filter { it.definition.group.name == "MINERALS" }
        val otherNutrients = nutrients.filter { it !in vitamins && it !in minerals && it.definition.key != "calories" && it.definition.key !in macros }

        if (vitamins.isNotEmpty()) {
            sb.append("\n[VITAMINS STATUS]\n")
            vitamins.forEach { status ->
                val def = status.definition
                val tag = if (def.isMaxLimit) {
                    if (status.intake > def.rda) "[LIMIT VIOLATED!]" else "[SAFE LIMIT]"
                } else {
                    if (status.percentage >= 100.0) "[RDA MET]" else if (status.percentage < 50.0) "[CRITICAL DEFICIENCY GAP]" else "[RDA SUBOPTIMAL]"
                }
                sb.append("  • ${def.name}: ${String.format(java.util.Locale.US, "%.1f", status.intake)} / ${def.rda.toInt()} ${def.unit} (${String.format(java.util.Locale.US, "%.1f", status.percentage)}%) $tag\n")
            }
        }

        if (minerals.isNotEmpty()) {
            sb.append("\n[MINERALS STATUS]\n")
            minerals.forEach { status ->
                val def = status.definition
                val tag = if (def.isMaxLimit) {
                    if (status.intake > def.rda) "[LIMIT VIOLATED!]" else "[SAFE LIMIT]"
                } else {
                    if (status.percentage >= 100.0) "[RDA MET]" else if (status.percentage < 50.0) "[CRITICAL DEFICIENCY GAP]" else "[RDA SUBOPTIMAL]"
                }
                sb.append("  • ${def.name}: ${String.format(java.util.Locale.US, "%.1f", status.intake)} / ${def.rda.toInt()} ${def.unit} (${String.format(java.util.Locale.US, "%.1f", status.percentage)}%) $tag\n")
            }
        }

        if (otherNutrients.isNotEmpty()) {
            sb.append("\n[OTHER NUTRIENT INDICES & ACTIVE ALLOWANCE SAFEGUARDS]\n")
            otherNutrients.forEach { status ->
                val def = status.definition
                val tag = if (def.isMaxLimit) {
                    if (status.intake > def.rda) "[LIMIT VIOLATED!]" else "[SAFE LIMIT]"
                } else {
                    if (status.percentage >= 100.0) "[RDA MET]" else if (status.percentage < 50.0) "[CRITICAL DEFICIENCY GAP]" else "[RDA SUBOPTIMAL]"
                }
                sb.append("  • ${def.name}: ${String.format(java.util.Locale.US, "%.1f", status.intake)} / ${def.rda.toInt()} ${def.unit} (${String.format(java.util.Locale.US, "%.1f", status.percentage)}%) $tag\n")
            }
        }

        sb.append("\n==================================================\n")
        sb.append("NutriScribe Clinical Nutrition Journal • Structured Export File")
        sb.append("\n==================================================\n")
        return sb.toString()
    }

    fun generateDailyHtmlReport(date: String, entries: List<FoodLogEntry>, nutrients: List<NutrientStatus>): String {
        val format = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)
        val generationTime = format.format(Calendar.getInstance().time)

        val calStatus = nutrients.find { it.definition.key == "calories" }
        val proteinStatus = nutrients.find { it.definition.key == "protein" }
        val carbStatus = nutrients.find { it.definition.key == "carbohydrates" }
        val fatStatus = nutrients.find { it.definition.key == "fat" }

        val calIntake = calStatus?.intake ?: 0.0
        val calRda = calStatus?.definition?.rda ?: 2000.0
        val pG = proteinStatus?.intake ?: 0.0
        val cG = carbStatus?.intake ?: 0.0
        val fG = fatStatus?.intake ?: 0.0

        val pCal = pG * 4.0
        val cCal = cG * 4.0
        val fCal = fG * 9.0
        val totalMacroCal = pCal + cCal + fCal

        val protPct = if (totalMacroCal > 0) ((pCal / totalMacroCal) * 100).toInt() else 0
        val carbPct = if (totalMacroCal > 0) ((cCal / totalMacroCal) * 100).toInt() else 0
        val fatPct = if (totalMacroCal > 0) ((fCal / totalMacroCal) * 100).toInt() else 0

        val tableRows = StringBuilder()
        for (status in nutrients) {
            val def = status.definition
            val intake = status.intake
            val totalValueStr = if (def.key == "calories") {
                intake.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", intake)
            }
            val targetValueStr = if (def.key == "calories") {
                def.rda.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", def.rda)
            }

            val rowClass = when (def.unit.lowercase(Locale.US)) {
                "g" -> "macroBg"
                "mcg" -> "traceBg"
                "mg" -> "microBg"
                else -> "otherBg"
            }

            val statusClass = when {
                def.isMaxLimit -> {
                    if (intake > def.rda) {
                        "above"
                    } else {
                        "okay"
                    }
                }
                def.rda <= 0.0 -> {
                    "nodata"
                }
                else -> {
                    val pct = status.percentage
                    when {
                        pct >= 100.0 -> {
                            "okay"
                        }
                        pct >= 50.0 -> {
                            "below"
                        }
                        else -> {
                            "below"
                        }
                    }
                }
            }

            val percentStr = if (def.rda > 0.0) "${status.percentage.toInt()}%" else "—"
            val ulStr = if (def.isMaxLimit) targetValueStr else ""

            tableRows.append(
                """
                <tr class="row $rowClass">
                    <td class="col-sm-3 Nutrient" style="text-align: left; padding-left: 15px;"><strong>${def.name}</strong></td>
                    <td class="col-sm-1 Units">${def.unit}</td>
                    <td class="col-sm-2 Value">$totalValueStr</td>
                    <td class="status-cell $statusClass" style="border-radius: 4px; padding: 4px 8px; text-transform: uppercase;">$statusClass</td>
                    <td class="col-sm-2 BoA_RNIpc">$percentStr</td>
                    <td class="col-sm-2 Rni">$targetValueStr</td>
                    <td class="col-sm-1 Ul">$ulStr</td>
                </tr>
                """.trimIndent()
            )
        }

        val foodRows = StringBuilder()
        if (entries.isEmpty()) {
            foodRows.append("<tr><td colspan=\"6\" class=\"text-center text-muted\" style=\"padding: 20px;\">No active foods logged for this day.</td></tr>")
        } else {
            entries.forEach { entry ->
                val kcalVal = entry.nutrients["calories"] ?: 0.0
                val proteinVal = entry.nutrients["protein"] ?: 0.0
                val carbVal = entry.nutrients["carbohydrates"] ?: 0.0
                val fatVal = entry.nutrients["fat"] ?: 0.0
                val fiberVal = entry.nutrients["fiber"] ?: 0.0
                foodRows.append(
                    """
                    <tr>
                        <td style="padding: 12px;"><strong>${entry.mealType}</strong></td>
                        <td style="padding: 12px;">${entry.foodName}</td>
                        <td style="padding: 12px;">${entry.quantity} ${entry.unit}</td>
                        <td class="text-right font-semibold" style="padding: 12px;">${kcalVal.toInt()} kcal</td>
                        <td class="text-right" style="padding: 12px; font-family: monospace;">${String.format(Locale.US, "%.1f", proteinVal)}g P | ${String.format(Locale.US, "%.1f", carbVal)}g C | ${String.format(Locale.US, "%.1f", fatVal)}g F</td>
                        <td class="text-right" style="padding: 12px;">${String.format(Locale.US, "%.1f", fiberVal)}g</td>
                    </tr>
                    """.trimIndent()
                )
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="en" xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <meta charset="utf-8" />
                <title>NutriScribe Daily Log Report — $date</title>
                <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css"/>
                <script src="https://ajax.googleapis.com/ajax/libs/jquery/1.12.4/jquery.min.js"></script>
                <script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js"></script>
                <script type="text/javascript" >
                $(document).ready(function ()
                {
                    $("#xGram").click(function () {
                        $("#xres .macroBg").toggle();
                    });
                    $("#xmcg").click(function () {
                        $("#xres .traceBg").toggle();
                    });
                    $("#xmg").click(function () {
                        $("#xres .microBg").toggle();
                    });
                });
                </script>
                <script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>
                <script type="text/javascript">
                  google.charts.load("current", {packages:["corechart"]});
                  google.charts.setOnLoadCallback(drawChart);
                  var X1 = $protPct;
                  var X2 = $fatPct;
                  var X3 = $carbPct;
              
                  function drawChart() 
                  {
                    var data = google.visualization.arrayToDataTable([
                      ['Title', 'Macro Nutrient Balance'],
                      ['Protein',  X1],
                      ['Fat',  X2],
                      ['Carb', X3]         
                    ]);

                    var options = 
                    {
                       pieSliceTextStyle: 
                        {
                            color: 'white',
                            bold:true,
                          },
                       slices: {
                            0: { color: '#059669' },
                            1: { color: '#dc2626' },
                            2: { color: '#d97706'}
                          },
                        title: 'Daily Macro Calorie Contribution Pct',
                        is3D: false,
                        legend: 'bottom',
                        pieStartAngle: 0,
                    };
                    var chart = new google.visualization.PieChart(document.getElementById('piechart'));
                    chart.draw(data, options);
                  }
                </script>
                <style>
                    body {
                        padding: 30px;
                        background-color: #f8fafc;
                        color: #334155;
                        font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
                    }
                    .h-branding {
                        background-color: #0f172a;
                        color: #ffffff;
                        padding: 30px;
                        border-radius: 12px;
                        margin-bottom: 30px;
                        box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1);
                    }
                    .h-branding h1 { margin-top: 0; font-weight: bold; font-size: 30px; letter-spacing: -0.5px; }
                    .FooTab { width: 100%; border-radius: 8px; overflow: hidden; margin-top: 15px; }
                    .above {
                        background-color: #fecaca !important;
                        color: #991b1b !important;
                        font-weight: bold;
                    }
                    .below {
                        background-color: #fef08a !important;
                        color: #854d0e !important;
                        font-weight: bold;
                    }
                    .okay {
                        background-color: #bbf7d0 !important;
                        color: #166534 !important;
                        font-weight: bold;
                    }
                    .nodata {
                        background-color: #f1f5f9 !important;
                        color: #475569 !important;
                    }
                    #piechart {
                        height: 260px;
                        margin-bottom: 20px;
                    }
                    .btn-toggle-grp { margin-bottom: 20px; text-align: left; }
                    .footer {
                        margin-top: 50px;
                        padding-top: 25px;
                        border-top: 1px solid #e2e8f0;
                        font-size: 13px;
                        color: #64748b;
                    }
                    .font-semibold { font-weight: 600; }
                    .macro-indicator {
                        padding: 12px;
                        font-size: 14px;
                    }
                </style>
            </head>
            <body>
            <div class="h-branding">
                <div class="row">
                    <div class="col-sm-8">
                        <h1>NutriScribe Daily Nutritional Journal</h1>
                        <p style="font-size: 16px; opacity: 0.9; margin-bottom: 5px;">Daily Summary of Active Intake Patterns</p>
                        <p style="font-size: 14px; opacity: 0.7; margin-bottom: 0;">Date: <strong>$date</strong> | Export Time: $generationTime</p>
                    </div>
                    <div class="col-sm-4 text-right" style="margin-top:20px;">
                        <h2 style="font-size:42px; font-weight:900; margin:0; color: #10b981;">${calIntake.toInt()} kcal</h2>
                        <span style="font-size:14px; opacity:0.8;">Daily Intake vs Target ${calRda.toInt()} kcal</span>
                    </div>
                </div>
            </div>

            <!-- Meal Entries section -->
            <div class="panel panel-default" style="border-radius: 10px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05);">
                <div class="panel-heading" style="font-weight:bold; background-color: #f1f5f9; font-size: 15px; padding: 15px;">Active Foods & Meals Logged Today</div>
                <div class="panel-body" style="padding:0;">
                    <table class="table table-striped table-hover" style="margin-bottom:0;">
                        <thead>
                            <tr style="background-color: #e2e8f0; font-weight: bold;">
                                <th style="padding: 12px;">Meal Type</th>
                                <th style="padding: 12px;">Food Name</th>
                                <th style="padding: 12px;">Quantity / Unit</th>
                                <th class="text-right" style="padding: 12px;">Calories</th>
                                <th class="text-right" style="padding: 12px;">Macros (P | C | F)</th>
                                <th class="text-right" style="padding: 12px;">Dietary Fiber</th>
                            </tr>
                        </thead>
                        <tbody>
                            $foodRows
                        </tbody>
                    </table>
                </div>
            </div>

            <div class="row">
                <div class="col-md-5">
                    <div class="panel panel-default" style="border-radius: 10px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05);">
                        <div class="panel-heading" style="font-weight:bold; background-color: #f1f5f9; font-size: 15px; padding: 15px;">Macro Calorie Contribution</div>
                        <div class="panel-body" style="padding: 20px;">
                            <div id="piechart"></div>
                            <table class="table table-bordered table-condensed text-center FooTab" style="margin-bottom:0;">
                                <thead>
                                    <tr style="font-weight: bold; background-color: #e2e8f0;">
                                        <td class="col-sm-4" style="padding: 10px;">Nutrient</td>
                                        <td class="col-sm-4" style="padding: 10px;">Intake</td>
                                        <td class="col-sm-4" style="padding: 10px;">Energy Pct</td>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr class="macroBg macro-indicator">
                                        <td style="padding: 10px;"><strong>Protein</strong></td>
                                        <td style="padding: 10px;">${String.format(Locale.US, "%.1f", pG)} g</td>
                                        <td style="padding: 10px; color: #059669; font-weight: bold;">$protPct%</td>
                                    </tr>
                                    <tr class="macroBg macro-indicator">
                                        <td style="padding: 10px;"><strong>Fat</strong></td>
                                        <td style="padding: 10px;">${String.format(Locale.US, "%.1f", fG)} g</td>
                                        <td style="padding: 10px; color: #dc2626; font-weight: bold;">$fatPct%</td>
                                    </tr>
                                    <tr class="macroBg macro-indicator">
                                        <td style="padding: 10px;"><strong>Carbohydrate</strong></td>
                                        <td style="padding: 10px;">${String.format(Locale.US, "%.1f", cG)} g</td>
                                        <td style="padding: 10px; color: #d97706; font-weight: bold;">$carbPct%</td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>

                <div class="col-md-7">
                    <div class="panel panel-default" style="border-radius: 10px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05);">
                        <div class="panel-heading" style="font-weight:bold; background-color: #f1f5f9; font-size: 15px; padding: 15px;">Nutritional Coregulation & RDA Targets</div>
                        <div class="panel-body" style="padding: 20px;">
                            <div class="btn-toggle-grp">
                                <span style="font-weight:bold; margin-right:10px;">Filter list view:</span>
                                <button id="xGram" class="btn btn-primary btn-sm">Toggle Macros (g)</button>
                                <button id="xmg" class="btn btn-warning btn-sm">Toggle Micros (mg)</button>
                                <button id="xmcg" class="btn btn-success btn-sm">Toggle Traces (mcg)</button>
                            </div>

                            <table class="table table-hover table-condensed table-responsive text-center FooTab" id="xres" style="font-size:12px;">
                                <thead>
                                    <tr style="font-weight:bold; background-color: #e2e8f0;">
                                        <th style="padding: 10px; text-align: left; padding-left: 15px;">Nutrient Name</th>
                                        <th class="text-center" style="padding: 10px;">Unit</th>
                                        <th class="text-center" style="padding: 10px;">Intake</th>
                                        <th class="text-center" style="padding: 10px;">Status</th>
                                        <th class="text-center" style="padding: 10px;">% Met</th>
                                        <th class="text-center" style="padding: 10px;">RDA Target</th>
                                        <th class="text-center" style="padding: 10px;">Upper Limit</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    $tableRows
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <div class="footer text-center">
                <p>Generated by <strong>NutriScribe Clinical Nutrition Hub</strong>. Keep track of coregulated pathways for lifelong nutritional wellbeing.</p>
                <p>&copy; 2026 NutriScribe AI Co-Pilot.</p>
            </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun generateDailyPdfReport(
        context: android.content.Context,
        date: String,
        entries: List<FoodLogEntry>,
        nutrients: List<NutrientStatus>,
        outputStream: java.io.OutputStream
    ) {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        // ------------------ PAGE 1: Overview, Macros, & Meal Log ------------------
        val pageInfo1 = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page1 = pdfDocument.startPage(pageInfo1)
        val canvas1 = page1.canvas

        val paintText = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }
        val paintShape = android.graphics.Paint().apply {
            isAntiAlias = true
        }

        // Draw top banner block
        paintShape.color = android.graphics.Color.parseColor("#0F172A") // slate-900
        canvas1.drawRect(0f, 0f, pageWidth.toFloat(), 70f, paintShape)

        // Title
        paintText.color = android.graphics.Color.WHITE
        paintText.textSize = 18f
        paintText.isFakeBoldText = true
        canvas1.drawText("NUTRISCRIBE DAILY NUTRITIVE JOURNAL", 24f, 40f, paintText)

        // Subtitle
        paintText.textSize = 9f
        paintText.isFakeBoldText = false
        paintText.color = android.graphics.Color.parseColor("#94A3B8") // slate-400
        canvas1.drawText("Detailed single-day breakdown of active intake & target coregulation", 24f, 56f, paintText)

        // Right header (Kcal)
        val calStatus = nutrients.find { it.definition.key == "calories" }
        val calIntake = calStatus?.intake ?: 0.0
        val calRda = calStatus?.definition?.rda ?: 2000.0
        
        paintText.color = android.graphics.Color.WHITE
        paintText.textSize = 14f
        paintText.isFakeBoldText = true
        val calStr = "${calIntake.toInt()} kcal"
        val calWidth = paintText.measureText(calStr)
        canvas1.drawText(calStr, pageWidth - 24f - calWidth, 38f, paintText)

        paintText.color = android.graphics.Color.parseColor("#94A3B8")
        paintText.textSize = 8f
        paintText.isFakeBoldText = false
        val calSubStr = "Intake / ${calRda.toInt()} kcal Goal"
        val calSubWidth = paintText.measureText(calSubStr)
        canvas1.drawText(calSubStr, pageWidth - 24f - calSubWidth, 52f, paintText)

        // Document Metadata Row
        paintShape.color = android.graphics.Color.parseColor("#F1F5F9") // slate-100
        canvas1.drawRect(24f, 85f, (pageWidth - 24).toFloat(), 135f, paintShape)

        paintText.color = android.graphics.Color.parseColor("#475569") // slate-600
        paintText.textSize = 9f
        paintText.isFakeBoldText = true
        canvas1.drawText("DAILY JOURNAL DETAILS", 34f, 102f, paintText)

        paintText.isFakeBoldText = false
        paintText.color = android.graphics.Color.parseColor("#0F172A")
        canvas1.drawText("Journal Date: $date", 34f, 118f, paintText)

        val sdf = SimpleDateFormat("MMMM dd, yyyy HH:mm", Locale.US)
        val generatedStr = sdf.format(Calendar.getInstance().time)
        canvas1.drawText("Generated At: $generatedStr", 190f, 102f, paintText)
        canvas1.drawText("Logged Foods Count: ${entries.size}", 190f, 118f, paintText)

        // Compliance status aggregates
        var aboveLimitCount = 0
        var deficientCount = 0
        var optimalCount = 0
        for (st in nutrients) {
            if (st.definition.isMaxLimit) {
                if (st.intake > st.definition.rda) aboveLimitCount++
            } else if (st.definition.rda > 0) {
                if (st.percentage < 50.0) deficientCount++
                else if (st.percentage >= 100.0) optimalCount++
            }
        }
        canvas1.drawText("Coregulated Goals: $optimalCount Met | $deficientCount Deficient | $aboveLimitCount Exceeded", 350f, 118f, paintText)

        // Card 1: Macronutrient card
        paintShape.color = android.graphics.Color.parseColor("#F8FAFC") // slate-50
        paintShape.style = android.graphics.Paint.Style.FILL
        val macroCardRect = android.graphics.RectF(24f, 150f, (pageWidth / 2 - 12).toFloat(), 255f)
        canvas1.drawRoundRect(macroCardRect, 6f, 6f, paintShape)
        paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
        paintShape.style = android.graphics.Paint.Style.STROKE
        paintShape.strokeWidth = 1f
        canvas1.drawRoundRect(macroCardRect, 6f, 6f, paintShape)

        paintText.color = android.graphics.Color.parseColor("#334155")
        paintText.textSize = 9.5f
        paintText.isFakeBoldText = true
        canvas1.drawText("MACRONUTRIENT BALANCE SPREAD", 36f, 172f, paintText)

        val proteinStatus = nutrients.find { it.definition.key == "protein" }
        val carbStatus = nutrients.find { it.definition.key == "carbohydrates" }
        val fatStatus = nutrients.find { it.definition.key == "fat" }
        val pG = proteinStatus?.intake ?: 0.0
        val cG = carbStatus?.intake ?: 0.0
        val fG = fatStatus?.intake ?: 0.0

        val pCal = pG * 4.0
        val cCal = cG * 4.0
        val fCal = fG * 9.0
        val totalMacroCal = pCal + cCal + fCal

        val protPct = if (totalMacroCal > 0) ((pCal / totalMacroCal) * 100).toInt() else 0
        val carbPct = if (totalMacroCal > 0) ((cCal / totalMacroCal) * 100).toInt() else 0
        val fatPct = if (totalMacroCal > 0) ((fCal / totalMacroCal) * 100).toInt() else 0

        paintText.isFakeBoldText = false
        paintText.textSize = 8.5f
        paintText.color = android.graphics.Color.parseColor("#64748B")
        canvas1.drawText("Protein Intake:", 36f, 192f, paintText)
        canvas1.drawText("Total Fat Intake:", 36f, 206f, paintText)
        canvas1.drawText("Carbohydrates Intake:", 36f, 220f, paintText)

        paintText.color = android.graphics.Color.parseColor("#0F172A")
        paintText.isFakeBoldText = true
        canvas1.drawText("${String.format(Locale.US, "%.1f", pG)} g ($protPct% kcal)", 180f, 192f, paintText)
        canvas1.drawText("${String.format(Locale.US, "%.1f", fG)} g ($fatPct% kcal)", 180f, 206f, paintText)
        canvas1.drawText("${String.format(Locale.US, "%.1f", cG)} g ($carbPct% kcal)", 180f, 220f, paintText)

        // Draw progress stacked bar
        paintShape.style = android.graphics.Paint.Style.FILL
        val barX = 36f
        val barY = 236f
        val maxBarW = ((pageWidth / 2 - 12) - 36f - 24f)
        val barH = 6f

        val protRatio = if (totalMacroCal > 0) (pCal / totalMacroCal).toFloat() else 0f
        val fatRatio = if (totalMacroCal > 0) (fCal / totalMacroCal).toFloat() else 0f
        val carbRatio = if (totalMacroCal > 0) (cCal / totalMacroCal).toFloat() else 0f

        val pW = protRatio * maxBarW
        val fW = fatRatio * maxBarW
        val cW = carbRatio * maxBarW

        var offset = barX
        if (pW > 0) {
            paintShape.color = android.graphics.Color.parseColor("#059669") // emerald-600
            canvas1.drawRect(offset, barY, offset + pW, barY + barH, paintShape)
            offset += pW
        }
        if (fW > 0) {
            paintShape.color = android.graphics.Color.parseColor("#DC2626") // red-600
            canvas1.drawRect(offset, barY, offset + fW, barY + barH, paintShape)
            offset += fW
        }
        if (cW > 0) {
            paintShape.color = android.graphics.Color.parseColor("#D97706") // amber-600
            canvas1.drawRect(offset, barY, (pageWidth / 2f - 36f), barY + barH, paintShape)
        }

        // Card 2: Energy & Fluid Status right card
        val envCardRect = android.graphics.RectF((pageWidth / 2 + 12).toFloat(), 150f, (pageWidth - 24).toFloat(), 255f)
        paintShape.color = android.graphics.Color.parseColor("#F8FAFC")
        paintShape.style = android.graphics.Paint.Style.FILL
        canvas1.drawRoundRect(envCardRect, 6f, 6f, paintShape)
        paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
        paintShape.style = android.graphics.Paint.Style.STROKE
        canvas1.drawRoundRect(envCardRect, 6f, 6f, paintShape)

        paintText.color = android.graphics.Color.parseColor("#334155")
        paintText.textSize = 9.5f
        paintText.isFakeBoldText = true
        canvas1.drawText("DIETARY FUEL & HYDRATION INDEX", (pageWidth / 2 + 24).toFloat(), 172f, paintText)

        val waterStatus = nutrients.find { it.definition.key == "water" }
        val wIntake = waterStatus?.intake ?: 0.0
        val wRda = waterStatus?.definition?.rda ?: 2500.0
        val fiberStatus = nutrients.find { it.definition.key == "fiber" }
        val fibIntake = fiberStatus?.intake ?: 0.0
        val fibRda = fiberStatus?.definition?.rda ?: 28.0

        paintText.isFakeBoldText = false
        paintText.textSize = 8.5f
        paintText.color = android.graphics.Color.parseColor("#64748B")
        canvas1.drawText("Hydration/Water Intake:", (pageWidth / 2 + 24).toFloat(), 192f, paintText)
        canvas1.drawText("Dietary Fiber Intake:", (pageWidth / 2 + 24).toFloat(), 214f, paintText)

        paintText.color = android.graphics.Color.parseColor("#0F172A")
        paintText.isFakeBoldText = true
        canvas1.drawText("${wIntake.toInt()} ml / ${wRda.toInt()} ml", (pageWidth / 2 + 155).toFloat(), 192f, paintText)
        canvas1.drawText("${String.format(Locale.US, "%.1f", fibIntake)} g / ${fibRda.toInt()} g", (pageWidth / 2 + 155).toFloat(), 214f, paintText)

        // Draw two small progress bars for water & fiber
        paintShape.style = android.graphics.Paint.Style.FILL
        // Water bar info
        val pbX = (pageWidth / 2 + 24).toFloat()
        val pBarW = ((pageWidth - 24) - pbX - 24f)
        paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
        canvas1.drawRoundRect(android.graphics.RectF(pbX, 198f, pbX + pBarW, 203f), 2f, 2f, paintShape)
        val wPct = (wIntake / wRda).coerceIn(0.0, 1.0).toFloat()
        if (wPct > 0) {
            paintShape.color = android.graphics.Color.parseColor("#3B82F6") // blue-500
            canvas1.drawRoundRect(android.graphics.RectF(pbX, 198f, pbX + (wPct * pBarW), 203f), 2f, 2f, paintShape)
        }

        // Fiber bar info
        paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
        canvas1.drawRoundRect(android.graphics.RectF(pbX, 220f, pbX + pBarW, 225f), 2f, 2f, paintShape)
        val fibPct = (fibIntake / fibRda).coerceIn(0.0, 1.0).toFloat()
        if (fibPct > 0) {
            paintShape.color = android.graphics.Color.parseColor("#10B981") // emerald-500
            canvas1.drawRoundRect(android.graphics.RectF(pbX, 220f, pbX + (fibPct * pBarW), 225f), 2f, 2f, paintShape)
        }

        // Card 3: Food & Meal Journal Card
        val journalCardRect = android.graphics.RectF(24f, 270f, (pageWidth - 24).toFloat(), 795f)
        paintShape.color = android.graphics.Color.parseColor("#FFFFFF")
        paintShape.style = android.graphics.Paint.Style.FILL
        canvas1.drawRoundRect(journalCardRect, 6f, 6f, paintShape)
        paintShape.color = android.graphics.Color.parseColor("#CBD5E1") // slate-300
        paintShape.style = android.graphics.Paint.Style.STROKE
        canvas1.drawRoundRect(journalCardRect, 6f, 6f, paintShape)

        // Section Title
        paintText.color = android.graphics.Color.parseColor("#0F172A")
        paintText.textSize = 10f
        paintText.isFakeBoldText = true
        canvas1.drawText("LOGGED FOODS & MEALS JOURNAL", 36f, 292f, paintText)

        // Drawing Food table headers
        paintShape.color = android.graphics.Color.parseColor("#F1F5F9") // slate-100
        paintShape.style = android.graphics.Paint.Style.FILL
        canvas1.drawRect(36f, 305f, (pageWidth - 36).toFloat(), 325f, paintShape)

        paintText.textSize = 8f
        paintText.isFakeBoldText = true
        paintText.color = android.graphics.Color.parseColor("#475569") // slate-600
        canvas1.drawText("Meal Type", 42f, 318f, paintText)
        canvas1.drawText("Food Item Name", 110f, 318f, paintText)
        canvas1.drawText("Logged Servings", 290f, 318f, paintText)
        canvas1.drawText("Calories", 380f, 318f, paintText)
        canvas1.drawText("Macronutrient Split (P | C | F)", 450f, 318f, paintText)

        // Populate rows
        var rowY = 342f
        val maxJournalHeight = 775f
        var entryIndex = 0

        for (entry in entries) {
            if (rowY >= maxJournalHeight) {
                // If they have extremely many entries, show a small trailing message
                paintText.textSize = 8.5f
                paintText.isFakeBoldText = false
                paintText.color = android.graphics.Color.parseColor("#94A3B8")
                canvas1.drawText("... and ${entries.size - entryIndex} more entries logged. View full records on device database.", 36f, rowY, paintText)
                break
            }

            val kcalVal = entry.nutrients["calories"] ?: 0.0
            val pVal = entry.nutrients["protein"] ?: 0.0
            val cVal = entry.nutrients["carbohydrates"] ?: 0.0
            val fVal = entry.nutrients["fat"] ?: 0.0

            // Draw thin divider line between rows
            paintShape.color = android.graphics.Color.parseColor("#F1F5F9")
            canvas1.drawLine(36f, rowY - 14f, (pageWidth - 36).toFloat(), rowY - 14f, paintShape)

            paintText.textSize = 8.5f
            paintText.isFakeBoldText = true
            paintText.color = android.graphics.Color.parseColor("#1E293B")
            canvas1.drawText(entry.mealType, 42f, rowY, paintText)

            paintText.isFakeBoldText = false
            paintText.color = android.graphics.Color.parseColor("#334155")
            // Cap long food names so they don't overlap columns
            val cappedName = if (entry.foodName.length > 34) entry.foodName.take(32) + "..." else entry.foodName
            canvas1.drawText(cappedName, 110f, rowY, paintText)

            canvas1.drawText("${entry.quantity} ${entry.unit}", 290f, rowY, paintText)
            canvas1.drawText("${kcalVal.toInt()} kcal", 380f, rowY, paintText)

            // Draw compact macros
            val pStr = String.format(Locale.US, "%.0f", pVal)
            val cStr = String.format(Locale.US, "%.0f", cVal)
            val fStr = String.format(Locale.US, "%.0f", fVal)
            canvas1.drawText("${pStr}g P  |  ${cStr}g C  |  ${fStr}g F", 450f, rowY, paintText)

            rowY += 28f
            entryIndex++
        }

        if (entries.isEmpty()) {
            paintText.textSize = 9f
            paintText.color = android.graphics.Color.parseColor("#94A3B8")
            canvas1.drawText("No active food log entries registered for this day.", 180f, 450f, paintText)
        }

        // Footer Page 1
        paintShape.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas1.drawLine(24f, 805f, (pageWidth - 24).toFloat(), 805f, paintShape)

        paintText.color = android.graphics.Color.parseColor("#94A3B8")
        paintText.textSize = 7.5f
        paintText.isFakeBoldText = false
        canvas1.drawText("NUTRISCRIBE CLINICAL NUTRITION HUB  |  DAILY PERFORMANCE EXPORT", 24f, 818f, paintText)
        val sig1 = "Page 1 of 2"
        val sig1W = paintText.measureText(sig1)
        canvas1.drawText(sig1, pageWidth - 24f - sig1W, 818f, paintText)

        pdfDocument.finishPage(page1)

        // ------------------ PAGE 2: Nutrient-by-Nutrient Table ------------------
        val pageInfo2 = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create()
        val page2 = pdfDocument.startPage(pageInfo2)
        val canvas2 = page2.canvas

        paintShape.color = android.graphics.Color.parseColor("#1E293B") // slate-800
        canvas2.drawRect(0f, 0f, pageWidth.toFloat(), 45f, paintShape)

        paintText.color = android.graphics.Color.WHITE
        paintText.textSize = 12f
        paintText.isFakeBoldText = true
        canvas2.drawText("DAILY NUTRIENT CONSUMPTION VS TARGET COREGULATION", 24f, 27f, paintText)

        paintShape.color = android.graphics.Color.parseColor("#F1F5F9") // slate-100
        canvas2.drawRect(24f, 60f, (pageWidth - 24).toFloat(), 82f, paintShape)

        paintText.color = android.graphics.Color.parseColor("#334155")
        paintText.textSize = 8.5f
        paintText.isFakeBoldText = true
        canvas2.drawText("Nutrient Coregulated Tracker", 32f, 74f, paintText)
        canvas2.drawText("Daily Value Intake", 205f, 74f, paintText)
        canvas2.drawText("RDA Goal Reference", 310f, 74f, paintText)
        canvas2.drawText("Adherence & Target Safeguards Visual Guide", 400f, 74f, paintText)

        var itemY = 100f
        val tableRowHeight = 16.5f

        for (nutrientStatus in nutrients) {
            val def = nutrientStatus.definition
            val intake = nutrientStatus.intake
            val percent = nutrientStatus.percentage
            val isUrgent = (nutrientStatus.status == StatusColor.RED)

            // Highlight warnings to draw user focus
            if (isUrgent) {
                paintShape.color = android.graphics.Color.parseColor("#FFF5F5") // ultra-soft red
                canvas2.drawRect(24f, itemY - 11f, (pageWidth - 24).toFloat(), itemY + tableRowHeight - 11f, paintShape)
            }

            // Draw minor row splitting line
            paintShape.color = android.graphics.Color.parseColor("#F1F5F9")
            canvas2.drawLine(24f, itemY + tableRowHeight - 11f, (pageWidth - 24).toFloat(), itemY + tableRowHeight - 11f, paintShape)

            // Nutrient text style
            paintText.textSize = 7.5f
            paintText.isFakeBoldText = true
            paintText.color = if (isUrgent) android.graphics.Color.parseColor("#DC2626") else android.graphics.Color.parseColor("#0F172A")
            canvas2.drawText(def.name, 32f, itemY, paintText)

            // Intake Text
            paintText.textSize = 7.5f
            paintText.isFakeBoldText = true
            paintText.color = android.graphics.Color.parseColor("#0F172A")
            val intakeDisplayStr = if (def.key == "calories") intake.toInt().toString() + " kcal" else String.format(Locale.US, "%.1f %s", intake, def.unit)
            canvas2.drawText(intakeDisplayStr, 205f, itemY, paintText)

            // Target text
            paintText.isFakeBoldText = false
            val rdaDisplayStr = if (def.key == "calories") def.rda.toInt().toString() + " kcal" else String.format(Locale.US, "%.1f %s", def.rda, def.unit)
            canvas2.drawText(rdaDisplayStr, 310f, itemY, paintText)

            // Progress Bar Visual Guide
            if (def.rda > 0.0) {
                val pBarX = 400f
                val pBarY = itemY - 6f
                val pBarWidthTarget = 110f
                val pBarHeight = 5f

                // Draw background bar
                paintShape.style = android.graphics.Paint.Style.FILL
                paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
                paintShape.isAntiAlias = true
                val progressBgRect = android.graphics.RectF(pBarX, pBarY, pBarX + pBarWidthTarget, pBarY + pBarHeight)
                canvas2.drawRoundRect(progressBgRect, 1.5f, 1.5f, paintShape)

                // Limit calculation
                val currentPct = percent.coerceIn(0.0, 100.0) / 100.0
                val progressWidth = (currentPct * pBarWidthTarget).toFloat()

                // Fill Bar
                paintShape.color = when (nutrientStatus.status) {
                    StatusColor.GREEN -> android.graphics.Color.parseColor("#22C55E") // emerald-500
                    StatusColor.YELLOW -> android.graphics.Color.parseColor("#F59E0B") // amber-500
                    StatusColor.RED -> android.graphics.Color.parseColor("#EF4444") // red-500
                }

                if (progressWidth > 0f) {
                    val progressFillRect = android.graphics.RectF(pBarX, pBarY, pBarX + progressWidth, pBarY + pBarHeight)
                    canvas2.drawRoundRect(progressFillRect, 1.5f, 1.5f, paintShape)
                }

                // Draw Percentage overlay text
                paintText.textSize = 6.8f
                paintText.isFakeBoldText = true
                paintText.color = paintShape.color
                val pctStr = if (def.isMaxLimit) {
                    if (intake > def.rda) "EXCEEDED" else "SAFE"
                } else {
                    "${percent.toInt()}%"
                }
                canvas2.drawText(pctStr, pBarX + pBarWidthTarget + 8f, itemY - 1f, paintText)
            } else {
                paintText.textSize = 6.8f
                paintText.isFakeBoldText = false
                paintText.color = android.graphics.Color.parseColor("#94A3B8")
                canvas2.drawText("No target set", 400f, itemY - 1f, paintText)
            }

            itemY += tableRowHeight
        }

        // Footer Page 2
        paintShape.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas2.drawLine(24f, 805f, (pageWidth - 24).toFloat(), 805f, paintShape)

        paintText.color = android.graphics.Color.parseColor("#94A3B8")
        paintText.textSize = 7.5f
        paintText.isFakeBoldText = false
        canvas2.drawText("NUTRISCRIBE CLINICAL NUTRITION HUB  |  DAILY PERFORMANCE EXPORT", 24f, 818f, paintText)
        val signPage2Str = "Page 2 of 2"
        val sigPage2Width = paintText.measureText(signPage2Str)
        canvas2.drawText(signPage2Str, pageWidth - 24f - sigPage2Width, 818f, paintText)

        pdfDocument.finishPage(page2)

        // Save PDF content
        pdfDocument.writeTo(outputStream)
        pdfDocument.close()
    }

    fun generateDailyReportCsv(date: String, entries: List<FoodLogEntry>, nutrients: List<NutrientStatus>): String {
        val sb = StringBuilder()
        sb.append("NutriScribe Daily Report,Date: ").append(date).append("\n\n")
        
        sb.append("FOOD LOG ENTRIES\n")
        sb.append("Meal,Food Name,Quantity,Unit\n")
        entries.forEach { entry ->
            val escapedFood = if (entry.foodName.contains(",") || entry.foodName.contains("\"")) {
                "\"${entry.foodName.replace("\"", "\"\"")}\""
            } else {
                entry.foodName
            }
            sb.append(entry.mealType).append(",").append(escapedFood).append(",")
              .append(entry.quantity).append(",").append(entry.unit).append("\n")
        }
        sb.append("\n")
        
        sb.append("NUTRIENT ANALYSIS\n")
        sb.append("Nutrient,Category,Logged Intake,RDA Target/Limit,Unit,Percentage Met (%)\n")
        nutrients.forEach { status ->
            val name = status.definition.name
            val group = status.definition.group.displayName
            sb.append(name).append(",").append(group).append(",")
              .append(String.format(Locale.US, "%.2f", status.intake)).append(",")
              .append(String.format(Locale.US, "%.2f", status.definition.rda)).append(",")
              .append(status.definition.unit).append(",")
              .append(String.format(Locale.US, "%.1f", status.percentage)).append("\n")
        }
        
        return sb.toString()
    }

    fun generateMonthlyCsvReport(): String {
        val sb = java.lang.StringBuilder()
        sb.append("NutriScribe Monthly History Report\n")
        sb.append("Generated on: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Calendar.getInstance().time)).append("\n\n")
        
        sb.append("Date,Calories (kcal),Protein (g),Carbohydrates (g),Fat (g),Fiber (g),Sodium (mg),Water (ml),Deficient Nutrients,Food Items Consumed\n")
        
        val logs = allLogEntries.value
        val groupedByDate = logs.groupBy { it.date }
        
        val cal = java.util.Calendar.getInstance()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        
        val past30Days = mutableListOf<String>()
        for (i in 0 until 30) {
            past30Days.add(format.format(cal.time))
            cal.add(java.util.Calendar.DATE, -1)
        }
        past30Days.reverse()
        
        val rdaOverrides = _customRdaOverrides.value
        
        for (dateStr in past30Days) {
            val dayLogs = groupedByDate[dateStr] ?: emptyList()
            
            val dailySums = mutableMapOf<String, Double>()
            for (entry in dayLogs) {
                for ((key, value) in entry.nutrients) {
                    val multiplied = value * entry.quantity
                    dailySums[key] = (dailySums[key] ?: 0.0) + multiplied
                }
            }
            
            val deficienciesList = mutableListOf<String>()
            for (def in com.example.data.Nutrients.DEFINITIONS) {
                val targetRda = rdaOverrides[def.key] ?: def.rda
                val currentIntake = dailySums[def.key] ?: 0.0
                
                if (targetRda > 0.0) {
                    if (def.isMaxLimit) {
                        if (currentIntake > targetRda) {
                            deficienciesList.add("LIMIT EXCEEDED: ${def.name} (${currentIntake.toInt()}/${targetRda.toInt()} ${def.unit})")
                        }
                    } else {
                        if (currentIntake < targetRda) {
                            val pct = ((currentIntake / targetRda) * 100).toInt()
                            deficienciesList.add("${def.name} (${pct}% met)")
                        }
                    }
                }
            }
            
            val deficienciesStr = if (deficienciesList.isEmpty()) {
                "In Perfect Balance"
            } else {
                "\"" + deficienciesList.joinToString("; ").replace("\"", "\"\"") + "\""
            }
            
            val foodsList = dayLogs.map { "${it.foodName} (${it.quantity} ${it.unit})" }
            val foodsStr = if (foodsList.isEmpty()) {
                "No logs today"
            } else {
                "\"" + foodsList.joinToString("; ").replace("\"", "\"\"") + "\""
            }
            
            val dailyCalories = dailySums["calories"] ?: 0.0
            val dailyProtein = dailySums["protein"] ?: 0.0
            val dailyCarbs = dailySums["carbohydrates"] ?: 0.0
            val dailyFat = dailySums["fat"] ?: 0.0
            val dailyFiber = dailySums["fiber"] ?: 0.0
            val dailySodium = dailySums["sodium"] ?: 0.0
            val dailyWater = dailySums["water"] ?: 0.0
            
            sb.append(dateStr).append(",")
              .append(String.format(java.util.Locale.US, "%.0f", dailyCalories)).append(",")
              .append(String.format(java.util.Locale.US, "%.1f", dailyProtein)).append(",")
              .append(String.format(java.util.Locale.US, "%.1f", dailyCarbs)).append(",")
              .append(String.format(java.util.Locale.US, "%.1f", dailyFat)).append(",")
              .append(String.format(java.util.Locale.US, "%.1f", dailyFiber)).append(",")
              .append(String.format(java.util.Locale.US, "%.0f", dailySodium)).append(",")
              .append(String.format(java.util.Locale.US, "%.0f", dailyWater)).append(",")
              .append(deficienciesStr).append(",")
              .append(foodsStr).append("\n")
        }
        
        return sb.toString()
    }

    fun generateHtmlReport(report: PeriodicReport): String {
        val totalDays = report.daysWithLogs
        val divisor = if (totalDays > 0) totalDays.toDouble() else 1.0
        val avgCal = report.avgCaloriesPerDay.toInt()
        val carbPct = (report.avgMacros.carbsPercent * 100).toInt()
        val protPct = (report.avgMacros.proteinPercent * 100).toInt()
        val fatPct = (report.avgMacros.fatPercent * 100).toInt()

        var aboveCount = 0
        var belowCount = 0
        var okayCount = 0
        var nodataCount = 0

        val tableRows = StringBuilder()
        for (status in report.averageNutrients) {
            val def = status.definition
            val intake = status.intake
            val totalValueStr = if (def.key == "calories") {
                (intake * divisor).toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", intake * divisor)
            }
            val averageValueStr = if (def.key == "calories") {
                intake.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", intake)
            }
            val targetValueStr = if (def.key == "calories") {
                def.rda.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", def.rda)
            }

            val rowClass = when (def.unit.lowercase(Locale.US)) {
                "g" -> "macroBg"
                "mcg" -> "traceBg"
                "mg" -> "microBg"
                else -> "otherBg"
            }

            val statusClass = when {
                def.isMaxLimit -> {
                    if (intake > def.rda) {
                        aboveCount++
                        "above"
                    } else {
                        okayCount++
                        "okay"
                    }
                }
                def.rda <= 0.0 -> {
                    nodataCount++
                    "nodata"
                }
                else -> {
                    val pct = status.percentage
                    when {
                        pct >= 100.0 -> {
                            okayCount++
                            "okay"
                        }
                        pct >= 50.0 -> {
                            belowCount++
                            "below"
                        }
                        else -> {
                            belowCount++
                            "below"
                        }
                    }
                }
            }

            val percentStr = if (def.rda > 0.0) "${status.percentage.toInt()}%" else ""
            val ulStr = if (def.isMaxLimit) targetValueStr else ""

            tableRows.append(
                """
                <tr class="row $rowClass">
                    <td class="col-sm-2 Nutrient">${def.name}</td>
                    <td class="col-sm-1 Units">${def.unit}</td>
                    <td class="col-sm-1 Value">$totalValueStr</td>
                    <td class="$statusClass">$averageValueStr</td>
                    <td class="col-sm-1 BoA_RNIpc">$percentStr</td>
                    <td class="col-sm-1 Rni">$targetValueStr</td>
                    <td class="col-sm-1 Ul">$ulStr</td>
                </tr>
                """.trimIndent()
            )
        }

        val diagnosticsBuilder = StringBuilder()
        if (report.insights.isNotEmpty()) {
            diagnosticsBuilder.append(
                """
                <div class="panel panel-warning" style="margin-top: 15px;">
                    <div class="panel-heading">
                        <h3 class="panel-title" style="font-weight: bold;">Periodic Diagnostics & warnings/Insights</h3>
                    </div>
                    <div class="panel-body" style="max-height: 480px; overflow-y: auto;">
                        <ul class="list-group" style="margin-bottom: 0;">
                """.trimIndent()
            )

            for (insight in report.insights) {
                val typeText = if (insight.isExcess) "EXCESS WARNING" else "DEFICIT WARNING"
                val typeClass = if (insight.isExcess) "label-danger" else "label-warning"
                diagnosticsBuilder.append(
                    """
                    <li class="list-group-item">
                        <h4 style="margin-top:0;"><span class="label $typeClass">$typeText</span> <strong>${insight.name}</strong></h4>
                        <p style="font-size: 13px;"><strong>Observed Average:</strong> ${insight.intakeString} vs target ${insight.rdaString}</p>
                        <p style="font-size: 13px; color: #555;"><strong>Short-term Risk:</strong> ${insight.shortTermRisk}</p>
                        <p style="font-size: 13px; color: #c0392b;"><strong>Long-term Cumulative Risk:</strong> ${insight.longTermRisk}</p>
                    </li>
                    """.trimIndent()
                )
            }

            diagnosticsBuilder.append(
                """
                        </ul>
                    </div>
                </div>
                """.trimIndent()
            )
        } else {
            diagnosticsBuilder.append(
                """
                <div class="alert alert-success" style="margin-top: 15px; font-size: 15px;">
                    <strong>Perfect Balance achieved!</strong> Your average intake patterns fulfill all critical dietary standards.
                </div>
                """.trimIndent()
            )
        }

        val format = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)
        val generationTime = format.format(Calendar.getInstance().time)

        return """
            <!DOCTYPE html>
            <html lang="en" xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <meta charset="utf-8" />
                <title>${report.periodName} Log report</title>
                <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css"/>
                <script src="https://ajax.googleapis.com/ajax/libs/jquery/1.12.4/jquery.min.js"></script>
                <script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js"></script>
                <script type="text/javascript" >
                $(document).ready(function ()
                {
                    $("#xGram").click(function () {
                        $("#xres .macroBg").toggle();
                    });
                    $("#xmcg").click(function () {
                        $("#xres .traceBg").toggle();
                    });
                    $("#xmg").click(function () {
                        $("#xres .microBg").toggle();
                    });
                });
                </script>
                <script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>
                <script type="text/javascript">
                  google.charts.load("current", {packages:["corechart"]});
                  google.charts.setOnLoadCallback(drawChart);
                  var X1 = $protPct;
                  var X2 = $fatPct;
                  var X3 = $carbPct;
             
                  function drawChart() 
                  {
                    var data = google.visualization.arrayToDataTable([
                      ['Title', 'Macro Nutrient Balance'],
                      ['Protein',  X1],
                      ['Fat',  X2],
                      ['Carb', X3]         
                    ]);

                    var options = 
                    {
                       pieSliceTextStyle: 
                        {
                            color: 'white',
                            bold:true,
                          },
                       slices: {
                            0: { color: 'Green' },
                            1: { color: 'Red' },
                            2: { color: 'Orange'}
                          },
                        title: 'Macro Nutrient Balance',
                        is3D: false,
                        legend: 'bottom',
                        pieStartAngle: 0,
                    };
                    var chart = new google.visualization.PieChart(document.getElementById('piechart'));
                    chart.draw(data, options);
                  }
                </script>
                <style>
                    body {
                        padding: 30px;
                        background-color: #f8fafc;
                        color: #334155;
                        font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
                    }
                    .h-branding {
                        background-color: #1e293b;
                        color: #ffffff;
                        padding: 24px;
                        border-radius: 8px;
                        margin-bottom: 24px;
                        box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1);
                    }
                    .h-branding h1 { margin-top: 0; font-weight: bold; }
                    .FooTab { width: 100%; border-radius: 8px; overflow: hidden; }
                    .above {
                        background-color: #fecaca !important;
                        color: #991b1b !important;
                        font-weight: bold;
                    }
                    .below {
                        background-color: #fef08a !important;
                        color: #854d0e !important;
                        font-weight: bold;
                    }
                    .okay {
                        background-color: #bbf7d0 !important;
                        color: #166534 !important;
                        font-weight: bold;
                    }
                    .nodata {
                        background-color: #f1f5f9 !important;
                        color: #475569 !important;
                    }
                    .NBtotals td {
                        font-size: 16px;
                        font-weight: bold;
                    }
                    #piechart {
                        height: 260px;
                        margin-bottom: 20px;
                    }
                    .btn-toggle-grp { margin-bottom: 20px; }
                    .footer {
                        margin-top: 40px;
                        padding-top: 20px;
                        border-top: 1px solid #e2e8f0;
                        font-size: 12px;
                    }
                </style>
            </head>
            <body>
            <div class="h-branding">
                <div class="row">
                    <div class="col-sm-8">
                        <h1>NutriScribe Log Report</h1>
                        <h3>Assessment Period: ${report.periodName} (${report.daysCount} Days Evaluation)</h3>
                        <h4>Log Generation Time: $generationTime</h4>
                        <h4>Analyzed across $totalDays active logged day(s)</h4>
                    </div>
                    <div class="col-sm-4 text-right" style="margin-top:20px;">
                        <h2 style="font-size:36px; font-weight:bold; margin:0;">$avgCal kcal</h2>
                        <span style="font-size:14px; opacity:0.8;">Average Daily Intake</span>
                    </div>
                </div>
            </div>

            <div class="row">
                <div class="col-md-6">
                    <div class="panel panel-default">
                        <div class="panel-heading" style="font-weight:bold;">Macro Nutrient Balance spread (Calories)</div>
                        <div class="panel-body">
                            <div id="piechart"></div>
                            <table class="table table-bordered table-condensed table-responsive centre FooTab">
                            <thead>
                            <tr class="row" style="font-weight: bold; background-color: #e2e8f0;">
                            <td class="col-sm-4 mNutrient">Nutrient</td>
                            <td class="col-sm-4 Achieved">Achieved (Avg/Day)</td>
                            <td class="col-sm-4 EXPECT">Ratio (%)</td>
                            </tr>
                            </thead>
                            <tbody>
                              <tr class="row">
                               <td class="col-sm-4 mNutrient">Protein</td>
                               <td class="col-sm-4 Achieved">${report.avgMacros.proteinGrams.toInt()} g</td>
                               <td class="col-sm-4 EXPECT">$protPct%</td>
                              </tr>
                              <tr class="row">
                               <td class="col-sm-4 mNutrient">Fat</td>
                               <td class="col-sm-4 Achieved">${report.avgMacros.fatGrams.toInt()} g</td>
                               <td class="col-sm-4 EXPECT">$fatPct%</td>
                              </tr>
                              <tr class="row">
                               <td class="col-sm-4 mNutrient">Carbohydrates</td>
                               <td class="col-sm-4 Achieved">${report.avgMacros.carbsGrams.toInt()} g</td>
                               <td class="col-sm-4 EXPECT">$carbPct%</td>
                              </tr>
                              <tr class="row" style="font-weight:bold;">
                               <td class="col-sm-4 mNutrient">Calories</td>
                               <td class="col-sm-4 Achieved">$avgCal kcal</td>
                               <td class="col-sm-4 EXPECT">100%</td>
                              </tr>
                            </tbody>
                            </table>
                        </div>
                    </div>
                </div>
                <div class="col-md-6">
                    ${diagnosticsBuilder.toString()}
                </div>
            </div>

            <hr/>
            <h2>Nutrients Sums, Averages, Reference Intake & Limits</h2>
            
            <div class="btn-toggle-grp">
                <span style="font-weight:bold; margin-right: 12px;">Display Filters:</span>
                <button type="button" value="Grams" id="xGram" class="btn btn-primary btn-sm">Grams</button>
                <button type="button" value="Mg" id="xmg" class="btn btn-info btn-sm">Mg</button>
                <button type="button" value="Mcg" id="xmcg" class="btn btn-warning btn-sm">Mcg</button>
            </div>

            <table class="table table-bordered table-condensed table-responsive centre NBtotals" style="max-width: 480px; margin-bottom: 24px;">
            <thead>
                <tr style="background-color: #f1f5f9; font-weight: bold;">
                    <td class="col-xs-3" style="color:#991b1b;">Excess Limits</td>
                    <td class="col-xs-3" style="color:#854d0e;">Warnings (Below target)</td>
                    <td class="col-xs-3" style="color:#166534;">Target Achieved</td>
                    <td class="col-xs-3" style="color:#475569;">No Target Set</td>
                </tr>
            </thead>
            <tbody>
                <tr class="row">
                    <td class="col-xs-3 above">$aboveCount</td>
                    <td class="col-xs-3 below">$belowCount</td>
                    <td class="col-xs-3 okay">$okayCount</td>
                    <td class="col-xs-3 nodata">$nodataCount</td>
                </tr>
            </tbody>
            </table>

            <table class="table table-bordered table-condensed table-responsive centre FooTab" id="xres">
                <thead>
                    <tr class="row table-info" style="font-weight: bold; background-color: #f1f5f9;">
                        <td class="col-sm-2 Nutrient">Nutrient</td>
                        <td class="col-sm-1">Units</td>
                        <td class="col-sm-1">Total Period Sum</td>
                        <td class="col-sm-2">Daily Average Intake</td>
                        <td class="col-sm-1">RDA Standard %</td>
                        <td class="col-sm-1">RDA Target Reference</td>
                        <td class="col-sm-1">Upper Limit (UL)</td>
                    </tr>
                </thead>
                <tbody>
                    ${tableRows.toString()}
                </tbody>
            </table>

            <section class="footer">
            <dl class="dl-horizontal">
            <dt>Daily Average Intake</dt>
            <dd>Calculated as average = sum / active logged days.</dd>
            <dt>Total Period Sum</dt>
            <dd>Total cumulative nutrition tracking captured across checked database range.</dd>
            <dt>RDA Standard %</dt>
            <dd>Percentage achieved relative to daily target guides.</dd>
            <dt>RDA Target Reference</dt>
            <dd>Recommended optimal intake benchmarks.</dd>
            <dt>Upper Limit (UL)</dt>
            <dd>Safe upper limit guidelines where applicable.</dd>
            </dl>
            <p class="text-center text-muted" style="margin-top: 32px;">Report exported from NutriScribe Daily Diet Tracker AI Platform © 2026</p>
            </section>
            </body>
            </html>
        """.trimIndent()
    }

    fun generatePdfReport(context: android.content.Context, report: PeriodicReport, outputStream: java.io.OutputStream) {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        
        // Define dimensions for A4 page size
        val pageWidth = 595
        val pageHeight = 842
        
        // ------------------ PAGE 1: Overview and Insights ------------------
        val pageInfo1 = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page1 = pdfDocument.startPage(pageInfo1)
        val canvas1 = page1.canvas
        
        val paintText = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }
        val paintShape = android.graphics.Paint().apply {
            isAntiAlias = true
        }
        
        // Let's draw a nice top banner block
        paintShape.color = android.graphics.Color.parseColor("#0F172A") // slate-900 (very executive!)
        canvas1.drawRect(0f, 0f, pageWidth.toFloat(), 70f, paintShape)
        
        // Title
        paintText.color = android.graphics.Color.WHITE
        paintText.textSize = 18f
        paintText.isFakeBoldText = true
        canvas1.drawText("NUTRISCRIBE FITNESS & HEALTH REPORT", 24f, 40f, paintText)
        
        // Subtitle
        paintText.textSize = 9f
        paintText.isFakeBoldText = false
        paintText.color = android.graphics.Color.parseColor("#94A3B8") // slate-400
        canvas1.drawText("Personalized Intake Evaluation against RDA Target Benchmarks", 24f, 56f, paintText)
        
        // Right header (Kcal)
        paintText.color = android.graphics.Color.WHITE
        paintText.textSize = 14f
        paintText.isFakeBoldText = true
        val avgKcalStr = "${report.avgCaloriesPerDay.toInt()} kcal"
        val kcalWidth = paintText.measureText(avgKcalStr)
        canvas1.drawText(avgKcalStr, pageWidth - 24f - kcalWidth, 38f, paintText)
        
        paintText.color = android.graphics.Color.parseColor("#94A3B8")
        paintText.textSize = 8f
        paintText.isFakeBoldText = false
        val kcalSubStr = "Daily Avg Energy"
        val kcalSubWidth = paintText.measureText(kcalSubStr)
        canvas1.drawText(kcalSubStr, pageWidth - 24f - kcalSubWidth, 52f, paintText)
        
        // Document Metadata Row (draw beautiful line grid background)
        paintShape.color = android.graphics.Color.parseColor("#F1F5F9") // slate-100
        canvas1.drawRect(24f, 85f, (pageWidth - 24).toFloat(), 135f, paintShape)
        
        paintText.color = android.graphics.Color.parseColor("#475569") // slate-600
        paintText.textSize = 9f
        paintText.isFakeBoldText = true
        canvas1.drawText("REPORT DETAILS", 34f, 102f, paintText)
        
        paintText.isFakeBoldText = false
        paintText.color = android.graphics.Color.parseColor("#0F172A")
        val sdfDate = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
        val generatedDateStr = sdfDate.format(Calendar.getInstance().time)
        canvas1.drawText("Date Generated: $generatedDateStr", 34f, 118f, paintText)
        
        canvas1.drawText("Assessment Interval: ${report.periodName} (${report.daysCount} Days Checked)", 190f, 102f, paintText)
        canvas1.drawText("Active Logs Evaluated: ${report.daysWithLogs} Days with Entries", 190f, 118f, paintText)
        
        canvas1.drawText("Target Profile: Personalized Override", 390f, 102f, paintText)
        canvas1.drawText("Status Integrity: Fully Validated", 390f, 118f, paintText)
        
        // Draw macro energy spread card
        paintShape.color = android.graphics.Color.parseColor("#F8FAFC") // slate-50
        paintShape.style = android.graphics.Paint.Style.FILL
        val cardRect = android.graphics.RectF(24f, 150f, (pageWidth / 2 - 12).toFloat(), 245f)
        canvas1.drawRoundRect(cardRect, 6f, 6f, paintShape)
        // Card Border
        paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
        paintShape.style = android.graphics.Paint.Style.STROKE
        paintShape.strokeWidth = 1f
        canvas1.drawRoundRect(cardRect, 6f, 6f, paintShape)
        
        paintText.color = android.graphics.Color.parseColor("#334155")
        paintText.textSize = 10f
        paintText.isFakeBoldText = true
        canvas1.drawText("MACRONUTRIENT BALANCE SPREAD", 36f, 172f, paintText)
        
        paintText.isFakeBoldText = false
        paintText.textSize = 9f
        paintText.color = android.graphics.Color.parseColor("#64748B")
        canvas1.drawText("Protein Daily Avg:", 36f, 192f, paintText)
        canvas1.drawText("Fat Daily Avg:", 36f, 208f, paintText)
        canvas1.drawText("Carbohydrates Daily Avg:", 36f, 224f, paintText)
        
        paintText.color = android.graphics.Color.parseColor("#0F172A")
        paintText.isFakeBoldText = true
        val carbPct = (report.avgMacros.carbsPercent * 100).toInt()
        val protPct = (report.avgMacros.proteinPercent * 100).toInt()
        val fatPct = (report.avgMacros.fatPercent * 100).toInt()
        
        canvas1.drawText("${report.avgMacros.proteinGrams.toInt()} g  ($protPct%)", 185f, 192f, paintText)
        canvas1.drawText("${report.avgMacros.fatGrams.toInt()} g  ($fatPct%)", 185f, 208f, paintText)
        canvas1.drawText("${report.avgMacros.carbsGrams.toInt()} g  ($carbPct%)", 185f, 224f, paintText)
        
        // Draw Macro progress stacked indicator bar inside card
        paintShape.style = android.graphics.Paint.Style.FILL
        val barX = 36f
        val barY = 232f
        val barWidth = ((pageWidth / 2 - 12) - 36f - 24f)
        val barHeight = 6f
        
        val protW = (report.avgMacros.proteinPercent * barWidth).toFloat()
        val fatW = (report.avgMacros.fatPercent * barWidth).toFloat()
        val carbW = (report.avgMacros.carbsPercent * barWidth).toFloat()
        
        var currentOffset = barX
        if (protW > 0) {
            paintShape.color = android.graphics.Color.parseColor("#FF9800") // Orange
            canvas1.drawRect(currentOffset, barY, currentOffset + protW, barY + barHeight, paintShape)
            currentOffset += protW
        }
        if (fatW > 0) {
            paintShape.color = android.graphics.Color.parseColor("#4CAF50") // Green
            canvas1.drawRect(currentOffset, barY, currentOffset + fatW, barY + barHeight, paintShape)
            currentOffset += fatW
        }
        if (carbW > 0) {
            paintShape.color = android.graphics.Color.parseColor("#3897F5") // Blue
            canvas1.drawRect(currentOffset, barY, (pageWidth / 2 - 36f).toFloat(), barY + barHeight, paintShape)
        }
        
        // Draw compliance scores card
        val scoreCardRect = android.graphics.RectF((pageWidth / 2 + 12).toFloat(), 150f, (pageWidth - 24).toFloat(), 245f)
        paintShape.color = android.graphics.Color.parseColor("#F8FAFC")
        paintShape.style = android.graphics.Paint.Style.FILL
        canvas1.drawRoundRect(scoreCardRect, 6f, 6f, paintShape)
        
        paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
        paintShape.style = android.graphics.Paint.Style.STROKE
        canvas1.drawRoundRect(scoreCardRect, 6f, 6f, paintShape)
        
        paintText.color = android.graphics.Color.parseColor("#334155")
        paintText.textSize = 10f
        paintText.isFakeBoldText = true
        canvas1.drawText("DIETARY INTEGRITY SCORECARD", (pageWidth / 2 + 24).toFloat(), 172f, paintText)
        
        // calculate above/below/okay/nodata from report
        var aboveCount = 0
        var belowCount = 0
        var okayCount = 0
        var nodataCount = 0
        for (status in report.averageNutrients) {
            val def = status.definition
            val intake = status.intake
            if (def.isMaxLimit) {
                if (intake > def.rda) aboveCount++ else okayCount++
            } else if (def.rda <= 0.0) {
                nodataCount++
            } else {
                val pct = status.percentage
                if (pct >= 100.0) okayCount++ else belowCount++
            }
        }
        
        paintText.isFakeBoldText = false
        paintText.textSize = 8.5f
        paintText.color = android.graphics.Color.parseColor("#64748B")
        val scX = (pageWidth / 2 + 24).toFloat()
        canvas1.drawText("Target Milestones Achieved:", scX, 192f, paintText)
        canvas1.drawText("Sub-Optimal Nutrient Deficits:", scX, 206f, paintText)
        canvas1.drawText("Upper Limit Cumulative Excesses:", scX, 220f, paintText)
        canvas1.drawText("Nutrients Untracked / No Goal:", scX, 234f, paintText)
        
        paintText.isFakeBoldText = true
        paintText.color = android.graphics.Color.parseColor("#15803D") // strong green
        canvas1.drawText("$okayCount targets met", scX + 170, 192f, paintText)
        
        paintText.color = android.graphics.Color.parseColor("#B45309") // dark gold
        canvas1.drawText("$belowCount warnings", scX + 170, 206f, paintText)
        
        paintText.color = android.graphics.Color.parseColor("#B91C1C") // dark red
        canvas1.drawText("$aboveCount warnings", scX + 170, 220f, paintText)
        
        paintText.color = android.graphics.Color.parseColor("#475569")
        canvas1.drawText("$nodataCount tracked", scX + 170, 234f, paintText)
        
        // EXPERT DIAGNOSTICS & WARNINGS SECTION (Draws insights)
        paintText.color = android.graphics.Color.parseColor("#0F172A")
        paintText.textSize = 11f
        paintText.isFakeBoldText = true
        canvas1.drawText("BIO-INTELLIGENCE HEALTH DIAGNOSTICS & ALERTS", 24f, 275f, paintText)
        
        paintShape.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas1.drawLine(24f, 282f, (pageWidth - 24).toFloat(), 282f, paintShape)
        
        var currentY = 298f
        if (report.insights.isEmpty()) {
            paintShape.color = android.graphics.Color.parseColor("#F0FDF4") // soft green fill
            paintShape.style = android.graphics.Paint.Style.FILL
            val innerBorder = android.graphics.RectF(24f, currentY, (pageWidth - 24).toFloat(), currentY + 120f)
            canvas1.drawRoundRect(innerBorder, 4f, 4f, paintShape)
            
            paintShape.color = android.graphics.Color.parseColor("#DCFCE7")
            paintShape.style = android.graphics.Paint.Style.STROKE
            canvas1.drawRoundRect(innerBorder, 4f, 4f, paintShape)
            
            // Draw custom positive text
            paintText.color = android.graphics.Color.parseColor("#15803D")
            paintText.textSize = 10f
            paintText.isFakeBoldText = true
            canvas1.drawText("PERFECT ADHERENCE ACHIEVED", 40f, currentY + 25f, paintText)
            
            paintText.isFakeBoldText = false
            paintText.textSize = 9f
            paintText.color = android.graphics.Color.parseColor("#166534")
            canvas1.drawText("Your cumulative daily diets fulfill 100% of standard Recommended Dietary Allowance (RDA) thresholds.", 40f, currentY + 45f, paintText)
            canvas1.drawText("No critical nutrient deficits, micronutrient starvation states, or compound excesses were detected.", 40f, currentY + 62f, paintText)
            canvas1.drawText("Continue your current food logging patterns and RDA configurations. Excellent work!", 40f, currentY + 79f, paintText)
        } else {
            // Draw up to 3 diagnostic alerts
            val maxInsights = minOf(3, report.insights.size)
            for (i in 0 until maxInsights) {
                val insight = report.insights[i]
                
                // Color coordination
                val alertBg = if (insight.isExcess) "#FEF2F2" else "#FFFBEB"
                val alertBorder = if (insight.isExcess) "#FCA5A5" else "#FDE68A"
                val alertTextHex = if (insight.isExcess) "#991B1B" else "#92400E"
                
                paintShape.style = android.graphics.Paint.Style.FILL
                paintShape.color = android.graphics.Color.parseColor(alertBg)
                val alertRect = android.graphics.RectF(24f, currentY, (pageWidth - 24).toFloat(), currentY + 145f)
                canvas1.drawRoundRect(alertRect, 4f, 4f, paintShape)
                
                paintShape.style = android.graphics.Paint.Style.STROKE
                paintShape.color = android.graphics.Color.parseColor(alertBorder)
                canvas1.drawRoundRect(alertRect, 4f, 4f, paintShape)
                
                // Draw Alert Type Tag & Title
                paintText.color = android.graphics.Color.parseColor(alertTextHex)
                paintText.textSize = 9.5f
                paintText.isFakeBoldText = true
                val alertHeaderStr = "${if (insight.isExcess) "CUMULATIVE EXCESS NOTICE:" else "CRITICAL DEFICIT ALERT:"} ${insight.name.uppercase(Locale.US)}"
                canvas1.drawText(alertHeaderStr, 36f, currentY + 22f, paintText)
                
                paintText.color = android.graphics.Color.parseColor("#334155")
                paintText.isFakeBoldText = false
                paintText.textSize = 8.5f
                canvas1.drawText("Daily Average Evaluated: ${insight.intakeString} / day  (RDA Reference: ${insight.rdaString})", 36f, currentY + 38f, paintText)
                
                // Draw risks
                paintText.textSize = 8f
                paintText.color = android.graphics.Color.parseColor("#475569")
                
                val stRisk = "Short-term health effect: " + insight.shortTermRisk
                val ltRisk = "Long-term cumulative damage risk: " + insight.longTermRisk
                
                val stLines = wrapTextForPdf(stRisk, paintText, pageWidth - 80)
                val ltLines = wrapTextForPdf(ltRisk, paintText, pageWidth - 80)
                
                var stYOffset = currentY + 54f
                for (line in stLines) {
                    canvas1.drawText("• " + line, 42f, stYOffset, paintText)
                    stYOffset += 11f
                }
                
                var ltYOffset = stYOffset + 4f
                for (line in ltLines) {
                    canvas1.drawText("• " + line, 42f, ltYOffset, paintText)
                    ltYOffset += 11f
                }
                
                currentY += 155f
            }
            
            if (report.insights.size > 3) {
                paintText.textSize = 8f
                paintText.color = android.graphics.Color.parseColor("#64748B")
                paintText.isFakeBoldText = true
                val extraCount = report.insights.size - 3
                canvas1.drawText("+ $extraCount more diagnostic notices logged on the next page table.", 24f, 785f, paintText)
            }
        }
        
        // Footer signature
        paintShape.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas1.drawLine(24f, 805f, (pageWidth - 24).toFloat(), 805f, paintShape)
        
        paintText.color = android.graphics.Color.parseColor("#94A3B8")
        paintText.textSize = 7.5f
        paintText.isFakeBoldText = false
        canvas1.drawText("NUTRISCRIBE DIGITAL TRACKING LABS  |  OFFICIAL HEALTH EXPORT", 24f, 818f, paintText)
        val signatureStr = "Page 1 of 2"
        val sigWidth = paintText.measureText(signatureStr)
        canvas1.drawText(signatureStr, pageWidth - 24f - sigWidth, 818f, paintText)
        
        pdfDocument.finishPage(page1)
        
        // ------------------ PAGE 2: Nutrient-by-Nutrient ------------------
        val pageInfo2 = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create()
        val page2 = pdfDocument.startPage(pageInfo2)
        val canvas2 = page2.canvas
        
        // Top banner for details page
        paintShape.color = android.graphics.Color.parseColor("#1E293B") // slate-800
        paintShape.style = android.graphics.Paint.Style.FILL
        canvas2.drawRect(0f, 0f, pageWidth.toFloat(), 45f, paintShape)
        
        paintText.color = android.graphics.Color.WHITE
        paintText.textSize = 12f
        paintText.isFakeBoldText = true
        canvas2.drawText("DETAILED NUTRIENT CONSUMPTION VS PERSONALIZED RDA GOALS", 24f, 27f, paintText)
        
        // Headers of the table
        paintShape.color = android.graphics.Color.parseColor("#F1F5F9") // slate-100
        canvas2.drawRect(24f, 60f, (pageWidth - 24).toFloat(), 82f, paintShape)
        
        paintText.color = android.graphics.Color.parseColor("#334155")
        paintText.textSize = 8.5f
        paintText.isFakeBoldText = true
        
        canvas2.drawText("Nutrient Tracker", 32f, 74f, paintText)
        canvas2.drawText("Daily Average Intake", 205f, 74f, paintText)
        canvas2.drawText("RDA Goal Reference", 310f, 74f, paintText)
        canvas2.drawText("Adherence & Achievements Visual Guide", 400f, 74f, paintText)
        
        var itemY = 98f
        val tableRowHeight = 24f
        
        for (nutrientStatus in report.averageNutrients) {
            val def = nutrientStatus.definition
            val intake = nutrientStatus.intake
            val percent = nutrientStatus.percentage
            val isUrgent = (nutrientStatus.status == StatusColor.RED)
            
            // Highlight warnings to draw user focus
            if (isUrgent) {
                paintShape.color = android.graphics.Color.parseColor("#FFF5F5") // ultra-soft red
                canvas2.drawRect(24f, itemY - 14f, (pageWidth - 24).toFloat(), itemY + tableRowHeight - 16f, paintShape)
            }
            
            // Draw minor row splitting line
            paintShape.color = android.graphics.Color.parseColor("#F1F5F9")
            canvas2.drawLine(24f, itemY + tableRowHeight - 16f, (pageWidth - 24).toFloat(), itemY + tableRowHeight - 16f, paintShape)
            
            // Nutrient text style
            paintText.textSize = 8.5f
            paintText.isFakeBoldText = true
            paintText.color = if (isUrgent) android.graphics.Color.parseColor("#DC2626") else android.graphics.Color.parseColor("#0F172A")
            canvas2.drawText(def.name, 32f, itemY, paintText)
            
            // Daily group info
            paintText.textSize = 7f
            paintText.isFakeBoldText = false
            paintText.color = android.graphics.Color.parseColor("#64748B")
            canvas2.drawText(def.group.name, 32f, itemY + 8f, paintText)
            
            // Intake Text
            paintText.textSize = 8.5f
            paintText.isFakeBoldText = true
            paintText.color = android.graphics.Color.parseColor("#0F172A")
            val intakeDisplayStr = if (def.key == "calories") intake.toInt().toString() + " kcal" else String.format(Locale.US, "%.1f %s", intake, def.unit)
            canvas2.drawText(intakeDisplayStr, 205f, itemY, paintText)
            
            // Target text
            paintText.isFakeBoldText = false
            val rdaDisplayStr = if (def.key == "calories") def.rda.toInt().toString() + " kcal" else String.format(Locale.US, "%.1f %s", def.rda, def.unit)
            canvas2.drawText(rdaDisplayStr, 310f, itemY, paintText)
            
            // Progress Bar Visual Guide
            if (def.rda > 0.0) {
                val pBarX = 400f
                val pBarY = itemY - 6f
                val pBarWidthTarget = 110f
                val pBarHeight = 7f
                
                // Draw background bar
                paintShape.style = android.graphics.Paint.Style.FILL
                paintShape.color = android.graphics.Color.parseColor("#E2E8F0")
                paintShape.isAntiAlias = true
                val progressBgRect = android.graphics.RectF(pBarX, pBarY, pBarX + pBarWidthTarget, pBarY + pBarHeight)
                canvas2.drawRoundRect(progressBgRect, 2f, 2f, paintShape)
                
                // Limit calculation
                val currentPct = percent.coerceIn(0.0, 100.0) / 100.0
                val progressWidth = (currentPct * pBarWidthTarget).toFloat()
                
                // Fill Bar
                paintShape.color = when (nutrientStatus.status) {
                    StatusColor.GREEN -> android.graphics.Color.parseColor("#22C55E") // clean emerald green
                    StatusColor.YELLOW -> android.graphics.Color.parseColor("#F59E0B") // fine amber yellow
                    StatusColor.RED -> android.graphics.Color.parseColor("#EF4444") // bright crimson red
                }
                
                if (progressWidth > 0f) {
                    val progressFillRect = android.graphics.RectF(pBarX, pBarY, pBarX + progressWidth, pBarY + pBarHeight)
                    canvas2.drawRoundRect(progressFillRect, 2f, 2f, paintShape)
                }
                
                // Draw Percentage overlay text
                paintText.textSize = 7.5f
                paintText.isFakeBoldText = true
                paintText.color = paintShape.color
                val pctStr = if (def.isMaxLimit) {
                    if (intake > def.rda) "EXCEEDED" else "SAFE"
                } else {
                    "${percent.toInt()}%"
                }
                canvas2.drawText(pctStr, pBarX + pBarWidthTarget + 8f, itemY + 1f, paintText)
            } else {
                paintText.textSize = 7.5f
                paintText.isFakeBoldText = false
                paintText.color = android.graphics.Color.parseColor("#94A3B8")
                canvas2.drawText("No target set", 400f, itemY + 1f, paintText)
            }
            
            itemY += tableRowHeight
        }
        
        // Footer Page 2
        paintShape.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas2.drawLine(24f, 805f, (pageWidth - 24).toFloat(), 805f, paintShape)
        
        paintText.color = android.graphics.Color.parseColor("#94A3B8")
        paintText.textSize = 7.5f
        paintText.isFakeBoldText = false
        canvas2.drawText("NUTRISCRIBE DIGITAL TRACKING LABS  |  OFFICIAL HEALTH EXPORT", 24f, 818f, paintText)
        val signPage2Str = "Page 2 of 2"
        val sigPage2Width = paintText.measureText(signPage2Str)
        canvas2.drawText(signPage2Str, pageWidth - 24f - sigPage2Width, 818f, paintText)
        
        pdfDocument.finishPage(page2)
        
        // Save PDF content
        pdfDocument.writeTo(outputStream)
        pdfDocument.close()
    }
    
    // Help method to wrap long risk description strings in PDF drawing
    private fun wrapTextForPdf(text: String, paint: android.graphics.Paint, maxWidth: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            val width = paint.measureText(testLine)
            if (width > maxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            } else {
                currentLine = StringBuilder(testLine)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }
    
    // --- Google Search Grounded Nutrient Benefits ---
    private val _selectedGroundedNutrient = MutableStateFlow<String?>(null)
    val selectedGroundedNutrient: StateFlow<String?> = _selectedGroundedNutrient.asStateFlow()

    private val _groundedBenefitText = MutableStateFlow<String?>(null)
    val groundedBenefitText: StateFlow<String?> = _groundedBenefitText.asStateFlow()

    private val _isGroundedBenefitLoading = MutableStateFlow(false)
    val isGroundedBenefitLoading: StateFlow<Boolean> = _isGroundedBenefitLoading.asStateFlow()

    private val _groundedBenefitSources = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // title, link
    val groundedBenefitSources: StateFlow<List<Pair<String, String>>> = _groundedBenefitSources.asStateFlow()

    fun queryNutrientBenefitGrounding(nutrientName: String, nutrientKey: String, fallbackText: String) {
        _selectedGroundedNutrient.value = nutrientName
        _isGroundedBenefitLoading.value = true
        _groundedBenefitText.value = null
        _groundedBenefitSources.value = emptyList()

        val apiKey = com.example.data.GeminiAuthHandler.getApiKey()
        val hasValidKey = apiKey.isNotEmpty()

        if (!hasValidKey) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(400)
                _groundedBenefitText.value = fallbackText + "\n\n(Note: This is a standard clinical description. To unlock live benefits and citations with Google Search grounding, configure your Gemini API Key in your workspace Secrets.)"
                _groundedBenefitSources.value = listOf(
                    Pair("NIH Dietary Supplement Facts", "https://ods.od.nih.gov/"),
                    Pair("USDA Nutrient Hub Reference", "https://fdc.nal.usda.gov/")
                )
                _isGroundedBenefitLoading.value = false
            }
            return
        }

        viewModelScope.launch {
            val prompt = """
                You are NutriScribe's Biochemistry & Clinical Nutrition Researcher.
                Provide a brief clinical 'Nutrient Benefit' summary of $nutrientName (ID: $nutrientKey) explaining its active roles in cellular respiration, muscular/cognitive health, and physiological benefits.
                Answer in 2 to 3 concise, highly professional sentences max.
            """.trimIndent()

            try {
                val reqObj = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("tools", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("googleSearch", org.json.JSONObject())
                        })
                    })
                }
                val request = com.example.data.GeminiAuthHandler.buildRequest(
                    "gemini-3.5-flash:generateContent",
                    reqObj.toString()
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val root = org.json.JSONObject(responseBody)
                            val candidates = root.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                val candidate = candidates.getJSONObject(0)
                                val text = candidate.getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")

                                val loadedSources = mutableListOf<Pair<String, String>>()
                                val metadata = candidate.optJSONObject("groundingMetadata")
                                if (metadata != null) {
                                    val chunksJson = metadata.optJSONArray("groundingChunks")
                                    if (chunksJson != null) {
                                        for (i in 0 until chunksJson.length()) {
                                            val chunk = chunksJson.optJSONObject(i)
                                            val web = chunk?.optJSONObject("web")
                                            if (web != null) {
                                                val title = web.optString("title")
                                                val uri = web.optString("uri")
                                                if (title.isNotEmpty() && uri.isNotEmpty()) {
                                                    loadedSources.add(Pair(title, uri))
                                                }
                                            }
                                        }
                                    }
                                }

                                if (loadedSources.isEmpty()) {
                                    loadedSources.add(Pair("USDA FoodData Central reference basis", "https://fdc.nal.usda.gov/"))
                                    loadedSources.add(Pair("NIH Office of Dietary Supplements", "https://ods.od.nih.gov/"))
                                }

                                _groundedBenefitText.value = text
                                _groundedBenefitSources.value = loadedSources.distinctBy { it.second }.take(4)
                            } else {
                                _groundedBenefitText.value = fallbackText + "\n\n(Fallback benefit summary loaded: unable to decode live search components.)"
                                _groundedBenefitSources.value = listOf(Pair("USDA FoodData Central", "https://fdc.nal.usda.gov/"))
                            }
                        } else {
                            _groundedBenefitText.value = fallbackText + "\n\n(Fallback benefit summary loaded: dynamic model response offline.)"
                            _groundedBenefitSources.value = listOf(Pair("USDA FoodData Reference", "https://fdc.nal.usda.gov/"))
                        }
                    }
                }
            } catch (e: Exception) {
                _groundedBenefitText.value = fallbackText + "\n\n(Fallback summary loaded: Connection standard offline.)"
                _groundedBenefitSources.value = listOf(Pair("USDA FoodData Reference", "https://fdc.nal.usda.gov/"))
            } finally {
                _isGroundedBenefitLoading.value = false
            }
        }
    }

    fun clearNutrientBenefitGrounding() {
        _selectedGroundedNutrient.value = null
        _groundedBenefitText.value = null
        _groundedBenefitSources.value = emptyList()
    }

    fun fetchAiPersonalizedTips(forceGemini: Boolean = false) {
        val statusList = dailyNutrients.value
        val deficiencies = statusList.filter { !it.definition.isMaxLimit && it.percentage < 50.0 && it.definition.rda > 0.0 }
        val excesses = statusList.filter { it.definition.isMaxLimit && it.intake > it.definition.rda }
        val date = currentDate.value

        viewModelScope.launch {
            val apiKey = com.example.data.GeminiAuthHandler.getApiKey()
            val hasValidKey = apiKey.isNotEmpty()

            if (!hasValidKey) {
                _isAiLoading.value = true
                kotlinx.coroutines.delay(300)
                val fallbackTip = generateLocalFallbackTips(deficiencies, excesses)
                _aiNutritionalTip.value = fallbackTip
                _isGeminiResponseActive.value = false
                _isAiLoading.value = false
                if (forceGemini) {
                    _aiError.value = "Gemini API key is not configured. Showing local smart tips."
                }
                return@launch
            }

            _isAiLoading.value = true
            _aiError.value = null

            val prompt = """
                You are NutriScribe's Elite AI Clinical Nutritionist.
                Analyze the user's current nutrient logs for the day and provide 3 highly personalized, hyper-targeted, and scientifically precise nutritional tips to fix deficiencies and avoid excesses.
                
                Nutrient Status Profile for Today ($date):
                
                Deficiency Warning Areas (Intake < 50% of RDA goals):
                ${if (deficiencies.isEmpty()) "None - All goals progressing well!" else deficiencies.joinToString("\n") { "• ${it.definition.name}: Intake ${it.intake.toInt()}${it.definition.unit} of ${it.definition.rda.toInt()}${it.definition.unit} goal (${it.percentage.toInt()}% met)" }}
                
                Excess Warning Areas (Intake above max recommended limits):
                ${if (excesses.isEmpty()) "None - All limits respected safely!" else excesses.joinToString("\n") { "• ${it.definition.name}: Intake ${it.intake.toInt()}${it.definition.unit} (exceeded upper limit of ${it.definition.rda.toInt()}${it.definition.unit})" }}
                
                Format your response in a beautiful, structured format with EXACTLY 3 bullet points or short subsections (using markdown like **Target Area**: text). For each tip:
                1. First, name the Target Area clearly (e.g., "**Protein Deficit Balance**" or "**Saturated Fat Control**").
                2. Tell the user EXACTLY which localized real foods, herbs, or clean ingredients they should consume or restrict (e.g. low-fat Greek yogurt, pumpkin seeds, spinach, celery juices) to optimize their profile. Keep it actionable, friendly, and clinic-grade professional. Keep the response very concise (max 150 words in total). Do not include introductory notes or friendly chit-chat, jump straight into the tips.
            """.trimIndent()

            try {
                val reqObj = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }
                val request = com.example.data.GeminiAuthHandler.buildRequest(
                    "gemini-3.5-flash:generateContent",
                    reqObj.toString()
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val root = org.json.JSONObject(responseBody)
                            val candidates = root.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                val text = candidates.getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")

                                _aiNutritionalTip.value = text
                                _isGeminiResponseActive.value = true
                            } else {
                                _aiError.value = "Unable to process Gemini Response structure."
                            }
                        } else {
                            _aiError.value = "Gemini Service returned error ${response.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                _aiError.value = "Gemini API Connection Error: ${e.localizedMessage}"
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun fetchSuggestedFoodsForDeficiencies(forceGemini: Boolean = false) {
        val statusList = dailyNutrients.value
        val deficiencies = statusList.filter { !it.definition.isMaxLimit && it.percentage < 100.0 && it.definition.rda > 0.0 }
            .sortedBy { it.percentage }

        viewModelScope.launch {
            val apiKey = com.example.data.GeminiAuthHandler.getApiKey()
            val hasValidKey = apiKey.isNotEmpty()

            if (!hasValidKey) {
                _isSuggestionsLoading.value = true
                kotlinx.coroutines.delay(400)
                val fallbackList = generateOfflineFoodSuggestions(deficiencies)
                _targetSuggestedFoods.value = fallbackList
                _isSuggestionsLoading.value = false
                if (forceGemini) {
                    _suggestionsError.value = "Gemini API key is not configured. Showing smart fallback suggestions."
                }
                return@launch
            }

            _isSuggestionsLoading.value = true
            _suggestionsError.value = null

            val prompt = """
                You are NutriScribe's Elite AI Clinical Nutritionist.
                Analyze the user's current deficient nutrients below and suggest EXACTLY 5 high-density, real-world foods that can help correct these deficiencies.
                
                Current Nutrient Deficiencies (Intake < 100% of goal):
                ${if (deficiencies.isEmpty()) "None - All goals progressing well!" else deficiencies.joinToString("\n") { "• ${it.definition.name}: ${it.percentage.toInt()}% met (${it.intake.toInt()}/${it.definition.rda.toInt()}${it.definition.unit})" }}
                
                Respond ONLY with a valid raw JSON array containing EXACTLY 5 objects. Do not wrap the JSON in ```json or ``` or any markdown text. Each of the 5 objects in the JSON array must have exactly these keys:
                - "food_name": (string, e.g. "Sardines", "Spinach", "Pumpkin Seeds")
                - "dense_nutrients": (string, the main nutrients this food is rich in that help correct these deficiencies, e.g., "Protein, Intestinal Fiber, Calcium")
                - "content_measure": (string, realistic nutritional quantity of the relevant nutrient per 100g or typical serving, e.g., "19.2 µg Vit D & 382mg Calcium per 100g")
                - "explanation": (string, concise 1-2 sentence explanation of why this specific food is highly bioavailable or beneficial in correcting the identified deficits, e.g. "An excellent whole-food source providing clean protein, omega-3 acids, and highly bioavailable calcium to address your bone density baseline needs.")
            """.trimIndent()

            try {
                val reqObj = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }
                val request = com.example.data.GeminiAuthHandler.buildRequest(
                    "gemini-3.5-flash:generateContent",
                    reqObj.toString()
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val root = org.json.JSONObject(responseBody)
                            val candidates = root.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                var reply = candidates.getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")

                                if (reply.contains("```json")) {
                                    reply = reply.substringAfter("```json").substringBefore("```")
                                } else if (reply.contains("```")) {
                                    reply = reply.substringAfter("```").substringBefore("```")
                                }
                                reply = reply.trim()

                                val parsedArray = org.json.JSONArray(reply)
                                val list = mutableListOf<FoodSuggestion>()
                                for (i in 0 until parsedArray.length()) {
                                    val obj = parsedArray.getJSONObject(i)
                                    list.add(
                                        FoodSuggestion(
                                            foodName = obj.optString("food_name", ""),
                                            denseNutrients = obj.optString("dense_nutrients", ""),
                                            contentMeasure = obj.optString("content_measure", ""),
                                            explanation = obj.optString("explanation", "")
                                        )
                                    )
                                }
                                _targetSuggestedFoods.value = list
                            } else {
                                _suggestionsError.value = "Unable to process Gemini suggestions structure."
                                _targetSuggestedFoods.value = generateOfflineFoodSuggestions(deficiencies)
                            }
                        } else {
                            _suggestionsError.value = "Gemini Service returned error ${response.code}"
                            _targetSuggestedFoods.value = generateOfflineFoodSuggestions(deficiencies)
                        }
                    }
                }
            } catch (e: Exception) {
                _suggestionsError.value = "Gemini API Connection Error: ${e.localizedMessage}"
                _targetSuggestedFoods.value = generateOfflineFoodSuggestions(deficiencies)
            } finally {
                _isSuggestionsLoading.value = false
            }
        }
    }

    fun fetchRecipeSuggestions(query: String, forceGemini: Boolean = false) {
        val statusList = dailyNutrients.value
        val deficiencies = statusList.filter { 
            !it.definition.isMaxLimit && 
            it.percentage < 100.0 && 
            it.definition.rda > 0.0 
        }.sortedBy { it.percentage }

        val deficienciesStrings = deficiencies.map { 
            "${it.definition.name}: ${it.percentage.toInt()}% met (${it.intake.toInt()}/${it.definition.rda.toInt()}${it.definition.unit})" 
        }

        viewModelScope.launch {
            val apiKey = com.example.data.GeminiAuthHandler.getApiKey()
            val hasValidKey = apiKey.isNotEmpty()

            val trimmedQuery = query.trim()

            if (!hasValidKey) {
                _isRecipesLoading.value = true
                kotlinx.coroutines.delay(400)
                _aiSuggestedRecipes.value = generateOfflineRecipes(deficienciesStrings, trimmedQuery)
                _isRecipesLoading.value = false
                if (forceGemini) {
                    _recipesError.value = "Gemini API key is not configured. Showing smart fallback suggestions."
                }
                return@launch
            }

            _isRecipesLoading.value = true
            _recipesError.value = null

            val gapsText = if (deficienciesStrings.isEmpty()) "None - All goals progressing well!" else deficienciesStrings.joinToString("\n") { "• $it" }

            val prompt = """
                You are NutriScribe's elite AI chef and clinical nutritionist.
                Recommend exactly 3 specific, detailed recipes that match the user's search query "$trimmedQuery" (or generally high-density nourishing recipes if the search query is empty/broad), and are specifically designed to replenish the nutrients where the user currently has an RDA deficiency.
                
                Current RDA Gaps / Missing Nutrients:
                $gapsText
                
                Each recommended recipe should prioritize ingredients that are common, raw or whole foods, which can be found in the USDA FoodData Central database.
                
                Respond ONLY with a valid raw JSON array containing exactly 3 recipe objects. Do not wrap the JSON in ```json or ``` or any markdown text.
                Each recipe object in the array must have exactly these keys (and the values must be correctly typed as specified below):
                - "recipeName": (string) e.g. "Iron-Boost spinach & sesame chicken bowl"
                - "description": (string, 1-2 sentence overview explaining how this recipe addresses their deficiencies) e.g. "A savory sesame bowl featuring dark leafy greens and tender poultry, packed with easily absorbed heme iron and vitamin C to enhance mineral uptake."
                - "targetedNutrients": (string, comma-separated list of the key deficient nutrients this recipe targets) e.g. "Iron, Vitamin C, Protein"
                - "ingredients": (array of strings, where each item is a single ingredient with its measurement) e.g. ["150g Fresh Baby Spinach", "200g Grilled Chicken Breast", "1 tbsp Sesame Seeds", "1 tsp Olive Oil"]
                - "instructions": (array of strings, listing the clear cooking steps) e.g. ["Thoroughly wash the fresh baby spinach and arrange in a serving bowl.", "Sauté chicken breast with olive oil in a hot pan until cooked through.", "Slice chicken and place on spinach, then sprinkle with toasted sesame seeds and sesame oil."]
                - "prepTime": (string, total estimated prep & cook time) e.g. "15 minutes"
            """.trimIndent()

            try {
                val reqObj = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }
                val request = com.example.data.GeminiAuthHandler.buildRequest(
                    "gemini-3.5-flash:generateContent",
                    reqObj.toString()
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val root = org.json.JSONObject(responseBody)
                            val candidates = root.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                var reply = candidates.getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")

                                if (reply.contains("```json")) {
                                    reply = reply.substringAfter("```json").substringBefore("```")
                                } else if (reply.contains("```")) {
                                    reply = reply.substringAfter("```").substringBefore("```")
                                }
                                reply = reply.trim()

                                val parsedArray = org.json.JSONArray(reply)
                                val list = mutableListOf<AiRecipeSuggestion>()
                                for (i in 0 until parsedArray.length()) {
                                    val obj = parsedArray.getJSONObject(i)
                                    
                                    val ingArray = obj.getJSONArray("ingredients")
                                    val ingredientsList = mutableListOf<String>()
                                    for (j in 0 until ingArray.length()) {
                                        ingredientsList.add(ingArray.getString(j))
                                    }
                                    
                                    val instArray = obj.getJSONArray("instructions")
                                    val instructionsList = mutableListOf<String>()
                                    for (j in 0 until instArray.length()) {
                                        instructionsList.add(instArray.getString(j))
                                    }
                                    
                                    list.add(
                                        AiRecipeSuggestion(
                                            recipeName = obj.optString("recipeName", ""),
                                            description = obj.optString("description", ""),
                                            targetedNutrients = obj.optString("targetedNutrients", ""),
                                            ingredients = ingredientsList,
                                            instructions = instructionsList,
                                            prepTime = obj.optString("prepTime", "")
                                        )
                                    )
                                }
                                _aiSuggestedRecipes.value = list
                            } else {
                                _recipesError.value = "Unable to process Gemini recipe recommendations structure."
                                _aiSuggestedRecipes.value = generateOfflineRecipes(deficienciesStrings, trimmedQuery)
                            }
                        } else {
                            _recipesError.value = "Gemini Service returned error ${response.code}"
                            _aiSuggestedRecipes.value = generateOfflineRecipes(deficienciesStrings, trimmedQuery)
                        }
                    }
                }
            } catch (e: Exception) {
                _recipesError.value = "Gemini API Connection Error: ${e.localizedMessage}"
                _aiSuggestedRecipes.value = generateOfflineRecipes(deficienciesStrings, trimmedQuery)
            } finally {
                _isRecipesLoading.value = false
            }
        }
    }

    fun generateOfflineRecipes(deficiencies: List<String>, query: String): List<AiRecipeSuggestion> {
        val trimmed = query.lowercase().trim()
        val allRecipes = listOf(
            AiRecipeSuggestion(
                recipeName = "Super-Greens Iron & Vitamin C Salad",
                description = "An iron-packed bowl combining tender spinach, toasted sunflower seeds, and citrus dressing designed to maximize iron bioavailability using organic nutrients.",
                targetedNutrients = "Iron, Vitamin C, Dietary Fiber, Magnesium",
                ingredients = listOf("150g Fresh Spinach", "30g Sunflower Seeds", "1 Medium Orange (segmented)", "1 tbsp Extra Virgin Olive Oil"),
                instructions = listOf(
                    "Wash spinach leaves thoroughly and dry them.",
                    "Toast sunflower seeds in a dry pan over medium heat for 2-3 minutes until golden.",
                    "Toss spinach, orange segments, and seeds together.",
                    "Drizzle with cold-pressed olive oil and fresh orange juice."
                ),
                prepTime = "10 mins"
            ),
            AiRecipeSuggestion(
                recipeName = "Creamy Chia & Greek Yogurt Protein Parfait",
                description = "Rich in calcium, vitamin D, and high-quality protein to support muscle recovery and dense bone matrices.",
                targetedNutrients = "Calcium, Protein, Phosphorus, Riboflavin",
                ingredients = listOf("200g Plain Greek Yogurt", "2 tbsp Whole Chia Seeds", "100g Fresh Blueberries", "1 tbsp Pure Honey"),
                instructions = listOf(
                    "Mix chia seeds into Greek yogurt and let sit for 5 minutes to bloom.",
                    "Layer the chia-yogurt mixture with fresh blueberries in a glass.",
                    "Drizzle with pure organic honey before serving cold."
                ),
                prepTime = "8 mins"
            ),
            AiRecipeSuggestion(
                recipeName = "Sesame Garlic Salmon & Steamed Broccoli Bowl",
                description = "High in premium omega-3 fatty acids, potassium, and vitamin D to stimulate arterial and skeletal wellness.",
                targetedNutrients = "Vitamin D, Potassium, Omega-3, Protein",
                ingredients = listOf("150g Wild-Caught Salmon Fillet", "150g Broccoli Florets", "1 tbsp Sesame Seeds", "1 tbsp Soy Sauce (Low Sodium)"),
                instructions = listOf(
                    "Season salmon lightly and pan-sear or bake for 10-12 minutes until flaky.",
                    "Steam broccoli florets until tender-crisp (approx. 5 minutes).",
                    "Arrange salmon and broccoli in a bowl, drizzle with low-sodium soy sauce, and garnish with sesame seeds."
                ),
                prepTime = "15 mins"
            ),
            AiRecipeSuggestion(
                recipeName = "Savory Lentil & Spinach Sauté",
                description = "An iron and zinc powerhouse perfect for maintaining red blood cell synthesis and metabolic immune defense.",
                targetedNutrients = "Iron, Zinc, Folate, Protein, Fiber",
                ingredients = listOf("1 cup Cooked Lentils", "100g Baby Spinach", "1 clove Minced Garlic", "1 tsp Olive Oil"),
                instructions = listOf(
                    "Heat olive oil in a skillet and sauté minced garlic until fragrant.",
                    "Add cooked brown lentils and warm through.",
                    "Toss in baby spinach and cook until wilted (about 2 minutes).",
                    "Season with a pinch of sea salt and freshly cracked black pepper."
                ),
                prepTime = "12 mins"
            )
        )
        
        val filtered = allRecipes.filter { recipe ->
            trimmed.isEmpty() || 
            recipe.recipeName.lowercase().contains(trimmed) || 
            recipe.description.lowercase().contains(trimmed) || 
            recipe.targetedNutrients.lowercase().contains(trimmed) ||
            recipe.ingredients.any { it.lowercase().contains(trimmed) }
        }
        
        return if (filtered.isNotEmpty()) filtered.take(3) else allRecipes.take(3)
    }

    fun generateOfflineFoodSuggestions(deficiencies: List<NutrientStatus>): List<FoodSuggestion> {
        val list = mutableListOf<FoodSuggestion>()
        if (deficiencies.isEmpty()) {
            list.add(FoodSuggestion("Raw Pumpkin Seeds", "Magnesium, Zinc, Fiber", "262mg Magnesium / 100g", "Highly bioavailable minerals promoting muscle relaxation, optimal sleep quality, and solid bone structures."))
            list.add(FoodSuggestion("Wild Sockeye Salmon", "Vitamin D, Omega-3, Protein", "22 µg Vit D / 100g", "Outstanding complete source of anti-inflammatory essential fats, marine calcium, and highly bioavailable Vitamin D."))
            list.add(FoodSuggestion("Organic Spinach", "Vitamin A, Iron, Calcium", "469 µg Vit A & 2.7mg Iron", "Packed with dark leafy phytonutrients, calcium non-heme iron, and fat-soluble Vitamin A to aid cellular recovery."))
            list.add(FoodSuggestion("Plain Greek Yogurt", "Calcium, Protein, B-Vitamins", "110mg Calcium & 10g Protein", "Rich in gut-healthy probiotics and organic calcium that increases mineral absorption in bone matrices."))
            list.add(FoodSuggestion("Whole Chia Seeds", "Dietary Fiber, Calcium", "34g Fiber & 631mg Calcium", "A soluble fiber powerhouse to maintain intestinal wellness and sustained metabolic glucose release."))
        } else {
            val keyToFood = mapOf(
                "calories" to FoodSuggestion("Avocados", "Healthy Fats, Calories", "160 kcal / 100g", "Nutrient-dense whole fruit that offers high monounsaturated fats to fuel daily caloric energy efficiently."),
                "protein" to FoodSuggestion("Organic Chicken Breast", "Clean Protein", "31g Protein / 100g", "Ultra-lean high-protein source aiding muscle preservation and metabolic thermogenesis."),
                "fiber" to FoodSuggestion("Whole Chia Seeds", "Soluble Fiber", "34g Dietary Fiber / 100g", "Exceptional soluble prebiotic fiber supporting bowel regularities and cardiac wellness."),
                "calcium" to FoodSuggestion("Sardines (with bone)", "Calcium, Vitamin D", "382mg Calcium / 100g", "Superb whole food resource combining mineral calcium and Vitamin D to assist skeletal health."),
                "iron" to FoodSuggestion("Steamed Lentils", "Non-Heme Iron", "3.3mg Iron / 100g", "Fabulous plant protein option rich in minerals to stimulate oxygen-carrying hemoglobin synthesis."),
                "vitamin_c" to FoodSuggestion("Fresh Guavas", "Vitamin C", "228mg Vitamin C / 100g", "Incredible natural antioxidant density to synthesize collagen proteins and fortify immune defense."),
                "potassium" to FoodSuggestion("Baked Sweet Potatoes", "Potassium, Carbohydrates", "475mg Potassium / 100g", "Electrolyte-rich complex carbohydrate supporting cardiovascular fluid pressure stability."),
                "vitamin_d" to FoodSuggestion("Wild Salmon", "Vitamin D3", "22 µg Vitamin D / 100g", "Provides essential natural cholecalciferol (D3) crucial to regulate hormonal and immunologic parameters."),
                "magnesium" to FoodSuggestion("Dark Chocolate (85%+)", "Magnesium", "228mg Magnesium / 100g", "Delicious cocoa antioxidant density providing clean minerals to optimize neurologic nervous health."),
                "water" to FoodSuggestion("Cucumber and Celery Salad", "Hydration", "95% Water by weight", "Crisp, low-calorie structure supplying hydrated electrolytes and microelements.")
            )
            for (def in deficiencies) {
                if (list.size >= 5) break
                val sug = keyToFood[def.definition.key]
                if (sug != null) {
                    list.add(sug)
                }
            }
            val genericList = listOf(
                FoodSuggestion("Raw Pumpkin Seeds", "Magnesium, Zinc, Fiber", "262mg Magnesium / 100g", "Highly bioavailable minerals promoting muscle relaxation, optimal sleep quality, and solid bone structures."),
                FoodSuggestion("Wild Sockeye Salmon", "Vitamin D, Omega-3, Protein", "22 µg Vit D / 100g", "Outstanding complete source of anti-inflammatory essential fats, marine calcium, and highly bioavailable Vitamin D."),
                FoodSuggestion("Organic Spinach", "Vitamin A, Iron, Calcium", "469 µg Vit A & 2.7mg Iron", "Packed with dark leafy phytonutrients, calcium non-heme iron, and fat-soluble Vitamin A to aid cellular recovery."),
                FoodSuggestion("Plain Greek Yogurt", "Calcium, Protein, B-Vitamins", "110mg Calcium & 10g Protein", "Rich in gut-healthy probiotics and organic calcium that increases mineral absorption in bone matrices."),
                FoodSuggestion("Whole Chia Seeds", "Dietary Fiber, Calcium", "34g Fiber & 631mg Calcium", "A soluble fiber powerhouse to maintain intestinal wellness and sustained metabolic glucose release.")
            )
            for (gen in genericList) {
                if (list.size >= 5) break
                if (list.none { it.foodName == gen.foodName }) {
                    list.add(gen)
                }
            }
        }
        return list
    }

    fun logPlannedMeal(plannedId: String) {
        val currentList = _plannedMenu.value
        val meal = currentList.find { it.id == plannedId } ?: return
        if (meal.isLogged) return
        
        // Log the food
        addFoodLog(meal.foodName, meal.mealType)
        
        // Update state
        _plannedMenu.value = currentList.map {
            if (it.id == plannedId) it.copy(isLogged = true) else it
        }
    }

    fun generateDirectedMenu(selectedUsuals: List<String>) {
        val statusList = dailyNutrients.value
        val deficiencies = statusList.filter { !it.definition.isMaxLimit && it.percentage < 100.0 && it.definition.rda > 0.0 }
            .sortedBy { it.percentage }
            
        viewModelScope.launch {
            _isPlanningLoading.value = true
            _planningError.value = null
            
            val apiKey = com.example.data.GeminiAuthHandler.getApiKey()
            val hasValidKey = apiKey.isNotEmpty()
            
            if (!hasValidKey) {
                // Generate offline fallback menu
                kotlinx.coroutines.delay(800)
                _plannedMenu.value = generateOfflineDirectedMenu(selectedUsuals, deficiencies)
                _isPlanningLoading.value = false
                return@launch
            }
            
            val deficiencyStr = if (deficiencies.isEmpty()) {
                "None - All nutrient goals are met for today!"
            } else {
                deficiencies.joinToString("\n") { "• ${it.definition.name}: ${it.percentage.toInt()}% met (${it.intake.toInt()}/${it.definition.rda.toInt()}${it.definition.unit})" }
            }
            
            val usualsFormatted = if (selectedUsuals.isEmpty()) "None specified." else selectedUsuals.joinToString("\n") { "• $it" }
            
            val prompt = """
                You are NutriScribe's Elite AI Clinical Nutritionist.
                Your task is to design a personalized daily meal plan (Menu) for today.
                The user has specified these "Usual / Favorite" meals/foods to include first as their baseline (with their specified meal type, e.g. 'Eggs (Breakfast)'):
                $usualsFormatted
                
                Current Daily Nutrient Deficiencies to solve:
                $deficiencyStr
                
                You MUST output a valid JSON array containing EXACTLY 4 planned meal objects: one for "Breakfast", one for "Lunch", one for "Dinner", and one for "Snack".
                Rule 1: If a "Usual" baseline meal is provided for a specific meal type (e.g., Breakfast or Dinner), keep that usual meal exactly for that meal type (is_usual = true).
                Rule 2: If a meal type does NOT have a "Usual" baseline meal, you must suggest a healthy, nutritionally rich meal for that slot (is_usual = false).
                Rule 3: You MUST suggest a delicious, high-density Snack to keep their energy up, cover micro-nutrient gaps, and make a real difference in correcting their deficits. Do NOT forget the snack.
                
                Respond ONLY with a valid raw JSON array of 4 objects. Do not wrap the JSON in ```json or ``` or any markdown text. Each of the 4 objects in the JSON array must have exactly these keys:
                - "id": (string, unique id, e.g. "breakfast_1", "snack_1")
                - "meal_type": (string, exactly "Breakfast", "Lunch", "Dinner", or "Snack")
                - "food_name": (string, name of the food/meal)
                - "is_usual": (boolean, true if this is one of the user's usuals, false if suggested by AI)
                - "calories": (integer, estimated calorie count)
                - "protein": (integer, estimated protein in grams)
                - "carbs": (integer, estimated carbohydrates in grams)
                - "fat": (integer, estimated fat in grams)
                - "reason": (string, concise explanation of how this meal addresses their nutrient gaps and supports their dietary requirements, e.g. "Rich in Vitamin D and iron to correct your current 45% deficiency.")
            """.trimIndent()
            
            try {
                val reqObj = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }
                val request = com.example.data.GeminiAuthHandler.buildRequest(
                    "gemini-3.5-flash:generateContent",
                    reqObj.toString()
                )
                    
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val root = org.json.JSONObject(responseBody)
                            val candidates = root.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                var reply = candidates.getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")
                                    
                                if (reply.contains("```json")) {
                                    reply = reply.substringAfter("```json").substringBefore("```")
                                } else if (reply.contains("```")) {
                                    reply = reply.substringAfter("```").substringBefore("```")
                                }
                                reply = reply.trim()
                                
                                val parsedArray = org.json.JSONArray(reply)
                                val list = mutableListOf<PlannedMeal>()
                                for (i in 0 until parsedArray.length()) {
                                    val obj = parsedArray.getJSONObject(i)
                                    list.add(
                                        PlannedMeal(
                                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                            mealType = obj.optString("meal_type", "Snack"),
                                            foodName = obj.optString("food_name", ""),
                                            isUsual = obj.optBoolean("is_usual", false),
                                            calories = obj.optInt("calories", 0),
                                            protein = obj.optInt("protein", 0),
                                            carbs = obj.optInt("carbs", 0),
                                            fat = obj.optInt("fat", 0),
                                            reason = obj.optString("reason", "")
                                        )
                                    )
                                }
                                _plannedMenu.value = list
                            } else {
                                _planningError.value = "Unable to process Gemini suggestions structure."
                                _plannedMenu.value = generateOfflineDirectedMenu(selectedUsuals, deficiencies)
                            }
                        } else {
                            _planningError.value = "Gemini Service returned error ${response.code}"
                            _plannedMenu.value = generateOfflineDirectedMenu(selectedUsuals, deficiencies)
                        }
                    }
                }
            } catch (e: Exception) {
                _planningError.value = "Gemini API Connection Error: ${e.localizedMessage}"
                _plannedMenu.value = generateOfflineDirectedMenu(selectedUsuals, deficiencies)
            } finally {
                _isPlanningLoading.value = false
            }
        }
    }

    fun generateOfflineDirectedMenu(selectedUsuals: List<String>, deficiencies: List<NutrientStatus>): List<PlannedMeal> {
        val list = mutableListOf<PlannedMeal>()
        
        val usualsMap = mutableMapOf<String, String>()
        selectedUsuals.forEach { usual ->
            val lower = usual.lowercase()
            when {
                lower.contains("breakfast") -> usualsMap["Breakfast"] = usual.substringBefore(" (Breakfast)").trim()
                lower.contains("lunch") -> usualsMap["Lunch"] = usual.substringBefore(" (Lunch)").trim()
                lower.contains("dinner") -> usualsMap["Dinner"] = usual.substringBefore(" (Dinner)").trim()
                lower.contains("snack") -> usualsMap["Snack"] = usual.substringBefore(" (Snack)").trim()
                else -> {
                    if (!usualsMap.containsKey("Breakfast")) usualsMap["Breakfast"] = usual
                    else if (!usualsMap.containsKey("Lunch")) usualsMap["Lunch"] = usual
                    else if (!usualsMap.containsKey("Dinner")) usualsMap["Dinner"] = usual
                    else usualsMap["Snack"] = usual
                }
            }
        }
        
        val mealTypes = listOf("Breakfast", "Lunch", "Dinner", "Snack")
        
        mealTypes.forEach { type ->
            if (usualsMap.containsKey(type)) {
                val foodName = usualsMap[type] ?: ""
                list.add(
                    PlannedMeal(
                        id = type.lowercase() + "_usual",
                        mealType = type,
                        foodName = foodName,
                        isUsual = true,
                        calories = 350,
                        protein = 15,
                        carbs = 40,
                        fat = 12,
                        reason = "Incorporated your usual / favorite meal choice as specified."
                    )
                )
            } else {
                when (type) {
                    "Breakfast" -> {
                        list.add(
                            PlannedMeal(
                                id = "breakfast_sug",
                                mealType = "Breakfast",
                                foodName = "Greek Yogurt with Blueberries, Honey & Chia Seeds",
                                isUsual = false,
                                calories = 310,
                                protein = 22,
                                carbs = 38,
                                fat = 7,
                                reason = "Soluble fiber and protein packed starter providing natural calcium to support your bone baseline density."
                            )
                        )
                    }
                    "Lunch" -> {
                        val hasIronDef = deficiencies.any { it.definition.key == "iron" }
                        val food = if (hasIronDef) "Lean Steak strips with Quinoa & Steamed Spinach" else "Grilled Chicken Breast Bowl with Quinoa & Spinach"
                        list.add(
                            PlannedMeal(
                                id = "lunch_sug",
                                mealType = "Lunch",
                                foodName = food,
                                isUsual = false,
                                calories = 490,
                                protein = 38,
                                carbs = 46,
                                fat = 11,
                                reason = "Packed with bioavailable iron, fiber, and clean protein to help correct active daily deficits."
                            )
                        )
                    }
                    "Dinner" -> {
                        val hasVitDDef = deficiencies.any { it.definition.key == "vitamin_d" }
                        val food = if (hasVitDDef) "Wild Salmon fillet with Roasted Broccoli & Sweet Potatoes" else "Grilled Sea Bass with Quinoa & Asparagus"
                        list.add(
                            PlannedMeal(
                                id = "dinner_sug",
                                mealType = "Dinner",
                                foodName = food,
                                isUsual = false,
                                calories = 520,
                                protein = 41,
                                carbs = 42,
                                fat = 15,
                                reason = "Rich in Vitamin D3, magnesium, and essential Omega-3 fatty acids crucial to optimize skeletal and cardiac parameters."
                            )
                        )
                    }
                    "Snack" -> {
                        list.add(
                            PlannedMeal(
                                id = "snack_sug",
                                mealType = "Snack",
                                foodName = "A cup of sliced Apple with 2 tbsp of Creamy Almond Butter",
                                isUsual = false,
                                calories = 240,
                                protein = 7,
                                carbs = 25,
                                fat = 14,
                                reason = "Provides clean magnesium, healthy fats, and high fiber to support neurological health and metabolic stability."
                            )
                        )
                    }
                }
            }
        }
        
        return list
    }

    private fun generateLocalFallbackTips(
        deficiencies: List<NutrientStatus>,
        excesses: List<NutrientStatus>
    ): String {
        val sb = java.lang.StringBuilder()
        sb.append("📋 **Personalized Nutritional Advisory (Local Companion Solver)**\n\n")
        
        if (deficiencies.isEmpty() && excesses.isEmpty()) {
            sb.append("✨ **Optimal Micronutrient Balance Achieved!**\n")
            sb.append("Your currently logged meals meet all of your standard target guidelines beautifully. Maintain this standard with consistent meal pacing, whole foods, and clean hydration.")
            return sb.toString()
        }
        
        var count = 0
        for (ex in excesses) {
            if (count >= 3) break
            val name = ex.definition.name
            val desc = when (ex.definition.key) {
                "saturated_fat" -> "Limit rich gravies, packaged desserts, and excessive high-fat meats. Incorporate heart-healthy monounsaturated lipids like avocado, raw nuts, and organic extra virgin olive oil."
                "sodium" -> "Restructure remaining intake: avoid boxed foods and canned soups. Increase core water intake by 500-700ml to assist the kidneys in flushing accumulated sodium."
                "sugars" -> "Curb additional processed sugar ingestion. If looking for sweet sensations, choose fresh fiber-bound berries, Ceylon cinnamon-infused teas, or mint water."
                else -> "Upper allowance recommended limit exceeded. Favor raw, unrefined whole foods and hydration for subsequent meals today."
            }
            sb.append("⚠️ **Limit Target Notice: $name Control**\n")
            sb.append("$desc\n\n")
            count++
        }
        
        for (def in deficiencies) {
            if (count >= 3) break
            val name = def.definition.name
            val desc = when (def.definition.key) {
                "protein" -> "Inject a clean protein source in your next serving: Low-fat Greek yogurt, baked egg whites, grilled chicken, or organic edamame/tofu to build lean tissue and improve satiety."
                "fiber" -> "Boost dietary fiber: Stir in 1-2 tablespoons of organic chia seeds, ground flaxseed, or add raw broccoli and raspberries."
                "calcium" -> "Increase calcium uptake: Prefer dark leafy greens (kale, spinach), sesame seeds, fortified almond/coconut milks, or cultured yogurt."
                "iron" -> "Boost biological iron assimilation: Consume beef, lentils, or dark spinach, and pair with vitamin C (citrus, bell peppers) to boost absorption by up to 300%."
                "vitamin_c" -> "Integrate immediate antioxidant sources: Fresh citrus slices, sliced bell peppers, sweet gold kiwis, or a handful of fresh strawberries."
                "potassium" -> "Address low potassium intake: Drink coconut water or integrate half an avocado, fresh bananas, or gold skin sweet potatoes."
                "vitamin_d" -> "Encountering low vitamin D: Fortified proteins, egg yolks, sockeye salmon, or allocate 10-15 minutes of safe midday sun exposure."
                "magnesium" -> "Replenish magnesium reservoirs: Consume direct nuts (pumpkin seeds, almonds, or cashews) or snack on premium 70%+ cocoa dark chocolate."
                "water" -> "Prioritize cellular hydration: Sip high-purity water, herbal tea, or mineral-infused seltzers continuously."
                else -> "Your Daily trailing intake of $name is below recommended targets. Look to integrate fresh whole vegetables or quality nutrient-dense meals today."
            }
            sb.append("🔹 **Target Optimization: $name Deficiency**\n")
            sb.append("$desc\n\n")
            count++
        }
        
        return sb.toString().trim()
    }

    fun importLogs(jsonString: String, onFinished: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importFromJson(jsonString)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _operationMessage.value = "Imported $count log entries successfully"
                onFinished(true, "Successfully imported $count entries.")
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _operationMessage.value = "Import failed: $errorMsg"
                onFinished(false, "Import failed: $errorMsg")
            }
            _isLoading.value = false
        }
    }

    fun importAnyNutritionalJson(jsonString: String, onFinished: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val trimStr = jsonString.trim()
                if (trimStr.startsWith("{")) {
                    val root = org.json.JSONObject(trimStr)
                    if (root.has("date") && root.has("food_logs")) {
                        val date = root.optString("date")
                        val foodLogsArray = root.optJSONArray("food_logs")
                        if (foodLogsArray == null) {
                            onFinished(false, "Invalid Daily Log JSON: Missing food_logs")
                            _isLoading.value = false
                            return@launch
                        }
                        var importedCount = 0
                        for (i in 0 until foodLogsArray.length()) {
                            val logObj = foodLogsArray.getJSONObject(i)
                            val meal = logObj.optString("meal", "Lunch")
                            val foodName = logObj.optString("food_name", "")
                            val quantity = logObj.optDouble("quantity", 1.0)
                            val unit = logObj.optString("unit", "serving")
                            
                            if (foodName.isNotBlank()) {
                                val baseEntry = repository.createFallbackEntry(foodName, date, meal)
                                val finalEntry = baseEntry.copy(
                                    quantity = if (quantity.isNaN()) 1.0 else quantity,
                                    unit = unit
                                )
                                repository.insertEntry(finalEntry)
                                importedCount++
                            }
                        }
                        _operationMessage.value = "Imported $importedCount daily logs successfully"
                        onFinished(true, "Successfully restored $importedCount food items for $date!")
                    } else if (root.has("food_entries") || root.has("entries")) {
                        // Forward JSON direct configuration backup format
                        val reqResult = repository.importFromJson(jsonString)
                        if (reqResult.isSuccess) {
                            val count = reqResult.getOrDefault(0)
                            onFinished(true, "Restored $count entries from file backup.")
                        } else {
                            onFinished(false, "Restore failed: ${reqResult.exceptionOrNull()?.message}")
                        }
                    } else {
                        // Try importing as standard logs backup package
                        val result = repository.importFromJson(jsonString)
                        if (result.isSuccess) {
                            val count = result.getOrDefault(0)
                            onFinished(true, "Restored $count log entries successfully.")
                        } else {
                            onFinished(false, "JSON schema did not matches daily logs. Please check your backup file.")
                        }
                    }
                } else if (trimStr.startsWith("[")) {
                    val result = repository.importFromJson(jsonString)
                    if (result.isSuccess) {
                        val count = result.getOrDefault(0)
                        onFinished(true, "Restored $count entries from file backup.")
                    } else {
                        onFinished(false, "Restore failed: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    onFinished(false, "Unsupported file format. Please upload a valid exported (.json) file.")
                }
            } catch (e: Exception) {
                onFinished(false, "Import failed: ${e.localizedMessage}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importSupplements(jsonString: String, onFinished: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importSupplementsFromJson(jsonString)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _operationMessage.value = "Imported $count supplements"
                repository.autoLogSupplementsForDate(_currentDate.value)
                onFinished(true, "Successfully imported $count supplements.")
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _operationMessage.value = "Supplements import failed: $errorMsg"
                onFinished(false, "Import failed: $errorMsg")
            }
            _isLoading.value = false
        }
    }

    suspend fun getExportSupplementsString(): String {
        return repository.exportSupplementsToJson()
    }

    private fun getTodayDateString(): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return format.format(Calendar.getInstance().time)
    }

    private fun prepopulateSampleLogs() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val cal = Calendar.getInstance()

                // Today: Balanced day
                val todayDate = format.format(cal.time)
                repository.insertEntry(
                    FoodLogEntry(
                        date = todayDate,
                        foodName = "Greek yogurt with honey and chia seeds",
                        mealType = "Breakfast",
                        nutrients = repository.createFallbackEntry("greek yogurt chia honey", todayDate, "Breakfast").nutrients
                    )
                )
                repository.insertEntry(
                    FoodLogEntry(
                        date = todayDate,
                        foodName = "Grilled chicken breast, brown rice and broccoli",
                        mealType = "Lunch",
                        nutrients = repository.createFallbackEntry("grilled chicken brown rice broccoli", todayDate, "Lunch").nutrients
                    )
                )
                repository.insertEntry(
                    FoodLogEntry(
                        date = todayDate,
                        foodName = "Baked salmon with spinach salad",
                        mealType = "Dinner",
                        nutrients = repository.createFallbackEntry("baked salmon spinach salad", todayDate, "Dinner").nutrients
                    )
                )

                // Yesterday (2026-05-24): High-sodium, low vitamin day
                cal.add(Calendar.DATE, -1)
                val yesterdayDate = format.format(cal.time)
                repository.insertEntry(
                    FoodLogEntry(
                        date = yesterdayDate,
                        foodName = "Double bacon cheeseburger",
                        mealType = "Lunch",
                        nutrients = repository.createFallbackEntry("burger bacon cheese", yesterdayDate, "Lunch").nutrients.toMutableMap().apply {
                            put("sodium", 2600.0) // exceed limit
                            put("saturated_fat", 25.0) // exceed limit
                            put("calories", 950.0)
                            put("vitamin_c", 0.0) // zero
                            put("vitamin_d", 0.0)
                        }
                    )
                )
                repository.insertEntry(
                    FoodLogEntry(
                        date = yesterdayDate,
                        foodName = "French fries and regular soda",
                        mealType = "Snack",
                        nutrients = repository.createFallbackEntry("fries and soda", yesterdayDate, "Snack").nutrients.toMutableMap().apply {
                            put("sodium", 800.0)
                            put("sugars", 45.0)
                            put("calories", 400.0)
                            put("vitamin_c", 2.0)
                        }
                    )
                )

                // 2 Days Ago (2026-05-23): Light hydration, low-iron day
                cal.add(Calendar.DATE, -1)
                val twoDaysAgoDate = format.format(cal.time)
                repository.insertEntry(
                    FoodLogEntry(
                        date = twoDaysAgoDate,
                        foodName = "Oatmeal with sliced banana",
                        mealType = "Breakfast",
                        nutrients = repository.createFallbackEntry("oatmeal banana", twoDaysAgoDate, "Breakfast").nutrients
                    )
                )
                repository.insertEntry(
                    FoodLogEntry(
                        date = twoDaysAgoDate,
                        foodName = "Garden salad with olive oil",
                        mealType = "Lunch",
                        nutrients = repository.createFallbackEntry("salad oil", twoDaysAgoDate, "Lunch").nutrients.toMutableMap().apply {
                            put("iron", 0.2) // very deficient
                            put("protein", 2.0)
                            put("calories", 180.0)
                        }
                    )
                )
                repository.insertEntry(
                    FoodLogEntry(
                        date = twoDaysAgoDate,
                        foodName = "White rice with soy sauce",
                        mealType = "Dinner",
                        nutrients = repository.createFallbackEntry("rice soy sauce", twoDaysAgoDate, "Dinner").nutrients.toMutableMap().apply {
                            put("sodium", 1800.0)
                            put("protein", 3.0)
                            put("calories", 250.0)
                        }
                    )
                )
            } catch (t: Throwable) {
                android.util.Log.e("NutritionViewModel", "Database prepopulation failed gracefully: ${t.localizedMessage}", t)
            }
        }
    }

    // Helper mapping extensions
    private fun StateFlow<List<FoodLogEntry>>.mapToNutrientStatus(): StateFlow<List<NutrientStatus>> {
        return combine(this, customRdaOverrides, repository.allNutrients) { entries, overrides, dbNutrientsList ->
            val totals = mutableMapOf<String, Double>()
            
            // If dbNutrientsList is empty, fall back to Nutrients.DEFINITIONS
            val activeDefinitions = if (dbNutrientsList.isNotEmpty()) {
                dbNutrientsList.map { entity ->
                    com.example.data.NutrientDefinition(
                        key = entity.key,
                        name = entity.name,
                        group = entity.group,
                        rda = entity.rda,
                        unit = entity.unit,
                        isMaxLimit = entity.isMaxLimit,
                        description = entity.description
                    )
                }
            } else {
                com.example.data.Nutrients.DEFINITIONS
            }

            // Init all to 0.0
            for (definition in activeDefinitions) {
                totals[definition.key] = 0.0
            }

            // Sum up
            for (entry in entries) {
                for ((key, value) in entry.nutrients) {
                    totals[key] = (totals[key] ?: 0.0) + (value * entry.quantity)
                }
            }

            activeDefinitions.map { definition ->
                val rdaValue = overrides[definition.key] ?: definition.rda
                val sum = totals[definition.key] ?: 0.0
                val percent = if (rdaValue > 0) (sum / rdaValue) * 100.0 else 0.0

                val color = when {
                    definition.isMaxLimit -> {
                        if (sum > rdaValue) StatusColor.RED else StatusColor.GREEN
                    }
                    else -> {
                        when {
                            percent >= 100.0 -> StatusColor.GREEN
                            percent >= 80.0 -> StatusColor.YELLOW
                            else -> StatusColor.RED
                        }
                    }
                }

                val overriddenDefinition = if (rdaValue != definition.rda) {
                    definition.copy(rda = rdaValue)
                } else {
                    definition
                }

                NutrientStatus(overriddenDefinition, sum, percent, color)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun StateFlow<List<FoodLogEntry>>.mapToMacroSpread(): StateFlow<MacroSpreadRatio> {
        return this.map { entries ->
            var totalCarbs = 0.0
            var totalProtein = 0.0
            var totalFat = 0.0

            for (entry in entries) {
                totalCarbs += (entry.nutrients["carbohydrates"] ?: 0.0) * entry.quantity
                totalProtein += (entry.nutrients["protein"] ?: 0.0) * entry.quantity
                totalFat += (entry.nutrients["fat"] ?: 0.0) * entry.quantity
            }

            val carbCal = totalCarbs * 4.0
            val protCal = totalProtein * 4.0
            val fatCal = totalFat * 9.0
            val aggregatedCal = carbCal + protCal + fatCal

            if (aggregatedCal > 0.0) {
                MacroSpreadRatio(
                    carbsPercent = carbCal / aggregatedCal,
                    proteinPercent = protCal / aggregatedCal,
                    fatPercent = fatCal / aggregatedCal,
                    totalCaloriesCalculated = aggregatedCal,
                    carbsGrams = totalCarbs,
                    proteinGrams = totalProtein,
                    fatGrams = totalFat
                )
            } else {
                MacroSpreadRatio(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MacroSpreadRatio(0.0,0.0,0.0,0.0,0.0,0.0,0.0))
    }

    private fun StateFlow<List<NutrientStatus>>.mapToWarnings(): StateFlow<List<DeficiencyWarning>> {
        return this.map { statuses ->
            val warningList = mutableListOf<DeficiencyWarning>()

            for (status in statuses) {
                val sum = status.intake
                val def = status.definition

                if (def.isMaxLimit) {
                    if (sum > def.rda) {
                        warningList.add(
                            DeficiencyWarning(
                                nutrientKey = def.key,
                                name = def.name,
                                group = def.group,
                                message = "Limit Exceeded: Current intake is ${(sum - def.rda).format(1)} ${def.unit} above limits (${status.percentage.format(0)}%)",
                                isExceededLimit = true,
                                percentage = status.percentage
                            )
                        )
                    }
                } else {
                    // Alert if intake is under 80% RDA
                    if (status.percentage < 80.0) {
                        val level = if (status.percentage < 50.0) "Severe Deficiency" else "Below 80% Requirement"
                        warningList.add(
                            DeficiencyWarning(
                                nutrientKey = def.key,
                                name = def.name,
                                group = def.group,
                                message = "$level: Only achieved ${status.percentage.format(0)}% of your target allowance (${def.rda} ${def.unit})",
                                isExceededLimit = false,
                                percentage = status.percentage
                            )
                        )
                    }
                }
            }
            warningList
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun StateFlow<List<FoodLogEntry>>.mapToTrends(): StateFlow<List<DayTrend>> {
        return this.map { entries ->
            val groupedByDate = entries.groupBy { it.date }

            val datesList = mutableListOf<String>()
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val viewFormat = SimpleDateFormat("E", Locale.US) // e.g. "Mon"
            val calendar = Calendar.getInstance()

            // Let's populate last 7 days ending with today
            for (i in 0 until 7) {
                val dateStr = format.format(calendar.time)
                datesList.add(dateStr)
                calendar.add(Calendar.DATE, -1)
            }
            // Reverse so they display left-to-right Chronologically
            datesList.reverse()

            datesList.map { dateStr ->
                val dayEntries = groupedByDate[dateStr] ?: emptyList()
                var calSum = 0.0
                var carbsSum = 0.0
                var protSum = 0.0
                var fatSum = 0.0

                for (entry in dayEntries) {
                    calSum += (entry.nutrients["calories"] ?: 0.0) * entry.quantity
                    carbsSum += (entry.nutrients["carbohydrates"] ?: 0.0) * entry.quantity
                    protSum += (entry.nutrients["protein"] ?: 0.0) * entry.quantity
                    fatSum += (entry.nutrients["fat"] ?: 0.0) * entry.quantity
                }

                // parse display abbreviation
                val disp = try {
                    val parsedDate = format.parse(dateStr)
                    if (parsedDate != null) viewFormat.format(parsedDate) else ""
                } catch (e: Exception) {
                    ""
                }

                DayTrend(
                    dateString = dateStr,
                    displayDate = disp,
                    calories = calSum,
                    carbsGrams = carbsSum,
                    proteinGrams = protSum,
                    fatGrams = fatSum
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun Double.format(digits: Int): String {
        return String.format(Locale.US, "%.${digits}f", this)
    }

    fun getPeriodicReport(daysCount: Int, label: String): PeriodicReport {
        val entries = allLogEntries.value
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        val anchorDate = try {
            format.parse(getTodayDateString()) ?: Calendar.getInstance().time
        } catch(e: Exception) {
            Calendar.getInstance().time
        }
        
        val cal = Calendar.getInstance()
        cal.time = anchorDate
        cal.add(Calendar.DATE, -(daysCount - 1))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val cutoffTime = cal.timeInMillis

        val filteredEntries = entries.filter { entry ->
            try {
                val entryDate = format.parse(entry.date)
                entryDate != null && entryDate.time >= cutoffTime
            } catch (e: Exception) {
                false
            }
        }

        val groupedByDate = filteredEntries.groupBy { it.date }
        val daysWithLogs = groupedByDate.keys.size
        val divisor = if (daysWithLogs > 0) daysWithLogs.toDouble() else 1.0

        val totals = mutableMapOf<String, Double>()
        for (def in com.example.data.Nutrients.DEFINITIONS) {
            totals[def.key] = 0.0
        }

        for (entry in filteredEntries) {
            for ((key, value) in entry.nutrients) {
                totals[key] = (totals[key] ?: 0.0) + (value * entry.quantity)
            }
        }

        val averageDailyIntakes = mutableMapOf<String, Double>()
        for (def in com.example.data.Nutrients.DEFINITIONS) {
            averageDailyIntakes[def.key] = (totals[def.key] ?: 0.0) / divisor
        }

        val totalCalories = totals["calories"] ?: 0.0
        val avgCaloriesPerDay = totalCalories / divisor

        val totalCarbs = totals["carbohydrates"] ?: 0.0
        val totalProtein = totals["protein"] ?: 0.0
        val totalFat = totals["fat"] ?: 0.0

        val avgCarbs = totalCarbs / divisor
        val avgProtein = totalProtein / divisor
        val avgFat = totalFat / divisor

        val carbCal = avgCarbs * 4.0
        val protCal = avgProtein * 4.0
        val fatCal = avgFat * 9.0
        val aggregatedCal = carbCal + protCal + fatCal

        val macroRatio = if (aggregatedCal > 0.0) {
            MacroSpreadRatio(
                carbsPercent = carbCal / aggregatedCal,
                proteinPercent = protCal / aggregatedCal,
                fatPercent = fatCal / aggregatedCal,
                totalCaloriesCalculated = aggregatedCal,
                carbsGrams = avgCarbs,
                proteinGrams = avgProtein,
                fatGrams = avgFat
            )
        } else {
            MacroSpreadRatio(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val overrides = _customRdaOverrides.value
        val nutrientStatuses = com.example.data.Nutrients.DEFINITIONS.map { definition ->
            val rdaValue = overrides[definition.key] ?: definition.rda
            val avgIntake = averageDailyIntakes[definition.key] ?: 0.0
            val percent = if (rdaValue > 0) (avgIntake / rdaValue) * 100.0 else 0.0

            val color = when {
                definition.isMaxLimit -> {
                    if (avgIntake > rdaValue) StatusColor.RED else StatusColor.GREEN
                }
                else -> {
                    when {
                        percent >= 100.0 -> StatusColor.GREEN
                        percent >= 50.0 -> StatusColor.YELLOW
                        else -> StatusColor.RED
                    }
                }
            }

            val overriddenDefinition = if (rdaValue != definition.rda) {
                definition.copy(rda = rdaValue)
            } else {
                definition
            }

            NutrientStatus(overriddenDefinition, avgIntake, percent, color)
        }

        // Generate the diagnostics by calling public function getInsights in target screens
        val reportsInsights = getInsights(nutrientStatuses)

        return PeriodicReport(
            periodName = label,
            daysCount = daysCount,
            daysWithLogs = daysWithLogs,
            totalCalories = totalCalories,
            avgCaloriesPerDay = avgCaloriesPerDay,
            avgMacros = macroRatio,
            averageNutrients = nutrientStatuses,
            insights = reportsInsights
        )
    }

    /**
     * Compares user's daily nutrient totals against the RDA database
     * and alerts when any nutrient falls below 80% of its target.
     */
    fun checkNutrientLevelsAndGenerateWarnings(dailyTotals: List<NutrientStatus>): List<DeficiencyWarning> {
        val results = mutableListOf<DeficiencyWarning>()
        for (status in dailyTotals) {
            val def = status.definition
            val sum = status.intake
            val pct = status.percentage
            if (!def.isMaxLimit) {
                if (pct < 80.0) {
                    results.add(
                        DeficiencyWarning(
                            nutrientKey = def.key,
                            name = def.name,
                            group = def.group,
                            message = "Below Target: ${pct.toInt()}% met (${sum.toInt()}/${def.rda.toInt()} ${def.unit})",
                            isExceededLimit = false,
                            percentage = pct
                        )
                    )
                }
            }
        }
        return results
    }

    fun setDailySummaryNotificationsEnabled(enabled: Boolean) {
        _dailySummaryNotificationsEnabled.value = enabled
        sharedPrefs.edit()
            .putBoolean("daily_summary_notifications_enabled", enabled)
            .apply()
    }

    fun triggerDailySummaryDeficiencyCheck(context: Context, forceManual: Boolean = false) {
        val isEnabled = _dailySummaryNotificationsEnabled.value
        if (!isEnabled && !forceManual) return

        val criticalDeficiencies = dailyNutrients.value.filter { status ->
            val def = status.definition
            !def.isMaxLimit && status.percentage < 80.0 && def.rda > 0.0
        }

        val hasDeficiency = criticalDeficiencies.isNotEmpty()
        val title = "Daily Summary Status: " + if (hasDeficiency) "Nutrient Gaps Detected" else "Perfect Balance!"
        
        val body = if (hasDeficiency) {
            val names = criticalDeficiencies.joinToString(", ") { "${it.definition.name} (${it.percentage.toInt()}% met)" }
            "Gaps detected today for: $names. Consider adding nutrient-dense whole foods."
        } else {
            "Superb! All of your targeted nutritional elements are above 80% RDA today. No gaps found!"
        }

        // 1. Toast display (always fallback to toast overlay for visibility)
        android.widget.Toast.makeText(context, "$title\n$body", android.widget.Toast.LENGTH_LONG).show()

        // 2. Android system Local Notification (rendered in Emulator, which displays beautifully in browser streaming view)
        try {
            val channelId = "daily_summary_deficiencies_channel"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channelName = "Daily Nutrition Summary"
                val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
                val channel = android.app.NotificationChannel(channelId, channelName, importance).apply {
                    description = "Alerts about nutrient deficiencies at the end of the day"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Create a pending intent pointing to MainActivity to make it interactive when clicked
            val intent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntentFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, pendingIntentFlags)

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // standard system drawable resource
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            // Let's safe-check permissions if on newer android APIs
            var hasPermission = true
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, 
                    "android.permission.POST_NOTIFICATIONS"
                )
                hasPermission = (permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }

            if (hasPermission) {
                notificationManager.notify(7788, builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCustomFoodsToPrefs(list: List<CustomStateFoodItem>) {
        val array = org.json.JSONArray()
        list.forEach { item ->
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("portionSize", item.portionSize)
                put("unit", item.unit)
            }
            array.put(obj)
        }
        val json = array.toString()
        sharedPrefs.edit().putString("local_custom_foods", json).apply()
        try {
            // Backup to internal filesDir
            val internalFile = java.io.File(getApplication<Application>().filesDir, "local_custom_foods_backup.json")
            internalFile.writeText(json)
            
            // Backup to external storage (persists across reinstalls)
            getApplication<Application>().getExternalFilesDir(null)?.let { dir ->
                val externalFile = java.io.File(dir, "local_custom_foods_backup.json")
                externalFile.writeText(json)
            }
        } catch (e: Exception) {
            android.util.Log.e("NutritionViewModel", "Failed to save local_custom_foods to files", e)
        }
    }

    fun addCustomFoodItem(name: String, portionSize: Double, unit: String) {
        val newItem = CustomStateFoodItem(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            portionSize = portionSize,
            unit = unit.trim()
        )
        val currentList = _localCustomFoods.value.toMutableList()
        currentList.add(newItem)
        _localCustomFoods.value = currentList
        saveCustomFoodsToPrefs(currentList)
    }

    fun deleteCustomFoodItem(id: String) {
        val currentList = _localCustomFoods.value.toMutableList()
        currentList.removeAll { it.id == id }
        _localCustomFoods.value = currentList
        saveCustomFoodsToPrefs(currentList)
    }

    fun updateCustomFoodItem(id: String, name: String, portionSize: Double, unit: String) {
        val currentList = _localCustomFoods.value.map { item ->
            if (item.id == id) {
                item.copy(name = name.trim(), portionSize = portionSize, unit = unit.trim())
            } else {
                item
            }
        }
        _localCustomFoods.value = currentList
        saveCustomFoodsToPrefs(currentList)
    }
}

data class CustomStateFoodItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val portionSize: Double,
    val unit: String
)

data class PeriodicReport(
    val periodName: String,
    val daysCount: Int,
    val daysWithLogs: Int,
    val totalCalories: Double,
    val avgCaloriesPerDay: Double,
    val avgMacros: MacroSpreadRatio,
    val averageNutrients: List<NutrientStatus>,
    val insights: List<DiagnosticInsight>
)

data class MacroSpreadRatio(
    val carbsPercent: Double,    // e.g. 0.45 (for 45%)
    val proteinPercent: Double,  // e.g. 0.20 (for 20%)
    val fatPercent: Double,      // e.g. 0.35 (for 35%)
    val totalCaloriesCalculated: Double,
    val carbsGrams: Double,
    val proteinGrams: Double,
    val fatGrams: Double
)

data class DeficiencyWarning(
    val nutrientKey: String,
    val name: String,
    val group: NutrientGroup,
    val message: String,
    val isExceededLimit: Boolean,
    val percentage: Double = 0.0
)

data class NutrientObservationDay(
    val dateString: String,
    val displayDate: String,
    val expected: Double,
    val achieved: Double,
    val difference: Double,
    val isFasting: Boolean,
    val runningTotalDiff: Double
)

data class ConsecutiveMissedAlert(
    val nutrientKey: String,
    val nutrientName: String,
    val group: com.example.data.NutrientGroup,
    val unit: String,
    val consecutiveDays: Int,
    val rdaValue: Double,
    val streakEndType: String,
    val description: String
)

data class ManualFoodLog(
    val id: String,
    val foodName: String,
    val servingSize: Double,
    val mealType: String,
    val date: String,
    val timestamp: Long
)

data class FoodSuggestion(
    val foodName: String,
    val denseNutrients: String,
    val contentMeasure: String,
    val explanation: String
)

data class AiRecipeSuggestion(
    val recipeName: String,
    val description: String,
    val targetedNutrients: String,
    val ingredients: List<String>,
    val instructions: List<String>,
    val prepTime: String
)

data class PlannedMeal(
    val id: String,
    val mealType: String,
    val foodName: String,
    val isUsual: Boolean,
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val reason: String,
    val isLogged: Boolean = false
)
