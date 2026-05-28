package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val dosage: String,
    val frequency: String, // "Once Daily", "Twice Daily", "Weekly", "Alternate Days"
    val daysOfWeek: String = "", // E.g., "Monday,Thursday" for Weekly
    val timeOfDay: String = "Morning", // "Morning", "Afternoon", "Evening", "Night"
    val notes: String = "",
    val nutrients: Map<String, Double> = emptyMap()
)
