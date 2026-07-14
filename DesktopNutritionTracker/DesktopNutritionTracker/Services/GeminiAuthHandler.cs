using System;
using System.IO;
using System.Net.Http;
using System.Text;

namespace DesktopNutritionTracker.Services
{
    public static class GeminiAuthHandler
    {
        private static string CleanApiKey(string key)
        {
            if (string.IsNullOrEmpty(key)) return "";
            key = key.Trim();
            if (key.StartsWith("\"") && key.EndsWith("\"") && key.Length >= 2)
            {
                key = key.Substring(1, key.Length - 2).Trim();
            }
            return key;
        }

        public static string GetApiKey()
        {
            // 1. Check system environment variables first
            string apiKey = Environment.GetEnvironmentVariable("GEMINI_API_KEY") ?? "";
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
                        if (trimmed.StartsWith("GEMINI_API_KEY="))
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
                        if (trimmed.StartsWith("GEMINI_API_KEY="))
                        {
                            string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                            if (!string.IsNullOrEmpty(val)) return CleanApiKey(val);
                        }
                    }
                }
            }
            catch {}

            // 4. Search upward from the current application folder
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
                                if (!string.IsNullOrEmpty(val)) return CleanApiKey(val);
                            }
                        }
                    }
                    dir = Path.GetDirectoryName(dir);
                }
            }
            catch {}

            return "";
        }

        public static HttpRequestMessage CreateRequest(string modelAndAction, string jsonBody)
        {
            string apiKey = GetApiKey();
            string url = $"https://generativelanguage.googleapis.com/v1beta/models/{modelAndAction}?key={apiKey}";

            var requestMessage = new HttpRequestMessage(HttpMethod.Post, url);
            requestMessage.Content = new StringContent(jsonBody, Encoding.UTF8, "application/json");

            // Inject standard headers
            requestMessage.Headers.Add("User-Agent", "NutritionTracker/1.0 (Desktop; C#)");
            requestMessage.Headers.Add("Accept", "application/json");

            if (!string.IsNullOrEmpty(apiKey) && 
                apiKey != "MY_GEMINI_API_KEY" && 
                apiKey != "GEMINI_API_KEY" && 
                apiKey != "YOUR_GEMINI_API_KEY" && 
                apiKey != "YOUR_API_KEY")
            {
                requestMessage.Headers.Add("x-goog-api-key", apiKey);
            }

            return requestMessage;
        }
    }
}
