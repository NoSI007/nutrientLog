package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [FoodLogEntry::class, Supplement::class, FavoriteMeal::class, NutrientEntity::class, UsdaCacheEntity::class, FavoriteFood::class], version = 8, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foodLogDao(): FoodLogDao
    abstract fun supplementDao(): SupplementDao
    abstract fun favoriteMealDao(): FavoriteMealDao
    abstract fun nutrientDao(): NutrientDao
    abstract fun usdaCacheDao(): UsdaCacheDao
    abstract fun favoriteFoodDao(): FavoriteFoodDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nutrition_tracker_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
