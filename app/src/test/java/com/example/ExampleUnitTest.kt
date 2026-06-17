package com.example

import com.example.data.ResilientDoubleAdapter
import com.example.data.Supplement
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testMoshiJsonParsing() {
    val jsonString = """
    [
      {
        "id": 3,
        "name": "multi-max",
        "dosage": "1 Capsule",
        "frequency": "Once Daily",
        "daysOfWeek": "",
        "timeOfDay": "Morning",
        "notes": "Includes Multi-Vitamins and Minerals",
        "nutrients": {
          "Vitamin_D_(D3)µg": 5.0,
          "Vitamin_E_mg": 50.0,
          "Vitamin_K_(K1)µg": 20.0,
          "Vitamin_C_mg": 100.0,
          "Thiamin_(Vitamin_B1)mg": 25.0,
          "Riboflavin_(Vitamin_B2)mg": 12.0,
          "Niacin_(Vitamin_B3)mg": 50.0,
          "Vitamin_B6_(Pyridoxine)mg": 10.0,
          "Folic_Acid_µg": 400.0,
          "Vitamin_B12_µg": 50.0,
          "Biotin_µg": 150.0,
          "Pantothenic_Acid_(Vitamin_B5)_mg": 25.0,
          "Calcium_mg": 120.0,
          "Magnesium_300mg": 300.0,
          "Iron_mg": 14.0,
          "Zinc_mg": 15.0,
          "Selenium_µg": 200.0,
          "Copper_mg": 0.50,
          "Manganese_mg": 2.0,
          "Chromium_µg": 0.50,
          "Molybdenum_µg": 75.0,
          "Iodine_µg": 150.0,
          "Inositol_mg": 125.0,
          "Para_Amino_Benzoic_Acid_(PABA)mg": 25
        }
      }
    ]
    """.trim()

    val moshi = Moshi.Builder()
        .add(Double::class.java, ResilientDoubleAdapter)
        .add(Double::class.javaObjectType, ResilientDoubleAdapter)
        .add(KotlinJsonAdapterFactory())
        .build()
    val listType = Types.newParameterizedType(List::class.java, Supplement::class.java)
    val adapter = moshi.adapter<List<Supplement>>(listType)
    try {
        val supplements = adapter.fromJson(jsonString)
        assertNotNull(supplements)
        assertEquals(1, supplements!!.size)
        val supp = supplements[0]
        assertEquals("multi-max", supp.name)
        val nutrients = supp.nutrients
        assertEquals(24, nutrients.size)
        assertEquals(25.0, nutrients["Para_Amino_Benzoic_Acid_(PABA)mg"])
    } catch (e: Exception) {
        e.printStackTrace()
        fail("Moshi parsing failed: " + e.message)
    }
  }
}

