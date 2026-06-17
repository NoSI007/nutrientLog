package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteMealDao {
    @Query("SELECT * FROM favorite_meals ORDER BY name ASC")
    fun getAllFavoriteMeals(): Flow<List<FavoriteMeal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteMeal(meal: FavoriteMeal)

    @Delete
    suspend fun deleteFavoriteMeal(meal: FavoriteMeal)

    @Query("DELETE FROM favorite_meals WHERE id = :id")
    suspend fun deleteFavoriteMealById(id: Int)
}
