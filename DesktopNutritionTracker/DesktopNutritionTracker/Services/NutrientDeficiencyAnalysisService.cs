using System;
using System.Collections.Generic;
using System.Linq;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    public class DeficiencyItem
    {
        public string NutrientKey { get; set; } = "";
        public string NutrientName { get; set; } = "";
        public string Group { get; set; } = "";
        public double Intake { get; set; }
        public double Rda { get; set; }
        public double Difference { get; set; } // RDA - Intake
        public double MetPercentage { get; set; } // (Intake / RDA) * 100
        public string Unit { get; set; } = "";
        public string Status { get; set; } = ""; // "Deficient", "Marginal", "Met"
        public string Description { get; set; } = "";
    }

    public class DeficiencyReport
    {
        public string Date { get; set; } = "";
        public List<DeficiencyItem> Deficiencies { get; set; } = new List<DeficiencyItem>();
        public List<DeficiencyItem> MetNutrients { get; set; } = new List<DeficiencyItem>();
        public double AverageMetPercentage { get; set; }
        public string OverallSummary { get; set; } = "";
    }

    public interface INutrientDeficiencyAnalysisService
    {
        DeficiencyReport AnalyzeDeficiencies(string date);
        DeficiencyReport AnalyzeDeficiencies(string date, StorageData db);
    }

    public class NutrientDeficiencyAnalysisService : INutrientDeficiencyAnalysisService
    {
        private readonly StorageService _storageService;

        public NutrientDeficiencyAnalysisService()
        {
            _storageService = new StorageService();
        }

        public DeficiencyReport AnalyzeDeficiencies(string date)
        {
            var db = _storageService.Load();
            return AnalyzeDeficiencies(date, db);
        }

        public DeficiencyReport AnalyzeDeficiencies(string date, StorageData db)
        {
            var report = new DeficiencyReport { Date = date };

            if (db == null)
            {
                report.OverallSummary = "No database data available.";
                return report;
            }

            // Compute total daily quantitative intakes for the selected date
            var dailyIntake = NutritionEntry.ComputeForDate(date, db.FoodEntries, db.Supplements, db.TakenSupplementsForToday);

            // Get static RDA values / definitions
            var definitions = Nutrients.Definitions;

            double totalMetPercentageSum = 0.0;
            int countForAverage = 0;

            foreach (var def in definitions)
            {
                // Skip if the RDA is not defined or is 0
                if (def.Rda <= 0.0)
                    continue;

                // Skip limit-based nutrients like saturated fat/trans fat/sodium/cholesterol since they represent ceilings, not baseline minimum needs
                if (def.IsMaxLimit)
                    continue;

                double intake = dailyIntake.Nutrients.ContainsKey(def.Key) ? dailyIntake.Nutrients[def.Key] : 0.0;
                double diff = def.Rda - intake;
                double metPercentage = (intake / def.Rda) * 100.0;

                totalMetPercentageSum += Math.Min(metPercentage, 100.0); // Cap at 100% for overall average contribution
                countForAverage++;

                var item = new DeficiencyItem
                {
                    NutrientKey = def.Key,
                    NutrientName = def.Name,
                    Group = def.Group.GetDisplayName(),
                    Intake = Math.Round(intake, 2),
                    Rda = Math.Round(def.Rda, 2),
                    Difference = Math.Round(Math.Max(0.0, diff), 2),
                    MetPercentage = Math.Round(metPercentage, 1),
                    Unit = def.Unit,
                    Description = def.Description
                };

                if (intake < def.Rda)
                {
                    if (metPercentage < 50.0)
                    {
                        item.Status = "Deficient";
                    }
                    else
                    {
                        item.Status = "Marginal";
                    }
                    report.Deficiencies.Add(item);
                }
                else
                {
                    item.Status = "Met";
                    item.Difference = 0.0;
                    report.MetNutrients.Add(item);
                }
            }

            if (countForAverage > 0)
            {
                report.AverageMetPercentage = Math.Round(totalMetPercentageSum / countForAverage, 1);
            }

            int deficiencyCount = report.Deficiencies.Count;
            if (deficiencyCount == 0)
            {
                report.OverallSummary = $"Perfect day! All {countForAverage} monitored clinical RDA targets have been fully met for {date}.";
            }
            else
            {
                int criticalCount = report.Deficiencies.Count(d => d.Status == "Deficient");
                report.OverallSummary = $"{date}: Met {report.MetNutrients.Count} of {countForAverage} clinical RDA targets. Identified {deficiencyCount} deficiency warnings ({criticalCount} critical).";
            }

            return report;
        }
    }
}
