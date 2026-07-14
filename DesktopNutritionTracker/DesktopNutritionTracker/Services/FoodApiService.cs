using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using DesktopNutritionTracker.Models;
using DesktopNutritionTracker.Repositories;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Service layer that coordinates between the repository pattern (SQLite EF Core) and the external APIs 
    /// (USDA FoodData Central and Nutritionix), managing caching and deserialization.
    /// </summary>
    public class FoodApiService
    {
        private readonly HttpClient _httpClient;
        private readonly IFoodItemRepository _foodItemRepository;

        private const string UsdaSearchUrl = "https://api.nal.usda.gov/fdc/v1/foods/search";
        private const string UsdaDetailUrl = "https://api.nal.usda.gov/fdc/v1/food/";

        private const string NutritionixSearchUrl = "https://trackapi.nutritionix.com/v2/search/instant";
        private const string NutritionixDetailUrl = "https://trackapi.nutritionix.com/v2/search/item";

        public FoodApiService(IFoodItemRepository? foodItemRepository = null, HttpClient? httpClient = null)
        {
            _foodItemRepository = foodItemRepository ?? new FoodItemRepository();
            _httpClient = httpClient ?? new HttpClient();
            if (httpClient == null)
            {
                _httpClient.Timeout = TimeSpan.FromSeconds(20);
                _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("DesktopNutritionTracker/1.0");
                _httpClient.DefaultRequestHeaders.Accept.ParseAdd("application/json");
            }
        }

        #region API Keys Retrievers

        private static string CleanKey(string key)
        {
            if (string.IsNullOrEmpty(key)) return "";
            key = key.Trim();
            if (key.StartsWith("\"") && key.EndsWith("\"") && key.Length >= 2)
            {
                key = key.Substring(1, key.Length - 2).Trim();
            }
            else if (key.StartsWith("'") && key.EndsWith("'") && key.Length >= 2)
            {
                key = key.Substring(1, key.Length - 2).Trim();
            }
            return key;
        }

        public static (string AppId, string ApiKey) GetNutritionixKeys()
        {
            // Try environment variables
            string appId = Environment.GetEnvironmentVariable("NUTRITIONIX_APP_ID") ?? "";
            string apiKey = Environment.GetEnvironmentVariable("NUTRITIONIX_API_KEY") ?? "";

            if (!string.IsNullOrEmpty(appId) && !string.IsNullOrEmpty(apiKey))
            {
                return (CleanKey(appId), CleanKey(apiKey));
            }

            // Check local .env files
            string[] paths = {
                Path.Combine(Directory.GetCurrentDirectory(), ".env"),
                "/.env",
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".env")
            };

            foreach (var path in paths)
            {
                try
                {
                    if (File.Exists(path))
                    {
                        string tempId = "";
                        string tempKey = "";
                        foreach (var line in File.ReadAllLines(path))
                        {
                            var trimmed = line.Trim();
                            if (trimmed.StartsWith("NUTRITIONIX_APP_ID="))
                            {
                                tempId = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                            }
                            else if (trimmed.StartsWith("NUTRITIONIX_API_KEY="))
                            {
                                tempKey = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                            }
                        }
                        if (!string.IsNullOrEmpty(tempId) && !string.IsNullOrEmpty(tempKey))
                        {
                            return (CleanKey(tempId), CleanKey(tempKey));
                        }
                    }
                }
                catch { }
            }

            return ("demo_id", "demo_key");
        }

        #endregion

        #region Search Coordinate

        /// <summary>
        /// Coordinates food searching across local repository (SQLite DB cache) and external APIs.
        /// </summary>
        public async Task<IEnumerable<FoodItem>> SearchFoodsAsync(string query, string provider = "usda")
        {
            if (string.IsNullOrWhiteSpace(query))
                return Enumerable.Empty<FoodItem>();

            query = query.Trim();
            provider = provider.ToLowerInvariant();

            // 1. Try checking the local database for existing FoodItems first (Offline First)
            try
            {
                var localMatches = await _foodItemRepository.SearchByNameAsync(query);
                if (localMatches != null && localMatches.Any())
                {
                    NutritionDbContext.LogActivity("API_COORDINATOR_LOCAL_HIT", $"Found {localMatches.Count()} items in local DB matching: '{query}'");
                    return localMatches;
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error querying local database: {ex.Message}");
            }

            // 2. Fetch from external APIs if not in DB
            var fetchedItems = new List<FoodItem>();

            if (provider == "nutritionix")
            {
                fetchedItems = await FetchNutritionixSearchAsync(query);
            }
            else
            {
                fetchedItems = await FetchUsdaSearchAsync(query);
            }

            // 3. Cache fetched items to local repository asynchronously
            foreach (var item in fetchedItems)
            {
                try
                {
                    if (!await _foodItemRepository.ExistsAsync(item.FdcId))
                    {
                        await _foodItemRepository.AddAsync(item);
                    }
                }
                catch (Exception cacheEx)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to cache food item '{item.Description}': {cacheEx.Message}");
                }
            }

            return fetchedItems;
        }

        /// <summary>
        /// Searches strictly the local SQLite database for food items by keyword, returning partial matches to improve the food entry UX.
        /// </summary>
        public async Task<IEnumerable<FoodItem>> SearchLocalFoodsAsync(string query)
        {
            if (string.IsNullOrWhiteSpace(query))
                return Enumerable.Empty<FoodItem>();

            query = query.Trim();
            try
            {
                var localMatches = await _foodItemRepository.SearchByNameAsync(query);
                if (localMatches != null)
                {
                    NutritionDbContext.LogActivity("API_COORDINATOR_LOCAL_SEARCH", $"Strict local search found {localMatches.Count()} items matching: '{query}'");
                    return localMatches;
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error performing local search: {ex.Message}");
            }

            return Enumerable.Empty<FoodItem>();
        }

        #endregion

        #region Detailed Nutrient Profile Coordinate

        /// <summary>
        /// Retrieves complete detailed food nutrients. Looks up in Repository first; falls back to external APIs on cache miss.
        /// </summary>
        public async Task<FoodItem?> GetFoodDetailsAsync(string fdcId, string provider = "usda")
        {
            if (string.IsNullOrWhiteSpace(fdcId)) return null;

            provider = provider.ToLowerInvariant();

            // 1. Check local repository (SQLite)
            try
            {
                var localItem = await _foodItemRepository.GetByFdcIdAsync(fdcId);
                // Return local item if we have it and the nutrients dictionary is not empty
                if (localItem != null && localItem.Nutrients != null && localItem.Nutrients.Count > 5)
                {
                    NutritionDbContext.LogActivity("API_COORDINATOR_DETAIL_HIT", $"Local database details cache hit for FdcId: {fdcId}");
                    return localItem;
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error checking local DB details: {ex.Message}");
            }

            // 2. Cache miss: Fetch details from API
            FoodItem? detailedItem = null;

            if (provider == "nutritionix" || fdcId.StartsWith("nix_"))
            {
                string nixId = fdcId.StartsWith("nix_") ? fdcId.Substring(4) : fdcId;
                detailedItem = await FetchNutritionixDetailsAsync(nixId);
            }
            else
            {
                detailedItem = await FetchUsdaDetailsAsync(fdcId);
            }

            // 3. Save / Update in repository
            if (detailedItem != null)
            {
                try
                {
                    if (await _foodItemRepository.ExistsAsync(detailedItem.FdcId))
                    {
                        await _foodItemRepository.UpdateAsync(detailedItem);
                    }
                    else
                    {
                        await _foodItemRepository.AddAsync(detailedItem);
                    }
                    NutritionDbContext.LogActivity("API_COORDINATOR_DETAIL_SAVE", $"Successfully saved detailed nutrients for FdcId: {detailedItem.FdcId}");
                }
                catch (Exception dbEx)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to store updated details: {dbEx.Message}");
                }
            }

            return detailedItem;
        }

        #endregion

        #region Private Fetchers & Deserializers: USDA

        private async Task<List<FoodItem>> FetchUsdaSearchAsync(string query)
        {
            var results = new List<FoodItem>();
            try
            {
                var usdaService = new UsdaService(_httpClient);
                string? cachedResponse = NutritionDbContext.GetCachedSearch(query);
                UsdaSearchResponse response;

                if (cachedResponse != null)
                {
                    response = usdaService.ParseSearchResponse(cachedResponse);
                }
                else
                {
                    var request = new UsdaSearchRequest { Query = query };
                    response = await usdaService.SearchAsync(request);
                    if (response.IsSuccess && !string.IsNullOrEmpty(response.RawJson))
                    {
                        NutritionDbContext.SaveCachedSearch(query, response.RawJson);
                    }
                }

                if (response.IsSuccess && response.Results != null)
                {
                    foreach (var profile in response.Results)
                    {
                        double calories = 0, carbs = 0, protein = 0, fat = 0, fiber = 0;
                        profile.Nutrients.TryGetValue("calories", out calories);
                        profile.Nutrients.TryGetValue("carbohydrates", out carbs);
                        profile.Nutrients.TryGetValue("protein", out protein);
                        profile.Nutrients.TryGetValue("fat", out fat);
                        profile.Nutrients.TryGetValue("fiber", out fiber);

                        results.Add(new FoodItem
                        {
                            FdcId = profile.FdcId,
                            Description = profile.Description,
                            BrandOwner = profile.BrandOwner,
                            ServingSize = profile.ServingSize,
                            ServingSizeUnit = profile.ServingSizeUnit,
                            Calories = calories,
                            Carbohydrates = carbs,
                            Protein = protein,
                            Fat = fat,
                            Fiber = fiber,
                            Nutrients = profile.Nutrients
                        });
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception fetching USDA search: {ex.Message}");
            }

            return results;
        }

        private async Task<FoodItem?> FetchUsdaDetailsAsync(string fdcId)
        {
            try
            {
                var usdaService = new UsdaService(_httpClient);
                string? cachedResponse = NutritionDbContext.GetCachedDetail(fdcId);
                UsdaDetailsResponse response;

                if (cachedResponse != null)
                {
                    response = usdaService.ParseDetailsResponse(cachedResponse);
                }
                else
                {
                    var request = new UsdaDetailsRequest { FdcId = fdcId };
                    response = await usdaService.GetDetailsAsync(request);
                    if (response.IsSuccess && !string.IsNullOrEmpty(response.RawJson))
                    {
                        NutritionDbContext.SaveCachedDetail(fdcId, response.RawJson);
                    }
                }

                if (response.IsSuccess && response.Profile != null)
                {
                    var profile = response.Profile;
                    double calories = 0, carbs = 0, protein = 0, fat = 0, fiber = 0;
                    profile.Nutrients.TryGetValue("calories", out calories);
                    profile.Nutrients.TryGetValue("carbohydrates", out carbs);
                    profile.Nutrients.TryGetValue("protein", out protein);
                    profile.Nutrients.TryGetValue("fat", out fat);
                    profile.Nutrients.TryGetValue("fiber", out fiber);

                    return new FoodItem
                    {
                        FdcId = profile.FdcId,
                        Description = profile.Description,
                        BrandOwner = profile.BrandOwner,
                        ServingSize = profile.ServingSize,
                        ServingSizeUnit = profile.ServingSizeUnit,
                        Calories = calories,
                        Carbohydrates = carbs,
                        Protein = protein,
                        Fat = fat,
                        Fiber = fiber,
                        Nutrients = profile.Nutrients
                    };
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception fetching USDA details: {ex.Message}");
            }
            return null;
        }

        #endregion

        #region Private Fetchers & Deserializers: Nutritionix

        private async Task<List<FoodItem>> FetchNutritionixSearchAsync(string query)
        {
            var results = new List<FoodItem>();
            var keys = GetNutritionixKeys();
            if (keys.AppId == "demo_id" || keys.ApiKey == "demo_key")
            {
                System.Diagnostics.Debug.WriteLine("Nutritionix keys not fully configured. Please configure NUTRITIONIX_APP_ID and NUTRITIONIX_API_KEY.");
                return results;
            }

            try
            {
                string body;
                string? cachedResponse = NutritionDbContext.GetCachedSearch("nix_" + query);

                if (cachedResponse != null)
                {
                    body = cachedResponse;
                }
                else
                {
                    string encodedQuery = Uri.EscapeDataString(query);
                    string url = $"{NutritionixSearchUrl}?query={encodedQuery}";

                    using (var request = new HttpRequestMessage(HttpMethod.Get, url))
                    {
                        request.Headers.Add("x-app-id", keys.AppId);
                        request.Headers.Add("x-app-key", keys.ApiKey);

                        var response = await _httpClient.SendAsync(request);
                        if (!response.IsSuccessStatusCode) return results;

                        body = await response.Content.ReadAsStringAsync();
                        NutritionDbContext.SaveCachedSearch("nix_" + query, body);
                    }
                }

                var root = JObject.Parse(body);
                var brandedArray = root["branded"] as JArray;
                if (brandedArray == null) return results;

                foreach (var item in brandedArray)
                {
                    var itemId = item["nix_item_id"]?.ToString();
                    if (string.IsNullOrEmpty(itemId)) continue;

                    var rawDescription = item["food_name"]?.ToString() ?? "Unknown Food";
                    var brand = item["brand_name"]?.ToString();
                    double servingSize = item["serving_weight_grams"]?.Value<double>() ?? 100.0;
                    string servingUnit = "g";

                    double calories = item["nf_calories"]?.Value<double>() ?? 0.0;
                    double carbs = item["nf_total_carbohydrate"]?.Value<double>() ?? 0.0;
                    double protein = item["nf_protein"]?.Value<double>() ?? 0.0;
                    double fat = item["nf_total_fat"]?.Value<double>() ?? 0.0;
                    double fiber = item["nf_dietary_fiber"]?.Value<double>() ?? 0.0;

                    var nutrientMap = new Dictionary<string, double>
                    {
                        { "calories", calories },
                        { "carbohydrates", carbs },
                        { "protein", protein },
                        { "fat", fat },
                        { "fiber", fiber }
                    };

                    results.Add(new FoodItem
                    {
                        FdcId = "nix_" + itemId,
                        Description = FormatDescription(rawDescription, brand),
                        BrandOwner = string.IsNullOrEmpty(brand) ? null : brand,
                        ServingSize = servingSize,
                        ServingSizeUnit = servingUnit,
                        Calories = calories,
                        Carbohydrates = carbs,
                        Protein = protein,
                        Fat = fat,
                        Fiber = fiber,
                        Nutrients = nutrientMap
                    });
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception fetching Nutritionix search: {ex.Message}");
            }

            return results;
        }

        private async Task<FoodItem?> FetchNutritionixDetailsAsync(string nixItemId)
        {
            var keys = GetNutritionixKeys();
            if (keys.AppId == "demo_id" || keys.ApiKey == "demo_key")
            {
                System.Diagnostics.Debug.WriteLine("Nutritionix keys not configured.");
                return null;
            }

            try
            {
                string body;
                string? cachedResponse = NutritionDbContext.GetCachedDetail("nix_" + nixItemId);

                if (cachedResponse != null)
                {
                    body = cachedResponse;
                }
                else
                {
                    string url = $"{NutritionixDetailUrl}?nix_item_id={nixItemId}";

                    using (var request = new HttpRequestMessage(HttpMethod.Get, url))
                    {
                        request.Headers.Add("x-app-id", keys.AppId);
                        request.Headers.Add("x-app-key", keys.ApiKey);

                        var response = await _httpClient.SendAsync(request);
                        if (!response.IsSuccessStatusCode) return null;

                        body = await response.Content.ReadAsStringAsync();
                        NutritionDbContext.SaveCachedDetail("nix_" + nixItemId, body);
                    }
                }

                var root = JObject.Parse(body);
                var foodsArray = root["foods"] as JArray;
                if (foodsArray == null || foodsArray.Count == 0) return null;

                var foodObj = foodsArray[0] as JObject;
                if (foodObj == null) return null;

                var rawDescription = foodObj["food_name"]?.ToString() ?? "Unknown Food";
                var brand = foodObj["brand_name"]?.ToString();
                double servingSize = foodObj["serving_weight_grams"]?.Value<double>() ?? 100.0;
                string servingUnit = "g";

                double calories = foodObj["nf_calories"]?.Value<double>() ?? 0.0;
                double carbs = foodObj["nf_total_carbohydrate"]?.Value<double>() ?? 0.0;
                double protein = foodObj["nf_protein"]?.Value<double>() ?? 0.0;
                double fat = foodObj["nf_total_fat"]?.Value<double>() ?? 0.0;
                double fiber = foodObj["nf_dietary_fiber"]?.Value<double>() ?? 0.0;

                var nutrientMap = new Dictionary<string, double>
                {
                    { "calories", calories },
                    { "carbohydrates", carbs },
                    { "protein", protein },
                    { "fat", fat },
                    { "fiber", fiber }
                };

                // Add granular Nutritionix fields if available
                void MapValue(string nixField, string localKey)
                {
                    double? val = foodObj[nixField]?.Value<double>();
                    if (val.HasValue && !double.IsNaN(val.Value))
                    {
                        nutrientMap[localKey] = val.Value;
                    }
                }

                MapValue("nf_saturated_fat", "saturated_fat");
                MapValue("nf_cholesterol", "cholesterol");
                MapValue("nf_sodium", "sodium");
                MapValue("nf_potassium", "potassium");
                MapValue("nf_sugars", "sugars");

                // Check other extra fields in full nutrients if mapped
                var fullNutrients = foodObj["full_nutrients"] as JArray;
                if (fullNutrients != null)
                {
                    foreach (var nut in fullNutrients)
                    {
                        int attrId = nut["attr_id"]?.Value<int>() ?? 0;
                        double val = nut["value"]?.Value<double>() ?? 0.0;
                        string? mappedKey = MapNutritionixAttrId(attrId);
                        if (mappedKey != null)
                        {
                            nutrientMap[mappedKey] = val;
                        }
                    }
                }

                return new FoodItem
                {
                    FdcId = "nix_" + nixItemId,
                    Description = FormatDescription(rawDescription, brand),
                    BrandOwner = string.IsNullOrEmpty(brand) ? null : brand,
                    ServingSize = servingSize,
                    ServingSizeUnit = servingUnit,
                    Calories = calories,
                    Carbohydrates = carbs,
                    Protein = protein,
                    Fat = fat,
                    Fiber = fiber,
                    Nutrients = nutrientMap
                };
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception fetching Nutritionix details: {ex.Message}");
                return null;
            }
        }

        #endregion

        #region Mappers & Formatter Helper Functions

        private static string? MapNutrientName(string name, string number)
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

        private static string? MapNutritionixAttrId(int attrId)
        {
            // Maps Nutritionix nutrient attribute IDs to local keys
            switch (attrId)
            {
                case 208: return "calories";
                case 203: return "protein";
                case 205: return "carbohydrates";
                case 204: return "fat";
                case 291: return "fiber";
                case 269: return "sugars";
                case 307: return "sodium";
                case 306: return "potassium";
                case 301: return "calcium";
                case 303: return "iron";
                case 304: return "magnesium";
                case 309: return "zinc";
                case 305: return "phosphorus";
                case 606: return "saturated_fat";
                case 605: return "trans_fat";
                case 601: return "cholesterol";
                case 318: return "vitamin_a";
                case 401: return "vitamin_c";
                case 324: return "vitamin_d";
                case 323: return "vitamin_e";
                case 430: return "vitamin_k";
                case 404: return "thiamin";
                case 405: return "riboflavin";
                case 406: return "niacin";
                case 410: return "pantothenic_acid";
                case 415: return "vitamin_b6";
                case 435: return "folate";
                case 418: return "vitamin_b12";
                default: return null;
            }
        }

        private static string FormatDescription(string desc, string? brand)
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

        #endregion
    }
}
