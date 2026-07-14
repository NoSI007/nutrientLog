using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using DesktopNutritionTracker.Models;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace DesktopNutritionTracker.Services
{
    public class StorageService
    {
        private readonly ILocalStorageService _localStorage;
        private readonly string _dbPath;
        private readonly object _lock = new object();
        private readonly BackupService _backupService;

        public StorageService()
        {
            _localStorage = new LocalStorageService();
            _backupService = new BackupService(_localStorage);
            
            var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NutriScribe");
            _dbPath = Path.Combine(appData, "nutriscribe_db.sqlite");
        }

        public StorageData Load()
        {
            lock (_lock)
            {
                bool dbExists = File.Exists(_dbPath);

                // 1. Only pull/restore from Firestore if local database does not exist (first run)
                if (!dbExists)
                {
                    System.Diagnostics.Debug.WriteLine("Local database file not found. Syncing from Firestore backup...");
                    _backupService.SyncFromFirestore();
                }
                else
                {
                    System.Diagnostics.Debug.WriteLine("Local database exists. Preserving local state as source of truth.");
                }

                // 2. Load from LocalStorageService (which handles SQLite and JSON fallback)
                var data = _localStorage.LoadLocal();

                // 3. If online and has a pending offline changes flag, push them to Firestore!
                if (_localStorage.CheckNetworkConnectivity() && _localStorage.HasPendingSync)
                {
                    System.Diagnostics.Debug.WriteLine("Connectivity detected with pending offline changes. Syncing local state to Firestore...");
                    _backupService.SyncToFirestore(data);
                }

                return data;
            }
        }

        public void Save(StorageData data)
        {
            // 1. Always write offline-first to the local cache immediately
            _localStorage.SaveLocal(data);

            // 2. Synchronize to Firestore
            if (_localStorage.CheckNetworkConnectivity())
            {
                _backupService.SyncToFirestore(data);
            }
            else
            {
                _localStorage.HasPendingSync = true;
                _localStorage.LogOfflineActivity("OFFLINE_SAVE", $"Saved {data.FoodEntries.Count} food log entries and {data.Supplements.Count} supplement entries locally. Set pending cloud sync.");
            }
        }

        public void SaveLocalOnly(StorageData data)
        {
            _localStorage.SaveLocal(data);
        }

        public async Task<StorageData> GetFirestoreBackupDataAsync()
        {
            return await _backupService.GetFirestoreBackupDataAsync();
        }

        public async Task SaveToFirestoreAsync(StorageData data)
        {
            await _backupService.SaveToFirestoreAsync(data);
        }

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


        private string GetFirestoreProjectId()
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

        public static StorageData? DeserializeStorageData(string rawJson)
        {
            return BackupService.DeserializeStorageData(rawJson);
        }

        public static bool ValidateJsonSchema(string rawJson, out List<string> errors)
        {
            return BackupService.ValidateJsonSchema(rawJson, out errors);
        }

        public StorageData? ImportData(string rawJson)
        {
            return _backupService.ImportData(rawJson);
        }

        public static string NormalizeJsonKeys(string json)
        {
            return BackupService.NormalizeJsonKeys(json);
        }
    }
}
