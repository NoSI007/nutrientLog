package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteFoodDao {
    @Query("SELECT * FROM favorite_foods ORDER BY foodName ASC")
    fun getAllFavoriteFoods(): Flow<List<FavoriteFood>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteFood(food: FavoriteFood)

    @Delete
    suspend fun deleteFavoriteFood(food: FavoriteFood)

    @Query("DELETE FROM favorite_foods WHERE id = :id")
    suspend fun deleteFavoriteFoodById(id: Int)

    @Query("SELECT * FROM favorite_foods WHERE foodName = :name LIMIT 1")
    suspend fun getFavoriteFoodByName(name: String): FavoriteFood?
}
