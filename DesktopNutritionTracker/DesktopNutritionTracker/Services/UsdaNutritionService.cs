using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Service to search and retrieve comprehensive, normalized nutritional details from the USDA API.
    /// Orchestrates caching and delegates HTTP API interactions to UsdaService.
    /// </summary>
    public static class UsdaNutritionService
    {
        private static readonly UsdaService _usdaService = new UsdaService();

        /// <summary>
        /// Reads custom keys or falls back to standard "DEMO_KEY".
        /// </summary>
        public static string GetActiveApiKey()
        {
            return UsdaService.GetActiveApiKey();
        }

        /// <summary>
        /// Search USDA FoodData Central and return a list of food items with normalized nutritional profiles.
        /// </summary>
        public static async Task<List<UsdaNutrientProfile>> SearchFoodNutritionalProfileAsync(string query)
        {
            var results = new List<UsdaNutrientProfile>();

            try
            {
                string? cachedResponse = DesktopNutritionTracker.Models.NutritionDbContext.GetCachedSearch(query);

                if (cachedResponse != null)
                {
                    DesktopNutritionTracker.Models.NutritionDbContext.LogActivity("API_CACHE_HIT", $"USDA Search Cache hit for query: '{query}'");
                    var root = JObject.Parse(cachedResponse);
                    var foodsArray = root["foods"] as JArray;
                    if (foodsArray != null)
                    {
                        foreach (var item in foodsArray)
                        {
                            var id = item["fdcId"]?.ToString() ?? "";
                            var rawDescription = item["description"]?.ToString() ?? "Unknown Food";
                            var brand = item["brandOwner"]?.ToString();
                            double servingSize = item["servingSize"]?.Value<double>() ?? 100.0;
                            string servingUnit = item["servingSizeUnit"]?.ToString() ?? "g";

                            var rawNutrients = item["foodNutrients"] as JArray;
                            var nutrientMap = new Dictionary<string, double>();

                            if (rawNutrients != null)
                            {
                                foreach (var nut in rawNutrients)
                                {
                                    string name = (nut["nutrientName"]?.ToString() ?? "").ToLower();
                                    string number = nut["nutrientNumber"]?.ToString() ?? "";
                                    double valuePer100g = nut["value"]?.Value<double>() ?? 0.0;

                                    double scale = servingSize > 0 ? servingSize / 100.0 : 1.0;
                                    double valuePerServing = valuePer100g * scale;

                                    string? matchedKey = MapNutrientNameToLocalKey(name, number);
                                    if (matchedKey != null && !double.IsNaN(valuePerServing))
                                    {
                                        if (nutrientMap.ContainsKey(matchedKey))
                                        {
                                            nutrientMap[matchedKey] += valuePerServing;
                                        }
                                        else
                                        {
                                            nutrientMap[matchedKey] = valuePerServing;
                                        }
                                    }
                                }
                            }

                            results.Add(new UsdaNutrientProfile
                            {
                                FdcId = id,
                                Description = FormatRawDescription(rawDescription, brand),
                                BrandOwner = string.IsNullOrEmpty(brand) ? null : brand,
                                ServingSize = servingSize,
                                ServingSizeUnit = servingUnit,
                                Nutrients = nutrientMap
                            });
                        }
                    }
                }
                else
                {
                    var request = new UsdaSearchRequest { Query = query };
                    var response = await _usdaService.SearchAsync(request);

                    if (response.IsSuccess)
                    {
                        results = response.Results;
                        if (!string.IsNullOrEmpty(response.RawJson))
                        {
                            DesktopNutritionTracker.Models.NutritionDbContext.SaveCachedSearch(query, response.RawJson);
                            DesktopNutritionTracker.Models.NutritionDbContext.LogActivity("API_CACHE_MISS", $"USDA Search Cache miss. Saved network results for query: '{query}'");
                        }
                    }
                    else
                    {
                        DesktopNutritionTracker.Models.NutritionDbContext.LogActivity("API_ERROR", $"USDA Search error for query: '{query}'. Message: {response.ErrorMessage}");
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception in UsdaNutritionService.SearchFoodNutritionalProfileAsync: {ex.Message}");
            }

            return results;
        }

        /// <summary>
        /// Retrieves granular, high-fidelity nutrient details for a specific Food object.
        /// </summary>
        public static async Task<UsdaNutrientProfile?> GetFoodDetailsProfileAsync(string fdcId)
        {
            try
            {
                string? cachedResponse = DesktopNutritionTracker.Models.NutritionDbContext.GetCachedDetail(fdcId);

                if (cachedResponse != null)
                {
                    DesktopNutritionTracker.Models.NutritionDbContext.LogActivity("API_CACHE_HIT", $"USDA Detail Cache hit for FdcId: {fdcId}");
                    var foodObj = JObject.Parse(cachedResponse);

                    var id = foodObj["fdcId"]?.ToString() ?? "";
                    var rawDescription = foodObj["description"]?.ToString() ?? "Unknown Food";
                    var brand = foodObj["brandOwner"]?.ToString();
                    double servingSize = foodObj["servingSize"]?.Value<double>() ?? 100.0;
                    string servingUnit = foodObj["servingSizeUnit"]?.ToString() ?? "g";

                    var rawNutrients = foodObj["foodNutrients"] as JArray;
                    var nutrientMap = new Dictionary<string, double>();

                    if (rawNutrients != null)
                    {
                        foreach (var wrapper in rawNutrients)
                        {
                            double valuePer100g = wrapper["amount"]?.Value<double>() ?? 0.0;

                            var nutrientDetail = wrapper["nutrient"] as JObject;
                            if (nutrientDetail != null)
                            {
                                string name = (nutrientDetail["name"]?.ToString() ?? "").ToLower();
                                string number = nutrientDetail["number"]?.ToString() ?? "";

                                double scale = servingSize > 0 ? servingSize / 100.0 : 1.0;
                                double valuePerServing = valuePer100g * scale;

                                string? matchedKey = MapNutrientNameToLocalKey(name, number);
                                if (matchedKey != null && !double.IsNaN(valuePerServing))
                                {
                                    if (nutrientMap.ContainsKey(matchedKey))
                                    {
                                        nutrientMap[matchedKey] += valuePerServing;
                                    }
                                    else
                                    {
                                        nutrientMap[matchedKey] = valuePerServing;
                                    }
                                }
                            }
                        }
                    }

                    return new UsdaNutrientProfile
                    {
                        FdcId = id,
                        Description = FormatRawDescription(rawDescription, brand),
                        BrandOwner = string.IsNullOrEmpty(brand) ? null : brand,
                        ServingSize = servingSize,
                        ServingSizeUnit = servingUnit,
                        Nutrients = nutrientMap
                    };
                }
                else
                {
                    var request = new UsdaDetailsRequest { FdcId = fdcId };
                    var response = await _usdaService.GetDetailsAsync(request);

                    if (response.IsSuccess && response.Profile != null)
                    {
                        if (!string.IsNullOrEmpty(response.RawJson))
                        {
                            DesktopNutritionTracker.Models.NutritionDbContext.SaveCachedDetail(fdcId, response.RawJson);
                            DesktopNutritionTracker.Models.NutritionDbContext.LogActivity("API_CACHE_MISS", $"USDA Detail Cache miss. Saved network results for FdcId: {fdcId}");
                        }
                        return response.Profile;
                    }
                    else
                    {
                        DesktopNutritionTracker.Models.NutritionDbContext.LogActivity("API_ERROR", $"USDA Detail error for FdcId: {fdcId}. Message: {response.ErrorMessage}");
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception in UsdaNutritionService.GetFoodDetailsProfileAsync: {ex.Message}");
            }

            return null;
        }

        private static string? MapNutrientNameToLocalKey(string name, string number)
        {
            if (number == "208" || number == "1008" || name.Contains("energy") || name.Contains("calories")) return "calories";
            if (number == "203" || number == "1003" || name == "protein") return "protein";
            if (number == "205" || number == "1005" || name.Contains("carbohydrate")) return "carbohydrates";
            if (number == "204" || number == "1004" || name.Contains("total lipid") || name == "fat" || name.Contains("total fat")) return "fat";
            if (number == "291" || number == "1079" || name.Contains("fiber")) return "fiber";
            if (number == "269" || number == "2000" || number == "1010" || name.Contains("sugars, total") || name == "sugars" || name == "sugar") return "sugars";
            if (number == "307" || number == "1093" || name.Contains("sodium")) return "sodium";
            if (number == "306" || number == "1092" || name.Contains("potassium")) return "potassium";
            if (number == "301" || number == "1087" || name.Contains("calcium")) return "calcium";
            if (number == "303" || number == "1089" || name.Contains("iron")) return "iron";
            if (number == "304" || number == "1090" || name.Contains("magnesium")) return "magnesium";
            if (number == "309" || number == "1095" || name.Contains("zinc")) return "zinc";
            if (number == "305" || number == "1091" || name.Contains("phosphorus")) return "phosphorus";
            if (name.Contains("copper")) return "copper";
            if (name.Contains("manganese")) return "manganese";
            if (name.Contains("selenium")) return "selenium";
            if (number == "606" || number == "1258" || name.Contains("saturated fat") || name.Contains("fatty acids, total saturated")) return "saturated_fat";
            if (number == "605" || number == "1257" || name.Contains("trans fat") || name.Contains("fatty acids, total trans")) return "trans_fat";
            if (name.Contains("monounsaturated")) return "monounsaturated_fat";
            if (name.Contains("polyunsaturated")) return "polyunsaturated_fat";
            if (number == "601" || number == "1253" || name.Contains("cholesterol")) return "cholesterol";
            if (number == "320" || number == "1104" || name.Contains("vitamin a")) return "vitamin_a";
            if (number == "401" || number == "1162" || name.Contains("vitamin c")) return "vitamin_c";
            if (name.Contains("vitamin d")) return "vitamin_d";
            if (name.Contains("vitamin e")) return "vitamin_e";
            if (name.Contains("vitamin k")) return "vitamin_k";
            if (name.Contains("thiamin")) return "thiamin";
            if (name.Contains("riboflavin")) return "riboflavin";
            if (name.Contains("niacin")) return "niacin";
            if (name.Contains("pantothenic")) return "pantothenic_acid";
            if (name.Contains("vitamin b-6") || name.Contains("vitamin b6")) return "vitamin_b6";
            if (name.Contains("folate") || name.Contains("folic")) return "folate";
            if (name.Contains("vitamin b-12") || name.Contains("vitamin b12")) return "vitamin_b12";
            if (name.Contains("biotin")) return "biotin";
            if (name.Contains("choline")) return "choline";
            if (name.Contains("iodine")) return "iodine";
            if (name.Contains("chromium")) return "chromium";
            if (name.Contains("molybdenum")) return "molybdenum";

            return null;
        }

        private static string FormatRawDescription(string desc, string? brand)
        {
            if (string.IsNullOrWhiteSpace(desc)) return "Unknown Food";

            // Title case formatting
            string[] words = desc.ToLower().Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            for (int i = 0; i < words.Length; i++)
            {
                if (words[i].Length > 0)
                {
                    words[i] = char.ToUpper(words[i][0]) + words[i].Substring(1);
                }
            }
            string cleanDesc = string.Join(" ", words);

            return !string.IsNullOrEmpty(brand) ? $"[{brand}] {cleanDesc}" : cleanDesc;
        }
    }
}
