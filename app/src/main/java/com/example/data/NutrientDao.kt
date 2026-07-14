package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NutrientDao {
    @Query("SELECT * FROM essential_nutrients ORDER BY name ASC")
    fun getAllNutrientsFlow(): Flow<List<NutrientEntity>>

    @Query("SELECT * FROM essential_nutrients")
    suspend fun getAllNutrients(): List<NutrientEntity>

    @Query("SELECT * FROM essential_nutrients WHERE `key` = :key LIMIT 1")
    suspend fun getNutrientByKey(key: String): NutrientEntity?

    @Query("UPDATE essential_nutrients SET rda = :newRda WHERE `key` = :key")
    suspend fun updateRda(key: String, newRda: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNutrients(nutrients: List<NutrientEntity>)

    @Update
    suspend fun updateNutrient(nutrient: NutrientEntity)

    @Query("DELETE FROM essential_nutrients")
    suspend fun deleteAll()
}
