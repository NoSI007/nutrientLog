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

    public static class GeminiService
    {
        private static readonly HttpClient _httpClient;

        static GeminiService()
        {
            _httpClient = new HttpClient();
            _httpClient.Timeout = TimeSpan.FromSeconds(60);
        }

        private static string GetApiKey()
        {
            // 1. Check system environment variables first (in deployment/CI environments)
            string apiKey = Environment.GetEnvironmentVariable("GEMINI_API_KEY") ?? "";
            if (!string.IsNullOrEmpty(apiKey)) return apiKey;

            // 2. Check workspace absolute root level .env file
            try
            {
                string rootEnv = "/.env";
                if (File.Exists(rootEnv))
                {
                    foreach (var line in File.ReadAllLines(rootEnv))
                    {
                        var trimmed = line.Trim();
                        if (trimmed.StartsWith("GEMINI_API_KEY="))
                        {
                            string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                            if (!string.IsNullOrEmpty(val)) return val;
                        }
                    }
                }
            }
            catch {}

            // 3. Search upward from the current application folder (for local Visual Studio developer usage)
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
                            if (trimmed.StartsWith("GEMINI_API_KEY="))
                            {
                                string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                                if (!string.IsNullOrEmpty(val)) return val;
                            }
                        }
                    }
                    dir = Path.GetDirectoryName(dir);
                }
            }
            catch {}

            return "";
        }

        public static async Task<ParsedFoodResult?> ParseFoodWithAiAsync(string foodInput)
        {
            string apiKey = GetApiKey();
            if (string.IsNullOrEmpty(apiKey) || apiKey == "MY_GEMINI_API_KEY")
            {
                throw new InvalidOperationException("Gemini API Key is not configured. Please add GEMINI_API_KEY to your environment variables or a .env file.");
            }

            string url = $"https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key={apiKey}";

            string prompt = $@"
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
            var content = new StringContent(jsonRequest, Encoding.UTF8, "application/json");

            var response = await _httpClient.PostAsync(url, content);
            response.EnsureSuccessStatusCode();

            string jsonResponse = await response.Content.ReadAsStringAsync();
            var geminiResponse = JsonConvert.DeserializeObject<GeminiResponse>(jsonResponse);

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

                var result = JsonConvert.DeserializeObject<ParsedFoodResult>(rawJson);
                return result;
            }

            return null;
        }
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
