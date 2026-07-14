package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FirestoreService {
    private var firestoreInstance: FirebaseFirestore? = null

    // Helper extension to await standard play-services Tasks without extra dependencies
    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: RuntimeException("Task failed with no exception"))
            }
        }
    }

    fun initialize(context: Context) {
        if (firestoreInstance != null) return
        try {
            val apiKey = com.example.BuildConfig.GEMINI_API_KEY
            val projectId = com.example.BuildConfig.FIRESTORE_PROJECT_ID
            
            if (apiKey.isBlank() || projectId.isBlank() || apiKey.startsWith("MY_") || apiKey.startsWith("YOUR_")) {
                Log.e("FirestoreService", "Firebase API Key or Project ID is missing or placeholder! Skipping Firestore initialization.")
                return
            }

            val options = FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setApplicationId("1:2934f3bd6033432a8e221598cead54fb:android:86bc61ad0c4b2b62")
                .build()

            val app = if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context, options)
            } else {
                FirebaseApp.getInstance()
            }
            
            firestoreInstance = FirebaseFirestore.getInstance(app)
            Log.i("FirestoreService", "Firebase and Firestore initialized successfully for project: $projectId")
        } catch (e: Exception) {
            Log.e("FirestoreService", "Failed to initialize Firebase / Firestore", e)
        }
    }

    private fun getFirestore(): FirebaseFirestore {
        if (firestoreInstance == null) {
            throw IllegalStateException("FirestoreService has not been initialized. Call initialize(context) first.")
        }
        return firestoreInstance!!
    }

    fun isInitialized(): Boolean {
        return firestoreInstance != null
    }

    suspend fun saveFoodLogEntry(entry: FoodLogEntry) = withContext(Dispatchers.IO) {
        if (!isInitialized()) return@withContext
        try {
            val db = getFirestore()
            val data = hashMapOf(
                "id" to entry.id,
                "date" to entry.date,
                "foodName" to entry.foodName,
                "mealType" to entry.mealType,
                "quantity" to entry.quantity,
                "unit" to entry.unit,
                "nutrients" to entry.nutrients
            )
            db.collection("food_log_entries")
                .document(entry.id.toString())
                .set(data, SetOptions.merge())
                .awaitTask()
            Log.i("FirestoreService", "Saved food log entry ${entry.id} to Firestore")
        } catch (e: Exception) {
            Log.e("FirestoreService", "Error saving food log entry to Firestore", e)
        }
    }

    suspend fun saveAllFoodLogEntries(entries: List<FoodLogEntry>) = withContext(Dispatchers.IO) {
        if (!isInitialized() || entries.isEmpty()) return@withContext
        try {
            val db = getFirestore()
            val batch = db.batch()
            for (entry in entries) {
                val docRef = db.collection("food_log_entries").document(entry.id.toString())
                val data = hashMapOf(
                    "id" to entry.id,
                    "date" to entry.date,
                    "foodName" to entry.foodName,
                    "mealType" to entry.mealType,
                    "quantity" to entry.quantity,
                    "unit" to entry.unit,
                    "nutrients" to entry.nutrients
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().awaitTask()
            Log.i("FirestoreService", "Saved ${entries.size} food log entries to Firestore")
        } catch (e: Exception) {
            Log.e("FirestoreService", "Error batch saving food log entries to Firestore", e)
        }
    }

    suspend fun deleteFoodLogEntry(id: Int) = withContext(Dispatchers.IO) {
        if (!isInitialized()) return@withContext
        try {
            val db = getFirestore()
            db.collection("food_log_entries")
                .document(id.toString())
                .delete()
                .awaitTask()
            Log.i("FirestoreService", "Deleted food log entry $id from Firestore")
        } catch (e: Exception) {
            Log.e("FirestoreService", "Error deleting food log entry from Firestore", e)
        }
    }

    suspend fun deleteEntriesForDate(dateString: String) = withContext(Dispatchers.IO) {
        if (!isInitialized()) return@withContext
        try {
            val db = getFirestore()
            val querySnapshot = db.collection("food_log_entries")
                .whereEqualTo("date", dateString)
                .get()
                .awaitTask()
            
            if (querySnapshot.isEmpty) return@withContext
            
            val batch = db.batch()
            for (doc in querySnapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().awaitTask()
            Log.i("FirestoreService", "Deleted entries for date $dateString from Firestore")
        } catch (e: Exception) {
            Log.e("FirestoreService", "Error deleting entries for date $dateString from Firestore", e)
        }
    }

    suspend fun getAllFoodLogEntries(): List<FoodLogEntry> = withContext(Dispatchers.IO) {
        if (!isInitialized()) return@withContext emptyList()
        try {
            val db = getFirestore()
            val querySnapshot = db.collection("food_log_entries").get().awaitTask()
            val entries = mutableListOf<FoodLogEntry>()
            for (doc in querySnapshot.documents) {
                val id = doc.getLong("id")?.toInt() ?: continue
                val date = doc.getString("date") ?: ""
                val foodName = doc.getString("foodName") ?: ""
                val mealType = doc.getString("mealType") ?: ""
                val quantity = doc.getDouble("quantity") ?: 1.0
                val unit = doc.getString("unit") ?: "serving"
                val nutrientsRaw = doc.get("nutrients") as? Map<*, *>
                val nutrients = mutableMapOf<String, Double>()
                if (nutrientsRaw != null) {
                    for ((k, v) in nutrientsRaw) {
                        val key = k?.toString() ?: continue
                        val value = (v as? Number)?.toDouble() ?: 0.0
                        nutrients[key] = value
                    }
                }
                entries.add(
                    FoodLogEntry(
                        id = id,
                        date = date,
                        foodName = foodName,
                        mealType = mealType,
                        quantity = quantity,
                        unit = unit,
                        nutrients = nutrients
                    )
                )
            }
            Log.i("FirestoreService", "Fetched ${entries.size} food log entries from Firestore")
            entries
        } catch (e: Exception) {
            Log.e("FirestoreService", "Error fetching food log entries from Firestore", e)
            emptyList()
        }
    }
}
