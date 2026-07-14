package com.example.data

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import com.example.BuildConfig

object GeminiAuthHandler {
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences("nutrition_targets", android.content.Context.MODE_PRIVATE)
        android.util.Log.i("GeminiAuthHandler", "Initialized GeminiAuthHandler with shared preferences.")
    }

    fun getApiKey(): String {
        val savedKey = prefs?.getString("gemini_api_key", "")?.trim() ?: ""
        if (savedKey.isNotEmpty()) {
            return savedKey
        }
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }
        return if (apiKey == "MY_GEMINI_API_KEY" || apiKey == "GEMINI_API_KEY") "" else apiKey
    }

    fun getMaskedApiKey(): String {
        val key = getApiKey()
        if (key.isEmpty()) return "EMPTY/NOT CONFIGURED"
        if (key.length <= 8) return "TOO SHORT (length: ${key.length})"
        return "${key.take(6)}...${key.takeLast(4)} (length: ${key.length})"
    }

    fun getEnvApiKey(): String {
        val key = System.getenv("GEMINI_API_KEY") ?: ""
        return if (key == "MY_GEMINI_API_KEY" || key == "GEMINI_API_KEY") "" else key
    }

    fun getMaskedEnvApiKey(): String {
        val key = getEnvApiKey()
        if (key.isEmpty()) return "NOT FOUND IN SYSTEM ENV"
        if (key.length <= 8) return "TOO SHORT (length: ${key.length})"
        return "${key.take(6)}...${key.takeLast(4)} (length: ${key.length})"
    }

    fun getBuildConfigApiKey(): String {
        val key = try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }
        return if (key == "MY_GEMINI_API_KEY" || key == "GEMINI_API_KEY") "" else key
    }

    fun getMaskedBuildConfigApiKey(): String {
        val key = getBuildConfigApiKey()
        if (key.isEmpty()) return "NOT FOUND IN BUILDCONFIG"
        if (key.length <= 8) return "TOO SHORT (length: ${key.length})"
        return "${key.take(6)}...${key.takeLast(4)} (length: ${key.length})"
    }

    fun getCustomSavedKey(): String {
        return prefs?.getString("gemini_api_key", "") ?: ""
    }

    fun saveCustomKey(key: String) {
        prefs?.edit()?.putString("gemini_api_key", key.trim())?.apply()
    }

    fun getActiveModel(): String {
        return prefs?.getString("gemini_active_model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
    }

    fun saveActiveModel(model: String) {
        prefs?.edit()?.putString("gemini_active_model", model.trim())?.apply()
    }

    fun buildRequest(modelAndAction: String, jsonBody: String): Request {
        val activeModel = getActiveModel()
        val actualModelAndAction = if (modelAndAction.startsWith("gemini-")) {
            val parts = modelAndAction.split(":", limit = 2)
            if (parts.size == 2) {
                "$activeModel:${parts[1]}"
            } else {
                activeModel
            }
        } else {
            modelAndAction
        }

        val apiKey = getApiKey()
        val url = if (apiKey.isNotEmpty()) {
            "https://generativelanguage.googleapis.com/v1beta/models/$actualModelAndAction?key=$apiKey"
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/$actualModelAndAction"
        }
        
        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)
        
        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
            .addHeader("Accept", "application/json")
            
        if (apiKey.isNotEmpty()) {
            builder.addHeader("x-goog-api-key", apiKey)
        }
        
        return builder.post(body).build()
    }

    fun listModelsAsync() {
        val apiKey = getApiKey()
        val maskedKey = getMaskedApiKey()
        android.util.Log.i("GeminiAuthHandler", "Initializing Gemini model check with API Key: $maskedKey")
        if (apiKey.isEmpty()) {
            android.util.Log.e("GeminiAuthHandler", "API Key is empty! Cannot list models.")
            return
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "NutritionTracker/1.0 (Android; Kotlin; com.example)")
            .addHeader("Accept", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .get()
            .build()

        val client = okhttp3.OkHttpClient()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e("GeminiAuthHandler", "Failed to fetch models", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        android.util.Log.e("GeminiAuthHandler", "Unexpected code: ${response.code} body: ${response.body?.string()}")
                    } else {
                        val responseBody = response.body?.string() ?: ""
                        android.util.Log.i("GeminiAuthHandler", "Available models response: $responseBody")
                    }
                }
            }
        })
    }
}
