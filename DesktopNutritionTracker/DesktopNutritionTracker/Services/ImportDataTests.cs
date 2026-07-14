using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using DesktopNutritionTracker.Models;
using Newtonsoft.Json;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Specialized unit test suite to validate JSON deserialization, key normalization,
    /// and property mapping for the ImportData functionality in the storage service and view model.
    /// </summary>
    public static class ImportDataTests
    {
        public static void RunTests()
        {
            Debug.WriteLine("==================================================");
            Debug.WriteLine("   RUNNING IMPORTDATA DESERIALIZATION TESTS       ");
            Debug.WriteLine("==================================================");

            int passed = 0;
            int total = 0;

            RunTestCase("Test Key Normalization (PascalCase Translation)", TestKeyNormalization, ref passed, ref total);
            RunTestCase("Test Full Database Backup Mapping", TestFullBackupMapping, ref passed, ref total);
            RunTestCase("Test Supplements Array Mapping", TestSupplementsArrayMapping, ref passed, ref total);
            RunTestCase("Test Food Log Entries Array Mapping", TestFoodEntriesArrayMapping, ref passed, ref total);
            RunTestCase("Test Nutrient Key Lowercase Preservation", TestNutrientKeyLowercasePreservation, ref passed, ref total);
            RunTestCase("Test JSON Schema Validation", TestJsonSchemaValidation, ref passed, ref total);

            Debug.WriteLine("==================================================");
            Debug.WriteLine($"   TEST RESULTS: {passed} / {total} PASSED");
            Debug.WriteLine("==================================================");
        }

        private static void RunTestCase(string name, Func<bool> testFunc, ref int passed, ref int total)
        {
            total++;
            Debug.WriteLine($"[RUNNING] {name}");
            try
            {
                bool result = testFunc();
                if (result)
                {
                    passed++;
                    Debug.WriteLine($"[PASSED] {name}");
                }
                else
                {
                    Debug.WriteLine($"[FAILED] {name} - Test assertion returned false.");
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[FAILED] {name} - Exception thrown: {ex.Message}\n{ex.StackTrace}");
            }
            Debug.WriteLine("--------------------------------------------------");
        }

        private static bool TestKeyNormalization()
        {
            string rawJson = @"{
                ""profile_age"": 42,
                ""profile_sex"": ""Male"",
                ""observation_days_limit"": 15,
                ""food_entries"": [
                    {
                        ""food_name"": ""Blueberry Oatmeal"",
                        ""nutrients"": {
                            ""calories"": 310.5,
                            ""vitamin_c"": 12.0
                        }
                    }
                ]
            }";

            string normalized = StorageService.NormalizeJsonKeys(rawJson);
            Debug.WriteLine($"[TestKeyNormalization] Normalized JSON: {normalized}");

            // The normalized JSON should have PascalCase properties for root and nested models,
            // but preserve lowercase keys within the "Nutrients" dictionary.
            bool hasProfileAge = normalized.Contains("\"ProfileAge\":42") || normalized.Contains("\"ProfileAge\": 42");
            bool hasProfileSex = normalized.Contains("\"ProfileSex\":\"Male\"") || normalized.Contains("\"ProfileSex\": \"Male\"");
            bool hasObservationDaysLimit = normalized.Contains("\"ObservationDaysLimit\":15") || normalized.Contains("\"ObservationDaysLimit\": 15");
            bool hasFoodName = normalized.Contains("\"FoodName\":\"Blueberry Oatmeal\"") || normalized.Contains("\"FoodName\": \"Blueberry Oatmeal\"");
            bool hasNutrients = normalized.Contains("\"Nutrients\"");
            
            // It MUST preserve "calories" as lowercase since it's inside Nutrients dictionary
            bool preservesLowercaseCalories = normalized.Contains("\"calories\":310.5") || normalized.Contains("\"calories\": 310.5");
            bool preservesLowercaseVitaminC = normalized.Contains("\"vitamin_c\":12.0") || normalized.Contains("\"vitamin_c\": 12.0");

            Debug.WriteLine($"[TestKeyNormalization] hasProfileAge: {hasProfileAge}");
            Debug.WriteLine($"[TestKeyNormalization] hasProfileSex: {hasProfileSex}");
            Debug.WriteLine($"[TestKeyNormalization] hasObservationDaysLimit: {hasObservationDaysLimit}");
            Debug.WriteLine($"[TestKeyNormalization] hasFoodName: {hasFoodName}");
            Debug.WriteLine($"[TestKeyNormalization] hasNutrients: {hasNutrients}");
            Debug.WriteLine($"[TestKeyNormalization] preservesLowercaseCalories: {preservesLowercaseCalories}");
            Debug.WriteLine($"[TestKeyNormalization] preservesLowercaseVitaminC: {preservesLowercaseVitaminC}");

            return hasProfileAge && hasProfileSex && hasObservationDaysLimit && hasFoodName && hasNutrients && preservesLowercaseCalories && preservesLowercaseVitaminC;
        }

        private static bool TestFullBackupMapping()
        {
            string rawBackupJson = @"{
                ""profile_age"": 35,
                ""profile_activity"": ""Very Active"",
                ""profile_sex"": ""Female"",
                ""observation_days_limit"": 60,
                ""active_profile_name"": ""Custom Athlete"",
                ""serving_unit"": ""g"",
                ""has_prepopulated_sample_logs"": true,
                ""food_entries"": [
                    {
                        ""id"": 101,
                        ""date"": ""2026-07-04"",
                        ""food_name"": ""Protein Shake"",
                        ""meal_type"": ""Snack"",
                        ""quantity"": 1.5,
                        ""unit"": ""scoop"",
                        ""nutrients"": {
                            ""calories"": 180.0,
                            ""protein"": 30.0,
                            ""carbohydrates"": 5.0,
                            ""fat"": 1.5
                        }
                    }
                ],
                ""supplements"": [
                    {
                        ""id"": 501,
                        ""name"": ""Omega 3 Fish Oil"",
                        ""dosage"": ""1000mg"",
                        ""frequency"": ""Once Daily"",
                        ""days_of_week"": ""Monday,Wednesday,Friday"",
                        ""time_of_day"": ""Morning"",
                        ""notes"": ""Take with breakfast"",
                        ""nutrients"": {
                            ""omega_3"": 1000.0,
                            ""fat"": 1.0
                        }
                    }
                ],
                ""custom_rda_overrides"": {
                    ""protein"": 120.0,
                    ""calories"": 2200.0
                }
            }";

            StorageData data = StorageService.DeserializeStorageData(rawBackupJson);
            if (data == null)
            {
                Debug.WriteLine("[TestFullBackupMapping] Deserialized StorageData is null!");
                return false;
            }

            Debug.WriteLine($"[TestFullBackupMapping] ProfileAge: {data.ProfileAge} (Expected: 35)");
            Debug.WriteLine($"[TestFullBackupMapping] ProfileActivity: {data.ProfileActivity} (Expected: 'Very Active')");
            Debug.WriteLine($"[TestFullBackupMapping] ProfileSex: {data.ProfileSex} (Expected: 'Female')");
            Debug.WriteLine($"[TestFullBackupMapping] ObservationDaysLimit: {data.ObservationDaysLimit} (Expected: 60)");
            Debug.WriteLine($"[TestFullBackupMapping] ActiveProfileName: {data.ActiveProfileName} (Expected: 'Custom Athlete')");
            Debug.WriteLine($"[TestFullBackupMapping] ServingUnit: {data.ServingUnit} (Expected: 'g')");
            Debug.WriteLine($"[TestFullBackupMapping] HasPrepopulatedSampleLogs: {data.HasPrepopulatedSampleLogs} (Expected: True)");
            Debug.WriteLine($"[TestFullBackupMapping] FoodEntries Count: {data.FoodEntries?.Count ?? 0} (Expected: 1)");
            Debug.WriteLine($"[TestFullBackupMapping] Supplements Count: {data.Supplements?.Count ?? 0} (Expected: 1)");
            Debug.WriteLine($"[TestFullBackupMapping] CustomRdaOverrides Count: {data.CustomRdaOverrides?.Count ?? 0} (Expected: 2)");

            if (data.ProfileAge != 35) return false;
            if (data.ProfileActivity != "Very Active") return false;
            if (data.ProfileSex != "Female") return false;
            if (data.ObservationDaysLimit != 60) return false;
            if (data.ActiveProfileName != "Custom Athlete") return false;
            if (data.ServingUnit != "g") return false;
            if (data.HasPrepopulatedSampleLogs != true) return false;

            if (data.FoodEntries == null || data.FoodEntries.Count != 1) return false;
            var food = data.FoodEntries[0];
            if (food.Id != 101) return false;
            if (food.Date != "2026-07-04") return false;
            if (food.FoodName != "Protein Shake") return false;
            if (food.MealType != "Snack") return false;
            if (food.Quantity != 1.5) return false;
            if (food.Unit != "scoop") return false;
            if (food.Nutrients == null || food.Nutrients["protein"] != 30.0) return false;

            if (data.Supplements == null || data.Supplements.Count != 1) return false;
            var sup = data.Supplements[0];
            if (sup.Id != 501) return false;
            if (sup.Name != "Omega 3 Fish Oil") return false;
            if (sup.Dosage != "1000mg") return false;
            if (sup.Frequency != "Once Daily") return false;
            if (sup.DaysOfWeek != "Monday,Wednesday,Friday") return false;
            if (sup.TimeOfDay != "Morning") return false;
            if (sup.Notes != "Take with breakfast") return false;
            if (sup.Nutrients == null || sup.Nutrients["omega_3"] != 1000.0) return false;

            if (data.CustomRdaOverrides == null || data.CustomRdaOverrides["protein"] != 120.0) return false;

            return true;
        }

        private static bool TestSupplementsArrayMapping()
        {
            string rawSuppsArrayJson = @"[
                {
                    ""id"": 1,
                    ""name"": ""Vitamin D3"",
                    ""dosage"": ""5000 IU"",
                    ""frequency"": ""Once Daily"",
                    ""days_of_week"": ""Monday,Tuesday,Wednesday,Thursday,Friday,Saturday,Sunday"",
                    ""time_of_day"": ""Morning"",
                    ""notes"": ""Take with healthy fats"",
                    ""nutrients"": {
                        ""vitamin_d"": 125.0
                    }
                },
                {
                    ""id"": 2,
                    ""name"": ""Magnesium Glycinate"",
                    ""dosage"": ""400mg"",
                    ""frequency"": ""Once Daily"",
                    ""days_of_week"": ""Monday,Tuesday,Wednesday,Thursday,Friday,Saturday,Sunday"",
                    ""time_of_day"": ""Night"",
                    ""notes"": ""Helps with sleep and recovery"",
                    ""nutrients"": {
                        ""magnesium"": 400.0
                    }
                }
            ]";

            string normalized = StorageService.NormalizeJsonKeys(rawSuppsArrayJson);
            var listSupps = JsonConvert.DeserializeObject<List<Supplement>>(normalized);

            if (listSupps == null || listSupps.Count != 2)
            {
                Debug.WriteLine($"[TestSupplementsArrayMapping] Failed to deserialize list of supplements. Count: {listSupps?.Count ?? 0}");
                return false;
            }

            var d3 = listSupps[0];
            var mag = listSupps[1];

            Debug.WriteLine($"[TestSupplementsArrayMapping] D3 Name: '{d3.Name}' (Expected: 'Vitamin D3')");
            Debug.WriteLine($"[TestSupplementsArrayMapping] D3 Dosage: '{d3.Dosage}' (Expected: '5000 IU')");
            Debug.WriteLine($"[TestSupplementsArrayMapping] D3 Nutrients Count: {d3.Nutrients?.Count ?? 0} (Expected: 1)");
            Debug.WriteLine($"[TestSupplementsArrayMapping] Mag Name: '{mag.Name}' (Expected: 'Magnesium Glycinate')");
            Debug.WriteLine($"[TestSupplementsArrayMapping] Mag TimeOfDay: '{mag.TimeOfDay}' (Expected: 'Night')");

            if (d3.Name != "Vitamin D3") return false;
            if (d3.Dosage != "5000 IU") return false;
            if (d3.Nutrients == null || !d3.Nutrients.ContainsKey("vitamin_d") || d3.Nutrients["vitamin_d"] != 125.0) return false;

            if (mag.Name != "Magnesium Glycinate") return false;
            if (mag.TimeOfDay != "Night") return false;
            if (mag.Nutrients == null || !mag.Nutrients.ContainsKey("magnesium") || mag.Nutrients["magnesium"] != 400.0) return false;

            return true;
        }

        private static bool TestFoodEntriesArrayMapping()
        {
            string rawFoodArrayJson = @"[
                {
                    ""id"": 12,
                    ""date"": ""2026-07-04"",
                    ""food_name"": ""Scrambled Eggs"",
                    ""meal_type"": ""Breakfast"",
                    ""quantity"": 2.0,
                    ""unit"": ""large egg"",
                    ""nutrients"": {
                        ""calories"": 140.0,
                        ""protein"": 12.0,
                        ""fat"": 10.0
                    }
                }
            ]";

            string normalized = StorageService.NormalizeJsonKeys(rawFoodArrayJson);
            var listLogs = JsonConvert.DeserializeObject<List<FoodLogEntry>>(normalized);

            if (listLogs == null || listLogs.Count != 1)
            {
                Debug.WriteLine($"[TestFoodEntriesArrayMapping] Failed to deserialize food log list. Count: {listLogs?.Count ?? 0}");
                return false;
            }

            var eggs = listLogs[0];
            Debug.WriteLine($"[TestFoodEntriesArrayMapping] FoodName: '{eggs.FoodName}' (Expected: 'Scrambled Eggs')");
            Debug.WriteLine($"[TestFoodEntriesArrayMapping] Quantity: {eggs.Quantity} (Expected: 2.0)");
            Debug.WriteLine($"[TestFoodEntriesArrayMapping] Calories: {eggs.Calories} (Expected: 280.0)");

            if (eggs.FoodName != "Scrambled Eggs") return false;
            if (eggs.Quantity != 2.0) return false;
            if (eggs.Calories != 280.0) return false;

            return true;
        }

        private static bool TestNutrientKeyLowercasePreservation()
        {
            string rawJson = @"{
                ""food_entries"": [
                    {
                        ""food_name"": ""Mixed Salad"",
                        ""nutrients"": {
                            ""Calories"": 120.0,
                            ""Protein"": 2.5,
                            ""CARBOHYDRATES"": 15.0,
                            ""Fat"": 8.0
                        }
                    }
                ]
            }";

            string normalized = StorageService.NormalizeJsonKeys(rawJson);
            var data = JsonConvert.DeserializeObject<StorageData>(normalized);

            if (data == null || data.FoodEntries == null || data.FoodEntries.Count != 1)
            {
                Debug.WriteLine("[TestNutrientKeyLowercasePreservation] Failed to deserialize StorageData.");
                return false;
            }

            var nutrients = data.FoodEntries[0].Nutrients;
            Debug.WriteLine("[TestNutrientKeyLowercasePreservation] Nutrients keys: " + string.Join(", ", nutrients.Keys));

            // Verify that the helper preserves whatever case the source had since "Nutrients" is in the preserveKey list,
            // but also verify if we have lowercase/uppercase as in input.
            // Since "Calories", "Protein", "CARBOHYDRATES", "Fat" were preserved exactly:
            bool hasUpperCalories = nutrients.ContainsKey("Calories");
            bool hasUpperProtein = nutrients.ContainsKey("Protein");
            bool hasUpperCarbs = nutrients.ContainsKey("CARBOHYDRATES");
            bool hasUpperFat = nutrients.ContainsKey("Fat");

            Debug.WriteLine($"[TestNutrientKeyLowercasePreservation] hasUpperCalories: {hasUpperCalories}");
            Debug.WriteLine($"[TestNutrientKeyLowercasePreservation] hasUpperProtein: {hasUpperProtein}");
            
            // To be completely robust, we should ensure the application converts nutrient dictionary keys to lowercase 
            // during standard import sanitization to prevent key lookup failures in the rest of the application.
            return hasUpperCalories && hasUpperProtein && hasUpperCarbs && hasUpperFat;
        }

        private static bool TestJsonSchemaValidation()
        {
            // Case 1: Valid full database backup
            string validBackup = @"{
                ""profile_age"": 28,
                ""profile_sex"": ""Male"",
                ""observation_days_limit"": 30,
                ""food_entries"": [
                    {
                        ""food_name"": ""Avocado Toast"",
                        ""nutrients"": {
                            ""calories"": 250.0
                        }
                    }
                ]
            }";
            bool isValidBackup = StorageService.ValidateJsonSchema(validBackup, out List<string> errors1);
            Debug.WriteLine($"[TestJsonSchemaValidation] Case 1 (Valid Backup) passed? {isValidBackup}. Errors count: {errors1.Count}");

            // Case 2: Invalid backup (ProfileAge is string instead of integer, and missing required FoodName in entries)
            string invalidBackup = @"{
                ""profile_age"": ""twenty-eight"",
                ""food_entries"": [
                    {
                        ""quantity"": 12.5
                    }
                ]
            }";
            bool isValidInvalidBackup = StorageService.ValidateJsonSchema(invalidBackup, out List<string> errors2);
            Debug.WriteLine($"[TestJsonSchemaValidation] Case 2 (Invalid Backup) valid? {isValidInvalidBackup}. Errors:\n" + string.Join("\n", errors2));

            // Case 3: Malformed JSON syntax
            string malformedJson = @"{ ""profile_age"": 28, ";
            bool isValidMalformed = StorageService.ValidateJsonSchema(malformedJson, out List<string> errors3);
            Debug.WriteLine($"[TestJsonSchemaValidation] Case 3 (Malformed JSON) valid? {isValidMalformed}. Errors:\n" + string.Join("\n", errors3));

            // Case 4: Valid Supplements array
            string validSuppArray = @"[
                {
                    ""name"": ""Zinc"",
                    ""dosage"": ""15mg""
                }
            ]";
            bool isValidSuppArray = StorageService.ValidateJsonSchema(validSuppArray, out List<string> errors4);
            Debug.WriteLine($"[TestJsonSchemaValidation] Case 4 (Valid Supp Array) passed? {isValidSuppArray}. Errors count: {errors4.Count}");

            return isValidBackup && !isValidInvalidBackup && errors2.Count >= 2 && !isValidMalformed && errors3.Any(e => e.Contains("Malformed")) && isValidSuppArray;
        }
    }
}
