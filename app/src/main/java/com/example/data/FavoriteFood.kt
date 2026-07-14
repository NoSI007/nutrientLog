package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_foods")
data class FavoriteFood(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val foodName: String,
    val quantity: Double,
    val unit: String,
    val nutrients: Map<String, Double>
)
