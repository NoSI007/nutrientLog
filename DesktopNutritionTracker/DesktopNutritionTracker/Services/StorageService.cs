using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DesktopNutritionTracker.Models;
using Microsoft.Data.Sqlite;
using Newtonsoft.Json;

namespace DesktopNutritionTracker.Services
{
    public class StorageData
    {
        public List<FoodLogEntry> FoodEntries { get; set; } = new List<FoodLogEntry>();
        public List<Supplement> Supplements { get; set; } = new List<Supplement>();
        public Dictionary<string, double> CustomRdaOverrides { get; set; } = new Dictionary<string, double>();
        public int ProfileAge { get; set; } = 30;
        public string ProfileActivity { get; set; } = "Lightly Active";
        public string ProfileSex { get; set; } = "Female";
        public int ObservationDaysLimit { get; set; } = 30;
        public List<string> TakenSupplementsForToday { get; set; } = new List<string>(); // List of format: "Date|SupplementName"
        public List<CustomDailyProfile> CustomDailyProfiles { get; set; } = new List<CustomDailyProfile>();
        public string ActiveProfileName { get; set; } = "Standard Baseline";
        public List<FoodLogEntry> RecurringFoods { get; set; } = new List<FoodLogEntry>();
        public string ServingUnit { get; set; } = "g";
    }

    public class StorageService
    {
        private readonly string _jsonPath;
        private readonly string _dbPath;
        private readonly object _lock = new object();

        public StorageService()
        {
            _jsonPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "nutriscribe_db.json");
            _dbPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "nutriscribe_db.sqlite");
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

        public StorageData Load()
        {
            lock (_lock)
            {
                var data = new StorageData();
                bool migrated = false;

                // 1. If legacy JSON exists but SQLite DB doesn't, migrate JSON data!
                if (!File.Exists(_dbPath) && File.Exists(_jsonPath))
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

                // 2. Initialize database
                try
                {
                    InitializeDatabase();
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to initialize SQLite database: {ex.Message}");
                    return GetDefaultStorageData();
                }

                // 3. Save migrated data to SQLite and backup legacy file
                if (migrated)
                {
                    Save(data);
                    try
                    {
                        File.Move(_jsonPath, _jsonPath + ".bak");
                    }
                    catch {}
                    return data;
                }

                // 4. Load from SQLite
                try
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
                                var key = reader.GetString(0);
                                var value = reader.GetString(1);
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
                                        data.CustomRdaOverrides = JsonConvert.DeserializeObject<Dictionary<string, double>>(value) ?? new Dictionary<string, double>();
                                        break;
                                    case "TakenSupplementsForToday":
                                        data.TakenSupplementsForToday = JsonConvert.DeserializeObject<List<string>>(value) ?? new List<string>();
                                        break;
                                    case "CustomDailyProfiles":
                                        data.CustomDailyProfiles = JsonConvert.DeserializeObject<List<CustomDailyProfile>>(value) ?? new List<CustomDailyProfile>();
                                        break;
                                    case "ActiveProfileName":
                                        data.ActiveProfileName = value ?? "Standard Baseline";
                                        break;
                                    case "RecurringFoods":
                                        data.RecurringFoods = JsonConvert.DeserializeObject<List<FoodLogEntry>>(value) ?? new List<FoodLogEntry>();
                                        break;
                                    case "ServingUnit":
                                        data.ServingUnit = value ?? "g";
                                        break;
                                }
                            }
                        }

                        // Load FoodEntries
                        using (var cmd = new SqliteCommand("SELECT id, date, food_name, meal_type, quantity, unit, nutrients FROM food_log_entries", connection))
                        using (var reader = cmd.ExecuteReader())
                        {
                            while (reader.Read())
                            {
                                var food = new FoodLogEntry
                                {
                                    Id = reader.GetInt32(0),
                                    Date = reader.GetString(1),
                                    FoodName = reader.GetString(2),
                                    MealType = reader.GetString(3),
                                    Quantity = reader.GetDouble(4),
                                    Unit = reader.GetString(5),
                                    Nutrients = JsonConvert.DeserializeObject<Dictionary<string, double>>(reader.GetString(6)) ?? new Dictionary<string, double>()
                                };
                                data.FoodEntries.Add(food);
                            }
                        }

                        // Load Supplements
                        using (var cmd = new SqliteCommand("SELECT id, name, dosage, frequency, days_of_week, time_of_day, notes, nutrients FROM supplements", connection))
                        using (var reader = cmd.ExecuteReader())
                        {
                            while (reader.Read())
                            {
                                var sup = new Supplement
                                {
                                    Id = reader.GetInt32(0),
                                    Name = reader.GetString(1),
                                    Dosage = reader.GetString(2),
                                    Frequency = reader.GetString(3),
                                    DaysOfWeek = reader.IsDBNull(4) ? "" : reader.GetString(4),
                                    TimeOfDay = reader.IsDBNull(5) ? "" : reader.GetString(5),
                                    Notes = reader.IsDBNull(6) ? "" : reader.GetString(6),
                                    Nutrients = JsonConvert.DeserializeObject<Dictionary<string, double>>(reader.GetString(7)) ?? new Dictionary<string, double>()
                                };
                                data.Supplements.Add(sup);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to load SQLite database: {ex.Message}");
                    return GetDefaultStorageData();
                }

                // If Supplements list is empty (e.g. clean start), populate defaults
                if (data.Supplements.Count == 0)
                {
                    var defaultData = GetDefaultStorageData();
                    data.Supplements = defaultData.Supplements;
                    Save(data);
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
                    Save(data);
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
                    Save(data);
                }

                return data;
            }
        }

        public void Save(StorageData data)
        {
            lock (_lock)
            {
                try
                {
                    var connectionString = $"Data Source={_dbPath}";
                    using (var connection = new SqliteConnection(connectionString))
                    {
                        connection.Open();
                        using (var transaction = connection.BeginTransaction())
                        {
                            // 1. Save Settings (key_value_settings)
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
                                { "ServingUnit", data.ServingUnit ?? "g" }
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

                            // 2. Save FoodEntries
                            using (var cmdClear = new SqliteCommand("DELETE FROM food_log_entries", connection, transaction))
                            {
                                cmdClear.ExecuteNonQuery();
                            }

                            foreach (var food in data.FoodEntries)
                            {
                                using (var cmd = new SqliteCommand(
                                    "INSERT INTO food_log_entries (id, date, food_name, meal_type, quantity, unit, nutrients) VALUES (@id, @date, @food_name, @meal_type, @quantity, @unit, @nutrients)", 
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
                                }
                            }

                            // 3. Save Supplements
                            using (var cmdClear = new SqliteCommand("DELETE FROM supplements", connection, transaction))
                            {
                                cmdClear.ExecuteNonQuery();
                            }

                            foreach (var sup in data.Supplements)
                            {
                                using (var cmd = new SqliteCommand(
                                    "INSERT INTO supplements (id, name, dosage, frequency, days_of_week, time_of_day, notes, nutrients) VALUES (@id, @name, @dosage, @frequency, @days_of_week, @time_of_day, @notes, @nutrients)",
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
                                }
                            }

                            transaction.Commit();
                        }
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to save SQLite database: {ex.Message}");
                }
            }
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
