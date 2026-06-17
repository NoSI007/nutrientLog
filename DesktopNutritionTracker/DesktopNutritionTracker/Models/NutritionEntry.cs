using System;
using System.Collections.Generic;
using System.Linq;

namespace DesktopNutritionTracker.Models
{
    /// <summary>
    /// Represents a nutrition record defining the 41 essential nutrients,
    /// with built-in mechanics to compute intake status percentages against 
    /// standard Recommended Dietary Allowance (RDA) thresholds.
    /// </summary>
    public class NutritionEntry
    {
        /// <summary>
        /// Backing dictionary storing the raw quantitative value for each of the 41 essential nutrients.
        /// </summary>
        public Dictionary<string, double> Nutrients { get; set; } = new Dictionary<string, double>();

        /// <summary>
        /// Initializes a new instance of the NutritionEntry class and registers default values for all 41 essential nutrients.
        /// </summary>
        public NutritionEntry()
        {
            InitializeDefaultNutrients();
        }

        /// <summary>
        /// Initializes a new instance of the NutritionEntry class populated from an existing dictionary of nutrient values.
        /// </summary>
        public NutritionEntry(Dictionary<string, double> existingNutrients)
        {
            InitializeDefaultNutrients();
            if (existingNutrients != null)
            {
                foreach (var kvp in existingNutrients)
                {
                    if (Nutrients.ContainsKey(kvp.Key))
                    {
                        Nutrients[kvp.Key] = kvp.Value;
                    }
                }
            }
        }

        private void InitializeDefaultNutrients()
        {
            foreach (var def in Models.Nutrients.Definitions)
            {
                Nutrients[def.Key] = 0.0;
            }
        }

        #region Strongly-Typed Properties for the 41 Essential Nutrients

        // --- Macronutrients (6) ---
        public double Calories { get => GetNutrient("calories"); set => SetNutrient("calories", value); }
        public double Carbohydrates { get => GetNutrient("carbohydrates"); set => SetNutrient("carbohydrates", value); }
        public double Protein { get => GetNutrient("protein"); set => SetNutrient("protein", value); }
        public double Fat { get => GetNutrient("fat"); set => SetNutrient("fat", value); }
        public double Fiber { get => GetNutrient("fiber"); set => SetNutrient("fiber", value); }
        public double Water { get => GetNutrient("water"); set => SetNutrient("water", value); }

        // --- Lipids/Fat Breakdown (7) ---
        public double SaturatedFat { get => GetNutrient("saturated_fat"); set => SetNutrient("saturated_fat", value); }
        public double TransFat { get => GetNutrient("trans_fat"); set => SetNutrient("trans_fat", value); }
        public double MonounsaturatedFat { get => GetNutrient("monounsaturated_fat"); set => SetNutrient("monounsaturated_fat", value); }
        public double PolyunsaturatedFat { get => GetNutrient("polyunsaturated_fat"); set => SetNutrient("polyunsaturated_fat", value); }
        public double Omega3 { get => GetNutrient("omega3"); set => SetNutrient("omega3", value); }
        public double Omega6 { get => GetNutrient("omega6"); set => SetNutrient("omega6", value); }
        public double Cholesterol { get => GetNutrient("cholesterol"); set => SetNutrient("cholesterol", value); }

        // --- Vitamins (14) ---
        public double VitaminA { get => GetNutrient("vitamin_a"); set => SetNutrient("vitamin_a", value); }
        public double VitaminC { get => GetNutrient("vitamin_c"); set => SetNutrient("vitamin_c", value); }
        public double VitaminD { get => GetNutrient("vitamin_d"); set => SetNutrient("vitamin_d", value); }
        public double VitaminE { get => GetNutrient("vitamin_e"); set => SetNutrient("vitamin_e", value); }
        public double VitaminK { get => GetNutrient("vitamin_k"); set => SetNutrient("vitamin_k", value); }
        public double Thiamin { get => GetNutrient("thiamin"); set => SetNutrient("thiamin", value); }
        public double Riboflavin { get => GetNutrient("riboflavin"); set => SetNutrient("riboflavin", value); }
        public double Niacin { get => GetNutrient("niacin"); set => SetNutrient("niacin", value); }
        public double PantothenicAcid { get => GetNutrient("pantothenic_acid"); set => SetNutrient("pantothenic_acid", value); }
        public double VitaminB6 { get => GetNutrient("vitamin_b6"); set => SetNutrient("vitamin_b6", value); }
        public double Biotin { get => GetNutrient("biotin"); set => SetNutrient("biotin", value); }
        public double Folate { get => GetNutrient("folate"); set => SetNutrient("folate", value); }
        public double VitaminB12 { get => GetNutrient("vitamin_b12"); set => SetNutrient("vitamin_b12", value); }
        public double Choline { get => GetNutrient("choline"); set => SetNutrient("choline", value); }

        // --- Minerals (12) ---
        public double Calcium { get => GetNutrient("calcium"); set => SetNutrient("calcium", value); }
        public double Iron { get => GetNutrient("iron"); set => SetNutrient("iron", value); }
        public double Magnesium { get => GetNutrient("magnesium"); set => SetNutrient("magnesium", value); }
        public double Phosphorus { get => GetNutrient("phosphorus"); set => SetNutrient("phosphorus", value); }
        public double Potassium { get => GetNutrient("potassium"); set => SetNutrient("potassium", value); }
        public double Sodium { get => GetNutrient("sodium"); set => SetNutrient("sodium", value); }
        public double Zinc { get => GetNutrient("zinc"); set => SetNutrient("zinc", value); }
        public double Copper { get => GetNutrient("copper"); set => SetNutrient("copper", value); }
        public double Manganese { get => GetNutrient("manganese"); set => SetNutrient("manganese", value); }
        public double Selenium { get => GetNutrient("selenium"); set => SetNutrient("selenium", value); }
        public double Chromium { get => GetNutrient("chromium"); set => SetNutrient("chromium", value); }
        public double Molybdenum { get => GetNutrient("molybdenum"); set => SetNutrient("molybdenum", value); }

        // --- Others (2) ---
        public double Sugars { get => GetNutrient("sugars"); set => SetNutrient("sugars", value); }
        public double Iodine { get => GetNutrient("iodine"); set => SetNutrient("iodine", value); }

        #endregion

        #region Helper Methods for Accessing Backing Store

        private double GetNutrient(string key)
        {
            return Nutrients.TryGetValue(key, out var val) ? val : 0.0;
        }

        private void SetNutrient(string key, double value)
        {
            Nutrients[key] = value;
        }

        #endregion

        #region RDA Calculation & Percentages Logic

        /// <summary>
        /// Compiles the total nutrient intake for a specific date from food entries and taken supplements.
        /// </summary>
        /// <param name="date">The target date (Format: YYYY-MM-DD).</param>
        /// <param name="foodEntries">All food entries in local storage.</param>
        /// <param name="supplements">All system supplements.</param>
        /// <param name="takenSupplementKeys">The keys tracking taken supplements in format "Date|SupplementName".</param>
        /// <returns>A populated NutritionEntry object representing total daily quantitative intakes.</returns>
        public static NutritionEntry ComputeForDate(
            string date, 
            IEnumerable<FoodLogEntry> foodEntries, 
            IEnumerable<Supplement> supplements, 
            IEnumerable<string> takenSupplementKeys)
        {
            var entry = new NutritionEntry();
            var takenSet = new HashSet<string>(takenSupplementKeys ?? Enumerable.Empty<string>());

            // 1. Accumulate nutrients from foods logged on the specific date
            var foodsOnDate = foodEntries.Where(f => f.Date == date);
            foreach (var food in foodsOnDate)
            {
                foreach (var kvp in food.Nutrients)
                {
                    if (entry.Nutrients.ContainsKey(kvp.Key))
                    {
                        entry.Nutrients[kvp.Key] += kvp.Value * food.Quantity;
                    }
                }
            }

            // 2. Accumulate nutrients from taken supplements logged on the specific date
            foreach (var sup in supplements)
            {
                string key = $"{date}|{sup.Name}";
                if (takenSet.Contains(key))
                {
                    foreach (var kvp in sup.Nutrients)
                    {
                        if (entry.Nutrients.ContainsKey(kvp.Key))
                        {
                            entry.Nutrients[kvp.Key] += kvp.Value;
                        }
                    }
                }
            }

            return entry;
        }

        /// <summary>
        /// Calculates the RDA percentages for all 41 essential nutrients, supporting custom RDA value overrides.
        /// </summary>
        /// <param name="customRdaOverrides">Optional dictionary containing custom daily target overrides.</param>
        /// <returns>A dictionary containing the calculated percentage maps.</returns>
        public Dictionary<string, double> CalculatePercentages(Dictionary<string, double>? customRdaOverrides = null)
        {
            var percentages = new Dictionary<string, double>();
            foreach (var def in Models.Nutrients.Definitions)
            {
                double targetRda = def.Rda;
                if (customRdaOverrides != null && customRdaOverrides.TryGetValue(def.Key, out double customVal))
                {
                    targetRda = customVal;
                }

                double intake = GetNutrient(def.Key);
                double percentage = 0.0;
                if (targetRda > 0.0)
                {
                    percentage = (intake / targetRda) * 100.0;
                }
                percentages[def.Key] = percentage;
            }
            return percentages;
        }

        /// <summary>
        /// Calculates the RDA percentages for all 41 essential nutrients based on standard RDA values.
        /// </summary>
        /// <returns>A dictionary containing the calculated percentages mapped by nutrient key.</returns>
        public Dictionary<string, double> CalculateRdaPercentages()
        {
            return CalculatePercentages(null);
        }

        /// <summary>
        /// Calculates the RDA percentages based on a specific named CustomDailyProfile.
        /// </summary>
        /// <param name="profile">The custom daily profile containing custom target definitions.</param>
        /// <returns>A dictionary containing the calculated percentage maps.</returns>
        public Dictionary<string, double> CalculateProfilePercentages(CustomDailyProfile profile)
        {
            if (profile == null) return CalculateRdaPercentages();
            return CalculatePercentages(profile.Targets);
        }

        /// <summary>
        /// Calculates the RDA percentage for a single nutrient identified by its unique key string.
        /// </summary>
        /// <param name="nutrientKey">The unique key of the nutrient (e.g., "calories", "vitamin_c").</param>
        /// <returns>The calculated percentage completed, or 0.0 if the nutrient definition is invalid or RDA is 0.</returns>
        public double GetPercentageOfRda(string nutrientKey)
        {
            var def = Models.Nutrients.GetByKey(nutrientKey);
            if (def == null || def.Rda <= 0.0) return 0.0;
            return (GetNutrient(nutrientKey) / def.Rda) * 100.0;
        }

        #endregion
    }
}
