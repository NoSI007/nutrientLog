package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "essential_nutrients")
data class NutrientEntity(
    @PrimaryKey
    val key: String,
    val name: String,
    val group: NutrientGroup,
    val rda: Double,
    val unit: String,
    val isMaxLimit: Boolean = false,
    val description: String = ""
)
