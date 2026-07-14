package com.example.data

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class Converters {
    private val moshi = Moshi.Builder()
        .add(Double::class.java, ResilientDoubleAdapter)
        .add(Double::class.javaObjectType, ResilientDoubleAdapter)
        .add(KotlinJsonAdapterFactory())
        .build()

    private val mapType = Types.newParameterizedType(Map::class.java, java.lang.String::class.java, java.lang.Double::class.java)
    private val adapter = moshi.adapter<Map<String, Double>>(mapType)

    private val favoriteMealFoodItemListType = Types.newParameterizedType(List::class.java, FavoriteMealFoodItem::class.java)
    private val favoriteMealFoodItemAdapter = moshi.adapter<List<FavoriteMealFoodItem>>(favoriteMealFoodItemListType)

    @TypeConverter
    fun fromFavoriteMealFoodItemList(value: String?): List<FavoriteMealFoodItem>? {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            favoriteMealFoodItemAdapter.fromJson(value)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @TypeConverter
    fun toFavoriteMealFoodItemList(list: List<FavoriteMealFoodItem>?): String {
        if (list == null) return "[]"
        return favoriteMealFoodItemAdapter.toJson(list)
    }

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

    @TypeConverter
    fun fromNutrientGroup(value: NutrientGroup?): String {
        return value?.name ?: NutrientGroup.OTHERS.name
    }

    @TypeConverter
    fun toNutrientGroup(value: String?): NutrientGroup {
        if (value == null) return NutrientGroup.OTHERS
        return try {
            NutrientGroup.valueOf(value)
        } catch (e: Exception) {
            NutrientGroup.OTHERS
        }
    }
}
