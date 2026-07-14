using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.ViewModels
{
    public class TrendPoint : INotifyPropertyChanged
    {
        private string _dateString = "";
        private string _displayDate = "";
        private double _value;
        private string _valueFormatted = "";
        private double _calories;
        private double _carbs;
        private double _protein;
        private double _fat;
        private bool _isTargetMet;
        private double _relativeHeight;

        public string DateString { get => _dateString; set => SetProperty(ref _dateString, value); }
        public string DisplayDate { get => _displayDate; set => SetProperty(ref _displayDate, value); }
        public double Value { get => _value; set => SetProperty(ref _value, value); }
        public string ValueFormatted { get => _valueFormatted; set => SetProperty(ref _valueFormatted, value); }
        public double Calories { get => _calories; set => SetProperty(ref _calories, value); }
        public double Carbs { get => _carbs; set => SetProperty(ref _carbs, value); }
        public double Protein { get => _protein; set => SetProperty(ref _protein, value); }
        public double Fat { get => _fat; set => SetProperty(ref _fat, value); }
        public bool IsTargetMet { get => _isTargetMet; set => SetProperty(ref _isTargetMet, value); }
        public double RelativeHeight { get => _relativeHeight; set => SetProperty(ref _relativeHeight, value); }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected bool SetProperty<T>(ref T storage, T value, [CallerMemberName] string? propertyName = null)
        {
            if (EqualityComparer<T>.Default.Equals(storage, value)) return false;
            storage = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
            return true;
        }
    }

    public class TrendsViewModel : INotifyPropertyChanged
    {
        private readonly NutritionViewModel _parent;

        public event PropertyChangedEventHandler? PropertyChanged;

        private string _selectedTimePeriod = "Weekly (7 Days)";
        public string SelectedTimePeriod
        {
            get => _selectedTimePeriod;
            set { if (SetProperty(ref _selectedTimePeriod, value)) RefreshTrends(); }
        }

        private NutrientDefinition _selectedNutrient;
        public NutrientDefinition SelectedNutrient
        {
            get => _selectedNutrient;
            set { if (SetProperty(ref _selectedNutrient, value)) RefreshTrends(); }
        }

        public List<string> TimePeriods { get; } = new List<string> { "Weekly (7 Days)", "Monthly (30 Days)", "Quarterly (90 Days)" };

        public ObservableCollection<NutrientDefinition> AvailableNutrients { get; } = new ObservableCollection<NutrientDefinition>();
        public ObservableCollection<TrendPoint> TrendPoints { get; } = new ObservableCollection<TrendPoint>();
        public ObservableCollection<NutrientPeriodSummary> PeriodAverages { get; } = new ObservableCollection<NutrientPeriodSummary>();

        // Statistics
        private double _averageIntake;
        private double _maxIntake;
        private double _minIntake;
        private double _totalIntake;
        private double _targetRda;
        private int _totalDays;
        private int _daysMet;
        private double _complianceRate;
        private string _trendInsight = "";

        public double AverageIntake { get => _averageIntake; set => SetProperty(ref _averageIntake, value); }
        public double MaxIntake { get => _maxIntake; set => SetProperty(ref _maxIntake, value); }
        public double MinIntake { get => _minIntake; set => SetProperty(ref _minIntake, value); }
        public double TotalIntake { get => _totalIntake; set => SetProperty(ref _totalIntake, value); }
        public double TargetRda { get => _targetRda; set => SetProperty(ref _targetRda, value); }
        public int TotalDays { get => _totalDays; set => SetProperty(ref _totalDays, value); }
        public int DaysMet { get => _daysMet; set => SetProperty(ref _daysMet, value); }
        public double ComplianceRate { get => _complianceRate; set => SetProperty(ref _complianceRate, value); }
        public string TrendInsight { get => _trendInsight; set => SetProperty(ref _trendInsight, value); }

        public TrendsViewModel(NutritionViewModel parent)
        {
            _parent = parent;
            
            // Populate all 41 essential nutrients
            foreach (var def in Nutrients.Definitions)
            {
                AvailableNutrients.Add(def);
            }

            // Default to Calories
            _selectedNutrient = AvailableNutrients.FirstOrDefault(n => n.Key == "calories") ?? AvailableNutrients[0];
            _selectedTimePeriod = TimePeriods[0]; // "Weekly (7 Days)"
        }

        public void RefreshTrends()
        {
            if (_parent == null || _parent.Database == null) return;

            TrendPoints.Clear();

            int daysCount = 7;
            if (SelectedTimePeriod == "Monthly (30 Days)") daysCount = 30;
            else if (SelectedTimePeriod == "Quarterly (90 Days)") daysCount = 90;

            // Anchor center date around CurrentDateString
            DateTime anchorDate;
            if (!DateTime.TryParse(_parent.CurrentDateString, out anchorDate))
            {
                anchorDate = DateTime.Today;
            }

            var days = new List<DateTime>();
            for (int i = daysCount - 1; i >= 0; i--)
            {
                days.Add(anchorDate.AddDays(-i));
            }

            var db = _parent.Database;
            var overrides = db.CustomRdaOverrides;
            var currentNutrient = SelectedNutrient;

            double target = currentNutrient.Rda;
            double customVal;
            if (overrides != null && overrides.TryGetValue(currentNutrient.Key, out customVal))
            {
                target = customVal;
            }
            TargetRda = target;

            double totalVal = 0.0;
            double maxVal = double.MinValue;
            double minVal = double.MaxValue;
            int metCount = 0;

            var rawValues = new List<double>();

            foreach (var date in days)
            {
                string dateStr = date.ToString("yyyy-MM-dd");
                string displayLabel = date.ToString(daysCount <= 7 ? "ddd d" : "MMM d");

                // Get daily complete computed intake from food history and supplements
                var dailyEntry = NutritionEntry.ComputeForDate(dateStr, db.FoodEntries, _parent.Supplements, db.TakenSupplementsForToday);
                double intakeVal;
                double val = dailyEntry.Nutrients.TryGetValue(currentNutrient.Key, out intakeVal) ? intakeVal : 0.0;

                double cal = dailyEntry.Calories;
                double carbs = dailyEntry.Carbohydrates;
                double protein = dailyEntry.Protein;
                double fat = dailyEntry.Fat;

                bool isMet = false;
                if (currentNutrient.IsMaxLimit)
                {
                    isMet = val <= target;
                }
                else
                {
                    isMet = target <= 0.0 || val >= target;
                }

                if (isMet) metCount++;

                totalVal += val;
                rawValues.Add(val);
                if (val > maxVal) maxVal = val;
                if (val < minVal) minVal = val;

                var pt = new TrendPoint
                {
                    DateString = dateStr,
                    DisplayDate = displayLabel,
                    Value = val,
                    ValueFormatted = $"{val:F1} {currentNutrient.Unit}",
                    Calories = cal,
                    Carbs = carbs,
                    Protein = protein,
                    Fat = fat,
                    IsTargetMet = isMet
                };
                TrendPoints.Add(pt);
            }

            TotalDays = daysCount;
            DaysMet = metCount;
            TotalIntake = totalVal;
            AverageIntake = daysCount > 0 ? totalVal / daysCount : 0.0;
            MaxIntake = rawValues.Count > 0 ? maxVal : 0.0;
            MinIntake = rawValues.Count > 0 ? minVal : 0.0;
            ComplianceRate = daysCount > 0 ? (double)metCount / daysCount * 100.0 : 0.0;

            // Scale Heights for bars dynamically
            double scaleMax = Math.Max(MaxIntake, TargetRda);
            if (scaleMax <= 0.0) scaleMax = 1.0;
            foreach (var pt in TrendPoints)
            {
                pt.RelativeHeight = (pt.Value / scaleMax) * 180.0;
                if (pt.RelativeHeight < 4.0) pt.RelativeHeight = 4.0; // minimum visible height
            }

            // Generate customized diagnostic interpretation
            GenerateClinicalInsight();

            // Calculate Period Averages for all 41 essential nutrients
            PeriodAverages.Clear();
            foreach (var def in Nutrients.Definitions)
            {
                double sum = 0.0;
                foreach (var date in days)
                {
                    string dateStr = date.ToString("yyyy-MM-dd");
                    var dailyEntry = NutritionEntry.ComputeForDate(dateStr, db.FoodEntries, _parent.Supplements, db.TakenSupplementsForToday);
                    double intakeVal;
                    double val = dailyEntry.Nutrients.TryGetValue(def.Key, out intakeVal) ? intakeVal : 0.0;
                    sum += val;
                }
                double avg = daysCount > 0 ? sum / daysCount : 0.0;

                double targetVal = def.Rda;
                double customVal;
                if (overrides != null && overrides.TryGetValue(def.Key, out customVal))
                {
                    targetVal = customVal;
                }

                double pct = targetVal > 0.0 ? (avg / targetVal) * 100.0 : 100.0;

                string status = "YELLOW";
                if (def.IsMaxLimit)
                {
                    status = avg <= targetVal ? "GREEN" : "RED";
                }
                else
                {
                    if (pct >= 100.0) status = "GREEN";
                    else if (pct >= 50.0) status = "YELLOW";
                    else status = "RED";
                }

                PeriodAverages.Add(new NutrientPeriodSummary
                {
                    Definition = def,
                    AverageIntake = avg,
                    TargetRda = targetVal,
                    Percentage = pct,
                    PercentageFormatted = targetVal > 0.0 ? $"{pct:F0}%" : "100%",
                    Status = status
                });
            }
        }

        private void GenerateClinicalInsight()
        {
            var nutrient = SelectedNutrient;
            double avg = AverageIntake;
            double rda = TargetRda;
            double pct = rda > 0 ? (avg / rda) * 100.0 : 100.0;

            string statusText = "";
            string guideline = "";

            if (nutrient.IsMaxLimit)
            {
                if (avg > rda)
                {
                    statusText = $"Elevated average ({avg:F1} {nutrient.Unit}) exceeds the safe limit of {rda:F1} {nutrient.Unit} by {avg - rda:F1} {nutrient.Unit} ({(pct - 100):F0}% over limit).";
                    guideline = "Guidance: Reduce high-density sources of this element and monitor co-supplement intake levels.";
                }
                else
                {
                    statusText = $"Optimal average ({avg:F1} {nutrient.Unit}) is within safe clinical boundaries (does not exceed {rda:F1} {nutrient.Unit}).";
                    guideline = "Compliance Met: Great work managing dietary limits. Maintain current healthy patterns.";
                }
            }
            else
            {
                if (pct >= 100.0)
                {
                    statusText = $"Optimal average ({avg:F1} {nutrient.Unit}) successfully meets your RDA target of {rda:F1} {nutrient.Unit} ({pct:F0}% achieved).";
                    guideline = "Compliance Met: Metabolic pathways are well supplied. Maintain current intake habits.";
                }
                else if (pct >= 50.0)
                {
                    statusText = $"Sub-optimal average ({avg:F1} {nutrient.Unit}) is at {pct:F0}% of your daily target of {rda:F1} {nutrient.Unit}.";
                    guideline = "Guidance: Enrich whole-food meals with items rich in this micronutrient to reach optimum targets.";
                }
                else
                {
                    statusText = $"Deficient average ({avg:F1} {nutrient.Unit}) represents a critical deficiency at only {pct:F0}% of your target {rda:F1} {nutrient.Unit}.";
                    guideline = "Action Required: Significant intake gap. Actively log meals, prioritize food sources high in this cofactor, or review targeted supplement logs.";
                }
            }

            TrendInsight = $"{statusText}\n{guideline}\nAccuracy: {DaysMet} of {TotalDays} days ({ComplianceRate:F0}%) compliant with target guidelines.";
        }

        protected bool SetProperty<T>(ref T storage, T value, [CallerMemberName] string? propertyName = null)
        {
            if (EqualityComparer<T>.Default.Equals(storage, value)) return false;
            storage = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
            return true;
        }
    }

    public class NutrientPeriodSummary : INotifyPropertyChanged
    {
        private NutrientDefinition _definition = new NutrientDefinition();
        private double _averageIntake;
        private double _targetRda;
        private double _percentage;
        private string _status = "YELLOW";
        private string _percentageFormatted = "0%";

        public NutrientDefinition Definition { get => _definition; set => SetProperty(ref _definition, value); }
        public double AverageIntake { get => _averageIntake; set => SetProperty(ref _averageIntake, value); }
        public double TargetRda { get => _targetRda; set => SetProperty(ref _targetRda, value); }
        public double Percentage { get => _percentage; set => SetProperty(ref _percentage, value); }
        public string Status { get => _status; set => SetProperty(ref _status, value); }
        public string PercentageFormatted { get => _percentageFormatted; set => SetProperty(ref _percentageFormatted, value); }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected bool SetProperty<T>(ref T storage, T value, [CallerMemberName] string? propertyName = null)
        {
            if (EqualityComparer<T>.Default.Equals(storage, value)) return false;
            storage = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
            return true;
        }
    }
}
