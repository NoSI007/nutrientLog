using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using DesktopNutritionTracker.Models;
using DesktopNutritionTracker.Services;

namespace DesktopNutritionTracker.ViewModels
{
    public class NutritionViewModel : INotifyPropertyChanged
    {
        private readonly StorageService _storageService;
        private readonly IRdaProfileService _rdaProfileService = new RdaProfileService();
        private readonly GeminiService _geminiService = new GeminiService();
        private StorageData _db;

        private readonly INutrientNotificationService _notificationService = new NutrientNotificationService();
        private readonly ObservableCollection<NutrientAlert> _activeNutrientAlerts = new ObservableCollection<NutrientAlert>();
        public ObservableCollection<NutrientAlert> ActiveNutrientAlerts => _activeNutrientAlerts;

        public bool HasActiveNutrientAlerts => ActiveNutrientAlerts.Any();

        private bool _isAlertModalVisible = false;
        public bool IsAlertModalVisible
        {
            get => _isAlertModalVisible;
            set => SetProperty(ref _isAlertModalVisible, value);
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        // Current UI Date State
        private string _currentDateString;
        public string CurrentDateString
        {
            get => _currentDateString;
            set { if (SetProperty(ref _currentDateString, value)) { RefreshTodayData(); } }
        }

        // Active State Properties
        private int _profileAge;
        public int ProfileAge
        {
            get => _profileAge;
            set { if (SetProperty(ref _profileAge, value)) SaveProfile(); }
        }

        private string _profileActivity = "Lightly Active";
        public string ProfileActivity
        {
            get => _profileActivity;
            set { if (SetProperty(ref _profileActivity, value)) SaveProfile(); }
        }

        private string _profileSex = "Female";
        public string ProfileSex
        {
            get => _profileSex;
            set { if (SetProperty(ref _profileSex, value)) SaveProfile(); }
        }

        public string CurrentServingUnit
        {
            get => _db.ServingUnit ?? "g";
            set
            {
                if (_db.ServingUnit != value)
                {
                    ToggleServingUnit(value);
                }
            }
        }

        public bool IsGramUnitActive
        {
            get => CurrentServingUnit == "g";
            set
            {
                if (value) ToggleServingUnit("g");
            }
        }

        public bool IsOunceUnitActive
        {
            get => CurrentServingUnit == "oz";
            set
            {
                if (value) ToggleServingUnit("oz");
            }
        }

        private string? _operationMessage;
        public string? OperationMessage
        {
            get => _operationMessage;
            set => SetProperty(ref _operationMessage, value);
        }

        private bool _isAiAnalyzing;
        public bool IsAiAnalyzing
        {
            get => _isAiAnalyzing;
            set => SetProperty(ref _isAiAnalyzing, value);
        }

        // Warning Banner Properties
        private bool _isWarningBannerExpanded = false;
        public bool IsWarningBannerExpanded
        {
            get => _isWarningBannerExpanded;
            set
            {
                if (SetProperty(ref _isWarningBannerExpanded, value))
                {
                    NotifyPropertyChanged(nameof(WarningExpandButtonText));
                }
            }
        }

        public string WarningExpandButtonText => _isWarningBannerExpanded ? "Hide Details ▲" : "Show Details ▼";

        public IEnumerable<NutrientStatus> WarningNutrients => NutrientStatuses.Where(s => s.IsWarning).ToList();

        public bool HasWarningNutrients => WarningNutrients.Any();

        public string WarningSummaryText
        {
            get
            {
                var count = WarningNutrients.Count();
                if (count == 0) return "All recommended RDA targets are met! Perfect daily nutrition balance.";
                return $"Pre-Clinical Warning: {count} recommended nutrient{(count == 1 ? "" : "s")} fall below 80% RDA today.";
            }
        }

        // AI Fetch Wizard Properties
        private string _aiQueryFoodName = "";
        public string AIQueryFoodName
        {
            get => _aiQueryFoodName;
            set => SetProperty(ref _aiQueryFoodName, value);
        }

        private string _aiQueryServingSizeText = "1.0";
        public string AIQueryServingSizeText
        {
            get => _aiQueryServingSizeText;
            set
            {
                if (SetProperty(ref _aiQueryServingSizeText, value))
                {
                    RefreshPreviewNutrients();
                    NotifyPropertyChanged(nameof(PreviewCalories));
                    NotifyPropertyChanged(nameof(PreviewProtein));
                    NotifyPropertyChanged(nameof(PreviewCarbs));
                    NotifyPropertyChanged(nameof(PreviewFat));
                }
            }
        }

        private string _aiQueryMealType = "Lunch";
        public string AIQueryMealType
        {
            get => _aiQueryMealType;
            set => SetProperty(ref _aiQueryMealType, value);
        }

        private bool _isAiFetching;
        public bool IsAiFetching
        {
            get => _isAiFetching;
            set => SetProperty(ref _isAiFetching, value);
        }

        private string _aiFetchStatusText = "Enter food name and quantity, then click Fetch!";
        public string AIFetchStatusText
        {
            get => _aiFetchStatusText;
            set => SetProperty(ref _aiFetchStatusText, value);
        }

        private ParsedFoodResult? _aiFetchedPreviewResult;
        public ParsedFoodResult? AIFetchedPreviewResult
        {
            get => _aiFetchedPreviewResult;
            set
            {
                if (SetProperty(ref _aiFetchedPreviewResult, value))
                {
                    RefreshPreviewNutrients();
                    NotifyPropertyChanged(nameof(HasPreviewResult));
                    NotifyPropertyChanged(nameof(PreviewFoodName));
                    NotifyPropertyChanged(nameof(PreviewCalories));
                    NotifyPropertyChanged(nameof(PreviewProtein));
                    NotifyPropertyChanged(nameof(PreviewCarbs));
                    NotifyPropertyChanged(nameof(PreviewFat));
                }
            }
        }

        public bool HasPreviewResult => _aiFetchedPreviewResult != null;

        public string PreviewFoodName => _aiFetchedPreviewResult?.FoodName ?? "";
        
        public double PreviewCalories => (_aiFetchedPreviewResult?.Nutrients != null && _aiFetchedPreviewResult.Nutrients.ContainsKey("calories"))
            ? _aiFetchedPreviewResult.Nutrients["calories"] * PreviewQuantity
            : 0.0;

        public double PreviewProtein => (_aiFetchedPreviewResult?.Nutrients != null && _aiFetchedPreviewResult.Nutrients.ContainsKey("protein"))
            ? _aiFetchedPreviewResult.Nutrients["protein"] * PreviewQuantity
            : 0.0;

        public double PreviewCarbs => (_aiFetchedPreviewResult?.Nutrients != null && _aiFetchedPreviewResult.Nutrients.ContainsKey("carbohydrates"))
            ? _aiFetchedPreviewResult.Nutrients["carbohydrates"] * PreviewQuantity
            : 0.0;

        public double PreviewFat => (_aiFetchedPreviewResult?.Nutrients != null && _aiFetchedPreviewResult.Nutrients.ContainsKey("fat"))
            ? _aiFetchedPreviewResult.Nutrients["fat"] * PreviewQuantity
            : 0.0;

        public double PreviewQuantity
        {
            get
            {
                double q;
                if (double.TryParse(_aiQueryServingSizeText, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out q) && q > 0)
                {
                    return q;
                }
                return _aiFetchedPreviewResult?.Quantity ?? 1.0;
            }
        }

        public ObservableCollection<NutritionPreviewItem> PreviewNutrients { get; } = new ObservableCollection<NutritionPreviewItem>();

        // Collections bound to UI
        public ObservableCollection<FoodLogEntry> CurrentDayFood { get; } = new ObservableCollection<FoodLogEntry>();
        public ObservableCollection<Supplement> Supplements { get; } = new ObservableCollection<Supplement>();
        public ObservableCollection<NutrientStatus> NutrientStatuses { get; } = new ObservableCollection<NutrientStatus>();
        public ObservableCollection<DayTrend> DayTrends { get; } = new ObservableCollection<DayTrend>();
        public ObservableCollection<CustomDailyProfile> CustomDailyProfiles { get; } = new ObservableCollection<CustomDailyProfile>();
        public ObservableCollection<FoodLogEntry> RecurringFoods { get; } = new ObservableCollection<FoodLogEntry>();

        private bool _isAiPlanning;
        public bool IsAiPlanning { get => _isAiPlanning; set => SetProperty(ref _isAiPlanning, value); }

        private string _aiPlannerStatusText = "Configure usual foods and press 'PLAN WITH AI'.";
        public string AiPlannerStatusText { get => _aiPlannerStatusText; set => SetProperty(ref _aiPlannerStatusText, value); }

        private bool _hasSuggestedMenu;
        public bool HasSuggestedMenu { get => _hasSuggestedMenu; set => SetProperty(ref _hasSuggestedMenu, value); }

        public ObservableCollection<FoodLogEntry> SelectedMenuStaples { get; } = new ObservableCollection<FoodLogEntry>();
        public ObservableCollection<FoodLogEntry> SuggestedMenuItems { get; } = new ObservableCollection<FoodLogEntry>();

        private string _activeProfileName = "Standard Baseline";
        public string ActiveProfileName { get => _activeProfileName; set => SetProperty(ref _activeProfileName, value); }

        // Quick Lists for Combos or Pickers
        public List<string> MealTypes { get; } = new List<string> { "Breakfast", "Lunch", "Dinner", "Snack" };
        public List<string> Sexes { get; } = new List<string> { "Female", "Male" };
        public List<string> ActivityLevels { get; } = new List<string> { "Sedentary", "Lightly Active", "Moderately Active", "Very Active", "Super Active" };

        // Last deleted food for UNDO option
        private FoodLogEntry? _lastDeletedEntry;

        public StorageData Database => _db;
        public TrendsViewModel Trends { get; }

        public NutritionViewModel()
        {
            _storageService = new StorageService();
            _db = _storageService.Load();

            // Load saved settings
            _profileAge = _db.ProfileAge;
            _profileActivity = _db.ProfileActivity;
            _profileSex = _db.ProfileSex;
            _currentDateString = DateTime.Today.ToString("yyyy-MM-dd");
            _activeProfileName = _db.ActiveProfileName ?? "Standard Baseline";

            // Populate CustomDailyProfiles
            RefreshCustomProfilesList();

            // Populate Supplements matching database state
            foreach (var sup in _db.Supplements)
            {
                Supplements.Add(sup);
            }

            // Clean database state initialization
            // Sanitize / Re-index duplicate or zero IDs to prevent SQLite PRIMARY KEY constraint violations
            bool needsResave = false;
            var seenIds = new HashSet<int>();
            for (int i = 0; i < _db.FoodEntries.Count; i++)
            {
                var entry = _db.FoodEntries[i];
                if (entry.Id == 0 || seenIds.Contains(entry.Id))
                {
                    int nextId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;
                    if (nextId <= 0) nextId = 1;
                    entry.Id = nextId;
                    needsResave = true;
                }
                seenIds.Add(entry.Id);
            }
            if (needsResave)
            {
                _storageService.Save(_db);
            }

            if (!_db.HasPrepopulatedSampleLogs)
            {
                if (_db.FoodEntries.Count == 0)
                {
                    PrepopulateSampleLogs();
                }
                _db.HasPrepopulatedSampleLogs = true;
                _storageService.Save(_db);
            }

            // Populate RecurringFoods matching database state
            RefreshRecurringFoodsList();

            Trends = new TrendsViewModel(this);
            RefreshTodayData();
        }

        // Load / Refresh UI bindings based on Current Selected Date
        public void RefreshTodayData()
        {
            // Today's Foods
            CurrentDayFood.Clear();
            var foodsOnDate = _db.FoodEntries.Where(f => f.Date == CurrentDateString).ToList();
            foreach (var f in foodsOnDate)
            {
                CurrentDayFood.Add(f);
            }

            // Sync Supplements IsTaken properties
            foreach (var sup in Supplements)
            {
                sup.IsTaken = IsSupplementTakenToday(sup);
            }

            // Recompute Nutrient Intake Totals
            ComputeNutrientIntakes();

            // Refill Historical Trend Curve
            RefreshTrends();

            // Refresh advanced weekly/monthly trends
            Trends?.RefreshTrends();

            // Update Nutrient Alerts (silently on simple load/refresh)
            CheckNutrientAlerts(autoShow: false);
        }

        public void CheckNutrientAlerts(bool autoShow = false)
        {
            _activeNutrientAlerts.Clear();
            var alerts = _notificationService.EvaluateDailyNutrients(NutrientStatuses);
            foreach (var alert in alerts)
            {
                _activeNutrientAlerts.Add(alert);
            }

            NotifyPropertyChanged(nameof(ActiveNutrientAlerts));
            NotifyPropertyChanged(nameof(HasActiveNutrientAlerts));

            if (autoShow && alerts.Any())
            {
                IsAlertModalVisible = true;
            }
        }

        private void ComputeNutrientIntakes()
        {
            NutrientStatuses.Clear();

            // 1 & 2. Compute total daily nutrient intakes using our core NutritionEntry model
            var dailyEntry = NutritionEntry.ComputeForDate(CurrentDateString, _db.FoodEntries, Supplements, _db.TakenSupplementsForToday);

            // 3. Compare with standard or customized RDA definitions and calculate percentages
            var percentages = dailyEntry.CalculatePercentages(_db.CustomRdaOverrides);

            foreach (var def in Nutrients.Definitions)
            {
                double targetRda = def.Rda;
                // Apply custom overrides if they exist
                if (_db.CustomRdaOverrides.ContainsKey(def.Key))
                {
                    targetRda = _db.CustomRdaOverrides[def.Key];
                }

                double val;
                double intake = dailyEntry.Nutrients.TryGetValue(def.Key, out val) ? val : 0.0;
                double pct;
                double percentage = percentages.TryGetValue(def.Key, out pct) ? pct : 0.0;

                // Determine Health status indicator color (Red, Yellow, Green)
                StatusColor status = StatusColor.YELLOW;
                if (def.IsMaxLimit)
                {
                    if (intake > targetRda) status = StatusColor.RED;
                    else status = StatusColor.GREEN;
                }
                else
                {
                    if (percentage >= 100.0) status = StatusColor.GREEN;
                    else if (percentage >= 50.0) status = StatusColor.YELLOW;
                    else status = StatusColor.RED;
                }

                // Temporary override local target representation for the status
                var tempDefinition = new NutrientDefinition(
                    def.Key, def.Name, def.Group, targetRda, def.Unit, def.IsMaxLimit, def.Description
                );

                NutrientStatuses.Add(new NutrientStatus(tempDefinition, intake, percentage, status));
            }

            NotifyPropertyChanged(nameof(WarningNutrients));
            NotifyPropertyChanged(nameof(HasWarningNutrients));
            NotifyPropertyChanged(nameof(WarningSummaryText));
        }

        private void RefreshTrends()
        {
            DayTrends.Clear();

            // Compile logs for the last 7 days leading up to CurrentDateString
            DateTime centerDate;
            if (DateTime.TryParse(CurrentDateString, out centerDate))
            {
                var days = new List<DateTime>();
                for (int i = 6; i >= 0; i--)
                {
                    days.Add(centerDate.AddDays(-i));
                }

                foreach (var day in days)
                {
                    string dateStr = day.ToString("yyyy-MM-dd");
                    string displayLabel = day.ToString("ddd d");

                    var foods = _db.FoodEntries.Where(f => f.Date == dateStr).ToList();
                    double calories = foods.Sum(f => (f.Nutrients.ContainsKey("calories") ? f.Nutrients["calories"] : 0.0) * f.Quantity);
                    double carbs = foods.Sum(f => (f.Nutrients.ContainsKey("carbohydrates") ? f.Nutrients["carbohydrates"] : 0.0) * f.Quantity);
                    double protein = foods.Sum(f => (f.Nutrients.ContainsKey("protein") ? f.Nutrients["protein"] : 0.0) * f.Quantity);
                    double fat = foods.Sum(f => (f.Nutrients.ContainsKey("fat") ? f.Nutrients["fat"] : 0.0) * f.Quantity);

                    // Add active daily supplement nutritional boost to trend if taken on that date
                    foreach (var sup in Supplements)
                    {
                        if (_db.TakenSupplementsForToday.Contains($"{dateStr}|{sup.Name}"))
                        {
                            if (sup.Nutrients.ContainsKey("calories")) calories += sup.Nutrients["calories"];
                            if (sup.Nutrients.ContainsKey("carbohydrates")) carbs += sup.Nutrients["carbohydrates"];
                            if (sup.Nutrients.ContainsKey("protein")) protein += sup.Nutrients["protein"];
                            if (sup.Nutrients.ContainsKey("fat")) fat += sup.Nutrients["fat"];
                        }
                    }

                    DayTrends.Add(new DayTrend(dateStr, displayLabel, calories, carbs, protein, fat));
                }
            }
        }

        // Insert new logged food item with automatic Clinical Parsing (Fallback Parser)
        public void AddFoodLog(string foodName, string mealType, double quantity, string unit)
        {
            if (string.IsNullOrWhiteSpace(foodName)) return;

            var nutrientsMap = CreateFallbackEntry(foodName);
            var lastId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;

            var entry = new FoodLogEntry
            {
                Id = lastId,
                Date = CurrentDateString,
                FoodName = foodName,
                MealType = mealType,
                Quantity = quantity,
                Unit = unit,
                Nutrients = nutrientsMap
            };

            NormalizeFoodLogEntryUnit(entry);

            _db.FoodEntries.Add(entry);
            _storageService.Save(_db);
            
            RefreshTodayData();
            CheckNutrientAlerts(autoShow: true);
            OperationMessage = $"Successfully logged '{foodName}' for {mealType}!";
        }

        // Insert new logged food item with advanced AI NLP parsing, falling back to clinical mapping on error/offline
        public async Task<bool> AddFoodLogAsync(string foodName, string mealType, double quantity, string unit)
        {
            if (string.IsNullOrWhiteSpace(foodName)) return false;

            IsAiAnalyzing = true;
            OperationMessage = "AI is analyzing nutritional profile... Please wait.";

            ParsedFoodResult? parsedResult = null;
            bool usedAi = false;
            string aiError = "";

            try
            {
                parsedResult = await _geminiService.ParseFoodWithAiAsync(foodName);
                if (parsedResult != null)
                {
                    usedAi = true;
                }
            }
            catch (Exception ex)
            {
                aiError = ex.Message;
                System.Diagnostics.Debug.WriteLine($"AI Parsing Error: {ex.Message}");
            }

            Dictionary<string, double> nutrientsMap;
            string finalFoodName = foodName;
            double finalQuantity = quantity;
            string finalUnit = unit;

            if (usedAi && parsedResult != null)
            {
                nutrientsMap = parsedResult.Nutrients;
                if (!string.IsNullOrEmpty(parsedResult.FoodName))
                {
                    finalFoodName = parsedResult.FoodName;
                }
                
                // If the user specified a custom quantity other than standard 1.0, preserve it.
                // Otherwise, use the portion size calculated by the NLP model (e.g., if they typed "two eggs").
                if (quantity != 1.0)
                {
                    finalQuantity = quantity;
                }
                else
                {
                    finalQuantity = parsedResult.Quantity;
                }

                if (!string.IsNullOrEmpty(parsedResult.Unit))
                {
                    finalUnit = parsedResult.Unit;
                }
            }
            else
            {
                nutrientsMap = CreateFallbackEntry(foodName);
            }

            IsAiAnalyzing = false;

            var lastId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;

            var entry = new FoodLogEntry
            {
                Id = lastId,
                Date = CurrentDateString,
                FoodName = finalFoodName,
                MealType = mealType,
                Quantity = finalQuantity,
                Unit = finalUnit,
                Nutrients = nutrientsMap
            };

            NormalizeFoodLogEntryUnit(entry);

            _db.FoodEntries.Add(entry);
            _storageService.Save(_db);
            
            RefreshTodayData();
            CheckNutrientAlerts(autoShow: true);

            if (usedAi)
            {
                OperationMessage = $"AI successfully analyzed and logged '{finalFoodName}' for {mealType}!";
            }
            else
            {
                if (!string.IsNullOrEmpty(aiError))
                {
                    string shortError = aiError.Length > 60 ? aiError.Substring(0, 57) + "..." : aiError;
                    OperationMessage = $"Used local fallback mapping. (AI status: {shortError})";
                }
                else
                {
                    OperationMessage = $"Logged '{foodName}' for {mealType} (Clinical fallback mapping).";
                }
            }

            return true;
        }

        // Delete logged food item
        public void DeleteFoodLog(FoodLogEntry entry)
        {
            if (entry == null) return;
            _lastDeletedEntry = entry;
            
            var match = _db.FoodEntries.FirstOrDefault(f => f.Id == entry.Id);
            if (match != null)
            {
                _db.FoodEntries.Remove(match);
            }
            else
            {
                _db.FoodEntries.Remove(entry);
            }

            // Explicitly recreate the food log list in memory to clear stale references/entries
            _db.FoodEntries = new List<FoodLogEntry>(_db.FoodEntries);
            _storageService.Save(_db);
            
            RefreshTodayData();
            NotifyPropertyChanged(nameof(Database));
            OperationMessage = $"Deleted {entry.FoodName}.";
        }

        // Undo deletion
        public void UndoDelete()
        {
            if (_lastDeletedEntry != null)
            {
                _db.FoodEntries.Add(_lastDeletedEntry);
                
                // Explicitly recreate the food log list in memory
                _db.FoodEntries = new List<FoodLogEntry>(_db.FoodEntries);
                _storageService.Save(_db);
                _lastDeletedEntry = null;
                
                RefreshTodayData();
                NotifyPropertyChanged(nameof(Database));
                OperationMessage = "Restored logged item.";
            }
        }

        // Custom Overrides Target Update
        public void UpdateCustomRda(string key, double value)
        {
            var def = Nutrients.GetByKey(key);
            if (def == null) return;

            if (value <= 0.0)
            {
                if (_db.CustomRdaOverrides.ContainsKey(key))
                {
                    _db.CustomRdaOverrides.Remove(key);
                }
                OperationMessage = $"Reset target for {def.Name} to Standard Default.";
            }
            else
            {
                _db.CustomRdaOverrides[key] = value;
                OperationMessage = $"Set custom daily target for {def.Name} to {value:F1} {def.Unit}.";
            }

            _storageService.Save(_db);
            RefreshTodayData();
        }

        // Multiplier & Custom Biological standard Profiler Trigger
        public void ApplyRdaProfile(int age, string activityLevel, string sex)
        {
            // 1. Seed/apply the 41 essential nutrient clinical RDA values based on matched age and sex category
            _rdaProfileService.ApplyRdaProfileToOverrides(age, sex, _db.CustomRdaOverrides);

            // 2. Perform dynamic metabolic and activity adjustments on top of the clinical baseline
            double baseCalories = (sex == "Male")
                ? (age < 30 ? 2400.0 : (age < 51 ? 2200.0 : 2000.0))
                : (age < 30 ? 2000.0 : (age < 51 ? 1800.0 : 1600.0));

            double multiplier = activityLevel switch
            {
                "Sedentary" => 0.9,
                "Lightly Active" => 1.0,
                "Moderately Active" => 1.15,
                "Very Active" => 1.3,
                "Super Active" => 1.45,
                _ => 1.0
            };

            double finalCalories = Math.Round((baseCalories * multiplier) / 50.0) * 50.0;
            double finalCarbs = Math.Round((finalCalories * 0.50 / 4.0) / 5.0) * 5.0;
            
            double proteinPercent = (age >= 60 || activityLevel == "Very Active" || activityLevel == "Super Active") ? 0.20 : 0.16;
            double finalProtein = Math.Round((finalCalories * proteinPercent / 4.0) / 5.0) * 5.0;
            
            double finalFat = Math.Round((finalCalories * 0.30 / 9.0) / 5.0) * 5.0;

            double baseWater = (sex == "Male") ? 3700.0 : 2700.0;
            double waterAdjustment = activityLevel switch
            {
                "Sedentary" => 0.0,
                "Lightly Active" => 250.0,
                "Moderately Active" => 500.0,
                "Very Active" => 1000.0,
                "Super Active" => 1500.0,
                _ => 0.0
            };
            double finalWater = baseWater + waterAdjustment;

            double finalFiber = (sex == "Male")
                ? (age < 51 ? 38.0 : 30.0)
                : (age < 51 ? 25.0 : 21.0);

            double finalCalcium = (sex == "Female" && age >= 51) || (sex == "Male" && age >= 71) ? 1200.0 : 1000.0;
            double finalIron = (sex == "Female" && age >= 18 && age < 51) ? 18.0 : 8.0;
            double finalSodium = (activityLevel == "Very Active" || activityLevel == "Super Active") ? 2300.0 : 2000.0;
            double finalVitaminD = age >= 70 ? 20.0 : 15.0;

            // Apply calculated constraints directly into custom overrides
            _db.CustomRdaOverrides["calories"] = finalCalories;
            _db.CustomRdaOverrides["carbohydrates"] = finalCarbs;
            _db.CustomRdaOverrides["protein"] = finalProtein;
            _db.CustomRdaOverrides["fat"] = finalFat;
            _db.CustomRdaOverrides["water"] = finalWater;
            _db.CustomRdaOverrides["fiber"] = finalFiber;
            _db.CustomRdaOverrides["calcium"] = finalCalcium;
            _db.CustomRdaOverrides["iron"] = finalIron;
            _db.CustomRdaOverrides["sodium"] = finalSodium;
            _db.CustomRdaOverrides["vitamin_d"] = finalVitaminD;

            _db.ProfileAge = age;
            _db.ProfileActivity = activityLevel;
            _db.ProfileSex = sex;

            _storageService.Save(_db);
            
            _profileAge = age;
            _profileActivity = activityLevel;
            _profileSex = sex;
            NotifyPropertyChanged(nameof(ProfileAge));
            NotifyPropertyChanged(nameof(ProfileActivity));
            NotifyPropertyChanged(nameof(ProfileSex));

            RefreshTodayData();
            OperationMessage = $"RDA medical target profile applied for {sex}, {age}y, {activityLevel}!";
        }

        // Reset Standards back to system presets
        public void ResetRdaToDefaults()
        {
            _db.CustomRdaOverrides.Clear();
            _db.ProfileAge = 30;
            _db.ProfileActivity = "Lightly Active";
            _db.ProfileSex = "Female";

            _storageService.Save(_db);

            _profileAge = 30;
            _profileActivity = "Lightly Active";
            _profileSex = "Female";
            NotifyPropertyChanged(nameof(ProfileAge));
            NotifyPropertyChanged(nameof(ProfileActivity));
            NotifyPropertyChanged(nameof(ProfileSex));

            RefreshTodayData();
            OperationMessage = "Custom limits restored to national system standards!";
        }

        // Toggles supplement log for today
        public void ToggleSupplementTaken(Supplement sup, bool isTaken)
        {
            string logKey = $"{CurrentDateString}|{sup.Name}";
            if (isTaken)
            {
                if (!_db.TakenSupplementsForToday.Contains(logKey))
                    _db.TakenSupplementsForToday.Add(logKey);
            }
            else
            {
                _db.TakenSupplementsForToday.Remove(logKey);
            }

            _storageService.Save(_db);
            RefreshTodayData();
            CheckNutrientAlerts(autoShow: true);
        }

        public bool IsSupplementTakenToday(Supplement sup)
        {
            return _db.TakenSupplementsForToday.Contains($"{CurrentDateString}|{sup.Name}");
        }

        private void SaveProfile()
        {
            _db.ProfileAge = ProfileAge;
            _db.ProfileActivity = ProfileActivity;
            _db.ProfileSex = ProfileSex;
            _storageService.Save(_db);
        }

        // Clinical Fallback Parsing Dictionary to calculate dietary elements natively
        private Dictionary<string, double> CreateFallbackEntry(string foodInput)
        {
            var lower = foodInput.ToLower();
            var accumulated = Nutrients.Definitions.ToDictionary(def => def.Key, def => 0.0);

            void AddNutrient(string key, double val)
            {
                if (accumulated.ContainsKey(key))
                {
                    accumulated[key] += val;
                }
            }

            bool matched = false;

            if (lower.Contains("egg"))
            {
                matched = true;
                AddNutrient("calories", 140.0);
                AddNutrient("protein", 12.0);
                AddNutrient("fat", 10.0);
                AddNutrient("saturated_fat", 3.0);
                AddNutrient("cholesterol", 370.0);
                AddNutrient("sodium", 140.0);
                AddNutrient("vitamin_d", 2.0);
                AddNutrient("choline", 290.0);
                AddNutrient("vitamin_a", 160.0);
                AddNutrient("iron", 1.8);
                AddNutrient("calcium", 50.0);
                AddNutrient("water", 150.0);
            }
            if (lower.Contains("banana"))
            {
                matched = true;
                AddNutrient("calories", 105.0);
                AddNutrient("carbohydrates", 27.0);
                AddNutrient("fiber", 3.0);
                AddNutrient("sugars", 14.0);
                AddNutrient("potassium", 422.0);
                AddNutrient("vitamin_c", 10.0);
                AddNutrient("vitamin_b6", 0.4);
                AddNutrient("magnesium", 32.0);
                AddNutrient("water", 90.0);
            }
            if (lower.Contains("chicken") || lower.Contains("poultry") || lower.Contains("turkey") || lower.Contains("breast"))
            {
                matched = true;
                AddNutrient("calories", 220.0);
                AddNutrient("protein", 35.0);
                AddNutrient("fat", 8.0);
                AddNutrient("saturated_fat", 2.2);
                AddNutrient("cholesterol", 95.0);
                AddNutrient("sodium", 80.0);
                AddNutrient("potassium", 300.0);
                AddNutrient("niacin", 12.0);
                AddNutrient("vitamin_b6", 0.6);
                AddNutrient("zinc", 2.0);
                AddNutrient("selenium", 25.0);
                AddNutrient("choline", 90.0);
                AddNutrient("water", 65.0);
            }
            if (lower.Contains("beef") || lower.Contains("steak") || lower.Contains("meat") || lower.Contains("burger"))
            {
                matched = true;
                AddNutrient("calories", 280.0);
                AddNutrient("protein", 28.0);
                AddNutrient("fat", 18.0);
                AddNutrient("saturated_fat", 7.0);
                AddNutrient("cholesterol", 85.0);
                AddNutrient("sodium", 70.0);
                AddNutrient("potassium", 350.0);
                AddNutrient("iron", 3.0);
                AddNutrient("zinc", 6.0);
                AddNutrient("vitamin_b12", 2.5);
                AddNutrient("phosphorus", 220.0);
                AddNutrient("choline", 80.0);
                AddNutrient("water", 60.0);
            }
            if (lower.Contains("apple"))
            {
                matched = true;
                AddNutrient("calories", 95.0);
                AddNutrient("carbohydrates", 25.0);
                AddNutrient("fiber", 4.4);
                AddNutrient("sugars", 19.0);
                AddNutrient("potassium", 195.0);
                AddNutrient("vitamin_c", 8.4);
                AddNutrient("water", 150.0);
            }
            if (lower.Contains("bread") || lower.Contains("toast") || lower.Contains("sandwich") || lower.Contains("bun"))
            {
                matched = true;
                AddNutrient("calories", 150.0);
                AddNutrient("carbohydrates", 28.0);
                AddNutrient("fiber", 2.5);
                AddNutrient("protein", 5.0);
                AddNutrient("fat", 2.0);
                AddNutrient("sodium", 300.0);
                AddNutrient("folate", 45.0);
                AddNutrient("iron", 1.5);
                AddNutrient("thiamin", 0.2);
                AddNutrient("riboflavin", 0.15);
                AddNutrient("water", 35.0);
            }
            if (lower.Contains("milk") || lower.Contains("cheese") || lower.Contains("yogurt") || lower.Contains("dairy") || lower.Contains("butter") || lower.Contains("honey") || lower.Contains("chia"))
            {
                matched = true;
                AddNutrient("calories", 180.0);
                AddNutrient("carbohydrates", 12.0);
                AddNutrient("sugars", 12.0);
                AddNutrient("protein", 9.0);
                AddNutrient("fat", 9.0);
                AddNutrient("saturated_fat", 6.0);
                AddNutrient("cholesterol", 30.0);
                AddNutrient("calcium", 300.0);
                AddNutrient("phosphorus", 250.0);
                AddNutrient("potassium", 380.0);
                AddNutrient("sodium", 150.0);
                AddNutrient("vitamin_b12", 1.2);
                AddNutrient("riboflavin", 0.4);
                AddNutrient("vitamin_d", 2.5);
                AddNutrient("water", 200.0);
            }
            if (lower.Contains("rice") || lower.Contains("grain") || lower.Contains("oat") || lower.Contains("cereal") || lower.Contains("pasta") || lower.Contains("oatmeal"))
            {
                matched = true;
                AddNutrient("calories", 220.0);
                AddNutrient("carbohydrates", 45.0);
                AddNutrient("protein", 5.0);
                AddNutrient("fiber", 1.5);
                AddNutrient("fat", 1.0);
                AddNutrient("sodium", 5.0);
                AddNutrient("thiamin", 0.25);
                AddNutrient("niacin", 2.5);
                AddNutrient("iron", 1.2);
                AddNutrient("potassium", 60.0);
                AddNutrient("water", 130.0);
            }
            if (lower.Contains("salmon") || lower.Contains("fish") || lower.Contains("tuna") || lower.Contains("seafood"))
            {
                matched = true;
                AddNutrient("calories", 210.0);
                AddNutrient("protein", 25.0);
                AddNutrient("fat", 11.0);
                AddNutrient("saturated_fat", 2.0);
                AddNutrient("polyunsaturated_fat", 4.0);
                AddNutrient("omega3", 2.3);
                AddNutrient("cholesterol", 60.0);
                AddNutrient("vitamin_d", 12.0);
                AddNutrient("vitamin_b12", 4.5);
                AddNutrient("selenium", 35.0);
                AddNutrient("sodium", 60.0);
                AddNutrient("potassium", 400.0);
                AddNutrient("niacin", 9.0);
                AddNutrient("phosphorus", 250.0);
                AddNutrient("water", 70.0);
            }
            if (lower.Contains("spinach") || lower.Contains("salad") || lower.Contains("lettuce") || lower.Contains("broccoli") || lower.Contains("vegetable") || lower.Contains("tomato") || lower.Contains("carrot"))
            {
                matched = true;
                AddNutrient("calories", 45.0);
                AddNutrient("carbohydrates", 8.0);
                AddNutrient("fiber", 3.0);
                AddNutrient("sugars", 3.0);
                AddNutrient("protein", 2.0);
                AddNutrient("vitamin_a", 500.0);
                AddNutrient("vitamin_c", 45.0);
                AddNutrient("vitamin_k", 150.0);
                AddNutrient("calcium", 90.0);
                AddNutrient("iron", 2.7);
                AddNutrient("potassium", 550.0);
                AddNutrient("magnesium", 79.0);
                AddNutrient("folate", 190.0);
                AddNutrient("water", 90.0);
            }

            // Universal fallback defaults if completely unkeyed to avoid zero charts
            if (!matched)
            {
                AddNutrient("calories", 180.0);
                AddNutrient("carbohydrates", 22.0);
                AddNutrient("protein", 6.0);
                AddNutrient("fat", 4.0);
                AddNutrient("sodium", 150.0);
                AddNutrient("water", 100.0);
            }

            return accumulated;
        }

        // Dynamic Sample Log population matching Kotlin exactly to show trends immediately
        private void PrepopulateSampleLogs()
        {
            var today = DateTime.Today;
            int nextId = 1;

            // Today: Balanced day
            _db.FoodEntries.Add(new FoodLogEntry(
                today.ToString("yyyy-MM-dd"),
                "Greek yogurt with honey and chia seeds",
                "Breakfast", 1.0, "serving", CreateFallbackEntry("greek yogurt chia honey")
            ) { Id = nextId++ });
            _db.FoodEntries.Add(new FoodLogEntry(
                today.ToString("yyyy-MM-dd"),
                "Grilled chicken breast, brown rice and broccoli",
                "Lunch", 1.0, "serving", CreateFallbackEntry("grilled chicken brown rice broccoli")
            ) { Id = nextId++ });
            _db.FoodEntries.Add(new FoodLogEntry(
                today.ToString("yyyy-MM-dd"),
                "Baked salmon with spinach salad",
                "Dinner", 1.0, "serving", CreateFallbackEntry("baked salmon spinach salad")
            ) { Id = nextId++ });

            // Yesterday: High-sodium, low vitamin day
            var yesterday = today.AddDays(-1);
            var burgerNutrients = CreateFallbackEntry("burger bacon cheese");
            burgerNutrients["sodium"] = 2600.0;
            burgerNutrients["saturated_fat"] = 25.0;
            burgerNutrients["calories"] = 950.0;
            burgerNutrients["vitamin_c"] = 0.0;
            burgerNutrients["vitamin_d"] = 0.0;
            _db.FoodEntries.Add(new FoodLogEntry(
                yesterday.ToString("yyyy-MM-dd"),
                "Double bacon cheeseburger",
                "Lunch", 1.0, "serving", burgerNutrients
            ) { Id = nextId++ });

            var friesNutrients = CreateFallbackEntry("fries and soda");
            friesNutrients["sodium"] = 800.0;
            friesNutrients["sugars"] = 45.0;
            friesNutrients["calories"] = 400.0;
            friesNutrients["vitamin_c"] = 2.0;
            _db.FoodEntries.Add(new FoodLogEntry(
                yesterday.ToString("yyyy-MM-dd"),
                "French fries and regular soda",
                "Snack", 1.0, "serving", friesNutrients
            ) { Id = nextId++ });

            // 2 Days Ago: Light hydration, low-iron day
            var twoDaysAgo = today.AddDays(-2);
            _db.FoodEntries.Add(new FoodLogEntry(
                twoDaysAgo.ToString("yyyy-MM-dd"),
                "Oatmeal with sliced banana",
                "Breakfast", 1.0, "serving", CreateFallbackEntry("oatmeal banana")
            ) { Id = nextId++ });
            _db.FoodEntries.Add(new FoodLogEntry(
                twoDaysAgo.ToString("yyyy-MM-dd"),
                "Garden salad with olive oil",
                "Lunch", 1.0, "serving", CreateFallbackEntry("salad garden lettuce olive oil")
            ) { Id = nextId++ });

            _storageService.Save(_db);
        }

        /// <summary>
        /// Exports the full food journal log history as a flat, database-friendly CSV table.
        /// Includes: Date, Meal Type, Food Name, Quantity, Unit, Calories, and all defined baseline nutrients.
        /// </summary>
        public string ExportAllLogsToCsv()
        {
            var sb = new System.Text.StringBuilder();
            
            // Build header columns
            var columns = new List<string> { "Date", "MealType", "FoodName", "Quantity", "Unit", "CaloriesKcal" };
            foreach (var def in Nutrients.Definitions)
            {
                // Clean headers to be spreadsheet safe
                string safeHeader = def.Name.Replace(" ", "").Replace("(", "").Replace(")", "").Replace("/", "").Replace("-", "");
                columns.Add($"{safeHeader}_{def.Unit}");
            }

            sb.AppendLine(string.Join(",", columns));

            foreach (var entry in _db.FoodEntries)
            {
                var rowValues = new List<string>
                {
                    entry.Date,
                    entry.MealType,
                    entry.FoodName.Contains(",") ? $"\"{entry.FoodName}\"" : entry.FoodName,
                    entry.Quantity.ToString("F2", System.Globalization.CultureInfo.InvariantCulture),
                    entry.Unit.Contains(",") ? $"\"{entry.Unit}\"" : entry.Unit,
                    (entry.Calories).ToString("F2", System.Globalization.CultureInfo.InvariantCulture)
                };

                foreach (var def in Nutrients.Definitions)
                {
                    double value = 0.0;
                    double rawVal;
                    if (entry.Nutrients != null && entry.Nutrients.TryGetValue(def.Key, out rawVal))
                    {
                        value = rawVal * entry.Quantity;
                    }
                    rowValues.Add(value.ToString("F2", System.Globalization.CultureInfo.InvariantCulture));
                }

                sb.AppendLine(string.Join(",", rowValues));
            }

            return sb.ToString();
        }

        /// <summary>
        /// Generates a CSV format report of the current daily nutrition tracking data.
        /// </summary>
        public string GenerateCsvReport()
        {
            var sb = new System.Text.StringBuilder();
            
            // Header information
            sb.AppendLine("=== NUTRI_SCRIBE DAILY CLINICAL REPORT ===");
            sb.AppendLine($"Date,{CurrentDateString}");
            sb.AppendLine($"Profile Age,{ProfileAge}");
            sb.AppendLine($"Profile Sex,{ProfileSex}");
            sb.AppendLine($"Profile Activity,{ProfileActivity}");
            sb.AppendLine();

            // Logged Foods Section
            sb.AppendLine("=== LOGGED FOOD INTAKES ===");
            sb.AppendLine("Meal Type,Food Name,Quantity,Unit");
            foreach (var food in CurrentDayFood)
            {
                // Escaping food name for safety if it contains comma
                string safeName = food.FoodName.Contains(",") ? $"\"{food.FoodName}\"" : food.FoodName;
                sb.AppendLine($"{food.MealType},{safeName},{food.Quantity:F1},{food.Unit}");
            }
            sb.AppendLine();

            // Taken Supplements Section
            sb.AppendLine("=== TAKEN SUPPLEMENTS ===");
            sb.AppendLine("Name,Dosage,Notes");
            foreach (var sup in Supplements)
            {
                string key = $"{CurrentDateString}|{sup.Name}";
                if (_db.TakenSupplementsForToday.Contains(key))
                {
                    string safeName = sup.Name.Contains(",") ? $"\"{sup.Name}\"" : sup.Name;
                    string safeNotes = sup.Notes.Contains(",") ? $"\"{sup.Notes}\"" : sup.Notes;
                    sb.AppendLine($"{safeName},{sup.Dosage},{safeNotes}");
                }
            }
            sb.AppendLine();

            // Nutrient Statuses Table
            sb.AppendLine("=== DETAILED NUTRIENT STATUS ANALYSIS ===");
            sb.AppendLine("Nutrient Name,Category,Your Intake,Daily Target RDA,Unit,Percentage Met,Status");
            foreach (var status in NutrientStatuses)
            {
                string statusText = status.Status switch
                {
                    StatusColor.GREEN => "Optimal/Healthy",
                    StatusColor.YELLOW => "Sub-optimal Guidance",
                    StatusColor.RED => status.Definition.IsMaxLimit ? "Limit Exceeded Risk" : "Deficient Attention Required",
                    _ => "Unknown"
                };
                string safeName = status.Definition.Name.Contains(",") ? $"\"{status.Definition.Name}\"" : status.Definition.Name;
                sb.AppendLine($"{safeName},{status.Definition.Group},{status.Intake:F1},{status.Definition.Rda:F1},{status.Definition.Unit},{status.Percentage:F1}%,{statusText}");
            }

            return sb.ToString();
        }

        /// <summary>
        /// Generates a structured plain-text / Markdown report of the current daily nutrition tracking data.
        /// </summary>
        public string GenerateTextReport()
        {
            var sb = new System.Text.StringBuilder();

            sb.AppendLine("==================================================================================");
            sb.AppendLine("                       NUTRI-SCRIBE DAILY CLINICAL REPORT                         ");
            sb.AppendLine("==================================================================================");
            sb.AppendLine($"Date Code:       {CurrentDateString}");
            sb.AppendLine($"Profile Sex:     {ProfileSex}");
            sb.AppendLine($"Profile Age:     {ProfileAge} years");
            sb.AppendLine($"Activity Index:  {ProfileActivity}");
            sb.AppendLine("==================================================================================");
            sb.AppendLine();

            // Foods
            sb.AppendLine("----------------------------------------------------------------------------------");
            sb.AppendLine("1. LOGGED FOOD JOURNAL                                                           ");
            sb.AppendLine("----------------------------------------------------------------------------------");
            if (CurrentDayFood.Count == 0)
            {
                sb.AppendLine(" (No food logs completed for this date.)");
            }
            else
            {
                sb.AppendLine(string.Format(" {0,-12} | {1,-35} | {2,10} {3,-5}", "MEAL TYPE", "FOOD ITEM DESCRIPTION", "QTY", "UNIT"));
                sb.AppendLine(" --------------------------------------------------------------------------------");
                foreach (var food in CurrentDayFood)
                {
                    sb.AppendLine(string.Format(" {0,-12} | {1,-35} | {2,10:F1} {3,-5}", food.MealType, food.FoodName, food.Quantity, food.Unit));
                }
            }
            sb.AppendLine();

            // Supplements
            sb.AppendLine("----------------------------------------------------------------------------------");
            sb.AppendLine("2. SUPPLEMENTS CO-PATTERNS LOGGED                                                ");
            sb.AppendLine("----------------------------------------------------------------------------------");
            var activeSups = Supplements.Where(sup => _db.TakenSupplementsForToday.Contains($"{CurrentDateString}|{sup.Name}")).ToList();
            if (activeSups.Count == 0)
            {
                sb.AppendLine(" (No active clinical supplements checked for today.)");
            }
            else
            {
                sb.AppendLine(string.Format(" {0,-25} | {1,-15} | {2,-30}", "SUPPLEMENT NAME", "DOSAGE RATE", "CLINICAL KEY NOTES"));
                sb.AppendLine(" --------------------------------------------------------------------------------");
                foreach (var sup in activeSups)
                {
                    sb.AppendLine(string.Format(" {0,-25} | {1,-15} | {2,-30}", sup.Name, sup.Dosage, sup.Notes));
                }
            }
            sb.AppendLine();

            // Detailed Nutrient Deficiencies Section
            sb.AppendLine("----------------------------------------------------------------------------------");
            sb.AppendLine("3. ACTIVE NUTRIENT DEFICIENCIES (INTAKE < 80% RDA RECOMMENDED VALUE)             ");
            sb.AppendLine("----------------------------------------------------------------------------------");
            var warningNutrients = NutrientStatuses.Where(s => s.IsWarning).ToList();
            if (warningNutrients.Count == 0)
            {
                sb.AppendLine(" Perfect Daily Balance! No critical pre-clinical nutritional deficiencies detected.");
            }
            else
            {
                sb.AppendLine($" Detected {warningNutrients.Count} pre-clinical nutrition deficiencies:");
                sb.AppendLine();
                sb.AppendLine(string.Format(" {0,-32} | {1,-14} | {2,-14} | {3,-8} | {4}", "DEFICIENT NUTRIENT", "YOUR INTAKE", "TARGET RDA", "RDA %", "STATUS CLINICAL NOTE"));
                sb.AppendLine(" --------------------------------------------------------------------------------");
                foreach (var status in warningNutrients)
                {
                    string intakeStr = $"{status.Intake:F1} {status.Definition.Unit}";
                    string rdaStr = $"{status.Definition.Rda:F1} {status.Definition.Unit}";
                    string note = status.Percentage < 50.0 ? "CRITICAL DEFICIT (Action Advised)" : "MARGINAL DEFICIT (Monitor)";
                    sb.AppendLine(string.Format(" {0,-32} | {1,-14} | {2,-14} | {3,-7:F0}% | {4}", 
                        status.Definition.Name, 
                        intakeStr, 
                        rdaStr, 
                        status.Percentage, 
                        note));
                }
            }
            sb.AppendLine();

            // Detailed Nutrient Status Table
            sb.AppendLine("----------------------------------------------------------------------------------");
            sb.AppendLine("4. CLINICAL NUTRIENT STATUS ANALYSIS                                               ");
            sb.AppendLine("----------------------------------------------------------------------------------");
            sb.AppendLine(string.Format(" {0,-32} | {1,-16} | {2,-14} | {3,-14} | {4,-8} | {5}", "NUTRIENT", "CATEGORY", "YOUR INTAKE", "TARGET RDA", "MET %", "STATUS REFERENCE"));
            sb.AppendLine(" ----------------------------------------------------------------------------------");
            
            foreach (var status in NutrientStatuses)
            {
                string statusText = status.Status switch
                {
                    StatusColor.GREEN => "OPTIMAL",
                    StatusColor.YELLOW => "SUB-OPTIMAL",
                    StatusColor.RED => status.Definition.IsMaxLimit ? "EXCEEDED" : "DEFICIENT",
                    _ => "UNKNOWN"
                };

                string intakeStr = $"{status.Intake:F1} {status.Definition.Unit}";
                string rdaStr = $"{status.Definition.Rda:F1} {status.Definition.Unit}";
                sb.AppendLine(string.Format(" {0,-32} | {1,-16} | {2,-14} | {3,-14} | {4,-7:F0}% | {5}", 
                    status.Definition.Name, 
                    status.Definition.Group, 
                    intakeStr, 
                    rdaStr, 
                    status.Percentage, 
                    statusText));
            }
            sb.AppendLine("==================================================================================");
            sb.AppendLine($" Generated from NutriScribe base engine at: {DateTime.Now}");

            return sb.ToString();
        }

        /// <summary>
        /// Generates a beautifully designed responsive HTML report of the current daily nutrition tracking data.
        /// </summary>
        public string GenerateHtmlReport()
        {
            var sb = new System.Text.StringBuilder();

            sb.AppendLine("<!DOCTYPE html>");
            sb.AppendLine("<html lang=\"en\">");
            sb.AppendLine("<head>");
            sb.AppendLine("    <meta charset=\"UTF-8\">");
            sb.AppendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
            sb.AppendLine("    <title>NutriScribe Clinical Daily Report</title>");
            sb.AppendLine("    <style>");
            sb.AppendLine("        :root {");
            sb.AppendLine("            --bg-color: #0f172a;");
            sb.AppendLine("            --card-bg: #1e293b;");
            sb.AppendLine("            --primary: #38bdf8;");
            sb.AppendLine("            --text: #f8fafc;");
            sb.AppendLine("            --text-secondary: #94a3b8;");
            sb.AppendLine("            --border: #334155;");
            sb.AppendLine("            --success: #10b981;");
            sb.AppendLine("            --warning: #f59e0b;");
            sb.AppendLine("            --danger: #ef4444;");
            sb.AppendLine("        }");
            sb.AppendLine("        body {");
            sb.AppendLine("            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;");
            sb.AppendLine("            background-color: var(--bg-color);");
            sb.AppendLine("            color: var(--text);");
            sb.AppendLine("            margin: 0;");
            sb.AppendLine("            padding: 24px;");
            sb.AppendLine("            line-height: 1.6;");
            sb.AppendLine("        }");
            sb.AppendLine("        .container {");
            sb.AppendLine("            max-width: 1000px;");
            sb.AppendLine("            margin: 0 auto;");
            sb.AppendLine("        }");
            sb.AppendLine("        .header {");
            sb.AppendLine("            text-align: center;");
            sb.AppendLine("            padding: 32px 24px;");
            sb.AppendLine("            background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);");
            sb.AppendLine("            border: 1px solid var(--border);");
            sb.AppendLine("            border-radius: 12px;");
            sb.AppendLine("            margin-bottom: 24px;");
            sb.AppendLine("        }");
            sb.AppendLine("        .header h1 {");
            sb.AppendLine("            margin: 0 0 8px 0;");
            sb.AppendLine("            color: var(--primary);");
            sb.AppendLine("            font-size: 2.2rem;");
            sb.AppendLine("            letter-spacing: 1px;");
            sb.AppendLine("        }");
            sb.AppendLine("        .header p {");
            sb.AppendLine("            margin: 0;");
            sb.AppendLine("            color: var(--text-secondary);");
            sb.AppendLine("            font-size: 1rem;");
            sb.AppendLine("        }");
            sb.AppendLine("        .meta-grid {");
            sb.AppendLine("            display: grid;");
            sb.AppendLine("            grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));");
            sb.AppendLine("            gap: 16px;");
            sb.AppendLine("            margin-top: 24px;");
            sb.AppendLine("        }");
            sb.AppendLine("        .meta-card {");
            sb.AppendLine("            background: rgba(255,255,255,0.03);");
            sb.AppendLine("            border: 1px solid var(--border);");
            sb.AppendLine("            padding: 12px 16px;");
            sb.AppendLine("            border-radius: 8px;");
            sb.AppendLine("            text-align: center;");
            sb.AppendLine("        }");
            sb.AppendLine("        .meta-card .label {");
            sb.AppendLine("            font-size: 0.8rem;");
            sb.AppendLine("            color: var(--text-secondary);");
            sb.AppendLine("            text-transform: uppercase;");
            sb.AppendLine("            margin-bottom: 4px;");
            sb.AppendLine("        }");
            sb.AppendLine("        .meta-card .value {");
            sb.AppendLine("            font-size: 1.1rem;");
            sb.AppendLine("            font-weight: bold;");
            sb.AppendLine("            color: var(--text);");
            sb.AppendLine("        }");
            sb.AppendLine("        .section-card {");
            sb.AppendLine("            background-color: var(--card-bg);");
            sb.AppendLine("            border: 1px solid var(--border);");
            sb.AppendLine("            border-radius: 12px;");
            sb.AppendLine("            padding: 24px;");
            sb.AppendLine("            margin-bottom: 24px;");
            sb.AppendLine("            box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1);");
            sb.AppendLine("        }");
            sb.AppendLine("        .section-card h2 {");
            sb.AppendLine("            margin-top: 0;");
            sb.AppendLine("            margin-bottom: 16px;");
            sb.AppendLine("            font-size: 1.4rem;");
            sb.AppendLine("            border-bottom: 2px solid var(--border);");
            sb.AppendLine("            padding-bottom: 8px;");
            sb.AppendLine("            color: var(--primary);");
            sb.AppendLine("        }");
            sb.AppendLine("        table {");
            sb.AppendLine("            width: 100%;");
            sb.AppendLine("            border-collapse: collapse;");
            sb.AppendLine("            margin-top: 8px;");
            sb.AppendLine("        }");
            sb.AppendLine("        th, td {");
            sb.AppendLine("            padding: 12px 16px;");
            sb.AppendLine("            text-align: left;");
            sb.AppendLine("            border-bottom: 1px solid var(--border);");
            sb.AppendLine("        }");
            sb.AppendLine("        th {");
            sb.AppendLine("            background-color: rgba(255, 255, 255, 0.02);");
            sb.AppendLine("            color: var(--text-secondary);");
            sb.AppendLine("            font-weight: 600;");
            sb.AppendLine("            font-size: 0.9rem;");
            sb.AppendLine("        }");
            sb.AppendLine("        tr:hover {");
            sb.AppendLine("            background-color: rgba(255, 255, 255, 0.01);");
            sb.AppendLine("        }");
            sb.AppendLine("        .badge {");
            sb.AppendLine("            display: inline-block;");
            sb.AppendLine("            padding: 4px 10px;");
            sb.AppendLine("            border-radius: 9999px;");
            sb.AppendLine("            font-size: 0.75rem;");
            sb.AppendLine("            font-weight: bold;");
            sb.AppendLine("            text-transform: uppercase;");
            sb.AppendLine("        }");
            sb.AppendLine("        .badge-optimal {");
            sb.AppendLine("            background-color: rgba(16, 185, 129, 0.15);");
            sb.AppendLine("            color: var(--success);");
            sb.AppendLine("        }");
            sb.AppendLine("        .badge-suboptimal {");
            sb.AppendLine("            background-color: rgba(245, 158, 11, 0.15);");
            sb.AppendLine("            color: var(--warning);");
            sb.AppendLine("        }");
            sb.AppendLine("        .badge-deficit {");
            sb.AppendLine("            background-color: rgba(239, 68, 68, 0.15);");
            sb.AppendLine("            color: var(--danger);");
            sb.AppendLine("        }");
            sb.AppendLine("        .empty-state {");
            sb.AppendLine("            color: var(--text-secondary);");
            sb.AppendLine("            font-style: italic;");
            sb.AppendLine("            padding: 12px 0;");
            sb.AppendLine("        }");
            sb.AppendLine("        .footer {");
            sb.AppendLine("            text-align: center;");
            sb.AppendLine("            padding: 24px 0;");
            sb.AppendLine("            color: var(--text-secondary);");
            sb.AppendLine("            font-size: 0.85rem;");
            sb.AppendLine("            border-top: 1px solid var(--border);");
            sb.AppendLine("            margin-top: 48px;");
            sb.AppendLine("        }");
            sb.AppendLine("        @media (max-width: 640px) {");
            sb.AppendLine("            body { padding: 12px; }");
            sb.AppendLine("            .header h1 { font-size: 1.8rem; }");
            sb.AppendLine("            th, td { padding: 8px 10px; font-size: 0.85rem; }");
            sb.AppendLine("        }");
            sb.AppendLine("    </style>");
            sb.AppendLine("</head>");
            sb.AppendLine("<body>");
            sb.AppendLine("    <div class=\"container\">");
            
            // Header
            sb.AppendLine("        <div class=\"header\">");
            sb.AppendLine("            <h1>NUTRI-SCRIBE DAILY REPORT</h1>");
            sb.AppendLine("            <p>Comprehensive Clinical Nutrition Summary & Intake Status Analysis</p>");
            sb.AppendLine("            <div class=\"meta-grid\">");
            sb.AppendLine($"                <div class=\"meta-card\"><div class=\"label\">Date</div><div class=\"value\">{CurrentDateString}</div></div>");
            sb.AppendLine($"                <div class=\"meta-card\"><div class=\"label\">Biological Sex</div><div class=\"value\">{ProfileSex}</div></div>");
            sb.AppendLine($"                <div class=\"meta-card\"><div class=\"label\">Age</div><div class=\"value\">{ProfileAge} yrs</div></div>");
            sb.AppendLine($"                <div class=\"meta-card\"><div class=\"label\">Activity</div><div class=\"value\">{ProfileActivity}</div></div>");
            sb.AppendLine("            </div>");
            sb.AppendLine("        </div>");

            // Section 1: Food Journal
            sb.AppendLine("        <div class=\"section-card\">");
            sb.AppendLine("            <h2>1. Food Journal Logs</h2>");
            if (CurrentDayFood.Count == 0)
            {
                sb.AppendLine("            <p class=\"empty-state\">No food logs recorded for this date.</p>");
            }
            else
            {
                sb.AppendLine("            <table>");
                sb.AppendLine("                <thead>");
                sb.AppendLine("                    <tr>");
                sb.AppendLine("                        <th>Meal Category</th>");
                sb.AppendLine("                        <th>Description</th>");
                sb.AppendLine("                        <th style=\"text-align: right;\">Quantity</th>");
                sb.AppendLine("                        <th>Unit</th>");
                sb.AppendLine("                    </tr>");
                sb.AppendLine("                </thead>");
                sb.AppendLine("                <tbody>");
                foreach (var food in CurrentDayFood)
                {
                    sb.AppendLine("                    <tr>");
                    sb.AppendLine($"                        <td style=\"font-weight: 500;\">{System.Net.WebUtility.HtmlEncode(food.MealType)}</td>");
                    sb.AppendLine($"                        <td>{System.Net.WebUtility.HtmlEncode(food.FoodName)}</td>");
                    sb.AppendLine($"                        <td style=\"text-align: right;\">{food.Quantity:F1}</td>");
                    sb.AppendLine($"                        <td>{System.Net.WebUtility.HtmlEncode(food.Unit)}</td>");
                    sb.AppendLine("                    </tr>");
                }
                sb.AppendLine("                </tbody>");
                sb.AppendLine("            </table>");
            }
            sb.AppendLine("        </div>");

            // Section 2: Supplements
            sb.AppendLine("        <div class=\"section-card\">");
            sb.AppendLine("            <h2>2. Supplement Logs</h2>");
            var activeSups = Supplements.Where(sup => _db.TakenSupplementsForToday.Contains($"{CurrentDateString}|{sup.Name}")).ToList();
            if (activeSups.Count == 0)
            {
                sb.AppendLine("            <p class=\"empty-state\">No supplements logged today.</p>");
            }
            else
            {
                sb.AppendLine("            <table>");
                sb.AppendLine("                <thead>");
                sb.AppendLine("                    <tr>");
                sb.AppendLine("                        <th>Supplement Name</th>");
                sb.AppendLine("                        <th>Dosage</th>");
                sb.AppendLine("                        <th>Clinical Notes</th>");
                sb.AppendLine("                    </tr>");
                sb.AppendLine("                </thead>");
                sb.AppendLine("                <tbody>");
                foreach (var sup in activeSups)
                {
                    sb.AppendLine("                    <tr>");
                    sb.AppendLine($"                        <td style=\"font-weight: 500;\">{System.Net.WebUtility.HtmlEncode(sup.Name)}</td>");
                    sb.AppendLine($"                        <td>{System.Net.WebUtility.HtmlEncode(sup.Dosage)}</td>");
                    sb.AppendLine($"                        <td style=\"color: var(--text-secondary);\">{System.Net.WebUtility.HtmlEncode(sup.Notes)}</td>");
                    sb.AppendLine("                    </tr>");
                }
                sb.AppendLine("                </tbody>");
                sb.AppendLine("            </table>");
            }
            sb.AppendLine("        </div>");

            // Section 3: Nutrient Statuses
            sb.AppendLine("        <div class=\"section-card\">");
            sb.AppendLine("            <h2>3. Clinical Nutrient Status Analysis</h2>");
            sb.AppendLine("            <table>");
            sb.AppendLine("                <thead>");
            sb.AppendLine("                    <tr>");
            sb.AppendLine("                        <th>Nutrient</th>");
            sb.AppendLine("                        <th>Category</th>");
            sb.AppendLine("                        <th style=\"text-align: right;\">Your Intake</th>");
            sb.AppendLine("                        <th style=\"text-align: right;\">Target RDA</th>");
            sb.AppendLine("                        <th style=\"text-align: right;\">Met %</th>");
            sb.AppendLine("                        <th style=\"text-align: center;\">Clinical Indicator</th>");
            sb.AppendLine("                    </tr>");
            sb.AppendLine("                </thead>");
            sb.AppendLine("                <tbody>");
            foreach (var status in NutrientStatuses)
            {
                string badgeClass = status.Status switch
                {
                    StatusColor.GREEN => "badge-optimal",
                    StatusColor.YELLOW => "badge-suboptimal",
                    StatusColor.RED => "badge-deficit",
                    _ => ""
                };
                string statusText = status.Status switch
                {
                    StatusColor.GREEN => "Optimal",
                    StatusColor.YELLOW => "Sub-Optimal",
                    StatusColor.RED => status.Definition.IsMaxLimit ? "Limit Exceeded" : "Deficient",
                    _ => "Unknown"
                };

                sb.AppendLine("                    <tr>");
                sb.AppendLine($"                        <td style=\"font-weight: 500;\">{System.Net.WebUtility.HtmlEncode(status.Definition.Name)}</td>");
                sb.AppendLine($"                        <td style=\"color: var(--text-secondary);\">{System.Net.WebUtility.HtmlEncode(status.Definition.Group.GetDisplayName())}</td>");
                sb.AppendLine($"                        <td style=\"text-align: right;\">{status.Intake:F1} {System.Net.WebUtility.HtmlEncode(status.Definition.Unit)}</td>");
                sb.AppendLine($"                        <td style=\"text-align: right;\">{status.Definition.Rda:F1} {System.Net.WebUtility.HtmlEncode(status.Definition.Unit)}</td>");
                sb.AppendLine($"                        <td style=\"text-align: right; font-weight: bold;\">{status.Percentage:F1}%</td>");
                sb.AppendLine($"                        <td style=\"text-align: center;\"><span class=\"badge {badgeClass}\">{statusText}</span></td>");
                sb.AppendLine("                    </tr>");
            }
            sb.AppendLine("                </tbody>");
            sb.AppendLine("            </table>");
            sb.AppendLine("        </div>");

            // Footer
            sb.AppendLine("        <div class=\"footer\">");
            sb.AppendLine($"            Report compiled by NutriScribe Daily Clinical Suite on {DateTime.Now:MMMM dd, yyyy} at {DateTime.Now:hh:mm tt}");
            sb.AppendLine("            <br>CONFIDENTIAL - Generated directly from local medical baseline definitions. All data resides strictly on-device.");
            sb.AppendLine("        </div>");
            sb.AppendLine("    </div>");
            sb.AppendLine("</body>");
            sb.AppendLine("</html>");

            return sb.ToString();
        }

        public string GenerateWeeklyHtmlReport()
        {
            DateTime centerDate;
            if (!DateTime.TryParse(CurrentDateString, out centerDate))
            {
                centerDate = DateTime.Today;
            }

            var days = new List<DateTime>();
            for (int i = 6; i >= 0; i--)
            {
                days.Add(centerDate.AddDays(-i));
            }

            string startDateStr = days.First().ToString("MMMM dd, yyyy");
            string endDateStr = days.Last().ToString("MMMM dd, yyyy");

            var dayStrings = days.Select(d => d.ToString("yyyy-MM-dd")).ToHashSet();
            var weeklyFoodLogs = _db.FoodEntries.Where(f => dayStrings.Contains(f.Date)).ToList();

            var nutrientTotals = new Dictionary<string, double>();
            foreach (var def in Models.Nutrients.Definitions)
            {
                nutrientTotals[def.Key] = 0.0;
            }

            int activeDaysCount = 7;
            foreach (var day in days)
            {
                string dateStr = day.ToString("yyyy-MM-dd");
                var dailyEntry = NutritionEntry.ComputeForDate(dateStr, _db.FoodEntries, Supplements, _db.TakenSupplementsForToday);
                foreach (var def in Models.Nutrients.Definitions)
                {
                    nutrientTotals[def.Key] += dailyEntry.Nutrients[def.Key];
                }
            }

            var weeklyStatuses = new List<NutrientStatus>();
            foreach (var def in Models.Nutrients.Definitions)
            {
                double avgIntake = nutrientTotals[def.Key] / activeDaysCount;
                double targetRda = def.Rda;
                double customVal;

                if (_db.ActiveProfileName != "Standard Baseline")
                {
                    var activeProfile = _db.CustomDailyProfiles.FirstOrDefault(p => p.Name == _db.ActiveProfileName);
                    if (activeProfile != null && activeProfile.Targets.TryGetValue(def.Key, out customVal))
                    {
                        targetRda = customVal;
                    }
                }
                else if (_db.CustomRdaOverrides.TryGetValue(def.Key, out customVal))
                {
                    targetRda = customVal;
                }

                double percentage = 0.0;
                if (targetRda > 0.0)
                {
                    percentage = (avgIntake / targetRda) * 100.0;
                }

                StatusColor status = StatusColor.GREEN;
                if (def.IsMaxLimit)
                {
                    status = (avgIntake > targetRda && targetRda > 0.0) ? StatusColor.RED : StatusColor.GREEN;
                }
                else
                {
                    if (percentage < 50.0) status = StatusColor.RED;
                    else if (percentage < 100.0) status = StatusColor.YELLOW;
                    else status = StatusColor.GREEN;
                }

                weeklyStatuses.Add(new NutrientStatus(def, avgIntake, percentage, status));
            }

            double avgCalories = weeklyStatuses.FirstOrDefault(s => s.Definition.Key == "calories")?.Intake ?? 0.0;
            double avgProtein = weeklyStatuses.FirstOrDefault(s => s.Definition.Key == "protein")?.Intake ?? 0.0;
            double avgCarbs = weeklyStatuses.FirstOrDefault(s => s.Definition.Key == "carbohydrates")?.Intake ?? 0.0;
            double avgFat = weeklyStatuses.FirstOrDefault(s => s.Definition.Key == "fat")?.Intake ?? 0.0;
            double avgWater = weeklyStatuses.FirstOrDefault(s => s.Definition.Key == "water")?.Intake ?? 0.0;

            var template = new Reports.WeeklyReportTemplate
            {
                StartDate = startDateStr,
                EndDate = endDateStr,
                ProfileName = _db.ActiveProfileName,
                BiologicalSex = ProfileSex,
                Age = ProfileAge,
                ActivityLevel = ProfileActivity,
                AvgCalories = avgCalories,
                AvgProtein = avgProtein,
                AvgCarbs = avgCarbs,
                AvgFat = avgFat,
                AvgWater = avgWater,
                WeeklyNutrientStatuses = weeklyStatuses,
                WeeklyFoodLogs = weeklyFoodLogs
            };

            return template.TransformText();
        }

        #region Custom Daily Profiles Management Methods

        public void RefreshCustomProfilesList()
        {
            CustomDailyProfiles.Clear();
            foreach (var profile in _db.CustomDailyProfiles)
            {
                CustomDailyProfiles.Add(profile);
            }
        }

        public void CreateCustomProfile(string name, string description, Dictionary<string, double> targets)
        {
            var existing = _db.CustomDailyProfiles.FirstOrDefault(p => p.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
            if (existing != null)
            {
                existing.Description = description;
                existing.Targets = targets ?? new Dictionary<string, double>();
            }
            else
            {
                _db.CustomDailyProfiles.Add(new CustomDailyProfile(name, description, targets));
            }
            _storageService.Save(_db);
            RefreshCustomProfilesList();
            OperationMessage = $"Custom daily profile '{name}' saved successfully.";
        }

        public void DeleteCustomProfile(string name)
        {
            var p = _db.CustomDailyProfiles.FirstOrDefault(x => x.Name == name);
            if (p != null)
            {
                _db.CustomDailyProfiles.Remove(p);
                if (_db.ActiveProfileName == name)
                {
                    _db.ActiveProfileName = "Standard Baseline";
                    _db.CustomRdaOverrides.Clear();
                }
                _storageService.Save(_db);
                ActiveProfileName = _db.ActiveProfileName;
                RefreshCustomProfilesList();
                RefreshTodayData();
                OperationMessage = $"Deleted custom daily profile '{name}'.";
            }
        }

        public void ApplyCustomProfile(string name)
        {
            if (name == "Standard Baseline")
            {
                _db.ActiveProfileName = "Standard Baseline";
                _db.CustomRdaOverrides.Clear();
                _storageService.Save(_db);
                ActiveProfileName = "Standard Baseline";
                RefreshTodayData();
                OperationMessage = "Activated Standard baseline dietary guidelines.";
                return;
            }

            var profile = _db.CustomDailyProfiles.FirstOrDefault(p => p.Name == name);
            if (profile != null)
            {
                _db.ActiveProfileName = profile.Name;
                _db.CustomRdaOverrides.Clear();
                foreach (var kvp in profile.Targets)
                {
                    _db.CustomRdaOverrides[kvp.Key] = kvp.Value;
                }
                _storageService.Save(_db);
                ActiveProfileName = profile.Name;
                RefreshTodayData();
                OperationMessage = $"Activated custom daily profile: {profile.Name}";
            }
        }

        public async Task<StorageData> GetFirestoreBackupDataAsync()
        {
            return await _storageService.GetFirestoreBackupDataAsync();
        }

        public async Task OverwriteFirestoreCollectionAsync(StorageData data)
        {
            await _storageService.SaveToFirestoreAsync(data);
            ImportData(data);
        }

        private Dictionary<string, double> LowercaseNutrientKeys(Dictionary<string, double> nutrients)
        {
            if (nutrients == null) return new Dictionary<string, double>();
            var sanitized = new Dictionary<string, double>();
            foreach (var kvp in nutrients)
            {
                if (string.IsNullOrEmpty(kvp.Key)) continue;
                string standardKey = MapToStandardKey(kvp.Key);
                sanitized[standardKey] = kvp.Value;
            }
            return sanitized;
        }

        private static string MapToStandardKey(string rawKey)
        {
            string clean = rawKey.ToLowerInvariant().Replace("_", " ").Replace("-", " ").Trim();
            
            if (clean.Contains("calories") || clean.Contains("energy")) return "calories";
            if (clean.Equals("carbohydrates") || clean.Contains("carbohydrate")) return "carbohydrates";
            if (clean.Equals("protein")) return "protein";
            if (clean.Equals("fat") || clean.Contains("total fat") || clean.Contains("lipid")) return "fat";
            if (clean.Contains("fiber")) return "fiber";
            if (clean.Contains("water") || clean.Contains("hydration")) return "water";
            
            if (clean.Contains("saturated fat")) return "saturated_fat";
            if (clean.Contains("trans fat")) return "trans_fat";
            if (clean.Contains("monounsaturated")) return "monounsaturated_fat";
            if (clean.Contains("polyunsaturated")) return "polyunsaturated_fat";
            if (clean.Contains("omega 3") || clean.Contains("omega3")) return "omega3";
            if (clean.Contains("omega 6") || clean.Contains("omega6")) return "omega6";
            if (clean.Contains("cholesterol")) return "cholesterol";
            
            if (clean.Contains("vitamin a")) return "vitamin_a";
            if (clean.Contains("vitamin c")) return "vitamin_c";
            if (clean.Contains("vitamin d")) return "vitamin_d";
            if (clean.Contains("vitamin e")) return "vitamin_e";
            if (clean.Contains("vitamin k")) return "vitamin_k";
            if (clean.Contains("thiamin")) return "thiamin";
            if (clean.Contains("riboflavin")) return "riboflavin";
            if (clean.Contains("niacin")) return "niacin";
            if (clean.Contains("pantothenic")) return "pantothenic_acid";
            if (clean.Contains("vitamin b6") || clean.Contains("vitamin b 6") || clean.Contains("pyridoxine")) return "vitamin_b6";
            if (clean.Contains("biotin")) return "biotin";
            if (clean.Contains("folate") || clean.Contains("folic") || clean.Contains("folacin")) return "folate";
            if (clean.Contains("vitamin b12") || clean.Contains("vitamin b 12") || clean.Contains("cobalamin")) return "vitamin_b12";
            if (clean.Contains("choline")) return "choline";
            
            if (clean.Contains("calcium")) return "calcium";
            if (clean.Contains("iron")) return "iron";
            if (clean.Contains("magnesium")) return "magnesium";
            if (clean.Contains("phosphorus")) return "phosphorus";
            if (clean.Contains("potassium")) return "potassium";
            if (clean.Contains("sodium")) return "sodium";
            if (clean.Contains("zinc")) return "zinc";
            if (clean.Contains("copper")) return "copper";
            if (clean.Contains("manganese")) return "manganese";
            if (clean.Contains("selenium")) return "selenium";
            if (clean.Contains("chromium")) return "chromium";
            if (clean.Contains("molybdenum")) return "molybdenum";
            if (clean.Contains("sugars") || clean.Equals("sugar")) return "sugars";
            if (clean.Contains("iodine")) return "iodine";

            return rawKey.ToLowerInvariant(); // fallback
        }

        public void ImportRawJsonData(string rawJson)
        {
            var data = _storageService.ImportData(rawJson);
            if (data != null)
            {
                ImportData(data);
            }
        }

        public void ImportData(StorageData importedData)
        {
            System.Diagnostics.Debug.WriteLine("[ImportData] Method entered.");
            if (importedData == null)
            {
                System.Diagnostics.Debug.WriteLine("[ImportData] Error: importedData is null!");
                return;
            }

            try
            {
                System.Diagnostics.Debug.WriteLine($"[ImportData] Initiating import. Counts -> FoodEntries: {importedData.FoodEntries?.Count ?? 0}, Supplements: {importedData.Supplements?.Count ?? 0}, CustomRdaOverrides: {importedData.CustomRdaOverrides?.Count ?? 0}, CustomDailyProfiles: {importedData.CustomDailyProfiles?.Count ?? 0}, RecurringFoods: {importedData.RecurringFoods?.Count ?? 0}");

                // Sanitize and lowercase nutrient dictionary keys to avoid mapping casing issues
                var sanitizedFoodEntries = importedData.FoodEntries ?? new List<FoodLogEntry>();
                foreach (var entry in sanitizedFoodEntries)
                {
                    entry.Nutrients = LowercaseNutrientKeys(entry.Nutrients);
                }

                var sanitizedSupplements = importedData.Supplements ?? new List<Supplement>();
                foreach (var sup in sanitizedSupplements)
                {
                    sup.Nutrients = LowercaseNutrientKeys(sup.Nutrients);
                }

                var sanitizedCustomOverrides = LowercaseNutrientKeys(importedData.CustomRdaOverrides);

                var sanitizedRecurringFoods = importedData.RecurringFoods ?? new List<FoodLogEntry>();
                foreach (var rec in sanitizedRecurringFoods)
                {
                    rec.Nutrients = LowercaseNutrientKeys(rec.Nutrients);
                }

                // Update backing DB structure
                _db.FoodEntries = sanitizedFoodEntries;
                _db.Supplements = sanitizedSupplements;
                _db.CustomRdaOverrides = sanitizedCustomOverrides;
                _db.ProfileAge = importedData.ProfileAge;
                _db.ProfileActivity = importedData.ProfileActivity ?? "Lightly Active";
                _db.ProfileSex = importedData.ProfileSex ?? "Female";
                _db.ObservationDaysLimit = importedData.ObservationDaysLimit > 0 ? importedData.ObservationDaysLimit : 30;
                _db.TakenSupplementsForToday = importedData.TakenSupplementsForToday ?? new List<string>();
                _db.CustomDailyProfiles = importedData.CustomDailyProfiles ?? new List<CustomDailyProfile>();
                _db.ActiveProfileName = importedData.ActiveProfileName ?? "Standard Baseline";
                _db.RecurringFoods = sanitizedRecurringFoods;
                _db.ServingUnit = importedData.ServingUnit ?? "g";
                _db.HasPrepopulatedSampleLogs = true;

                System.Diagnostics.Debug.WriteLine("[ImportData] _db structure successfully populated in-memory. Invoking _storageService.Save(_db)...");

                // Save immediately to regional database
                _storageService.Save(_db);

                System.Diagnostics.Debug.WriteLine("[ImportData] _storageService.Save completed successfully. Updating UI properties...");

                // Re-trigger standard property notifications
                ProfileAge = _db.ProfileAge;
                ProfileActivity = _db.ProfileActivity;
                ProfileSex = _db.ProfileSex;
                ActiveProfileName = _db.ActiveProfileName;

                // Sync Supplements checklist in ViewModel
                Supplements.Clear();
                foreach (var sup in _db.Supplements)
                {
                    Supplements.Add(sup);
                }
                System.Diagnostics.Debug.WriteLine($"[ImportData] UI Supplements collection populated with {Supplements.Count} items.");

                // Sync Custom profiles list
                RefreshCustomProfilesList();

                // Sync Recurring foods list
                RefreshRecurringFoodsList();

                // Refresh today's food inputs and recalculated status loops
                RefreshTodayData();

                System.Diagnostics.Debug.WriteLine("[ImportData] RefreshTodayData completed.");
                OperationMessage = "Database imported successfully.";
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[ImportData] CRITICAL ERROR during ImportData: {ex.Message}\n{ex.StackTrace}");
                throw;
            }
        }

        public void ImportSupplements(List<Supplement> importedSupplements)
        {
            System.Diagnostics.Debug.WriteLine("[ImportSupplements] Method entered.");
            if (importedSupplements == null || importedSupplements.Count == 0)
            {
                System.Diagnostics.Debug.WriteLine("[ImportSupplements] Warning: importedSupplements is null or empty.");
                return;
            }

            try
            {
                System.Diagnostics.Debug.WriteLine($"[ImportSupplements] Merging {importedSupplements.Count} supplements. Existing count: {_db.Supplements.Count}");

                // Merging strategy: if name doesn't exist, append it. If it exists, overwrite it.
                foreach (var imported in importedSupplements)
                {
                    if (string.IsNullOrEmpty(imported.Name)) continue;

                    // Ensure nutrient keys are lowercased
                    imported.Nutrients = LowercaseNutrientKeys(imported.Nutrients);

                    var existing = _db.Supplements.FirstOrDefault(s => s.Name.Equals(imported.Name, StringComparison.OrdinalIgnoreCase));
                    if (existing != null)
                    {
                        System.Diagnostics.Debug.WriteLine($"[ImportSupplements] Overwriting existing supplement: '{existing.Name}'");
                        // Update properties of existing supplement in-place
                        existing.Dosage = imported.Dosage ?? "";
                        existing.Frequency = imported.Frequency ?? "";
                        existing.DaysOfWeek = imported.DaysOfWeek ?? "";
                        existing.TimeOfDay = imported.TimeOfDay ?? "Morning";
                        existing.Notes = imported.Notes ?? "";
                        existing.Nutrients = imported.Nutrients;
                    }
                    else
                    {
                        // Create new and add
                        int newId = _db.Supplements.Count > 0 ? _db.Supplements.Max(s => s.Id) + 1 : 1;
                        imported.Id = newId;
                        _db.Supplements.Add(imported);
                        System.Diagnostics.Debug.WriteLine($"[ImportSupplements] Appended new supplement: '{imported.Name}' with ID: {imported.Id}");
                    }
                }

                System.Diagnostics.Debug.WriteLine("[ImportSupplements] Invoking _storageService.Save(_db)...");
                // Save immediately to regional database
                _storageService.Save(_db);
                System.Diagnostics.Debug.WriteLine("[ImportSupplements] _storageService.Save completed successfully. Updating UI list...");

                // Sync Supplements checklist in ViewModel
                Supplements.Clear();
                foreach (var sup in _db.Supplements)
                {
                    Supplements.Add(sup);
                }

                // Refresh today's food inputs and recalculated status loops
                RefreshTodayData();

                System.Diagnostics.Debug.WriteLine("[ImportSupplements] RefreshTodayData completed.");
                OperationMessage = $"Successfully merged {importedSupplements.Count} supplements/nutrients!";
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[ImportSupplements] CRITICAL ERROR during ImportSupplements: {ex.Message}\n{ex.StackTrace}");
                throw;
            }
        }

        public void ImportFoodLogs(List<FoodLogEntry> importedFoodLogs)
        {
            System.Diagnostics.Debug.WriteLine("[ImportFoodLogs] Method entered.");
            if (importedFoodLogs == null || importedFoodLogs.Count == 0)
            {
                System.Diagnostics.Debug.WriteLine("[ImportFoodLogs] Warning: importedFoodLogs is null or empty.");
                return;
            }

            try
            {
                System.Diagnostics.Debug.WriteLine($"[ImportFoodLogs] Importing {importedFoodLogs.Count} food log entries. Existing count: {_db.FoodEntries.Count}");

                foreach (var imported in importedFoodLogs)
                {
                    if (string.IsNullOrEmpty(imported.FoodName)) continue;

                    // Ensure nutrient keys are lowercased
                    imported.Nutrients = LowercaseNutrientKeys(imported.Nutrients);

                    // Create new and append to existing food log dictionary or DB list
                    int newId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;
                    imported.Id = newId;
                    if (string.IsNullOrEmpty(imported.Date))
                    {
                        imported.Date = CurrentDateString;
                    }
                    _db.FoodEntries.Add(imported);
                    System.Diagnostics.Debug.WriteLine($"[ImportFoodLogs] Appended food entry: '{imported.FoodName}' for date: {imported.Date} with ID: {imported.Id}");
                }

                System.Diagnostics.Debug.WriteLine("[ImportFoodLogs] Invoking _storageService.Save(_db)...");
                // Save immediately to database
                _storageService.Save(_db);
                System.Diagnostics.Debug.WriteLine("[ImportFoodLogs] _storageService.Save completed successfully. Refreshing UI...");

                // Refresh today's food inputs and recalculated status loops
                RefreshTodayData();

                System.Diagnostics.Debug.WriteLine("[ImportFoodLogs] RefreshTodayData completed.");
                OperationMessage = $"Successfully imported {importedFoodLogs.Count} food journal entries!";
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[ImportFoodLogs] CRITICAL ERROR during ImportFoodLogs: {ex.Message}\n{ex.StackTrace}");
                throw;
            }
        }

        public void RefreshRecurringFoodsList()
        {
            RecurringFoods.Clear();
            if (_db.RecurringFoods != null)
            {
                foreach (var food in _db.RecurringFoods)
                {
                    RecurringFoods.Add(food);
                }
            }
        }

        public void AddRecurringFoodToToday(FoodLogEntry template)
        {
            if (template == null) return;

            // Clone
            int newId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;
            var clone = new FoodLogEntry
            {
                Id = newId,
                Date = CurrentDateString,
                FoodName = template.FoodName,
                MealType = template.MealType,
                Quantity = template.Quantity,
                Unit = template.Unit,
                Nutrients = new Dictionary<string, double>(template.Nutrients ?? new Dictionary<string, double>())
            };

            _db.FoodEntries.Add(clone);
            _storageService.Save(_db);
            RefreshTodayData();
            OperationMessage = $"Added '{template.FoodName}' for today's {template.MealType}!";
        }

        public void PinFoodLogAsStaple(FoodLogEntry loggedEntry)
        {
            if (loggedEntry == null) return;

            // Check if name already exists in recurring foods
            var exists = _db.RecurringFoods.FirstOrDefault(r => r.FoodName.Equals(loggedEntry.FoodName, StringComparison.OrdinalIgnoreCase));
            if (exists != null)
            {
                exists.MealType = loggedEntry.MealType;
                exists.Quantity = loggedEntry.Quantity;
                exists.Unit = loggedEntry.Unit;
                exists.Nutrients = new Dictionary<string, double>(loggedEntry.Nutrients ?? new Dictionary<string, double>());
                OperationMessage = $"Updated existing '{loggedEntry.FoodName}' Favorite preset!";
            }
            else
            {
                int newId = _db.RecurringFoods.Count > 0 ? _db.RecurringFoods.Max(r => r.Id) + 1 : 1;
                var staple = new FoodLogEntry
                {
                    Id = newId,
                    Date = "",
                    FoodName = loggedEntry.FoodName,
                    MealType = loggedEntry.MealType,
                    Quantity = loggedEntry.Quantity,
                    Unit = loggedEntry.Unit,
                    Nutrients = new Dictionary<string, double>(loggedEntry.Nutrients ?? new Dictionary<string, double>())
                };
                _db.RecurringFoods.Add(staple);
                OperationMessage = $"Pinned '{loggedEntry.FoodName}' to your Favorites & Presets!";
            }

            _storageService.Save(_db);
            RefreshRecurringFoodsList();
        }

        public void SaveStapleTemplate(FoodLogEntry staple)
        {
            if (staple == null) return;
            NormalizeFoodLogEntryUnit(staple);
            var match = _db.RecurringFoods.FirstOrDefault(r => r.Id == staple.Id);
            if (match != null)
            {
                match.FoodName = staple.FoodName;
                match.MealType = staple.MealType;
                match.Quantity = staple.Quantity;
                match.Unit = staple.Unit;
                match.Nutrients = new Dictionary<string, double>(staple.Nutrients ?? new Dictionary<string, double>());
                _storageService.Save(_db);
                RefreshRecurringFoodsList();
                OperationMessage = $"Successfully edited Favorite Preset '{staple.FoodName}'!";
            }
        }

        public void DeleteStaple(FoodLogEntry staple)
        {
            if (staple == null) return;
            var match = _db.RecurringFoods.FirstOrDefault(r => r.Id == staple.Id);
            if (match != null)
            {
                _db.RecurringFoods.Remove(match);
                _storageService.Save(_db);
                RefreshRecurringFoodsList();
                OperationMessage = $"Removed '{staple.FoodName}' from your Favorites list.";
            }
        }

        public void AddCustomStaple(string foodName, string mealType, double quantity, string unit, Dictionary<string, double> nutrients)
        {
            int newId = _db.RecurringFoods.Count > 0 ? _db.RecurringFoods.Max(r => r.Id) + 1 : 1;
            var staple = new FoodLogEntry
            {
                Id = newId,
                Date = "",
                FoodName = foodName,
                MealType = mealType,
                Quantity = quantity,
                Unit = unit,
                Nutrients = nutrients ?? new Dictionary<string, double>()
            };

            NormalizeFoodLogEntryUnit(staple);

            _db.RecurringFoods.Add(staple);
            _storageService.Save(_db);
            RefreshRecurringFoodsList();
            OperationMessage = $"Favorite '{foodName}' created successfully!";
        }

        public void UpdateFoodLog(FoodLogEntry updatedEntry)
        {
            if (updatedEntry == null) return;
            NormalizeFoodLogEntryUnit(updatedEntry);
            var match = _db.FoodEntries.FirstOrDefault(f => f.Id == updatedEntry.Id);
            if (match != null)
            {
                match.FoodName = updatedEntry.FoodName;
                match.MealType = updatedEntry.MealType;
                match.Quantity = updatedEntry.Quantity;
                match.Unit = updatedEntry.Unit;
                match.Nutrients = new Dictionary<string, double>(updatedEntry.Nutrients ?? new Dictionary<string, double>());
                _storageService.Save(_db);
                RefreshTodayData();
                OperationMessage = $"Log updated for '{updatedEntry.FoodName}'!";
            }
        }

        public async Task FetchNutritionPreviewWithAiAsync()
        {
            if (string.IsNullOrWhiteSpace(AIQueryFoodName))
            {
                AIFetchStatusText = "Error: Please enter a valid food description first.";
                return;
            }

            IsAiFetching = true;
            AIFetchStatusText = "Consulting Gemini AI to solve nutritional breakdown... Please wait.";
            AIFetchedPreviewResult = null;

            try
            {
                var result = await _geminiService.ParseFoodWithAiAsync(AIQueryFoodName);
                if (result != null)
                {
                    AIFetchedPreviewResult = result;
                    AIFetchStatusText = $"Success! Gemini fetched details for '{result.FoodName}'";
                }
                else
                {
                    AIFetchStatusText = "Error: Gemini returned empty results.";
                }
            }
            catch (Exception ex)
            {
                AIFetchStatusText = $"Error: {ex.Message}";
                System.Diagnostics.Debug.WriteLine($"AI Fetch Error: {ex.Message}");
            }
            finally
            {
                IsAiFetching = false;
            }
        }

        public async Task GenerateAiMenuPlanAsync()
        {
            IsAiPlanning = true;
            AiPlannerStatusText = "Analyzing daily targets and staple foods... Please wait.";
            SuggestedMenuItems.Clear();
            HasSuggestedMenu = false;

            try
            {
                // 1. Build profile description
                string profile = $"Biological Sex: {ProfileSex}, Age: {ProfileAge} years, Activity Level Index: {ProfileActivity}.";

                // 2. Build staples description
                var staplesList = new List<string>();
                foreach (var s in SelectedMenuStaples)
                {
                    staplesList.Add($"{s.FoodName} ({s.Quantity} {s.Unit}) for {s.MealType}");
                }

                // 3. Build outstanding clinical deficits description
                // We'll calculate targets relative to 100% RDA
                var deficitsList = new List<string>();
                foreach (var status in NutrientStatuses)
                {
                    // If nutrient is a goal (not a limit) and is not met, calculate deficit
                    if (!status.Definition.IsMaxLimit && status.Percentage < 100.0 && status.Definition.Rda > 0)
                    {
                        double outstanding = status.Definition.Rda - status.Intake;
                        if (outstanding > 0.01)
                        {
                            deficitsList.Add($"{status.Definition.Name}: outstanding deficit of {outstanding:F1} {status.Definition.Unit} (currently {status.Percentage:F0}% met)");
                        }
                    }
                }

                AiPlannerStatusText = "Consulting Gemini AI to formulate optimal clinical remaining meals & targeted snacks... Please wait.";
                var suggestions = await _geminiService.SuggestRemainingMenuWithAiAsync(profile, staplesList, deficitsList);

                if (suggestions != null && suggestions.Count > 0)
                {
                    foreach (var s in suggestions)
                    {
                        var entry = new FoodLogEntry
                        {
                            FoodName = s.FoodName,
                            MealType = string.IsNullOrWhiteSpace(s.MealType) ? "Snack" : s.MealType,
                            Quantity = s.Quantity,
                            Unit = string.IsNullOrWhiteSpace(s.Unit) ? "serving" : s.Unit,
                            Nutrients = s.Nutrients ?? new Dictionary<string, double>()
                        };
                        SuggestedMenuItems.Add(entry);
                    }
                    HasSuggestedMenu = true;
                    AiPlannerStatusText = $"Success! Gemini suggested {suggestions.Count} additional clinical item(s), including optimized snacks!";
                }
                else
                {
                    AiPlannerStatusText = "Warning: Gemini suggested no additional items. Try adjusting your staples.";
                }
            }
            catch (Exception ex)
            {
                AiPlannerStatusText = $"Error: {ex.Message}";
                System.Diagnostics.Debug.WriteLine($"Menu Planner Error: {ex.Message}");
            }
            finally
            {
                IsAiPlanning = false;
            }
        }

        public void CommitPlannedMenuToJournal()
        {
            if (SuggestedMenuItems.Count == 0 && SelectedMenuStaples.Count == 0)
            {
                AiPlannerStatusText = "No planned items to commit. Please plan or configure usuals first.";
                return;
            }

            try
            {
                // Add selected staples
                foreach (var s in SelectedMenuStaples)
                {
                    var lastId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;
                    var entry = new FoodLogEntry
                    {
                        Id = lastId,
                        Date = CurrentDateString,
                        FoodName = s.FoodName,
                        MealType = s.MealType,
                        Quantity = s.Quantity,
                        Unit = s.Unit,
                        Nutrients = new Dictionary<string, double>(s.Nutrients ?? new Dictionary<string, double>())
                    };
                    _db.FoodEntries.Add(entry);
                }

                // Add suggested meals/snacks
                foreach (var s in SuggestedMenuItems)
                {
                    var lastId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;
                    var entry = new FoodLogEntry
                    {
                        Id = lastId,
                        Date = CurrentDateString,
                        FoodName = s.FoodName,
                        MealType = s.MealType,
                        Quantity = s.Quantity,
                        Unit = s.Unit,
                        Nutrients = new Dictionary<string, double>(s.Nutrients ?? new Dictionary<string, double>())
                    };
                    _db.FoodEntries.Add(entry);
                }

                _storageService.Save(_db);
                
                RefreshTodayData();
                CheckNutrientAlerts(autoShow: true);

                SuggestedMenuItems.Clear();
                SelectedMenuStaples.Clear();
                HasSuggestedMenu = false;

                AiPlannerStatusText = "Success! The selected usuals and AI suggestions have been written directly to your Clinical Food Journal.";
                OperationMessage = "Clinical menu successfully saved to journal!";
            }
            catch (Exception ex)
            {
                AiPlannerStatusText = $"Error saving: {ex.Message}";
            }
        }

        public void RefreshPreviewNutrients()
        {
            PreviewNutrients.Clear();
            if (_aiFetchedPreviewResult == null || _aiFetchedPreviewResult.Nutrients == null) return;

            double qty = PreviewQuantity;

            foreach (var def in Nutrients.Definitions)
            {
                double baseVal = 0.0;
                string key = def.Key.ToLower();
                double val;
                if (_aiFetchedPreviewResult.Nutrients.TryGetValue(key, out val))
                {
                    baseVal = val;
                }

                double totalVal = baseVal * qty;
                
                double targetRda = def.Rda;
                if (_db.CustomRdaOverrides.ContainsKey(def.Key))
                {
                    targetRda = _db.CustomRdaOverrides[def.Key];
                }

                double pct = 0.0;
                if (targetRda > 0)
                {
                    pct = (totalVal / targetRda) * 100.0;
                }

                string statusTxt = "Normal";
                string brush = "#4B5563"; // TextSecondary grey

                if (targetRda > 0)
                {
                    if (def.IsMaxLimit)
                    {
                        if (totalVal > targetRda)
                        {
                            statusTxt = "Limit Exceeded";
                            brush = "#EF4444"; // StatusRed
                        }
                        else if (totalVal > targetRda * 0.8)
                        {
                            statusTxt = "Near Limit";
                            brush = "#D97706"; // StatusYellow
                        }
                        else
                        {
                            statusTxt = "Safe";
                            brush = "#059669"; // StatusGreen
                        }
                    }
                    else
                    {
                        if (pct >= 100.0)
                        {
                            statusTxt = "RDA Met";
                            brush = "#059669"; // StatusGreen
                        }
                        else if (pct >= 50.0)
                        {
                            statusTxt = "Partial RDA";
                            brush = "#D97706"; // StatusYellow
                        }
                        else
                        {
                            statusTxt = "Low Daily Share";
                            brush = "#4B5563"; // Secondary
                        }
                    }
                }

                PreviewNutrients.Add(new NutritionPreviewItem
                {
                    Key = def.Key,
                    Name = def.Name,
                    Group = def.Group.GetDisplayName(),
                    DisplayIntake = $"{totalVal:F1} {def.Unit}",
                    DisplayRda = targetRda > 0 ? $"{targetRda:F1} {def.Unit}" : "N/A",
                    Percentage = pct,
                    StatusText = statusTxt,
                    StatusBrush = brush
                });
            }
        }

        public bool CommitAiFetchedResultToToday()
        {
            if (_aiFetchedPreviewResult == null) return false;

            double qty = PreviewQuantity;
            string finalFoodName = string.IsNullOrWhiteSpace(_aiFetchedPreviewResult.FoodName) ? AIQueryFoodName : _aiFetchedPreviewResult.FoodName;
            string finalUnit = string.IsNullOrWhiteSpace(_aiFetchedPreviewResult.Unit) ? "serving" : _aiFetchedPreviewResult.Unit;

            var lastId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;

            var entry = new FoodLogEntry
            {
                Id = lastId,
                Date = CurrentDateString,
                FoodName = finalFoodName,
                MealType = AIQueryMealType,
                Quantity = qty,
                Unit = finalUnit,
                Nutrients = new Dictionary<string, double>(_aiFetchedPreviewResult.Nutrients ?? new Dictionary<string, double>())
            };

            NormalizeFoodLogEntryUnit(entry);

            _db.FoodEntries.Add(entry);
            _storageService.Save(_db);
            
            RefreshTodayData();
            CheckNutrientAlerts(autoShow: true);
            
            OperationMessage = $"Successfully logged '{finalFoodName}' ({qty} {finalUnit}) for {AIQueryMealType}!";
            
            AIQueryFoodName = "";
            AIFetchedPreviewResult = null;
            AIFetchStatusText = "Food logged successfully! Ready for next inquiry.";
            return true;
        }

        #endregion

        public void ToggleServingUnit(string newUnit)
        {
            if (_db.ServingUnit == newUnit) return;

            string oldUnit = _db.ServingUnit ?? "g";
            _db.ServingUnit = newUnit;

            // Convert existing database logs and recurring foods in-place!
            ConvertDatabaseWeightUnits(oldUnit, newUnit);

            _storageService.Save(_db);
            NotifyPropertyChanged(nameof(CurrentServingUnit));
            NotifyPropertyChanged(nameof(IsGramUnitActive));
            NotifyPropertyChanged(nameof(IsOunceUnitActive));

            RefreshTodayData();
            RefreshRecurringFoodsList();
            OperationMessage = $"Serving unit changed to {(newUnit == "g" ? "Grams" : "Ounces")}. Existing weight logs converted.";
        }

        private void ConvertDatabaseWeightUnits(string fromUnit, string toUnit)
        {
            if (fromUnit == "g" && toUnit == "oz")
            {
                foreach (var entry in _db.FoodEntries)
                {
                    if (IsGramUnit(entry.Unit))
                    {
                        entry.Quantity /= 28.3495;
                        entry.Unit = "oz";
                        var keys = new List<string>(entry.Nutrients.Keys);
                        foreach (var key in keys)
                        {
                            entry.Nutrients[key] *= 28.3495;
                        }
                    }
                }
                foreach (var entry in _db.RecurringFoods)
                {
                    if (IsGramUnit(entry.Unit))
                    {
                        entry.Quantity /= 28.3495;
                        entry.Unit = "oz";
                        var keys = new List<string>(entry.Nutrients.Keys);
                        foreach (var key in keys)
                        {
                            entry.Nutrients[key] *= 28.3495;
                        }
                    }
                }
            }
            else if (fromUnit == "oz" && toUnit == "g")
            {
                foreach (var entry in _db.FoodEntries)
                {
                    if (IsOunceUnit(entry.Unit))
                    {
                        entry.Quantity *= 28.3495;
                        entry.Unit = "g";
                        var keys = new List<string>(entry.Nutrients.Keys);
                        foreach (var key in keys)
                        {
                            entry.Nutrients[key] /= 28.3495;
                        }
                    }
                }
                foreach (var entry in _db.RecurringFoods)
                {
                    if (IsOunceUnit(entry.Unit))
                    {
                        entry.Quantity *= 28.3495;
                        entry.Unit = "g";
                        var keys = new List<string>(entry.Nutrients.Keys);
                        foreach (var key in keys)
                        {
                            entry.Nutrients[key] /= 28.3495;
                        }
                    }
                }
            }
        }

        public static bool IsGramUnit(string unit)
        {
            if (string.IsNullOrEmpty(unit)) return false;
            string u = unit.Trim().ToLower();
            return u == "g" || u == "gram" || u == "grams";
        }

        public static bool IsOunceUnit(string unit)
        {
            if (string.IsNullOrEmpty(unit)) return false;
            string u = unit.Trim().ToLower();
            return u == "oz" || u == "ounce" || u == "ounces";
        }

        public void NormalizeFoodLogEntryUnit(FoodLogEntry entry)
        {
            if (entry == null) return;
            string targetUnit = CurrentServingUnit; // "g" or "oz"
            if (targetUnit == "oz")
            {
                if (IsGramUnit(entry.Unit))
                {
                    entry.Quantity /= 28.3495;
                    entry.Unit = "oz";
                    var keys = new List<string>(entry.Nutrients.Keys);
                    foreach (var key in keys)
                    {
                        entry.Nutrients[key] *= 28.3495;
                    }
                }
            }
            else if (targetUnit == "g")
            {
                if (IsOunceUnit(entry.Unit))
                {
                    entry.Quantity *= 28.3495;
                    entry.Unit = "g";
                    var keys = new List<string>(entry.Nutrients.Keys);
                    foreach (var key in keys)
                    {
                        entry.Nutrients[key] /= 28.3495;
                    }
                }
            }
        }

        // ==========================================
        // USDA Food Search Properties & Methods
        // ==========================================
        private string _usdaSearchQuery = "";
        public string UsdaSearchQuery
        {
            get => _usdaSearchQuery;
            set => SetProperty(ref _usdaSearchQuery, value);
        }

        private bool _isUsdaSearching;
        public bool IsUsdaSearching
        {
            get => _isUsdaSearching;
            set => SetProperty(ref _isUsdaSearching, value);
        }

        private string _usdaSearchStatusText = "Enter a search term and click Search to query USDA FoodData Central!";
        public string UsdaSearchStatusText
        {
            get => _usdaSearchStatusText;
            set => SetProperty(ref _usdaSearchStatusText, value);
        }

        public ObservableCollection<UsdaNutrientProfile> UsdaSearchResults { get; } = new ObservableCollection<UsdaNutrientProfile>();

        private UsdaNutrientProfile? _selectedUsdaResult;
        public UsdaNutrientProfile? SelectedUsdaResult
        {
            get => _selectedUsdaResult;
            set
            {
                if (SetProperty(ref _selectedUsdaResult, value))
                {
                    RefreshUsdaPreviewNutrients();
                    NotifyPropertyChanged(nameof(HasUsdaPreviewResult));
                    NotifyPropertyChanged(nameof(UsdaPreviewFoodName));
                    NotifyPropertyChanged(nameof(UsdaPreviewCalories));
                    NotifyPropertyChanged(nameof(UsdaPreviewProtein));
                    NotifyPropertyChanged(nameof(UsdaPreviewCarbs));
                    NotifyPropertyChanged(nameof(UsdaPreviewFat));
                }
            }
        }

        private string _usdaServingCountText = "1.0";
        public string UsdaServingCountText
        {
            get => _usdaServingCountText;
            set
            {
                if (SetProperty(ref _usdaServingCountText, value))
                {
                    RefreshUsdaPreviewNutrients();
                    NotifyPropertyChanged(nameof(UsdaPreviewCalories));
                    NotifyPropertyChanged(nameof(UsdaPreviewProtein));
                    NotifyPropertyChanged(nameof(UsdaPreviewCarbs));
                    NotifyPropertyChanged(nameof(UsdaPreviewFat));
                }
            }
        }

        private string _usdaMealType = "Breakfast";
        public string UsdaMealType
        {
            get => _usdaMealType;
            set => SetProperty(ref _usdaMealType, value);
        }

        public ObservableCollection<NutritionPreviewItem> UsdaPreviewNutrients { get; } = new ObservableCollection<NutritionPreviewItem>();

        public bool HasUsdaPreviewResult => SelectedUsdaResult != null;

        public string UsdaPreviewFoodName => SelectedUsdaResult?.Description ?? "";

        public double UsdaPreviewQuantity
        {
            get
            {
                double q;
                if (double.TryParse(_usdaServingCountText, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out q) && q > 0)
                {
                    return q;
                }
                return 1.0;
            }
        }

        public double UsdaPreviewCalories => GetUsdaPreviewNutrientValue("calories");
        public double UsdaPreviewProtein => GetUsdaPreviewNutrientValue("protein");
        public double UsdaPreviewCarbs => GetUsdaPreviewNutrientValue("carbohydrates");
        public double UsdaPreviewFat => GetUsdaPreviewNutrientValue("fat");

        private double GetUsdaPreviewNutrientValue(string key)
        {
            if (SelectedUsdaResult == null || SelectedUsdaResult.Nutrients == null) return 0.0;
            double baseVal = 0.0;
            SelectedUsdaResult.Nutrients.TryGetValue(key, out baseVal);
            return baseVal * UsdaPreviewQuantity;
        }

        public string UsdaApiKeyMasked
        {
            get
            {
                string key = UsdaNutritionService.GetActiveApiKey();
                if (string.IsNullOrEmpty(key) || key == "DEMO_KEY") return "Demo Mode (DEMO_KEY)";
                if (key.Length <= 8) return "Active Key: " + key;
                return "Active Key: " + key.Substring(0, 4) + "..." + key.Substring(key.Length - 4);
            }
        }

        private bool _isUsdaDetailLoading;
        public bool IsUsdaDetailLoading
        {
            get => _isUsdaDetailLoading;
            set => SetProperty(ref _isUsdaDetailLoading, value);
        }

        public async Task LoadSelectedUsdaDetailAsync(UsdaNutrientProfile? selectedItem)
        {
            if (selectedItem == null)
            {
                SelectedUsdaResult = null;
                return;
            }

            IsUsdaDetailLoading = true;
            try
            {
                var detailedProfile = await UsdaNutritionService.GetFoodDetailsProfileAsync(selectedItem.FdcId);
                if (detailedProfile != null)
                {
                    SelectedUsdaResult = detailedProfile;
                }
                else
                {
                    SelectedUsdaResult = selectedItem;
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to load USDA food details: {ex.Message}");
                SelectedUsdaResult = selectedItem;
            }
            finally
            {
                IsUsdaDetailLoading = false;
            }
        }

        public async Task SearchUsdaFoodsAsync()
        {
            if (string.IsNullOrWhiteSpace(UsdaSearchQuery))
            {
                UsdaSearchStatusText = "Please type a food search term first.";
                return;
            }

            IsUsdaSearching = true;
            UsdaSearchStatusText = "Searching USDA FoodData Central database... Please wait.";
            UsdaSearchResults.Clear();
            SelectedUsdaResult = null;

            try
            {
                var results = await UsdaNutritionService.SearchFoodNutritionalProfileAsync(UsdaSearchQuery);
                if (results == null || results.Count == 0)
                {
                    UsdaSearchStatusText = "No results found on USDA FoodData Central for: " + UsdaSearchQuery;
                }
                else
                {
                    foreach (var r in results)
                    {
                        UsdaSearchResults.Add(r);
                    }
                    UsdaSearchStatusText = $"Found {results.Count} matches. Select an item to view diagnostic details.";
                }
            }
            catch (Exception ex)
            {
                UsdaSearchStatusText = $"Search failed: {ex.Message}";
                System.Diagnostics.Debug.WriteLine($"USDA Search Error: {ex.Message}");
            }
            finally
            {
                IsUsdaSearching = false;
            }
        }

        public void RefreshUsdaPreviewNutrients()
        {
            UsdaPreviewNutrients.Clear();
            if (SelectedUsdaResult == null || SelectedUsdaResult.Nutrients == null) return;

            double qty = UsdaPreviewQuantity;

            foreach (var def in Nutrients.Definitions)
            {
                double baseVal = 0.0;
                string key = def.Key.ToLower();
                double val;
                if (SelectedUsdaResult.Nutrients.TryGetValue(key, out val))
                {
                    baseVal = val;
                }

                double totalVal = baseVal * qty;
                
                double targetRda = def.Rda;
                if (_db.CustomRdaOverrides.ContainsKey(def.Key))
                {
                    targetRda = _db.CustomRdaOverrides[def.Key];
                }

                double pct = 0.0;
                if (targetRda > 0)
                {
                    pct = (totalVal / targetRda) * 100.0;
                }

                string statusTxt = "Normal";
                string brush = "#94A3B8"; // Default slate color

                if (targetRda > 0)
                {
                    if (def.IsMaxLimit)
                    {
                        if (totalVal > targetRda)
                        {
                            statusTxt = "Limit Exceeded";
                            brush = "#EF4444"; // StatusRed
                        }
                        else if (totalVal > targetRda * 0.8)
                        {
                            statusTxt = "Near Limit";
                            brush = "#D97706"; // StatusYellow
                        }
                        else
                        {
                            statusTxt = "Safe";
                            brush = "#059669"; // StatusGreen
                        }
                    }
                    else
                    {
                        if (pct >= 100.0)
                        {
                            statusTxt = "RDA Met";
                            brush = "#059669"; // StatusGreen
                        }
                        else if (pct >= 50.0)
                        {
                            statusTxt = "Partial RDA";
                            brush = "#D97706"; // StatusYellow
                        }
                        else
                        {
                            statusTxt = "Low Daily Share";
                            brush = "#94A3B8"; // Slate text
                        }
                    }
                }

                UsdaPreviewNutrients.Add(new NutritionPreviewItem
                {
                    Key = def.Key,
                    Name = def.Name,
                    Group = def.Group.GetDisplayName(),
                    DisplayIntake = $"{totalVal:F1} {def.Unit}",
                    DisplayRda = targetRda > 0 ? $"{targetRda:F1} {def.Unit}" : "N/A",
                    Percentage = pct,
                    StatusText = statusTxt,
                    StatusBrush = brush
                });
            }
        }

        public bool CommitUsdaResultToToday()
        {
            if (SelectedUsdaResult == null) return false;

            double qty = UsdaPreviewQuantity;
            string finalFoodName = SelectedUsdaResult.Description;
            string finalUnit = string.IsNullOrWhiteSpace(SelectedUsdaResult.ServingSizeUnit) ? "g" : SelectedUsdaResult.ServingSizeUnit;

            var lastId = _db.FoodEntries.Count > 0 ? _db.FoodEntries.Max(f => f.Id) + 1 : 1;

            var entry = new FoodLogEntry
            {
                Id = lastId,
                Date = CurrentDateString,
                FoodName = finalFoodName,
                MealType = UsdaMealType,
                Quantity = qty,
                Unit = finalUnit,
                Nutrients = new Dictionary<string, double>(SelectedUsdaResult.Nutrients ?? new Dictionary<string, double>())
            };

            NormalizeFoodLogEntryUnit(entry);

            _db.FoodEntries.Add(entry);
            _storageService.Save(_db);
            
            RefreshTodayData();
            CheckNutrientAlerts(autoShow: true);
            
            OperationMessage = $"Successfully logged '{finalFoodName}' ({qty} {finalUnit}) for {UsdaMealType}!";
            
            UsdaSearchQuery = "";
            SelectedUsdaResult = null;
            UsdaSearchResults.Clear();
            UsdaSearchStatusText = "Food logged successfully! Ready for next inquiry.";
            return true;
        }

        // INotifyPropertyChanged helpers
        protected bool SetProperty<T>(ref T storage, T value, [CallerMemberName] string? propertyName = null)
        {
            if (EqualityComparer<T>.Default.Equals(storage, value)) return false;
            storage = value;
            NotifyPropertyChanged(propertyName);
            return true;
        }

        protected void NotifyPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }

    public class NutritionPreviewItem
    {
        public string Key { get; set; } = "";
        public string Name { get; set; } = "";
        public string Group { get; set; } = "";
        public string DisplayIntake { get; set; } = "";
        public string DisplayRda { get; set; } = "";
        public double Percentage { get; set; }
        public string StatusText { get; set; } = "";
        public string StatusBrush { get; set; } = "#4B5563";
    }
}
