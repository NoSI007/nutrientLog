using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    public interface IRdaProfileService
    {
        List<RdaProfileGroup> GetRdaProfiles();
        RdaProfileGroup GetProfileForCategory(int age, string gender);
        void ApplyRdaProfileToOverrides(int age, string gender, Dictionary<string, double> overrides);
    }

    public class RdaProfileService : IRdaProfileService
    {
        private readonly List<RdaProfileGroup> _profiles;

        public RdaProfileService()
        {
            _profiles = LoadProfiles();
        }

        public List<RdaProfileGroup> GetRdaProfiles()
        {
            return _profiles;
        }

        public RdaProfileGroup GetProfileForCategory(int age, string gender)
        {
            // Normalize gender string
            string targetGender = "Female";
            if (string.Equals(gender, "Male", StringComparison.OrdinalIgnoreCase))
            {
                targetGender = "Male";
            }

            // Find matching profile by age and gender
            var matched = _profiles.FirstOrDefault(p => 
                age >= p.MinAge && age <= p.MaxAge && 
                (p.Gender == "Any" || string.Equals(p.Gender, targetGender, StringComparison.OrdinalIgnoreCase)));

            // If no match, try finding by gender or age-only, or fall back to standard baseline
            if (matched == null)
            {
                matched = _profiles.FirstOrDefault(p => age >= p.MinAge && age <= p.MaxAge) 
                          ?? _profiles.FirstOrDefault(p => string.Equals(p.Gender, targetGender, StringComparison.OrdinalIgnoreCase))
                          ?? _profiles.FirstOrDefault();
            }

            return matched ?? new RdaProfileGroup();
        }

        public void ApplyRdaProfileToOverrides(int age, string gender, Dictionary<string, double> overrides)
        {
            var profile = GetProfileForCategory(age, gender);
            if (profile != null && profile.NutrientTargets != null)
            {
                foreach (var kvp in profile.NutrientTargets)
                {
                    overrides[kvp.Key] = kvp.Value;
                }
            }
        }

        private List<RdaProfileGroup> LoadProfiles()
        {
            string jsonFileName = "essential_nutrient_rdas.json";
            string[] possiblePaths = new[]
            {
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, jsonFileName),
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Resources", jsonFileName),
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "Resources", jsonFileName),
                jsonFileName
            };

            foreach (var path in possiblePaths)
            {
                if (File.Exists(path))
                {
                    try
                    {
                        string content = File.ReadAllText(path);
                        var loaded = JsonConvert.DeserializeObject<List<RdaProfileGroup>>(content);
                        if (loaded != null && loaded.Count > 0)
                        {
                            return loaded;
                        }
                    }
                    catch (Exception ex)
                    {
                        System.Diagnostics.Debug.WriteLine($"Failed to parse RDA profiles from {path}: {ex.Message}");
                    }
                }
            }

            // Fallback to robust embedded static seed data
            try
            {
                var loaded = JsonConvert.DeserializeObject<List<RdaProfileGroup>>(GetEmbeddedSeedData());
                if (loaded != null && loaded.Count > 0)
                {
                    return loaded;
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to load embedded RDA seed fallback: {ex.Message}");
            }

            return new List<RdaProfileGroup>();
        }

        private string GetEmbeddedSeedData()
        {
            // Fully populated 41 nutrients for fallback resilience
            return @"[
  {
    ""GroupCode"": ""Child_4_8"",
    ""GroupName"": ""Children (4-8 years)"",
    ""MinAge"": 4,
    ""MaxAge"": 8,
    ""Gender"": ""Any"",
    ""NutrientTargets"": {
      ""calories"": 1400.0, ""carbohydrates"": 130.0, ""protein"": 19.0, ""fat"": 45.0, ""fiber"": 25.0, ""water"": 1700.0,
      ""saturated_fat"": 15.0, ""trans_fat"": 1.0, ""monounsaturated_fat"": 15.0, ""polyunsaturated_fat"": 10.0, ""omega3"": 0.9, ""omega6"": 10.0,
      ""cholesterol"": 200.0, ""vitamin_a"": 400.0, ""vitamin_c"": 25.0, ""vitamin_d"": 15.0, ""vitamin_e"": 7.0, ""vitamin_k"": 55.0,
      ""thiamin"": 0.6, ""riboflavin"": 0.6, ""niacin"": 8.0, ""pantothenic_acid"": 3.0, ""vitamin_b6"": 0.6, ""biotin"": 12.0, ""folate"": 200.0,
      ""vitamin_b12"": 1.2, ""choline"": 250.0, ""calcium"": 1000.0, ""iron"": 10.0, ""magnesium"": 130.0, ""phosphorus"": 500.0, ""potassium"": 2300.0,
      ""sodium"": 1900.0, ""zinc"": 5.0, ""copper"": 0.44, ""manganese"": 1.5, ""selenium"": 30.0, ""chromium"": 15.0, ""molybdenum"": 22.0, ""sugars"": 35.0, ""iodine"": 90.0
    }
  },
  {
    ""GroupCode"": ""Male_19_30"",
    ""GroupName"": ""Adult Male (19-30 years)"",
    ""MinAge"": 19,
    ""MaxAge"": 30,
    ""Gender"": ""Male"",
    ""NutrientTargets"": {
      ""calories"": 2400.0, ""carbohydrates"": 300.0, ""protein"": 56.0, ""fat"": 80.0, ""fiber"": 38.0, ""water"": 3700.0,
      ""saturated_fat"": 24.0, ""trans_fat"": 2.0, ""monounsaturated_fat"": 30.0, ""polyunsaturated_fat"": 20.0, ""omega3"": 1.6, ""omega6"": 17.0,
      ""cholesterol"": 300.0, ""vitamin_a"": 900.0, ""vitamin_c"": 90.0, ""vitamin_d"": 15.0, ""vitamin_e"": 15.0, ""vitamin_k"": 120.0,
      ""thiamin"": 1.2, ""riboflavin"": 1.3, ""niacin"": 16.0, ""pantothenic_acid"": 5.0, ""vitamin_b6"": 1.3, ""biotin"": 30.0, ""folate"": 400.0,
      ""vitamin_b12"": 2.4, ""choline"": 550.0, ""calcium"": 1000.0, ""iron"": 8.0, ""magnesium"": 400.0, ""phosphorus"": 700.0, ""potassium"": 3400.0,
      ""sodium"": 2300.0, ""zinc"": 11.0, ""copper"": 0.9, ""manganese"": 2.3, ""selenium"": 55.0, ""chromium"": 35.0, ""molybdenum"": 45.0, ""sugars"": 50.0, ""iodine"": 150.0
    }
  },
  {
    ""GroupCode"": ""Female_19_30"",
    ""GroupName"": ""Adult Female (19-30 years)"",
    ""MinAge"": 19,
    ""MaxAge"": 30,
    ""Gender"": ""Female"",
    ""NutrientTargets"": {
      ""calories"": 2000.0, ""carbohydrates"": 250.0, ""protein"": 46.0, ""fat"": 65.0, ""fiber"": 25.0, ""water"": 2700.0,
      ""saturated_fat"": 20.0, ""trans_fat"": 1.5, ""monounsaturated_fat"": 25.0, ""polyunsaturated_fat"": 15.0, ""omega3"": 1.1, ""omega6"": 12.0,
      ""cholesterol"": 300.0, ""vitamin_a"": 700.0, ""vitamin_c"": 75.0, ""vitamin_d"": 15.0, ""vitamin_e"": 15.0, ""vitamin_k"": 90.0,
      ""thiamin"": 1.1, ""riboflavin"": 1.1, ""niacin"": 14.0, ""pantothenic_acid"": 5.0, ""vitamin_b6"": 1.3, ""biotin"": 30.0, ""folate"": 400.0,
      ""vitamin_b12"": 2.4, ""choline"": 425.0, ""calcium"": 1000.0, ""iron"": 18.0, ""magnesium"": 310.0, ""phosphorus"": 700.0, ""potassium"": 2600.0,
      ""sodium"": 2300.0, ""zinc"": 8.0, ""copper"": 0.9, ""manganese"": 1.8, ""selenium"": 55.0, ""chromium"": 25.0, ""molybdenum"": 45.0, ""sugars"": 45.0, ""iodine"": 150.0
    }
  },
  {
    ""GroupCode"": ""Male_31_50"",
    ""GroupName"": ""Adult Male (31-50 years)"",
    ""MinAge"": 31,
    ""MaxAge"": 50,
    ""Gender"": ""Male"",
    ""NutrientTargets"": {
      ""calories"": 2200.0, ""carbohydrates"": 275.0, ""protein"": 56.0, ""fat"": 73.0, ""fiber"": 38.0, ""water"": 3700.0,
      ""saturated_fat"": 22.0, ""trans_fat"": 2.0, ""monounsaturated_fat"": 27.0, ""polyunsaturated_fat"": 18.0, ""omega3"": 1.6, ""omega6"": 17.0,
      ""cholesterol"": 300.0, ""vitamin_a"": 900.0, ""vitamin_c"": 90.0, ""vitamin_d"": 15.0, ""vitamin_e"": 15.0, ""vitamin_k"": 120.0,
      ""thiamin"": 1.2, ""riboflavin"": 1.3, ""niacin"": 16.0, ""pantothenic_acid"": 5.0, ""vitamin_b6"": 1.3, ""biotin"": 30.0, ""folate"": 400.0,
      ""vitamin_b12"": 2.4, ""choline"": 550.0, ""calcium"": 1000.0, ""iron"": 8.0, ""magnesium"": 420.0, ""phosphorus"": 700.0, ""potassium"": 3400.0,
      ""sodium"": 2300.0, ""zinc"": 11.0, ""copper"": 0.9, ""manganese"": 2.3, ""selenium"": 55.0, ""chromium"": 35.0, ""molybdenum"": 45.0, ""sugars"": 50.0, ""iodine"": 150.0
    }
  },
  {
    ""GroupCode"": ""Female_31_50"",
    ""GroupName"": ""Adult Female (31-50 years)"",
    ""MinAge"": 31,
    ""MaxAge"": 50,
    ""Gender"": ""Female"",
    ""NutrientTargets"": {
      ""calories"": 1800.0, ""carbohydrates"": 225.0, ""protein"": 46.0, ""fat"": 60.0, ""fiber"": 25.0, ""water"": 2700.0,
      ""saturated_fat"": 18.0, ""trans_fat"": 1.5, ""monounsaturated_fat"": 22.0, ""polyunsaturated_fat"": 14.0, ""omega3"": 1.1, ""omega6"": 12.0,
      ""cholesterol"": 300.0, ""vitamin_a"": 700.0, ""vitamin_c"": 75.0, ""vitamin_d"": 15.0, ""vitamin_e"": 15.0, ""vitamin_k"": 90.0,
      ""thiamin"": 1.1, ""riboflavin"": 1.1, ""niacin"": 14.0, ""pantothenic_acid"": 5.0, ""vitamin_b6"": 1.3, ""biotin"": 30.0, ""folate"": 400.0,
      ""vitamin_b12"": 2.4, ""choline"": 425.0, ""calcium"": 1000.0, ""iron"": 18.0, ""magnesium"": 320.0, ""phosphorus"": 700.0, ""potassium"": 2600.0,
      ""sodium"": 2300.0, ""zinc"": 8.0, ""copper"": 0.9, ""manganese"": 1.8, ""selenium"": 55.0, ""chromium"": 25.0, ""molybdenum"": 45.0, ""sugars"": 45.0, ""iodine"": 150.0
    }
  }
]";
        }
    }
}
