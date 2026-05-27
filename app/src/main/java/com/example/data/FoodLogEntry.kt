package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "food_log_entries")
data class FoodLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,                  // Format: "YYYY-MM-DD"
    val foodName: String,              // e.g., "3 scrambled soft eggs"
    val mealType: String,              // "Breakfast", "Lunch", "Dinner", "Snack"
    val quantity: Double = 1.0,        // log quantity multiplier
    val unit: String = "serving",      // user-provided unit
    val nutrients: Map<String, Double> // Map of nutrient key to calculated mass
)
