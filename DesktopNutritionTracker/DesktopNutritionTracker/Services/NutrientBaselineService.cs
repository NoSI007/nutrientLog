using System;
using System.Collections.Generic;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    public interface INutrientBaselineService
    {
        List<NutrientDefinition> GetBaselineNutrients();
        NutrientDefinition GetNutrientByKey(string key);
    }

    public class NutrientBaselineService : INutrientBaselineService
    {
        private readonly List<NutrientDefinition> _baselineNutrients;

        public NutrientBaselineService()
        {
            _baselineNutrients = new List<NutrientDefinition>
            {
                // Macronutrients (6)
                new NutrientDefinition("calories", "Calories", NutrientGroup.MACROS, 2000.0, "kcal", false, "Energy required for daily cellular metabolic functions"),
                new NutrientDefinition("carbohydrates", "Carbohydrates", NutrientGroup.MACROS, 275.0, "g", false, "Primary fast-acting energy source for muscles and brain"),
                new NutrientDefinition("protein", "Protein", NutrientGroup.MACROS, 56.0, "g", false, "Building blocks for hormones, tissues, and muscles"),
                new NutrientDefinition("fat", "Total Fat", NutrientGroup.MACROS, 70.0, "g", false, "Crucial for organ protection, hormones, and vitamin absorption"),
                new NutrientDefinition("fiber", "Dietary Fiber", NutrientGroup.MACROS, 28.0, "g", false, "Essential for gut motility, cholesterol and blood sugar control"),
                new NutrientDefinition("water", "Water/Hydration", NutrientGroup.MACROS, 2500.0, "ml", false, "Regulates temperature, acts as solvent, and protects joints"),

                // Lipids/Fat Breakdown (7)
                new NutrientDefinition("saturated_fat", "Saturated Fat", NutrientGroup.LIPIDS, 20.0, "g", true, "Primarily from animal sources; monitor for cardiovascular health"),
                new NutrientDefinition("trans_fat", "Trans Fat", NutrientGroup.LIPIDS, 2.0, "g", true, "Artificially hardened fats; keep as low as possible"),
                new NutrientDefinition("monounsaturated_fat", "Monounsaturated Fat", NutrientGroup.LIPIDS, 25.0, "g", false, "Heart-healthy fats commonly found in olive oil and avocados"),
                new NutrientDefinition("polyunsaturated_fat", "Polyunsaturated Fat", NutrientGroup.LIPIDS, 17.0, "g", false, "Beneficial fats including essential omega-3 & omega-6 pathways"),
                new NutrientDefinition("omega3", "Omega-3 Fatty Acids", NutrientGroup.LIPIDS, 1.6, "g", false, "Anti-inflammatory, fundamental for heart and cognitive acuity"),
                new NutrientDefinition("omega6", "Omega-6 Fatty Acids", NutrientGroup.LIPIDS, 17.0, "g", false, "Supports cellular structures, skin resilience and hair density"),
                new NutrientDefinition("cholesterol", "Cholesterol", NutrientGroup.LIPIDS, 300.0, "mg", true, "Part of cellular membranes, but high intake should be monitored"),

                // Vitamins (14)
                new NutrientDefinition("vitamin_a", "Vitamin A", NutrientGroup.VITAMINS, 900.0, "mcg", false, "Critical for vision, dark adaptation, and immune defense"),
                new NutrientDefinition("vitamin_c", "Vitamin C", NutrientGroup.VITAMINS, 90.0, "mg", false, "Antioxidant, vital for collagen synthesis and tissue healing"),
                new NutrientDefinition("vitamin_d", "Vitamin D", NutrientGroup.VITAMINS, 15.0, "mcg", false, "Facilitates calcium integration and skeletal health"),
                new NutrientDefinition("vitamin_e", "Vitamin E", NutrientGroup.VITAMINS, 15.0, "mg", false, "Lipid-soluble antioxidant defending cellular membranes"),
                new NutrientDefinition("vitamin_k", "Vitamin K", NutrientGroup.VITAMINS, 120.0, "mcg", false, "Essential cofactor in calcium-binding and coagulation flow"),
                new NutrientDefinition("thiamin", "Thiamin (B1)", NutrientGroup.VITAMINS, 1.2, "mg", false, "Cofactor in carbohydrate conversion to cellular energy"),
                new NutrientDefinition("riboflavin", "Riboflavin (B2)", NutrientGroup.VITAMINS, 1.3, "mg", false, "Participates in cell development and cellular respiration"),
                new NutrientDefinition("niacin", "Niacin (B3)", NutrientGroup.VITAMINS, 16.0, "mg", false, "Crucial for lipid conversion and healthy dermal/nervous states"),
                new NutrientDefinition("pantothenic_acid", "Pantothenic Acid (B5)", NutrientGroup.VITAMINS, 5.0, "mg", false, "Crucial for coenzyme A formation and steroid synthesis"),
                new NutrientDefinition("vitamin_b6", "Vitamin B6", NutrientGroup.VITAMINS, 1.3, "mg", false, "Essential for amino acid processing and neurotransmitter synthesis"),
                new NutrientDefinition("biotin", "Biotin (B7)", NutrientGroup.VITAMINS, 30.0, "mcg", false, "Key component in fat synthesis, glucose creation, and protein use"),
                new NutrientDefinition("folate", "Folate (B9)", NutrientGroup.VITAMINS, 400.0, "mcg", false, "Critical for red blood cell formation and neural tube expansion"),
                new NutrientDefinition("vitamin_b12", "Vitamin B12", NutrientGroup.VITAMINS, 2.4, "mcg", false, "Crucial for myelination, DNA assembly, and blood generation"),
                new NutrientDefinition("choline", "Choline", NutrientGroup.VITAMINS, 550.0, "mg", false, "Aids memory retention, lipid mobilization, and cell membranes"),

                // Minerals (12)
                new NutrientDefinition("calcium", "Calcium", NutrientGroup.MINERALS, 1000.0, "mg", false, "Provides structural rigidity to bones, active in muscle pacing"),
                new NutrientDefinition("iron", "Iron", NutrientGroup.MINERALS, 18.0, "mg", false, "Sits inside hemoglobin to transport life-supporting oxygen"),
                new NutrientDefinition("magnesium", "Magnesium", NutrientGroup.MINERALS, 400.0, "mg", false, "Supports enzymatic networks, bone matrices, and nerve stability"),
                new NutrientDefinition("phosphorus", "Phosphorus", NutrientGroup.MINERALS, 700.0, "mg", false, "Combines with calcium to form hydroxyapatite; structural DNA component"),
                new NutrientDefinition("potassium", "Potassium", NutrientGroup.MINERALS, 3400.0, "mg", false, "Primary intracellular cation balancing cell potentials and cardiac beat"),
                new NutrientDefinition("sodium", "Sodium", NutrientGroup.MINERALS, 2300.0, "mg", true, "Regulates extracellular hydration; keep low for vascular health"),
                new NutrientDefinition("zinc", "Zinc", NutrientGroup.MINERALS, 11.0, "mg", false, "Cofactor for DNA synthesis, cell division, and immune systems"),
                new NutrientDefinition("copper", "Copper", NutrientGroup.MINERALS, 0.9, "mg", false, "Works with iron to build red cells, and aids blood vessel loops"),
                new NutrientDefinition("manganese", "Manganese", NutrientGroup.MINERALS, 2.3, "mg", false, "Core metabolic enzymic asset that defends bones from oxidation"),
                new NutrientDefinition("selenium", "Selenium", NutrientGroup.MINERALS, 55.0, "mcg", false, "Shields tissues from oxidative harm, supports thyroid pathways"),
                new NutrientDefinition("chromium", "Chromium", NutrientGroup.MINERALS, 35.0, "mcg", false, "Enhances insulin sensitivity and macronutrient metabolism"),
                new NutrientDefinition("molybdenum", "Molybdenum", NutrientGroup.MINERALS, 45.0, "mcg", false, "Metabolizes sulfur amino acids and generic cellular chemicals"),

                // Others (2)
                new NutrientDefinition("sugars", "Sugars", NutrientGroup.OTHERS, 50.0, "g", true, "Simple carbohydrates; restrict to keep glucose curves clean"),
                new NutrientDefinition("iodine", "Iodine", NutrientGroup.OTHERS, 150.0, "mcg", false, "Essential component of thyroid hormones regulating metabolism")
            };
        }

        public List<NutrientDefinition> GetBaselineNutrients()
        {
            return _baselineNutrients;
        }

        public NutrientDefinition GetNutrientByKey(string key)
        {
            if (string.IsNullOrEmpty(key)) return null;
            return _baselineNutrients.Find(n => n.Key.Equals(key, StringComparison.OrdinalIgnoreCase));
        }
    }
}
