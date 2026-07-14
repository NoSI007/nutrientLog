using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using System.Linq;
using DesktopNutritionTracker.Models;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Service to handle data export/import routines, cloud synchronization, and schema validation.
    /// Separates these utility operations from primary local storage and data-fetching services.
    /// </summary>
    public class BackupService
    {
        private readonly ILocalStorageService _localStorage;
        private readonly HttpClient _httpClient;

        public BackupService(ILocalStorageService? localStorage = null, HttpClient? httpClient = null)
        {
            _localStorage = localStorage ?? new LocalStorageService();
            _httpClient = httpClient ?? new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        }

        #region Firestore Cloud Synchronization

        /// <summary>
        /// Synchronizes data from Firestore backup locally (offline-first restore on first run).
        /// </summary>
        public void SyncFromFirestore()
        {
            try
            {
                var apiKey = GeminiAuthHandler.GetApiKey();
                var projectId = GetFirestoreProjectId();

                if (string.IsNullOrEmpty(apiKey) || apiKey.StartsWith("MY_") || apiKey.StartsWith("YOUR_"))
                {
                    System.Diagnostics.Debug.WriteLine("Firestore Sync: API Key is missing or placeholder.");
                    return;
                }

                var url = $"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/desktop_backup/primary_data?key={apiKey}";
                using (var client = new HttpClient())
                {
                    client.Timeout = TimeSpan.FromSeconds(10);
                    var response = client.GetAsync(url).GetAwaiter().GetResult();
                    if (response.IsSuccessStatusCode)
                    {
                        var responseBody = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                        var doc = JObject.Parse(responseBody);
                        string serializedData = doc["fields"]?["serialized_data"]?["stringValue"]?.ToString();
                        if (!string.IsNullOrEmpty(serializedData))
                        {
                            var firestoreData = JsonConvert.DeserializeObject<StorageData>(serializedData);
                            if (firestoreData != null)
                            {
                                System.Diagnostics.Debug.WriteLine($"Firestore Sync: Successfully loaded state from Firestore.");
                                _localStorage.SaveLocal(firestoreData);
                            }
                        }
                    }
                    else
                    {
                        System.Diagnostics.Debug.WriteLine($"Firestore Sync: No existing backup or error: {response.StatusCode}");
                    }
                }
            }
            catch (Exception ex)
            {
                LogError("SyncFromFirestore", ex);
                System.Diagnostics.Debug.WriteLine($"Firestore Sync: Error loading from Firestore: {ex.Message}");
            }
        }

        /// <summary>
        /// Asynchronously synchronizes local data to Firestore backup.
        /// </summary>
        public void SyncToFirestore(StorageData data)
        {
            Task.Run(async () =>
            {
                try
                {
                    var apiKey = GeminiAuthHandler.GetApiKey();
                    var projectId = GetFirestoreProjectId();

                    if (string.IsNullOrEmpty(apiKey) || apiKey.StartsWith("MY_") || apiKey.StartsWith("YOUR_"))
                    {
                        return;
                    }

                    var url = $"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/desktop_backup/primary_data?key={apiKey}";
                    
                    var serialized = JsonConvert.SerializeObject(data);
                    var payload = new
                    {
                        fields = new
                        {
                            serialized_data = new
                            {
                                stringValue = serialized
                            }
                        }
                    };

                    var jsonPayload = JsonConvert.SerializeObject(payload);
                    using (var client = new HttpClient())
                    {
                        client.Timeout = TimeSpan.FromSeconds(15);
                        var content = new StringContent(jsonPayload, Encoding.UTF8, "application/json");
                        var method = new HttpMethod("PATCH");
                        var request = new HttpRequestMessage(method, url) { Content = content };
                        var response = await client.SendAsync(request);
                        if (response.IsSuccessStatusCode)
                        {
                            System.Diagnostics.Debug.WriteLine("Firestore Sync: Successfully saved state to Firestore.");
                            _localStorage.HasPendingSync = false; // Successfully synced
                        }
                        else
                        {
                            var errorDetails = await response.Content.ReadAsStringAsync();
                            System.Diagnostics.Debug.WriteLine($"Firestore Sync: Failed to save state. Status: {response.StatusCode}, Details: {errorDetails}");
                            _localStorage.HasPendingSync = true;
                        }
                    }
                }
                catch (Exception ex)
                {
                    LogError("SyncToFirestore", ex);
                    System.Diagnostics.Debug.WriteLine($"Firestore Sync: Error saving to Firestore: {ex.Message}");
                    _localStorage.HasPendingSync = true;
                }
            });
        }

        /// <summary>
        /// Retrieves the raw Firestore backup data asynchronously.
        /// </summary>
        public async Task<StorageData> GetFirestoreBackupDataAsync()
        {
            var apiKey = GeminiAuthHandler.GetApiKey();
            var projectId = GetFirestoreProjectId();

            if (string.IsNullOrEmpty(apiKey) || apiKey.StartsWith("MY_") || apiKey.StartsWith("YOUR_"))
            {
                throw new InvalidOperationException("Firestore API Key is missing or placeholder in environment configuration.");
            }

            var url = $"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/desktop_backup/primary_data?key={apiKey}";
            using (var client = new HttpClient())
            {
                client.Timeout = TimeSpan.FromSeconds(15);
                var response = await client.GetAsync(url);
                if (!response.IsSuccessStatusCode)
                {
                    var errorDetails = await response.Content.ReadAsStringAsync();
                    throw new Exception($"Failed to retrieve Firestore backup. Status: {response.StatusCode}, Details: {errorDetails}");
                }

                var responseBody = await response.Content.ReadAsStringAsync();
                var doc = JObject.Parse(responseBody);
                string serializedData = doc["fields"]?["serialized_data"]?["stringValue"]?.ToString();
                if (string.IsNullOrEmpty(serializedData))
                {
                    throw new Exception("No serialized data was found in the Firestore backup document.");
                }

                var firestoreData = JsonConvert.DeserializeObject<StorageData>(serializedData);
                if (firestoreData == null)
                {
                    throw new Exception("The data in Firestore backup is invalid or could not be parsed.");
                }
                return firestoreData;
            }
        }

        /// <summary>
        /// Saves the data explicitly to Firestore backup asynchronously.
        /// </summary>
        public async Task SaveToFirestoreAsync(StorageData data)
        {
            var apiKey = GeminiAuthHandler.GetApiKey();
            var projectId = GetFirestoreProjectId();

            if (string.IsNullOrEmpty(apiKey) || apiKey.StartsWith("MY_") || apiKey.StartsWith("YOUR_"))
            {
                throw new InvalidOperationException("Firestore API Key is missing or placeholder in environment configuration.");
            }

            var url = $"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/desktop_backup/primary_data?key={apiKey}";

            var serialized = JsonConvert.SerializeObject(data);
            var payload = new
            {
                fields = new
                {
                    serialized_data = new
                    {
                        stringValue = serialized
                    }
                }
            };

            var jsonPayload = JsonConvert.SerializeObject(payload);
            using (var client = new HttpClient())
            {
                client.Timeout = TimeSpan.FromSeconds(20);
                var content = new StringContent(jsonPayload, Encoding.UTF8, "application/json");
                var method = new HttpMethod("PATCH");
                var request = new HttpRequestMessage(method, url) { Content = content };
                var response = await client.SendAsync(request);
                if (!response.IsSuccessStatusCode)
                {
                    var errorDetails = await response.Content.ReadAsStringAsync();
                    throw new Exception($"Failed to save to Firestore. Status: {response.StatusCode}, Details: {errorDetails}");
                }
            }
        }

        /// <summary>
        /// Resolves Firestore Project ID from environment or environment files.
        /// </summary>
        public string GetFirestoreProjectId()
        {
            string projectId = Environment.GetEnvironmentVariable("FIRESTORE_PROJECT_ID") ?? "";
            if (!string.IsNullOrEmpty(projectId)) return projectId.Trim();

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
                        foreach (var line in File.ReadAllLines(path))
                        {
                            var trimmed = line.Trim();
                            if (trimmed.StartsWith("FIRESTORE_PROJECT_ID="))
                            {
                                string val = trimmed.Split(new[] { '=' }, 2)[1].Trim();
                                if (!string.IsNullOrEmpty(val)) return val.Trim();
                            }
                        }
                    }
                }
                catch {}
            }
            return "ais-europe-west2-627d72ece55f4";
        }

        #endregion

        #region Import and Export Routines

        /// <summary>
        /// Deserializes a raw JSON string into a structured StorageData container.
        /// </summary>
        public static StorageData? DeserializeStorageData(string rawJson)
        {
            if (string.IsNullOrEmpty(rawJson)) return null;
            string normalizedJson = NormalizeJsonKeys(rawJson);
            return JsonConvert.DeserializeObject<StorageData>(normalizedJson);
        }

        /// <summary>
        /// Processes raw imported JSON payload, validates schemas, logs diagnostics, and writes local cache database.
        /// </summary>
        public StorageData? ImportData(string rawJson)
        {
            var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NutriScribe");
            Directory.CreateDirectory(appData);
            var diagnosticLogPath = Path.Combine(appData, "import_diagnostic_log.txt");

            StringBuilder logBuilder = new StringBuilder();
            logBuilder.AppendLine("==================================================");
            logBuilder.AppendLine($"IMPORT DATA DIAGNOSTIC LOG - {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
            logBuilder.AppendLine("==================================================");
            logBuilder.AppendLine($"Incoming JSON string length: {rawJson?.Length ?? 0} characters.");
            logBuilder.AppendLine("Incoming JSON Content Preview:");
            if (rawJson != null)
            {
                logBuilder.AppendLine(rawJson.Length > 5000 ? rawJson.Substring(0, 5000) + "\n... [TRUNCATED due to length]" : rawJson);
            }
            logBuilder.AppendLine("--------------------------------------------------");

            // Execute schema validation phase before attempting any deserialization or db write
            logBuilder.AppendLine("[Validation Phase] Running JSON Schema Validator...");
            if (!ValidateJsonSchema(rawJson, out List<string> validationErrors))
            {
                string errMsg = "JSON Schema Validation Failed:\n" + string.Join("\n", validationErrors);
                logBuilder.AppendLine($"[Validation Phase] FAILED with errors:\n{errMsg}");
                File.WriteAllText(diagnosticLogPath, logBuilder.ToString());
                throw new InvalidOperationException(errMsg);
            }
            logBuilder.AppendLine("[Validation Phase] SUCCESS: JSON structure matches expected schema rules.");
            logBuilder.AppendLine("--------------------------------------------------");

            StorageData? importedData = null;
            try
            {
                logBuilder.AppendLine("[Deserialization Phase] Normalizing JSON keys to PascalCase...");
                string normalizedJson = NormalizeJsonKeys(rawJson);
                logBuilder.AppendLine("Normalized JSON Content Preview:");
                logBuilder.AppendLine(normalizedJson.Length > 5000 ? normalizedJson.Substring(0, 5000) + "\n... [TRUNCATED due to length]" : normalizedJson);
                logBuilder.AppendLine("--------------------------------------------------");

                logBuilder.AppendLine("[Deserialization Phase] Deserializing JSON string into StorageData...");
                importedData = JsonConvert.DeserializeObject<StorageData>(normalizedJson);

                if (importedData == null)
                {
                    logBuilder.AppendLine("[Deserialization Phase] WARNING: Deserialized StorageData is NULL.");
                }
                else
                {
                    logBuilder.AppendLine("[Deserialization Phase] SUCCESS: StorageData deserialized successfully.");
                    logBuilder.AppendLine($"  ProfileAge: {importedData.ProfileAge}");
                    logBuilder.AppendLine($"  ProfileSex: {importedData.ProfileSex}");
                    logBuilder.AppendLine($"  ProfileActivity: {importedData.ProfileActivity}");
                    logBuilder.AppendLine($"  ObservationDaysLimit: {importedData.ObservationDaysLimit}");
                    logBuilder.AppendLine($"  ActiveProfileName: {importedData.ActiveProfileName}");
                    logBuilder.AppendLine($"  ServingUnit: {importedData.ServingUnit}");
                    logBuilder.AppendLine($"  FoodEntries Count: {importedData.FoodEntries?.Count ?? 0}");
                    logBuilder.AppendLine($"  Supplements Count: {importedData.Supplements?.Count ?? 0}");
                    logBuilder.AppendLine($"  CustomRdaOverrides Count: {importedData.CustomRdaOverrides?.Count ?? 0}");
                    logBuilder.AppendLine($"  RecurringFoods Count: {importedData.RecurringFoods?.Count ?? 0}");
                }
            }
            catch (Exception ex)
            {
                logBuilder.AppendLine($"[Deserialization Phase] FAILED with error: {ex.Message}");
                logBuilder.AppendLine($"Stack Trace: {ex.StackTrace}");
                File.WriteAllText(diagnosticLogPath, logBuilder.ToString());
                throw;
            }

            if (importedData != null)
            {
                try
                {
                    logBuilder.AppendLine("--------------------------------------------------");
                    logBuilder.AppendLine("[Database Write Phase] Starting write to local SQLite database...");
                    _localStorage.SaveLocal(importedData);
                    logBuilder.AppendLine("[Database Write Phase] SUCCESS: Local SQLite database save completed successfully.");
                }
                catch (Exception dbEx)
                {
                    logBuilder.AppendLine($"[Database Write Phase] FAILED with error: {dbEx.Message}");
                    logBuilder.AppendLine($"Stack Trace: {dbEx.StackTrace}");
                    File.WriteAllText(diagnosticLogPath, logBuilder.ToString());
                    throw;
                }
            }

            logBuilder.AppendLine("--------------------------------------------------");
            logBuilder.AppendLine("IMPORT PROCESS COMPLETED.");
            File.WriteAllText(diagnosticLogPath, logBuilder.ToString());
            return importedData;
        }

        #endregion

        #region JSON Key Normalization to PascalCase

        public static string NormalizeJsonKeys(string json)
        {
            try
            {
                var token = JToken.Parse(json);
                var normalizedToken = NormalizeJsonKeysToPascalCase(token, "");
                return normalizedToken.ToString(Formatting.None);
            }
            catch
            {
                return json; // Fallback to original string if parsing fails
            }
        }

        private static JToken NormalizeJsonKeysToPascalCase(JToken token, string parentPropertyName)
        {
            if (token is JObject obj)
            {
                var newObj = new JObject();
                foreach (var prop in obj.Properties())
                {
                    bool preserveKey = parentPropertyName.Equals("Nutrients", StringComparison.OrdinalIgnoreCase) ||
                                       parentPropertyName.Equals("CustomRdaOverrides", StringComparison.OrdinalIgnoreCase) ||
                                       parentPropertyName.Equals("Targets", StringComparison.OrdinalIgnoreCase);
                    
                    string newKey = preserveKey ? prop.Name : ToPascalCase(prop.Name);
                    newObj[newKey] = NormalizeJsonKeysToPascalCase(prop.Value, prop.Name);
                }
                return newObj;
            }
            else if (token is JArray arr)
            {
                var newArr = new JArray();
                foreach (var item in arr)
                {
                    newArr.Add(NormalizeJsonKeysToPascalCase(item, parentPropertyName));
                }
                return newArr;
            }
            return token;
        }

        private static string ToPascalCase(string input)
        {
            if (string.IsNullOrEmpty(input)) return input;
            
            var sb = new StringBuilder();
            bool capitalizeNext = true;
            foreach (char c in input)
            {
                if (c == '_')
                {
                    capitalizeNext = true;
                }
                else if (capitalizeNext)
                {
                    sb.Append(char.ToUpper(c));
                    capitalizeNext = false;
                }
                else
                {
                    sb.Append(c);
                }
            }
            return sb.ToString();
        }

        #endregion

        #region Schema Validation Logic

        /// <summary>
        /// Validates user-imported JSON payloads against schema expectations (object and array styles).
        /// </summary>
        public static bool ValidateJsonSchema(string rawJson, out List<string> errors)
        {
            errors = new List<string>();
            if (string.IsNullOrEmpty(rawJson))
            {
                errors.Add("JSON content is empty or null.");
                return false;
            }

            try
            {
                var token = JToken.Parse(rawJson);
                if (token is JObject obj)
                {
                    ValidateStorageDataObject(obj, errors, "Root");
                }
                else if (token is JArray arr)
                {
                    if (arr.Count == 0)
                    {
                        return true;
                    }

                    var firstItem = arr.First;
                    if (firstItem is JObject itemObj)
                    {
                        bool hasFoodKeys = GetPropertyIgnoreCase(itemObj, "FoodName") != null || GetPropertyIgnoreCase(itemObj, "food_name") != null;
                        bool hasSuppKeys = GetPropertyIgnoreCase(itemObj, "dosage") != null || GetPropertyIgnoreCase(itemObj, "Dosage") != null || GetPropertyIgnoreCase(itemObj, "frequency") != null || GetPropertyIgnoreCase(itemObj, "Frequency") != null;

                        if (hasFoodKeys)
                        {
                            int index = 0;
                            foreach (var item in arr)
                            {
                                if (item is JObject entryObj)
                                {
                                    ValidateFoodLogEntryObject(entryObj, errors, $"Array[{index}]");
                                }
                                else
                                {
                                    errors.Add($"Array element at index {index} is not a valid JSON object.");
                                }
                                index++;
                            }
                        }
                        else if (hasSuppKeys || GetPropertyIgnoreCase(itemObj, "Name") != null || GetPropertyIgnoreCase(itemObj, "name") != null)
                        {
                            int index = 0;
                            foreach (var item in arr)
                            {
                                if (item is JObject entryObj)
                                {
                                    ValidateSupplementObject(entryObj, errors, $"Array[{index}]");
                                }
                                else
                                {
                                    errors.Add($"Array element at index {index} is not a valid JSON object.");
                                }
                                index++;
                            }
                        }
                        else
                        {
                            errors.Add("JSON array elements must be either FoodLogEntries (with 'food_name') or Supplements (with 'name' or 'dosage').");
                        }
                    }
                    else
                    {
                        errors.Add("JSON array must contain valid JSON objects.");
                    }
                }
                else
                {
                    errors.Add("JSON must be either a JSON Object ({...}) or a JSON Array ([...]).");
                }
            }
            catch (JsonReaderException jrEx)
            {
                errors.Add($"Malformed JSON structure: {jrEx.Message} at line {jrEx.LineNumber}, position {jrEx.LinePosition}.");
            }
            catch (Exception ex)
            {
                errors.Add($"Parsing failed with unexpected error: {ex.Message}");
            }

            return errors.Count == 0;
        }

        private static JProperty? GetPropertyIgnoreCase(JObject obj, string name)
        {
            string cleanTarget = name.Replace("_", "");
            foreach (var prop in obj.Properties())
            {
                string cleanProp = prop.Name.Replace("_", "");
                if (string.Equals(cleanProp, cleanTarget, StringComparison.OrdinalIgnoreCase))
                {
                    return prop;
                }
            }
            return null;
        }

        private static void ValidateStorageDataObject(JObject obj, List<string> errors, string context)
        {
            var propAge = GetPropertyIgnoreCase(obj, "ProfileAge");
            if (propAge != null)
            {
                if (propAge.Value.Type != JTokenType.Integer)
                {
                    errors.Add($"{context}: 'ProfileAge' must be an integer (found {propAge.Value.Type}).");
                }
            }

            var propActivity = GetPropertyIgnoreCase(obj, "ProfileActivity");
            if (propActivity != null)
            {
                if (propActivity.Value.Type != JTokenType.String && propActivity.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'ProfileActivity' must be a string (found {propActivity.Value.Type}).");
                }
            }

            var propSex = GetPropertyIgnoreCase(obj, "ProfileSex");
            if (propSex != null)
            {
                if (propSex.Value.Type != JTokenType.String && propSex.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'ProfileSex' must be a string (found {propSex.Value.Type}).");
                }
            }

            var propLimit = GetPropertyIgnoreCase(obj, "ObservationDaysLimit");
            if (propLimit != null)
            {
                if (propLimit.Value.Type != JTokenType.Integer)
                {
                    errors.Add($"{context}: 'ObservationDaysLimit' must be an integer (found {propLimit.Value.Type}).");
                }
            }

            var propActiveProfile = GetPropertyIgnoreCase(obj, "ActiveProfileName");
            if (propActiveProfile != null)
            {
                if (propActiveProfile.Value.Type != JTokenType.String && propActiveProfile.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'ActiveProfileName' must be a string (found {propActiveProfile.Value.Type}).");
                }
            }

            var propServingUnit = GetPropertyIgnoreCase(obj, "ServingUnit");
            if (propServingUnit != null)
            {
                if (propServingUnit.Value.Type != JTokenType.String && propServingUnit.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'ServingUnit' must be a string (found {propServingUnit.Value.Type}).");
                }
            }

            var propSampleLogs = GetPropertyIgnoreCase(obj, "HasPrepopulatedSampleLogs");
            if (propSampleLogs != null)
            {
                if (propSampleLogs.Value.Type != JTokenType.Boolean)
                {
                    errors.Add($"{context}: 'HasPrepopulatedSampleLogs' must be a boolean (found {propSampleLogs.Value.Type}).");
                }
            }

            var propRda = GetPropertyIgnoreCase(obj, "CustomRdaOverrides");
            if (propRda != null)
            {
                if (propRda.Value is JObject rdaObj)
                {
                    ValidateNumericDictionary(rdaObj, errors, $"{context}.CustomRdaOverrides");
                }
                else if (propRda.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'CustomRdaOverrides' must be a JSON object representing a dictionary of numeric values (found {propRda.Value.Type}).");
                }
            }

            var propTakenSupps = GetPropertyIgnoreCase(obj, "TakenSupplementsForToday");
            if (propTakenSupps != null)
            {
                if (propTakenSupps.Value is JArray arr)
                {
                    int idx = 0;
                    foreach (var item in arr)
                    {
                        if (item.Type != JTokenType.String)
                        {
                            errors.Add($"{context}.TakenSupplementsForToday[{idx}]: Expected a string element, but found {item.Type}.");
                        }
                        idx++;
                    }
                }
                else if (propTakenSupps.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'TakenSupplementsForToday' must be an array (found {propTakenSupps.Value.Type}).");
                }
            }

            var propFoodEntries = GetPropertyIgnoreCase(obj, "FoodEntries");
            if (propFoodEntries != null)
            {
                if (propFoodEntries.Value is JArray arr)
                {
                    int idx = 0;
                    foreach (var item in arr)
                    {
                        if (item is JObject entryObj)
                        {
                            ValidateFoodLogEntryObject(entryObj, errors, $"{context}.FoodEntries[{idx}]");
                        }
                        else
                        {
                            errors.Add($"{context}.FoodEntries[{idx}]: Element is not a valid JSON object.");
                        }
                        idx++;
                    }
                }
                else if (propFoodEntries.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'FoodEntries' must be an array (found {propFoodEntries.Value.Type}).");
                }
            }

            var propSupps = GetPropertyIgnoreCase(obj, "Supplements");
            if (propSupps != null)
            {
                if (propSupps.Value is JArray arr)
                {
                    int idx = 0;
                    foreach (var item in arr)
                    {
                        if (item is JObject entryObj)
                        {
                            ValidateSupplementObject(entryObj, errors, $"{context}.Supplements[{idx}]");
                        }
                        else
                        {
                            errors.Add($"{context}.Supplements[{idx}]: Element is not a valid JSON object.");
                        }
                        idx++;
                    }
                }
                else if (propSupps.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'Supplements' must be an array (found {propSupps.Value.Type}).");
                }
            }

            var propRecFoods = GetPropertyIgnoreCase(obj, "RecurringFoods");
            if (propRecFoods != null)
            {
                if (propRecFoods.Value is JArray arr)
                {
                    int idx = 0;
                    foreach (var item in arr)
                    {
                        if (item is JObject entryObj)
                        {
                            ValidateFoodLogEntryObject(entryObj, errors, $"{context}.RecurringFoods[{idx}]");
                        }
                        else
                        {
                            errors.Add($"{context}.RecurringFoods[{idx}]: Element is not a valid JSON object.");
                        }
                        idx++;
                    }
                }
                else if (propRecFoods.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'RecurringFoods' must be an array (found {propRecFoods.Value.Type}).");
                }
            }

            var propCustomProfiles = GetPropertyIgnoreCase(obj, "CustomDailyProfiles");
            if (propCustomProfiles != null)
            {
                if (propCustomProfiles.Value is JArray arr)
                {
                    int idx = 0;
                    foreach (var item in arr)
                    {
                        if (item is JObject entryObj)
                        {
                            ValidateCustomDailyProfileObject(entryObj, errors, $"{context}.CustomDailyProfiles[{idx}]");
                        }
                        else
                        {
                            errors.Add($"{context}.CustomDailyProfiles[{idx}]: Element is not a valid JSON object.");
                        }
                        idx++;
                    }
                }
                else if (propCustomProfiles.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'CustomDailyProfiles' must be an array (found {propCustomProfiles.Value.Type}).");
                }
            }
        }

        private static void ValidateFoodLogEntryObject(JObject obj, List<string> errors, string context)
        {
            var propId = GetPropertyIgnoreCase(obj, "Id");
            if (propId != null)
            {
                if (propId.Value.Type != JTokenType.Integer)
                {
                    errors.Add($"{context}: 'Id' must be an integer (found {propId.Value.Type}).");
                }
            }

            var propFoodName = GetPropertyIgnoreCase(obj, "FoodName");
            if (propFoodName == null)
            {
                errors.Add($"{context}: Missing required field 'FoodName'.");
            }
            else if (propFoodName.Value.Type != JTokenType.String)
            {
                errors.Add($"{context}: 'FoodName' must be a string (found {propFoodName.Value.Type}).");
            }

            var propDate = GetPropertyIgnoreCase(obj, "Date");
            if (propDate != null && propDate.Value.Type != JTokenType.String && propDate.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'Date' must be a string (found {propDate.Value.Type}).");
            }

            var propMeal = GetPropertyIgnoreCase(obj, "MealType");
            if (propMeal != null && propMeal.Value.Type != JTokenType.String && propMeal.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'MealType' must be a string (found {propMeal.Value.Type}).");
            }

            var propQty = GetPropertyIgnoreCase(obj, "Quantity");
            if (propQty != null)
            {
                if (propQty.Value.Type != JTokenType.Float && propQty.Value.Type != JTokenType.Integer)
                {
                    errors.Add($"{context}: 'Quantity' must be a numeric value (found {propQty.Value.Type}).");
                }
            }

            var propUnit = GetPropertyIgnoreCase(obj, "Unit");
            if (propUnit != null && propUnit.Value.Type != JTokenType.String && propUnit.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'Unit' must be a string (found {propUnit.Value.Type}).");
            }

            var propNutrients = GetPropertyIgnoreCase(obj, "Nutrients");
            if (propNutrients != null)
            {
                if (propNutrients.Value is JObject nutObj)
                {
                    ValidateNumericDictionary(nutObj, errors, $"{context}.Nutrients");
                }
                else if (propNutrients.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'Nutrients' must be a JSON object representing a dictionary of numeric values (found {propNutrients.Value.Type}).");
                }
            }
        }

        private static void ValidateSupplementObject(JObject obj, List<string> errors, string context)
        {
            var propId = GetPropertyIgnoreCase(obj, "Id");
            if (propId != null)
            {
                if (propId.Value.Type != JTokenType.Integer)
                {
                    errors.Add($"{context}: 'Id' must be an integer (found {propId.Value.Type}).");
                }
            }

            var propName = GetPropertyIgnoreCase(obj, "Name");
            if (propName == null)
            {
                errors.Add($"{context}: Missing required field 'Name'.");
            }
            else if (propName.Value.Type != JTokenType.String)
            {
                errors.Add($"{context}: 'Name' must be a string (found {propName.Value.Type}).");
            }

            var propDosage = GetPropertyIgnoreCase(obj, "Dosage");
            if (propDosage != null && propDosage.Value.Type != JTokenType.String && propDosage.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'Dosage' must be a string (found {propDosage.Value.Type}).");
            }

            var propFreq = GetPropertyIgnoreCase(obj, "Frequency");
            if (propFreq != null && propFreq.Value.Type != JTokenType.String && propFreq.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'Frequency' must be a string (found {propFreq.Value.Type}).");
            }

            var propDays = GetPropertyIgnoreCase(obj, "DaysOfWeek");
            if (propDays != null && propDays.Value.Type != JTokenType.String && propDays.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'DaysOfWeek' must be a string (found {propDays.Value.Type}).");
            }

            var propTime = GetPropertyIgnoreCase(obj, "TimeOfDay");
            if (propTime != null && propTime.Value.Type != JTokenType.String && propTime.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'TimeOfDay' must be a string (found {propTime.Value.Type}).");
            }

            var propNotes = GetPropertyIgnoreCase(obj, "Notes");
            if (propNotes != null && propNotes.Value.Type != JTokenType.String && propNotes.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'Notes' must be a string (found {propNotes.Value.Type}).");
            }

            var propNutrients = GetPropertyIgnoreCase(obj, "Nutrients");
            if (propNutrients != null)
            {
                if (propNutrients.Value is JObject nutObj)
                {
                    ValidateNumericDictionary(nutObj, errors, $"{context}.Nutrients");
                }
                else if (propNutrients.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'Nutrients' must be a JSON object representing a dictionary of numeric values (found {propNutrients.Value.Type}).");
                }
            }
        }

        private static void ValidateCustomDailyProfileObject(JObject obj, List<string> errors, string context)
        {
            var propName = GetPropertyIgnoreCase(obj, "Name");
            if (propName == null)
            {
                errors.Add($"{context}: Missing required field 'Name'.");
            }
            else if (propName.Value.Type != JTokenType.String)
            {
                errors.Add($"{context}: 'Name' must be a string (found {propName.Value.Type}).");
            }

            var propDesc = GetPropertyIgnoreCase(obj, "Description");
            if (propDesc != null && propDesc.Value.Type != JTokenType.String && propDesc.Value.Type != JTokenType.Null)
            {
                errors.Add($"{context}: 'Description' must be a string (found {propDesc.Value.Type}).");
            }

            var propTargets = GetPropertyIgnoreCase(obj, "Targets");
            if (propTargets != null)
            {
                if (propTargets.Value is JObject targetsObj)
                {
                    ValidateNumericDictionary(targetsObj, errors, $"{context}.Targets");
                }
                else if (propTargets.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: 'Targets' must be a JSON object representing a dictionary of numeric targets (found {propTargets.Value.Type}).");
                }
            }
        }

        private static void ValidateNumericDictionary(JObject dictObj, List<string> errors, string context)
        {
            foreach (var prop in dictObj.Properties())
            {
                if (prop.Value.Type != JTokenType.Float && prop.Value.Type != JTokenType.Integer && prop.Value.Type != JTokenType.Null)
                {
                    errors.Add($"{context}: Nutrient/target key '{prop.Name}' must map to a numeric value (found {prop.Value.Type}).");
                }
            }
        }

        #endregion

        #region Helper Logging

        private void LogError(string context, Exception ex)
        {
            try
            {
                var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NutriScribe");
                Directory.CreateDirectory(appData);
                var errorPath = Path.Combine(appData, "nutriscribe_error.txt");
                File.AppendAllText(errorPath, $"[{DateTime.Now}] ERROR during {context}: {ex.Message}{Environment.NewLine}{ex.StackTrace}{Environment.NewLine}{Environment.NewLine}");
            }
            catch {}
        }

        #endregion
    }
}
