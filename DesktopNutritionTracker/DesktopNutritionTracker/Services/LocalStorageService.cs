using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Text;
using DesktopNutritionTracker.Models;
using Microsoft.Data.Sqlite;
using Newtonsoft.Json;

namespace DesktopNutritionTracker.Services
{
    public class LocalStorageService : ILocalStorageService
    {
        private readonly string _jsonPath;
        private readonly string _dbPath;
        private readonly object _lock = new object();
        private bool _hasPendingSync = false;
        private bool _isSqliteSupported = true;

        public bool IsOfflineMode { get; set; } = false;

        static LocalStorageService()
        {
            try
            {
                SQLitePCL.Batteries_V2.Init();
            }
            catch (Exception ex_v2)
            {
                System.Diagnostics.Debug.WriteLine($"SQLitePCL.Batteries_V2.Init() failed: {ex_v2.Message}. Trying fallback...");
                try
                {
                    SQLitePCL.Batteries.Init();
                }
                catch (Exception ex_v1)
                {
                    System.Diagnostics.Debug.WriteLine($"SQLitePCL.Batteries.Init() also failed: {ex_v1.Message}");
                }
            }
        }

        public bool HasPendingSync
        {
            get => _hasPendingSync;
            set
            {
                _hasPendingSync = value;
                if (!_isSqliteSupported) return;
                try
                {
                    TrySavePendingSyncToSqlite(value);
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to persist HasPendingSync state: {ex.Message}");
                    _isSqliteSupported = false;
                }
            }
        }

        private void TrySavePendingSyncToSqlite(bool value)
        {
            var connectionString = $"Data Source={_dbPath}";
            using (var connection = new SqliteConnection(connectionString))
            {
                connection.Open();
                using (var cmd = new SqliteCommand("INSERT OR REPLACE INTO key_value_settings (key, value) VALUES (@key, @value)", connection))
                {
                    cmd.Parameters.AddWithValue("@key", "HasPendingSync");
                    cmd.Parameters.AddWithValue("@value", value.ToString());
                    cmd.ExecuteNonQuery();
                }
            }
        }

        public LocalStorageService()
        {
            var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NutriScribe");
            Directory.CreateDirectory(appData);
            _jsonPath = Path.Combine(appData, "nutriscribe_db.json");
            _dbPath = Path.Combine(appData, "nutriscribe_db.sqlite");

            // Migration from legacy BaseDirectory to persistent LocalApplicationData
            try
            {
                var legacyJson = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "nutriscribe_db.json");
                if (File.Exists(legacyJson) && !File.Exists(_jsonPath))
                {
                    File.Copy(legacyJson, _jsonPath, true);
                }
            }
            catch {}

            try
            {
                var legacySqlite = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "nutriscribe_db.sqlite");
                if (File.Exists(legacySqlite) && !File.Exists(_dbPath))
                {
                    File.Copy(legacySqlite, _dbPath, true);
                }
            }
            catch {}

            // Try to initialize SQLite database and load the sync flag
            try
            {
                TryInitializeDatabaseAndSettings();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"SQLite not supported or failed to load. Falling back to JSON: {ex.Message}");
                _isSqliteSupported = false;
            }

            // Auto-detect offline mode during startup
            IsOfflineMode = !CheckNetworkConnectivity();
        }

        private void TryInitializeDatabaseAndSettings()
        {
            InitializeDatabase();
            var connectionString = $"Data Source={_dbPath}";
            using (var connection = new SqliteConnection(connectionString))
            {
                connection.Open();
                using (var cmd = new SqliteCommand("SELECT value FROM key_value_settings WHERE key = 'HasPendingSync'", connection))
                using (var reader = cmd.ExecuteReader())
                {
                    if (reader.Read() && !reader.IsDBNull(0))
                    {
                        bool.TryParse(reader.GetString(0), out _hasPendingSync);
                    }
                }
            }
        }

        private void InitializeDatabase()
        {
            var connectionString = $"Data Source={_dbPath}";
            using (var connection = new SqliteConnection(connectionString))
            {
                connection.Open();

                string createSettingsTable = @"
                    CREATE TABLE IF NOT EXISTS key_value_settings (
                        key TEXT PRIMARY KEY,
                        value TEXT
                    );";

                string createFoodTable = @"
                    CREATE TABLE IF NOT EXISTS food_log_entries (
                        id INTEGER PRIMARY KEY,
                        date TEXT,
                        food_name TEXT,
                        meal_type TEXT,
                        quantity REAL,
                        unit TEXT,
                        nutrients TEXT
                    );";

                string createSupplementsTable = @"
                    CREATE TABLE IF NOT EXISTS supplements (
                        id INTEGER PRIMARY KEY,
                        name TEXT,
                        dosage TEXT,
                        frequency TEXT,
                        days_of_week TEXT,
                        time_of_day TEXT,
                        notes TEXT,
                        nutrients TEXT
                    );";

                using (var cmd = new SqliteCommand(createSettingsTable, connection)) cmd.ExecuteNonQuery();
                using (var cmd = new SqliteCommand(createFoodTable, connection)) cmd.ExecuteNonQuery();
                using (var cmd = new SqliteCommand(createSupplementsTable, connection)) cmd.ExecuteNonQuery();
            }
        }

        public bool CheckNetworkConnectivity()
        {
            try
            {
                return NetworkInterface.GetIsNetworkAvailable();
            }
            catch
            {
                return false;
            }
        }

        public void LogOfflineActivity(string actionType, string details)
        {
            try
            {
                NutritionDbContext.LogActivity($"OFFLINE_{actionType}", details);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to write offline activity log: {ex.Message}");
            }
        }

        public StorageData LoadLocal()
        {
            lock (_lock)
            {
                var data = new StorageData();
                bool dbExists = File.Exists(_dbPath);

                if (!_isSqliteSupported)
                {
                    return LoadFromJsonFallback(null);
                }

                try
                {
                    InitializeDatabase();
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to initialize SQLite database: {ex.Message}");
                    _isSqliteSupported = false;
                    return LoadFromJsonFallback(ex);
                }

                bool migrated = false;

                // If legacy JSON exists but SQLite DB didn't exist, migrate JSON data
                if (!dbExists && File.Exists(_jsonPath))
                {
                    try
                    {
                        var json = File.ReadAllText(_jsonPath);
                        var legacyData = JsonConvert.DeserializeObject<StorageData>(json);
                        if (legacyData != null)
                        {
                            data = legacyData;
                            migrated = true;
                        }
                    }
                    catch (Exception ex)
                    {
                        System.Diagnostics.Debug.WriteLine($"Failed to load legacy JSON for migration: {ex.Message}");
                    }
                }

                if (migrated)
                {
                    SaveLocal(data);
                    try
                    {
                        File.Move(_jsonPath, _jsonPath + ".bak");
                    }
                    catch {}
                    return data;
                }

                // Load from SQLite
                try
                {
                    LoadDataFromSqlite(data);
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to load SQLite database: {ex.Message}");
                    _isSqliteSupported = false;
                    return LoadFromJsonFallback(ex);
                }

                // If Supplements list is empty (e.g. clean start), populate defaults
                if (data.Supplements.Count == 0)
                {
                    var defaultData = GetDefaultStorageData();
                    data.Supplements = defaultData.Supplements;
                    SaveLocal(data);
                }

                // Ensure lists and dictionaries are instantiated
                data.FoodEntries = data.FoodEntries ?? new List<FoodLogEntry>();
                data.Supplements = data.Supplements ?? new List<Supplement>();
                data.CustomRdaOverrides = data.CustomRdaOverrides ?? new Dictionary<string, double>();
                data.TakenSupplementsForToday = data.TakenSupplementsForToday ?? new List<string>();
                
                data.CustomDailyProfiles = data.CustomDailyProfiles ?? new List<CustomDailyProfile>();
                if (data.CustomDailyProfiles.Count == 0)
                {
                    data.CustomDailyProfiles.Add(new CustomDailyProfile(
                        "Keto Active Profile",
                        "High fat, standard proteins, and limiting daily carbohydrate intakes strictly to 20g.",
                        new Dictionary<string, double>
                        {
                            { "calories", 1800.0 }, { "carbohydrates", 20.0 }, { "protein", 95.0 }, { "fat", 145.0 }, { "water", 3000.0 }
                        }));
                    data.CustomDailyProfiles.Add(new CustomDailyProfile(
                        "Hypertension Low-Sodium",
                        "Focused on reducing daily Sodium ceiling to 1500mg, while increasing potassium to preserve vascular flow.",
                        new Dictionary<string, double>
                        {
                            { "sodium", 1500.0 }, { "potassium", 4000.0 }, { "water", 2800.0 }
                        }));
                    data.CustomDailyProfiles.Add(new CustomDailyProfile(
                        "Clinical Athletic Target",
                        "Slightly elevated protein and calories supporting lean mass recovery and cardiovascular conditioning.",
                        new Dictionary<string, double>
                        {
                            { "protein", 135.0 }, { "calories", 2500.0 }, { "carbohydrates", 320.0 }, { "water", 3500.0 }
                        }));
                    SaveLocal(data);
                }
                data.ActiveProfileName = data.ActiveProfileName ?? "Standard Baseline";

                data.RecurringFoods = data.RecurringFoods ?? new List<FoodLogEntry>();
                if (data.RecurringFoods.Count == 0)
                {
                    data.RecurringFoods.Add(new FoodLogEntry("", "Morning Coffee/Café", "Breakfast", 1.0, "cup", new Dictionary<string, double>
                    {
                        { "calories", 2.0 }, { "water", 240.0 }, { "sodium", 5.0 }
                    }) { Id = 1 });
                    data.RecurringFoods.Add(new FoodLogEntry("", "Mineral Water Hydration", "Snack", 1.0, "glass", new Dictionary<string, double>
                    {
                        { "calories", 0.0 }, { "water", 250.0 }, { "calcium", 20.0 }, { "magnesium", 10.0 }
                    }) { Id = 2 });
                    data.RecurringFoods.Add(new FoodLogEntry("", "Soft Boiled Eggs", "Breakfast", 2.0, "large egg", new Dictionary<string, double>
                    {
                        { "calories", 140.0 }, { "protein", 12.0 }, { "fat", 10.0 }, { "carbohydrates", 0.8 }, { "sodium", 140.0 }
                    }) { Id = 3 });
                    data.RecurringFoods.Add(new FoodLogEntry("", "Organic Steel Cut Oats", "Breakfast", 1.0, "bowl", new Dictionary<string, double>
                    {
                        { "calories", 150.0 }, { "carbohydrates", 27.0 }, { "protein", 5.0 }, { "fat", 3.0 }, { "fiber", 4.0 }, { "iron", 1.8 }
                    }) { Id = 4 });
                    SaveLocal(data);
                }

                return data;
            }
        }

        private void LoadDataFromSqlite(StorageData data)
        {
            var connectionString = $"Data Source={_dbPath}";
            using (var connection = new SqliteConnection(connectionString))
            {
                connection.Open();

                // Load Settings
                using (var cmd = new SqliteCommand("SELECT key, value FROM key_value_settings", connection))
                using (var reader = cmd.ExecuteReader())
                {
                    while (reader.Read())
                    {
                        try
                        {
                            var key = reader.GetString(0);
                            var value = reader.IsDBNull(1) ? null : reader.GetString(1);
                            if (value == null) continue;

                            switch (key)
                            {
                                case "ProfileAge":
                                    if (int.TryParse(value, out int age)) data.ProfileAge = age;
                                    break;
                                case "ProfileActivity":
                                    data.ProfileActivity = value;
                                    break;
                                case "ProfileSex":
                                    data.ProfileSex = value;
                                    break;
                                case "ObservationDaysLimit":
                                    if (int.TryParse(value, out int limit)) data.ObservationDaysLimit = limit;
                                    break;
                                case "CustomRdaOverrides":
                                    try
                                    {
                                        data.CustomRdaOverrides = JsonConvert.DeserializeObject<Dictionary<string, double>>(value) ?? new Dictionary<string, double>();
                                    }
                                    catch {}
                                    break;
                                case "TakenSupplementsForToday":
                                    try
                                    {
                                        data.TakenSupplementsForToday = JsonConvert.DeserializeObject<List<string>>(value) ?? new List<string>();
                                    }
                                    catch {}
                                    break;
                                case "CustomDailyProfiles":
                                    try
                                    {
                                        data.CustomDailyProfiles = JsonConvert.DeserializeObject<List<CustomDailyProfile>>(value) ?? new List<CustomDailyProfile>();
                                    }
                                    catch {}
                                    break;
                                case "ActiveProfileName":
                                    data.ActiveProfileName = value ?? "Standard Baseline";
                                    break;
                                case "RecurringFoods":
                                    try
                                    {
                                        data.RecurringFoods = JsonConvert.DeserializeObject<List<FoodLogEntry>>(value) ?? new List<FoodLogEntry>();
                                    }
                                    catch {}
                                    break;
                                case "ServingUnit":
                                    data.ServingUnit = value ?? "g";
                                    break;
                                case "HasPrepopulatedSampleLogs":
                                    if (bool.TryParse(value, out bool prepopulated)) data.HasPrepopulatedSampleLogs = prepopulated;
                                    break;
                            }
                        }
                        catch (Exception rowEx)
                        {
                            System.Diagnostics.Debug.WriteLine($"Failed to load key-value setting: {rowEx.Message}");
                        }
                    }
                }

                // Load FoodEntries
                using (var cmd = new SqliteCommand("SELECT id, date, food_name, meal_type, quantity, unit, nutrients FROM food_log_entries", connection))
                using (var reader = cmd.ExecuteReader())
                {
                    while (reader.Read())
                    {
                        try
                        {
                            var food = new FoodLogEntry
                            {
                                Id = reader.GetInt32(0),
                                Date = reader.IsDBNull(1) ? "" : reader.GetString(1),
                                FoodName = reader.IsDBNull(2) ? "" : reader.GetString(2),
                                MealType = reader.IsDBNull(3) ? "" : reader.GetString(3),
                                Quantity = reader.IsDBNull(4) ? 0.0 : reader.GetDouble(4),
                                Unit = reader.IsDBNull(5) ? "" : reader.GetString(5),
                                Nutrients = reader.IsDBNull(6) ? new Dictionary<string, double>() : JsonConvert.DeserializeObject<Dictionary<string, double>>(reader.GetString(6)) ?? new Dictionary<string, double>()
                            };
                            data.FoodEntries.Add(food);
                        }
                        catch (Exception rowEx)
                        {
                            System.Diagnostics.Debug.WriteLine($"Failed to load a food entry row: {rowEx.Message}");
                        }
                    }
                }

                // Load Supplements
                using (var cmd = new SqliteCommand("SELECT id, name, dosage, frequency, days_of_week, time_of_day, notes, nutrients FROM supplements", connection))
                using (var reader = cmd.ExecuteReader())
                {
                    while (reader.Read())
                    {
                        try
                        {
                            var sup = new Supplement
                            {
                                Id = reader.GetInt32(0),
                                Name = reader.IsDBNull(1) ? "" : reader.GetString(1),
                                Dosage = reader.IsDBNull(2) ? "" : reader.GetString(2),
                                Frequency = reader.IsDBNull(3) ? "" : reader.GetString(3),
                                DaysOfWeek = reader.IsDBNull(4) ? "" : reader.GetString(4),
                                TimeOfDay = reader.IsDBNull(5) ? "" : reader.GetString(5),
                                Notes = reader.IsDBNull(6) ? "" : reader.GetString(6),
                                Nutrients = reader.IsDBNull(7) ? new Dictionary<string, double>() : JsonConvert.DeserializeObject<Dictionary<string, double>>(reader.GetString(7)) ?? new Dictionary<string, double>()
                            };
                            data.Supplements.Add(sup);
                        }
                        catch (Exception rowEx)
                        {
                            System.Diagnostics.Debug.WriteLine($"Failed to load a supplement row: {rowEx.Message}");
                        }
                    }
                }
            }
        }

        public void SaveLocal(StorageData data)
        {
            lock (_lock)
            {
                if (!_isSqliteSupported)
                {
                    SaveToJsonFallback(data);
                    return;
                }

                try
                {
                    SaveDataToSqlite(data);
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to save SQLite database: {ex.Message}");
                    _isSqliteSupported = false;
                    SaveToJsonFallback(data);
                }
            }
        }

        private void SaveToJsonFallback(StorageData data)
        {
            try
            {
                var json = JsonConvert.SerializeObject(data, Formatting.Indented);
                File.WriteAllText(_jsonPath, json);
                System.Diagnostics.Debug.WriteLine("SQLite failed or disabled, but successfully saved to JSON fallback.");
            }
            catch (Exception jsonEx)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to save to JSON fallback: {jsonEx.Message}");
            }
        }

        private void SaveDataToSqlite(StorageData data)
        {
            System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Beginning persistence process.");
            var connectionString = $"Data Source={_dbPath}";
            System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Database Path: {_dbPath}");

            using (var connection = new SqliteConnection(connectionString))
            {
                System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Opening SQLite connection...");
                connection.Open();
                System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Connection opened. Starting transaction...");

                using (var transaction = connection.BeginTransaction())
                {
                    try
                    {
                        // 1. Save Settings (key_value_settings)
                        System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Step 1: Serializing and saving settings...");
                        var settings = new Dictionary<string, string>
                        {
                            { "ProfileAge", data.ProfileAge.ToString() },
                            { "ProfileActivity", data.ProfileActivity ?? "Lightly Active" },
                            { "ProfileSex", data.ProfileSex ?? "Female" },
                            { "ObservationDaysLimit", data.ObservationDaysLimit.ToString() },
                            { "CustomRdaOverrides", JsonConvert.SerializeObject(data.CustomRdaOverrides ?? new Dictionary<string, double>()) },
                            { "TakenSupplementsForToday", JsonConvert.SerializeObject(data.TakenSupplementsForToday ?? new List<string>()) },
                            { "CustomDailyProfiles", JsonConvert.SerializeObject(data.CustomDailyProfiles ?? new List<CustomDailyProfile>()) },
                            { "ActiveProfileName", data.ActiveProfileName ?? "Standard Baseline" },
                            { "RecurringFoods", JsonConvert.SerializeObject(data.RecurringFoods ?? new List<FoodLogEntry>()) },
                            { "ServingUnit", data.ServingUnit ?? "g" },
                            { "HasPrepopulatedSampleLogs", data.HasPrepopulatedSampleLogs.ToString() }
                        };

                        foreach (var kvp in settings)
                        {
                            using (var cmd = new SqliteCommand("INSERT OR REPLACE INTO key_value_settings (key, value) VALUES (@key, @value)", connection, transaction))
                            {
                                cmd.Parameters.AddWithValue("@key", kvp.Key);
                                cmd.Parameters.AddWithValue("@value", kvp.Value);
                                cmd.ExecuteNonQuery();
                            }
                        }
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Step 1 complete. Saved {settings.Count} key-value settings.");

                        // 2. Save FoodEntries
                        System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Step 2: Clearing existing food entries...");
                        using (var cmdClear = new SqliteCommand("DELETE FROM food_log_entries", connection, transaction))
                        {
                            int cleared = cmdClear.ExecuteNonQuery();
                            System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Cleared {cleared} old food log entries.");
                        }

                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Saving {data.FoodEntries.Count} new food entries...");
                        int savedFoods = 0;
                        foreach (var food in data.FoodEntries)
                        {
                            using (var cmd = new SqliteCommand(
                                "INSERT OR REPLACE INTO food_log_entries (id, date, food_name, meal_type, quantity, unit, nutrients) VALUES (@id, @date, @food_name, @meal_type, @quantity, @unit, @nutrients)", 
                                connection, transaction))
                            {
                                cmd.Parameters.AddWithValue("@id", food.Id);
                                cmd.Parameters.AddWithValue("@date", food.Date ?? "");
                                cmd.Parameters.AddWithValue("@food_name", food.FoodName ?? "");
                                cmd.Parameters.AddWithValue("@meal_type", food.MealType ?? "");
                                cmd.Parameters.AddWithValue("@quantity", food.Quantity);
                                cmd.Parameters.AddWithValue("@unit", food.Unit ?? "");
                                cmd.Parameters.AddWithValue("@nutrients", JsonConvert.SerializeObject(food.Nutrients ?? new Dictionary<string, double>()));
                                cmd.ExecuteNonQuery();
                                savedFoods++;
                            }
                        }
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Step 2 complete. Wrote {savedFoods} food log entries to database.");

                        // 3. Save Supplements
                        System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Step 3: Clearing existing supplements...");
                        using (var cmdClear = new SqliteCommand("DELETE FROM supplements", connection, transaction))
                        {
                            int clearedSups = cmdClear.ExecuteNonQuery();
                            System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Cleared {clearedSups} old supplements.");
                        }

                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Saving {data.Supplements.Count} new supplements...");
                        int savedSups = 0;
                        foreach (var sup in data.Supplements)
                        {
                            using (var cmd = new SqliteCommand(
                                "INSERT OR REPLACE INTO supplements (id, name, dosage, frequency, days_of_week, time_of_day, notes, nutrients) VALUES (@id, @name, @dosage, @frequency, @days_of_week, @time_of_day, @notes, @nutrients)",
                                connection, transaction))
                            {
                                cmd.Parameters.AddWithValue("@id", sup.Id);
                                cmd.Parameters.AddWithValue("@name", sup.Name ?? "");
                                cmd.Parameters.AddWithValue("@dosage", sup.Dosage ?? "");
                                cmd.Parameters.AddWithValue("@frequency", sup.Frequency ?? "");
                                cmd.Parameters.AddWithValue("@days_of_week", sup.DaysOfWeek ?? "");
                                cmd.Parameters.AddWithValue("@time_of_day", sup.TimeOfDay ?? "");
                                cmd.Parameters.AddWithValue("@notes", sup.Notes ?? "");
                                cmd.Parameters.AddWithValue("@nutrients", JsonConvert.SerializeObject(sup.Nutrients ?? new Dictionary<string, double>()));
                                cmd.ExecuteNonQuery();
                                savedSups++;
                            }
                        }
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Step 3 complete. Wrote {savedSups} supplements to database.");

                        System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Committing SQL transaction...");
                        transaction.Commit();
                        System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Transaction successfully committed.");
                    }
                    catch (Exception transEx)
                    {
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Error during database transaction: {transEx.Message}\n{transEx.StackTrace}");
                        try
                        {
                            System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Rolling back transaction...");
                            transaction.Rollback();
                        }
                        catch (Exception rollbackEx)
                        {
                            System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Failed to rollback transaction: {rollbackEx.Message}");
                        }
                        throw;
                    }

                    // Mirror to JSON for safety/fallback
                    try
                    {
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Mirroring data to JSON safety file at: {_jsonPath}");
                        var json = JsonConvert.SerializeObject(data, Formatting.Indented);
                        File.WriteAllText(_jsonPath, json);
                        System.Diagnostics.Debug.WriteLine("[SaveDataToSqlite] Mirror to JSON safety fallback file succeeded.");
                    }
                    catch (Exception jsonEx)
                    {
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Failed to mirror to JSON fallback: {jsonEx.Message}");
                    }

                    // Log user activity via EF Core / Local Database Log
                    try
                    {
                        LogOfflineActivity("DATA_SAVE", $"Successfully saved {data.FoodEntries.Count} food log entries and {data.Supplements.Count} supplement entries.");
                    }
                    catch (Exception activityEx)
                    {
                        System.Diagnostics.Debug.WriteLine($"[SaveDataToSqlite] Failed to write offline activity log: {activityEx.Message}");
                    }
                }
            }
        }

        private StorageData LoadFromJsonFallback(Exception sqliteEx)
        {
            try
            {
                if (File.Exists(_jsonPath))
                {
                    var json = File.ReadAllText(_jsonPath);
                    var data = JsonConvert.DeserializeObject<StorageData>(json);
                    if (data != null)
                    {
                        System.Diagnostics.Debug.WriteLine("SQLite failed, but loaded successfully from JSON fallback.");
                        return data;
                    }
                }
            }
            catch (Exception jsonEx)
            {
                System.Diagnostics.Debug.WriteLine($"JSON Fallback load also failed: {jsonEx.Message}");
            }
            return GetDefaultStorageData();
        }

        private StorageData GetDefaultStorageData()
        {
            var data = new StorageData();
            
            // Populate default supplements list matching Android app
            data.Supplements.Add(new Supplement("Vitamin D3", "2000 IU", "Once Daily", "", "Morning", "Supports skeletal integration and immune pathways", new Dictionary<string, double> { { "vitamin_d", 50.0 } }) { Id = 1 });
            data.Supplements.Add(new Supplement("Omega-3 Salmon Oil", "1200 mg", "Once Daily", "", "Morning", "Rich in EPA/DHA essential fatty structures", new Dictionary<string, double> { { "omega3", 1.2 }, { "fat", 1.2 } }) { Id = 2 });
            data.Supplements.Add(new Supplement("Magnesium Bisglycinate", "200 mg", "Once Daily", "", "Night", "Enhances neural stability and muscle relaxation", new Dictionary<string, double> { { "magnesium", 200.0 } }) { Id = 3 });
            data.Supplements.Add(new Supplement("Methyl-B12", "1000 mcg", "Weekly", "Monday", "Afternoon", "Supports cognitive acuity and blood formation", new Dictionary<string, double> { { "vitamin_b12", 1000.0 } }) { Id = 4 });

            return data;
        }
    }
}
