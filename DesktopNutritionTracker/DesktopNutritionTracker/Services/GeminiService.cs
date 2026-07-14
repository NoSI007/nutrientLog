using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace DesktopNutritionTracker.Services
{
    public class ParsedFoodResult
    {
        [JsonProperty("food_name")]
        public string FoodName { get; set; } = "";

        [JsonProperty("quantity")]
        public double Quantity { get; set; } = 1.0;

        [JsonProperty("unit")]
        public string Unit { get; set; } = "serving";

        [JsonProperty("nutrients")]
        public Dictionary<string, double> Nutrients { get; set; } = new Dictionary<string, double>();
    }

    public class MenuSuggestionResult
    {
        [JsonProperty("food_name")]
        public string FoodName { get; set; } = "";

        [JsonProperty("meal_type")]
        public string MealType { get; set; } = ""; // Breakfast, Lunch, Dinner, Snack

        [JsonProperty("quantity")]
        public double Quantity { get; set; } = 1.0;

        [JsonProperty("unit")]
        public string Unit { get; set; } = "serving";

        [JsonProperty("nutrients")]
        public Dictionary<string, double> Nutrients { get; set; } = new Dictionary<string, double>();
    }

    /// <summary>
    /// Service to encapsulate all Gemini API interactions, requests, and response deserialization/validation.
    /// Highly decoupled, supporting custom client injection and prompt management.
    /// </summary>
    public class GeminiService
    {
        private readonly HttpClient _httpClient;
        private static readonly GeminiService _defaultInstance = new GeminiService();

        public GeminiService(HttpClient? httpClient = null)
        {
            _httpClient = httpClient ?? new HttpClient();
            if (httpClient == null)
            {
                _httpClient.Timeout = TimeSpan.FromSeconds(60);
                _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("DesktopNutritionTracker/1.0");
                _httpClient.DefaultRequestHeaders.Accept.ParseAdd("application/json");
            }
        }

        /// <summary>
        /// Reads active API key from GeminiAuthHandler/Environment/Local Files.
        /// </summary>
        public static string GetActiveApiKey()
        {
            return GeminiAuthHandler.GetApiKey();
        }

        /// <summary>
        /// Prompt templates for AI actions.
        /// </summary>
        public static class Prompts
        {
            public static string GetParseFoodPrompt(string foodInput)
            {
                return $@"
You are a highly precise nutrition-parsing AI.
Your task is to parse a natural language description of a food or meal, estimate its serving portion, and fully analyze its nutritional profile across 41 essential nutrients in our system.

Input description: ""{foodInput}""

You MUST return a JSON object that adheres strictly to the following schema:
{{
  ""food_name"": ""A clean, concise, standardized name representing the food or meal"",
  ""quantity"": estimated serving multiplier count (e.g. 1.0 or 0.5. Defaults to 1.0 if not described),
  ""unit"": ""serving"",
  ""nutrients"": {{
    // Estimated quantitative values for each nutrient using EXACTLY the lowercase keys listed below
  }}
}}

RULES FOR NUTRIENT KEY MAPPINGS:
- Only return values in the exact metrics (grams, mg, mcg, etc.) specified in the nutrient listing below.
- Do not invent new keys. 
- Ensure all numbers are double/floats in standard decimal notation (e.g., 12.5, never scientific string formatting).
- For nutrients not present in the food description, default their value to 0.0.

Allowed Nutrient Keys (all lowercase) and their target units:
- calories (unit: kcal - e.g. 240.0)
- carbohydrates (unit: g)
- protein (unit: g)
- fat (unit: g)
- fiber (unit: g)
- water (unit: ml)
- saturated_fat (unit: g)
- trans_fat (unit: g)
- monounsaturated_fat (unit: g)
- polyunsaturated_fat (unit: g)
- omega3 (unit: g)
- omega6 (unit: g)
- cholesterol (unit: mg)
- vitamin_a (unit: mcg)
- vitamin_c (unit: mg)
- vitamin_d (unit: mcg)
- vitamin_e (unit: mg)
- vitamin_k (unit: mcg)
- thiamin (unit: mg)
- riboflavin (unit: mg)
- niacin (unit: mg)
- pantothenic_acid (unit: mg)
- vitamin_b6 (unit: mg)
- biotin (unit: mcg)
- folate (unit: mcg)
- vitamin_b12 (unit: mcg)
- choline (unit: mg)
- calcium (unit: mg)
- iron (unit: mg)
- magnesium (unit: mg)
- phosphorus (unit: mg)
- potassium (unit: mg)
- sodium (unit: mg)
- zinc (unit: mg)
- copper (unit: mg)
- manganese (unit: mg)
- selenium (unit: mcg)
- chromium (unit: mcg)
- molybdenum (unit: mcg)
- sugars (unit: g)
- iodine (unit: mcg)

Provide ONLY the valid JSON block as your output. Do not wrap it in additional conversational text.
";
            }

            public static string GetSuggestMenuPrompt(string profileDescription, string staplesText, string deficitsText)
            {
                return $@"
You are an expert clinical dietitian and AI menu planning engine.
Your goal is to suggest a customized daily food plan for the user to help them fully satisfy their clinical nutrient targets (RDA).

User Clinical Profile:
{profileDescription}

Daily Staples/Usual Foods (already added to the plan and logged first):
- {staplesText}

Outstanding Clinical Deficiencies/Deficits to satisfy:
- {deficitsText}

You MUST suggest additional meals and/or snacks for the remaining part of the day to satisfy the user's clinical targets.
CRITICAL MANDATES:
1. You MUST suggest at least one healthy, delicious snack (labeled as ""Snack"") specifically designed to address outstanding micronutrient/macronutrient deficits. Focus on unique, nutrient-dense ingredients (e.g. pumpkin seeds for magnesium/zinc, spinach smoothie for folate/iron, or yogurt with walnuts for calcium/omega-3) to make a real clinical difference. Avoid generic answers.
2. The suggested menu items should be realistic, delicious, and aligned with healthy dietary habits.
3. For each food item, calculate the full estimated breakdown for all 41 nutrients in our system. Ensure the values are realistic for the specified portion size.

Return a JSON array of suggested food items. Each item MUST adhere strictly to this schema:
{{
  ""food_name"": ""A clean, appetizing name representing the suggested meal or snack"",
  ""meal_type"": ""Breakfast"", ""Lunch"", ""Dinner"", or ""Snack"",
  ""quantity"": 1.0,
  ""unit"": ""serving"" or a specific unit,
  ""nutrients"": {{
    // Lowercase nutrient keys with estimated values in our standard units (calories in kcal, carbs/protein/fat/fiber in g, sodium/calcium/iron/magnesium in mg, etc.)
  }}
}}

Allowed Nutrient Keys (all lowercase):
- calories (unit: kcal)
- carbohydrates (unit: g)
- protein (unit: g)
- fat (unit: g)
- fiber (unit: g)
- water (unit: ml)
- saturated_fat (unit: g)
- trans_fat (unit: g)
- monounsaturated_fat (unit: g)
- polyunsaturated_fat (unit: g)
- omega3 (unit: g)
- omega6 (unit: g)
- cholesterol (unit: mg)
- vitamin_a (unit: mcg)
- vitamin_c (unit: mg)
- vitamin_d (unit: mcg)
- vitamin_e (unit: mg)
- vitamin_k (unit: mcg)
- thiamin (unit: mg)
- riboflavin (unit: mg)
- niacin (unit: mg)
- pantothenic_acid (unit: mg)
- vitamin_b6 (unit: mg)
- biotin (unit: mcg)
- folate (unit: mcg)
- vitamin_b12 (unit: mcg)
- choline (unit: mg)
- calcium (unit: mg)
- iron (unit: mg)
- magnesium (unit: mg)
- phosphorus (unit: mg)
- potassium (unit: mg)
- sodium (unit: mg)
- zinc (unit: mg)
- copper (unit: mg)
- manganese (unit: mg)
- selenium (unit: mcg)
- chromium (unit: mcg)
- molybdenum (unit: mcg)
- sugars (unit: g)
- iodine (unit: mcg)

Provide ONLY the valid JSON array as your output. Do not wrap it in additional conversational text or markdown formatting.
";
            }
        }

        #region Non-Static Instantiable Methods

        /// <summary>
        /// Parse candidate content from the raw Gemini HTTP JSON response.
        /// </summary>
        public ParsedFoodResult? ParseFoodResponse(string jsonBody)
        {
            var geminiResponse = JsonConvert.DeserializeObject<GeminiResponse>(jsonBody);

            if (geminiResponse?.Candidates != null && geminiResponse.Candidates.Count > 0)
            {
                var candidateText = geminiResponse.Candidates[0].Content.Parts[0].Text;
                
                // Strip markdown backticks if returned (e.g. ```json ... ```)
                string rawJson = candidateText.Trim();
                if (rawJson.StartsWith("```json"))
                {
                    rawJson = rawJson.Substring(7);
                    if (rawJson.EndsWith("```"))
                    {
                        rawJson = rawJson.Substring(0, rawJson.Length - 3);
                    }
                    rawJson = rawJson.Trim();
                }
                else if (rawJson.StartsWith("```"))
                {
                    rawJson = rawJson.Substring(3);
                    if (rawJson.EndsWith("```"))
                    {
                        rawJson = rawJson.Substring(0, rawJson.Length - 3);
                    }
                    rawJson = rawJson.Trim();
                }

                return JsonConvert.DeserializeObject<ParsedFoodResult>(rawJson);
            }

            return null;
        }

        /// <summary>
        /// Parse clinical suggestion array from raw Gemini HTTP JSON response.
        /// </summary>
        public List<MenuSuggestionResult> ParseMenuResponse(string jsonBody)
        {
            var geminiResponse = JsonConvert.DeserializeObject<GeminiResponse>(jsonBody);

            if (geminiResponse?.Candidates != null && geminiResponse.Candidates.Count > 0)
            {
                var candidateText = geminiResponse.Candidates[0].Content.Parts[0].Text;
                
                string rawJson = candidateText.Trim();
                if (rawJson.StartsWith("```json"))
                {
                    rawJson = rawJson.Substring(7);
                    if (rawJson.EndsWith("```"))
                    {
                        rawJson = rawJson.Substring(0, rawJson.Length - 3);
                    }
                    rawJson = rawJson.Trim();
                }
                else if (rawJson.StartsWith("```"))
                {
                    rawJson = rawJson.Substring(3);
                    if (rawJson.EndsWith("```"))
                    {
                        rawJson = rawJson.Substring(0, rawJson.Length - 3);
                    }
                    rawJson = rawJson.Trim();
                }

                var list = JsonConvert.DeserializeObject<List<MenuSuggestionResult>>(rawJson);
                return list ?? new List<MenuSuggestionResult>();
            }

            return new List<MenuSuggestionResult>();
        }

        /// <summary>
        /// Dispatches natural language description parsing to Gemini.
        /// </summary>
        public async Task<ParsedFoodResult?> ParseFoodWithAiAsync(string foodInput)
        {
            string apiKey = GeminiAuthHandler.GetApiKey();
            if (string.IsNullOrEmpty(apiKey) || 
                apiKey == "MY_GEMINI_API_KEY" || 
                apiKey == "GEMINI_API_KEY" || 
                apiKey == "YOUR_GEMINI_API_KEY" || 
                apiKey == "YOUR_API_KEY")
            {
                throw new InvalidOperationException("Gemini API Key is not configured. Please add GEMINI_API_KEY to your environment variables or a .env file.");
            }

            string prompt = Prompts.GetParseFoodPrompt(foodInput);

            var requestBody = new GeminiRequest
            {
                Contents = new List<GeminiContent>
                {
                    new GeminiContent
                    {
                        Parts = new List<GeminiPart> { new GeminiPart { Text = prompt } }
                    }
                },
                GenerationConfig = new GeminiGenerationConfig { ResponseMimeType = "application/json" }
            };

            string jsonRequest = JsonConvert.SerializeObject(requestBody);
            
            HttpResponseMessage response;
            using (var requestMessage = GeminiAuthHandler.CreateRequest("gemini-3.5-flash:generateContent", jsonRequest))
            {
                response = await _httpClient.SendAsync(requestMessage);
            }

            if (!response.IsSuccessStatusCode)
            {
                await HandleErrorResponseAsync(response);
            }

            string jsonResponse = await response.Content.ReadAsStringAsync();
            return ParseFoodResponse(jsonResponse);
        }

        /// <summary>
        /// Formulates a remaining meal and snack list targeting outstanding RDA targets via Gemini.
        /// </summary>
        public async Task<List<MenuSuggestionResult>> SuggestRemainingMenuWithAiAsync(
            string profileDescription, 
            List<string> staplesList, 
            List<string> deficitsList)
        {
            string apiKey = GeminiAuthHandler.GetApiKey();
            if (string.IsNullOrEmpty(apiKey) || 
                apiKey == "MY_GEMINI_API_KEY" || 
                apiKey == "GEMINI_API_KEY" || 
                apiKey == "YOUR_GEMINI_API_KEY" || 
                apiKey == "YOUR_API_KEY")
            {
                throw new InvalidOperationException("Gemini API Key is not configured. Please add GEMINI_API_KEY to your environment variables or a .env file.");
            }

            string staplesText = staplesList.Count > 0 ? string.Join("\n- ", staplesList) : "None (Starting from scratch)";
            string deficitsText = deficitsList.Count > 0 ? string.Join("\n- ", deficitsList) : "None (All RDA clinical targets met or general healthy balance)";

            string prompt = Prompts.GetSuggestMenuPrompt(profileDescription, staplesText, deficitsText);

            var requestBody = new GeminiRequest
            {
                Contents = new List<GeminiContent>
                {
                    new GeminiContent
                    {
                        Parts = new List<GeminiPart> { new GeminiPart { Text = prompt } }
                    }
                },
                GenerationConfig = new GeminiGenerationConfig { ResponseMimeType = "application/json" }
            };

            string jsonRequest = JsonConvert.SerializeObject(requestBody);
            
            HttpResponseMessage response;
            using (var requestMessage = GeminiAuthHandler.CreateRequest("gemini-3.5-flash:generateContent", jsonRequest))
            {
                response = await _httpClient.SendAsync(requestMessage);
            }

            if (!response.IsSuccessStatusCode)
            {
                await HandleErrorResponseAsync(response);
            }

            string jsonResponse = await response.Content.ReadAsStringAsync();
            return ParseMenuResponse(jsonResponse);
        }

        private async Task HandleErrorResponseAsync(HttpResponseMessage response)
        {
            if (response.IsSuccessStatusCode) return;

            string errorBody = "";
            try
            {
                errorBody = await response.Content.ReadAsStringAsync();
            }
            catch {}

            string friendlyMessage = $"Gemini API call failed with status code {response.StatusCode}.";
            try
            {
                if (!string.IsNullOrEmpty(errorBody))
                {
                    var errObj = Newtonsoft.Json.Linq.JObject.Parse(errorBody);
                    var errorToken = errObj["error"];
                    if (errorToken != null)
                    {
                        string apiMessage = errorToken["message"]?.ToString() ?? "";
                        string status = errorToken["status"]?.ToString() ?? "";
                        friendlyMessage = $"{apiMessage} (Status: {status})";
                        
                        if (apiMessage.IndexOf("leaked", StringComparison.OrdinalIgnoreCase) >= 0 || 
                            apiMessage.IndexOf("API key", StringComparison.OrdinalIgnoreCase) >= 0 || 
                            apiMessage.IndexOf("key", StringComparison.OrdinalIgnoreCase) >= 0)
                        {
                            friendlyMessage += "\n\n👉 ACTION REQUIRED: Your active Gemini API key is invalid or has been reported as leaked by Google. Please generate a brand new, secure API key in Google AI Studio (https://aistudio.google.com/app/apikey) and enter it into the Secrets panel in the AI Studio Build interface.";
                        }
                    }
                }
            }
            catch
            {
                if (!string.IsNullOrEmpty(errorBody))
                {
                    friendlyMessage += $" Response: {errorBody}";
                }
            }

            throw new InvalidOperationException(friendlyMessage);
        }

        #endregion

        #region Backward Compatibility Static Wrappers

        /// <summary>
        /// Backward-compatible static wrapper for ParseFoodWithAiAsync.
        /// </summary>
        public static Task<ParsedFoodResult?> ParseFoodWithAiStaticAsync(string foodInput)
        {
            return _defaultInstance.ParseFoodWithAiAsync(foodInput);
        }

        /// <summary>
        /// Backward-compatible static wrapper for SuggestRemainingMenuWithAiAsync.
        /// </summary>
        public static Task<List<MenuSuggestionResult>> SuggestRemainingMenuWithAiStaticAsync(
            string profileDescription, 
            List<string> staplesList, 
            List<string> deficitsList)
        {
            return _defaultInstance.SuggestRemainingMenuWithAiAsync(profileDescription, staplesList, deficitsList);
        }

        #endregion
    }

    #region Gemini Serialization Payload Handlers

    public class GeminiRequest
    {
        [JsonProperty("contents")]
        public List<GeminiContent> Contents { get; set; } = new List<GeminiContent>();

        [JsonProperty("generationConfig")]
        public GeminiGenerationConfig GenerationConfig { get; set; } = new GeminiGenerationConfig();
    }

    public class GeminiContent
    {
        [JsonProperty("parts")]
        public List<GeminiPart> Parts { get; set; } = new List<GeminiPart>();
    }

    public class GeminiPart
    {
        [JsonProperty("text")]
        public string Text { get; set; } = "";
    }

    public class GeminiGenerationConfig
    {
        [JsonProperty("responseMimeType")]
        public string ResponseMimeType { get; set; } = "application/json";
    }

    public class GeminiResponse
    {
        [JsonProperty("candidates")]
        public List<GeminiCandidate> Candidates { get; set; } = new List<GeminiCandidate>();
    }

    public class GeminiCandidate
    {
        [JsonProperty("content")]
        public GeminiContent Content { get; set; } = new GeminiContent();
    }

    #endregion
}
