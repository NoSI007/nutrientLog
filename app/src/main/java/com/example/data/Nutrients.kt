package com.example.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class NutrientGroup(val displayName: String) {
    MACROS("Macronutrients"),
    LIPIDS("Fats & Cholesterol"),
    VITAMINS("Vitamins"),
    MINERALS("Minerals"),
    OTHERS("Other Nutrients")
}

data class NutrientDefinition(
    val key: String,                 // unique database key (e.g. "calories")
    val name: String,                // Display name (e.g. "Vitamin C")
    val group: NutrientGroup,
    val rda: Double,                 // Recommended Daily Allowance target/limit
    val unit: String,                // "g", "mg", "mcg", "kcal", etc.
    val isMaxLimit: Boolean = false, // If true, target is a maximum limit (e.g. Sodium)
    val description: String = ""     // Brief summary of nutrient role
)

object Nutrients {
    private const val DB_FILE_NAME = "nutrients_database.json"

    val DEFAULT_DEFINITIONS = listOf(
        // Macronutrients (6)
        NutrientDefinition("calories", "Calories", NutrientGroup.MACROS, 2000.0, "kcal", description = "Energy required for daily cellular metabolic functions"),
        NutrientDefinition("carbohydrates", "Carbohydrates", NutrientGroup.MACROS, 275.0, "g", description = "Primary fast-acting energy source for muscles and brain"),
        NutrientDefinition("protein", "Protein", NutrientGroup.MACROS, 56.0, "g", description = "Building blocks for hormones, tissues, and muscles"),
        NutrientDefinition("fat", "Total Fat", NutrientGroup.MACROS, 70.0, "g", description = "Crucial for organ protection, hormones, and vitamin absorption"),
        NutrientDefinition("fiber", "Dietary Fiber", NutrientGroup.MACROS, 28.0, "g", description = "Essential for gut motility, cholesterol and blood sugar control"),
        NutrientDefinition("water", "Water/Hydration", NutrientGroup.MACROS, 2500.0, "ml", description = "Regulates temperature, acts as solvent, and protects joints"),

        // Lipids/Fat Breakdown (7)
        NutrientDefinition("saturated_fat", "Saturated Fat", NutrientGroup.LIPIDS, 20.0, "g", isMaxLimit = true, description = "Primarily from animal sources; monitor for cardiovascular health"),
        NutrientDefinition("trans_fat", "Trans Fat", NutrientGroup.LIPIDS, 2.0, "g", isMaxLimit = true, description = "Artificially hardened fats; keep as low as possible"),
        NutrientDefinition("monounsaturated_fat", "Monounsaturated Fat", NutrientGroup.LIPIDS, 25.0, "g", description = "Heart-healthy fats commonly found in olive oil and avocados"),
        NutrientDefinition("polyunsaturated_fat", "Polyunsaturated Fat", NutrientGroup.LIPIDS, 17.0, "g", description = "Beneficial fats including essential omega-3 & omega-6 pathways"),
        NutrientDefinition("omega3", "Omega-3 Fatty Acids", NutrientGroup.LIPIDS, 1.6, "g", description = "Anti-inflammatory, fundamental for heart and cognitive acuity"),
        NutrientDefinition("omega6", "Omega-6 Fatty Acids", NutrientGroup.LIPIDS, 17.0, "g", description = "Supports cellular structures, skin resilience and hair density"),
        NutrientDefinition("cholesterol", "Cholesterol", NutrientGroup.LIPIDS, 300.0, "mg", isMaxLimit = true, description = "Part of cellular membranes, but high intake should be monitored"),

        // Vitamins (14)
        NutrientDefinition("vitamin_a", "Vitamin A", NutrientGroup.VITAMINS, 900.0, "mcg", description = "Critical for vision, dark adaptation, and immune defense"),
        NutrientDefinition("vitamin_c", "Vitamin C", NutrientGroup.VITAMINS, 90.0, "mg", description = "Antioxidant, vital for collagen synthesis and tissue healing"),
        NutrientDefinition("vitamin_d", "Vitamin D", NutrientGroup.VITAMINS, 15.0, "mcg", description = "Facilitates calcium integration and skeletal health"),
        NutrientDefinition("vitamin_e", "Vitamin E", NutrientGroup.VITAMINS, 15.0, "mg", description = "Lipid-soluble antioxidant defending cellular membranes"),
        NutrientDefinition("vitamin_k", "Vitamin K", NutrientGroup.VITAMINS, 120.0, "mcg", description = "Essential cofactor in calcium-binding and coagulation flow"),
        NutrientDefinition("thiamin", "Thiamin (B1)", NutrientGroup.VITAMINS, 1.2, "mg", description = "Cofactor in carbohydrate conversion to cellular energy"),
        NutrientDefinition("riboflavin", "Riboflavin (B2)", NutrientGroup.VITAMINS, 1.3, "mg", description = "Participates in cell development and cellular respiration"),
        NutrientDefinition("niacin", "Niacin (B3)", NutrientGroup.VITAMINS, 16.0, "mg", description = "Crucial for lipid conversion and healthy dermal/nervous states"),
        NutrientDefinition("pantothenic_acid", "Pantothenic Acid (B5)", NutrientGroup.VITAMINS, 5.0, "mg", description = "Crucial for coenzyme A formation and steroid synthesis"),
        NutrientDefinition("vitamin_b6", "Vitamin B6", NutrientGroup.VITAMINS, 1.3, "mg", description = "Essential for amino acid processing and neurotransmitter synthesis"),
        NutrientDefinition("biotin", "Biotin (B7)", NutrientGroup.VITAMINS, 30.0, "mcg", description = "Key component in fat synthesis, glucose creation, and protein use"),
        NutrientDefinition("folate", "Folate (B9)", NutrientGroup.VITAMINS, 400.0, "mcg", description = "Critical for red blood cell formation and neural tube expansion"),
        NutrientDefinition("vitamin_b12", "Vitamin B12", NutrientGroup.VITAMINS, 2.4, "mcg", description = "Crucial for myelination, DNA assembly, and blood generation"),
        NutrientDefinition("choline", "Choline", NutrientGroup.VITAMINS, 550.0, "mg", description = "Aids memory retention, lipid mobilization, and cell membranes"),

        // Minerals (12)
        NutrientDefinition("calcium", "Calcium", NutrientGroup.MINERALS, 1000.0, "mg", description = "Provides structural rigidity to bones, active in muscle pacing"),
        NutrientDefinition("iron", "Iron", NutrientGroup.MINERALS, 18.0, "mg", description = "Sits inside hemoglobin to transport life-supporting oxygen"),
        NutrientDefinition("magnesium", "Magnesium", NutrientGroup.MINERALS, 400.0, "mg", description = "Supports enzymatic networks, bone matrices, and nerve stability"),
        NutrientDefinition("phosphorus", "Phosphorus", NutrientGroup.MINERALS, 700.0, "mg", description = "Combines with calcium to form hydroxyapatite; structural DNA component"),
        NutrientDefinition("potassium", "Potassium", NutrientGroup.MINERALS, 3400.0, "mg", description = "Primary intracellular cation balancing cell potentials and cardiac beat"),
        NutrientDefinition("sodium", "Sodium", NutrientGroup.MINERALS, 2300.0, "mg", isMaxLimit = true, description = "Regulates extracellular hydration; keep low for vascular health"),
        NutrientDefinition("zinc", "Zinc", NutrientGroup.MINERALS, 11.0, "mg", description = "Cofactor for DNA synthesis, cell division, and immune systems"),
        NutrientDefinition("copper", "Copper", NutrientGroup.MINERALS, 0.9, "mg", description = "Works with iron to build red cells, and aids blood vessel loops"),
        NutrientDefinition("manganese", "Manganese", NutrientGroup.MINERALS, 2.3, "mg", description = "Core metabolic enzymic asset that defends bones from oxidation"),
        NutrientDefinition("selenium", "Selenium", NutrientGroup.MINERALS, 55.0, "mcg", description = "Shields tissues from oxidative harm, supports thyroid pathways"),
        NutrientDefinition("chromium", "Chromium", NutrientGroup.MINERALS, 35.0, "mcg", description = "Enhances insulin sensitivity and macronutrient metabolism"),
        NutrientDefinition("molybdenum", "Molybdenum", NutrientGroup.MINERALS, 45.0, "mcg", description = "Metabolizes sulfur amino acids and generic cellular chemicals"),

        // Others (2)
        NutrientDefinition("sugars", "Sugars", NutrientGroup.OTHERS, 50.0, "g", isMaxLimit = true, description = "Simple carbohydrates; restrict to keep glucose curves clean"),
        NutrientDefinition("iodine", "Iodine", NutrientGroup.OTHERS, 150.0, "mcg", description = "Essential component of thyroid hormones regulating metabolism")
    )

    var DEFINITIONS: List<NutrientDefinition> = DEFAULT_DEFINITIONS

    fun getByKey(key: String): NutrientDefinition? {
        return DEFINITIONS.find { it.key == key }
    }

    // Load from JSON file or initialize it with DEFAULT_DEFINITIONS if file does not exist
    fun loadFromDatabase(context: Context): List<NutrientDefinition> {
        val file = File(context.filesDir, DB_FILE_NAME)
        if (!file.exists()) {
            saveListToDatabase(context, DEFAULT_DEFINITIONS)
            DEFINITIONS = DEFAULT_DEFINITIONS
            return DEFAULT_DEFINITIONS
        }

        return try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val parsedList = mutableListOf<NutrientDefinition>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val key = jsonObject.getString("key")
                val name = jsonObject.getString("name")
                val groupStr = jsonObject.getString("group")
                val group = NutrientGroup.valueOf(groupStr)
                val rda = jsonObject.getDouble("rda")
                val unit = jsonObject.getString("unit")
                val isMaxLimit = jsonObject.optBoolean("isMaxLimit", false)
                val description = jsonObject.optString("description", "")
                parsedList.add(NutrientDefinition(key, name, group, rda, unit, isMaxLimit, description))
            }
            DEFINITIONS = parsedList
            parsedList
        } catch (e: Exception) {
            android.util.Log.e("Nutrients", "Error loading JSON nutrient database, falling back to default.", e)
            DEFINITIONS = DEFAULT_DEFINITIONS
            DEFAULT_DEFINITIONS
        }
    }

    // Save list to database
    fun saveListToDatabase(context: Context, list: List<NutrientDefinition>) {
        try {
            val jsonArray = JSONArray()
            for (def in list) {
                val jsonObject = JSONObject().apply {
                    put("key", def.key)
                    put("name", def.name)
                    put("group", def.group.name)
                    put("rda", def.rda)
                    put("unit", def.unit)
                    put("isMaxLimit", def.isMaxLimit)
                    put("description", def.description)
                }
                jsonArray.put(jsonObject)
            }
            val file = File(context.filesDir, DB_FILE_NAME)
            file.writeText(jsonArray.toString(4))
            DEFINITIONS = list
        } catch (e: Exception) {
            android.util.Log.e("Nutrients", "Error saving JSON nutrient database.", e)
        }
    }

    // Set or update standard/RDA value for a nutrient key in the JSON-based database
    fun updateRda(context: Context, key: String, newRda: Double) {
        val currentList = loadFromDatabase(context).map {
            if (it.key == key) it.copy(rda = newRda) else it
        }
        saveListToDatabase(context, currentList)
    }

    // Reset database file to system defaults
    fun resetToDefaults(context: Context) {
        saveListToDatabase(context, DEFAULT_DEFINITIONS)
    }
}
