package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_meals")
data class FavoriteMeal(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val foods: List<FavoriteMealFoodItem>
)

data class FavoriteMealFoodItem(
    val foodName: String,
    val quantity: Double,
    val unit: String,
    val nutrients: Map<String, Double>
)
