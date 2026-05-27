package com.example.data

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class Converters {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val mapType = Types.newParameterizedType(Map::class.java, java.lang.String::class.java, java.lang.Double::class.java)
    private val adapter = moshi.adapter<Map<String, Double>>(mapType)

    @TypeConverter
    fun fromStringMap(value: String?): Map<String, Double>? {
        if (value.isNullOrEmpty()) return emptyMap()
        return try {
            adapter.fromJson(value)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun toStringMap(map: Map<String, Double>?): String {
        if (map == null) return "{}"
        return adapter.toJson(map)
    }
}
