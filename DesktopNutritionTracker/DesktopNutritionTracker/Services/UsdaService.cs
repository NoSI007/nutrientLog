using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace DesktopNutritionTracker.Services
{
    public class UsdaNutrientProfile
    {
        [JsonProperty("fdcId")]
        public string FdcId { get; set; } = "";

        [JsonProperty("description")]
        public string Description { get; set; } = "";

        [JsonProperty("brandOwner")]
        public string? BrandOwner { get; set; }

        [JsonProperty("servingSize")]
        public double ServingSize { get; set; } = 100.0;

        [JsonProperty("servingSizeUnit")]
        public string ServingSizeUnit { get; set; } = "g";

        [JsonProperty("nutrients")]
        public Dictionary<string, double> Nutrients { get; set; } = new Dictionary<string, double>();
    }

    public class UsdaSearchRequest
    {
        public string Query { get; set; } = "";
        public int PageSize { get; set; } = 15;
        public string? ApiKey { get; set; }
    }

    public class UsdaSearchResponse
    {
        public List<UsdaNutrientProfile> Results { get; set; } = new List<UsdaNutrientProfile>();
        public bool IsSuccess { get; set; }
        public string? ErrorMessage { get; set; }
        public string? RawJson { get; set; }
    }

    public class UsdaDetailsRequest
    {
        public string FdcId { get; set; } = "";
        public string? ApiKey { get; set; }
    }

    public class UsdaDetailsResponse
    {
        public UsdaNutrientProfile? Profile { get; set; }
        public bool IsSuccess { get; set; }
        public string? ErrorMessage { get; set; }
        public string? RawJson { get; set; }
    }

    /// <summary>
    /// Service to directly perform USDA FoodData Central HTTP API queries.
    /// Decoupled from database, EF Core, caching, and UI concerns.
    /// </summary>
    public class UsdaService
    {
        private readonly HttpClient _httpClient;
        private const string UsdaSearchUrl = "https://api.nal.usda.gov/fdc/v1/foods/search";
        private const string UsdaDetailUrl = "https://api.nal.usda.gov/fdc/v1/food/";

        public UsdaService(HttpClient? httpClient = null)
        {
            _httpClient = httpClient ?? new HttpClient();
            if (httpClient == null)
            {
                _httpClient.Timeout = TimeSpan.FromSeconds(20);
                _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("DesktopNutritionTracker/1.0");
                _httpClient.DefaultRequestHeaders.Accept.ParseAdd("application/json");
            }
        }

        private static string CleanApiKey(string key)
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

        public static string GetActiveApiKey()
        {
            // 1. Check system environment variables first
            string apiKey = Environment.GetEnvironmentVariable("USDA_API_KEY") ?? "";
            if (!string.IsNullOrEmpty(apiKey)) return CleanApiKey(apiKey);

            // 2. Check workspace/current directory .env file
            try
            {
                string currentEnv = Path.Combine(Directory.GetCurrentDirectory(), ".env");
                if (File.Exists(currentEnv))
                {
                    foreach (var line in File.ReadAllLines(currentEnv))
                    {
                        var trimmed = line.Trim();
                        if (trimmed.StartsWith("USDA_API_KEY="))
                        {
                            string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                            if (!string.IsNullOrEmpty(val)) return CleanApiKey(val);
                        }
                    }
                }
            }
            catch {}

            // 3. Check workspace absolute root level .env file
            try
            {
                string rootEnv = "/.env";
                if (File.Exists(rootEnv))
                {
                    foreach (var line in File.ReadAllLines(rootEnv))
                    {
                        var trimmed = line.Trim();
                        if (trimmed.StartsWith("USDA_API_KEY="))
                        {
                            string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                            if (!string.IsNullOrEmpty(val)) return CleanApiKey(val);
                        }
                    }
                }
            }
            catch { }

            // 4. Search upward from the current application folder (for local Visual Studio developer usage)
            try
            {
                string dir = AppDomain.CurrentDomain.BaseDirectory;
                while (!string.IsNullOrEmpty(dir))
                {
                    string envFile = Path.Combine(dir, ".env");
                    if (File.Exists(envFile))
                    {
                        foreach (var line in File.ReadAllLines(envFile))
                        {
                            var trimmed = line.Trim();
                            if (trimmed.StartsWith("USDA_API_KEY="))
                            {
                                string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                                if (!string.IsNullOrEmpty(val)) return CleanApiKey(val);
                            }
                        }
                    }
                    dir = Path.GetDirectoryName(dir);
                }
            }
            catch { }

            return "DEMO_KEY";
        }

        public UsdaSearchResponse ParseSearchResponse(string jsonBody)
        {
            var responseObj = new UsdaSearchResponse { RawJson = jsonBody, IsSuccess = true };
            try
            {
                var root = JObject.Parse(jsonBody);
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

                        responseObj.Results.Add(new UsdaNutrientProfile
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
            catch (Exception ex)
            {
                responseObj.IsSuccess = false;
                responseObj.ErrorMessage = ex.Message;
            }
            return responseObj;
        }

        public UsdaDetailsResponse ParseDetailsResponse(string jsonBody)
        {
            var responseObj = new UsdaDetailsResponse { RawJson = jsonBody, IsSuccess = true };
            try
            {
                var foodObj = JObject.Parse(jsonBody);

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

                responseObj.Profile = new UsdaNutrientProfile
                {
                    FdcId = id,
                    Description = FormatRawDescription(rawDescription, brand),
                    BrandOwner = string.IsNullOrEmpty(brand) ? null : brand,
                    ServingSize = servingSize,
                    ServingSizeUnit = servingUnit,
                    Nutrients = nutrientMap
                };
            }
            catch (Exception ex)
            {
                responseObj.IsSuccess = false;
                responseObj.ErrorMessage = ex.Message;
            }
            return responseObj;
        }

        public async Task<UsdaSearchResponse> SearchAsync(UsdaSearchRequest request)
        {
            if (request == null || string.IsNullOrWhiteSpace(request.Query))
            {
                return new UsdaSearchResponse
                {
                    IsSuccess = false,
                    ErrorMessage = "Search query is empty."
                };
            }

            string activeKey = request.ApiKey ?? GetActiveApiKey();

            try
            {
                string encodedQuery = Uri.EscapeDataString(request.Query);
                string url = $"{UsdaSearchUrl}?query={encodedQuery}&api_key={activeKey}&pageSize={request.PageSize}";

                var httpRequest = new HttpRequestMessage(HttpMethod.Get, url);
                httpRequest.Headers.Add("X-Api-Key", activeKey);
                var httpResponse = await _httpClient.SendAsync(httpRequest);

                if (httpResponse.StatusCode == System.Net.HttpStatusCode.Forbidden && activeKey != "DEMO_KEY")
                {
                    System.Diagnostics.Debug.WriteLine("USDA search returned 403. Retrying with DEMO_KEY...");
                    activeKey = "DEMO_KEY";
                    url = $"{UsdaSearchUrl}?query={encodedQuery}&api_key={activeKey}&pageSize={request.PageSize}";
                    httpRequest = new HttpRequestMessage(HttpMethod.Get, url);
                    httpRequest.Headers.Add("X-Api-Key", activeKey);
                    httpResponse = await _httpClient.SendAsync(httpRequest);
                }

                if (!httpResponse.IsSuccessStatusCode)
                {
                    return new UsdaSearchResponse
                    {
                        IsSuccess = false,
                        ErrorMessage = $"USDA search failed with status code: {httpResponse.StatusCode}"
                    };
                }

                string body = await httpResponse.Content.ReadAsStringAsync();
                return ParseSearchResponse(body);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception in UsdaService.SearchAsync: {ex.Message}");
                return new UsdaSearchResponse
                {
                    IsSuccess = false,
                    ErrorMessage = ex.Message
                };
            }
        }

        public async Task<UsdaDetailsResponse> GetDetailsAsync(UsdaDetailsRequest request)
        {
            if (request == null || string.IsNullOrWhiteSpace(request.FdcId))
            {
                return new UsdaDetailsResponse
                {
                    IsSuccess = false,
                    ErrorMessage = "FdcId is empty."
                };
            }

            string activeKey = request.ApiKey ?? GetActiveApiKey();

            try
            {
                string url = $"{UsdaDetailUrl}{request.FdcId}?api_key={activeKey}";
                var httpRequest = new HttpRequestMessage(HttpMethod.Get, url);
                httpRequest.Headers.Add("X-Api-Key", activeKey);
                var httpResponse = await _httpClient.SendAsync(httpRequest);

                if (httpResponse.StatusCode == System.Net.HttpStatusCode.Forbidden && activeKey != "DEMO_KEY")
                {
                    System.Diagnostics.Debug.WriteLine("USDA details returned 403. Retrying with DEMO_KEY...");
                    activeKey = "DEMO_KEY";
                    url = $"{UsdaDetailUrl}{request.FdcId}?api_key={activeKey}";
                    httpRequest = new HttpRequestMessage(HttpMethod.Get, url);
                    httpRequest.Headers.Add("X-Api-Key", activeKey);
                    httpResponse = await _httpClient.SendAsync(httpRequest);
                }

                if (!httpResponse.IsSuccessStatusCode)
                {
                    return new UsdaDetailsResponse
                    {
                        IsSuccess = false,
                        ErrorMessage = $"USDA details failed with status code: {httpResponse.StatusCode}"
                    };
                }

                string body = await httpResponse.Content.ReadAsStringAsync();
                return ParseDetailsResponse(body);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Exception in UsdaService.GetDetailsAsync: {ex.Message}");
                return new UsdaDetailsResponse
                {
                    IsSuccess = false,
                    ErrorMessage = ex.Message
                };
            }
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
