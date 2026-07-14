using System;
using System.Collections.Generic;
using System.Linq;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    public enum NutrientAlertType
    {
        CriticalDeficit,  // < 50% RDA
        ExceededCeiling   // > 100% UL (MaxLimit)
    }

    public class NutrientAlert
    {
        public string NutrientKey { get; set; } = "";
        public string NutrientName { get; set; } = "";
        public double CurrentIntake { get; set; }
        public double TargetRda { get; set; }
        public string Unit { get; set; } = "";
        public double Percentage { get; set; }
        public NutrientAlertType AlertType { get; set; }
        public string Message { get; set; } = "";
        public string Suggestion { get; set; } = "";
    }

    public interface INutrientNotificationService
    {
        List<NutrientAlert> EvaluateDailyNutrients(IEnumerable<NutrientStatus> statuses);
    }

    public class NutrientNotificationService : INutrientNotificationService
    {
        public List<NutrientAlert> EvaluateDailyNutrients(IEnumerable<NutrientStatus> statuses)
        {
            var alerts = new List<NutrientAlert>();

            if (statuses == null) return alerts;

            foreach (var status in statuses)
            {
                var def = status.Definition;
                if (def == null || def.Rda <= 0.0) continue;

                if (def.IsMaxLimit)
                {
                    // For limits, trigger alert if intake exceeds RDA (ceiling)
                    if (status.Intake > def.Rda)
                    {
                        double excessPct = (status.Intake / def.Rda) * 100.0;
                        alerts.Add(new NutrientAlert
                        {
                            NutrientKey = def.Key,
                            NutrientName = def.Name,
                            CurrentIntake = Math.Round(status.Intake, 1),
                            TargetRda = Math.Round(def.Rda, 1),
                            Unit = def.Unit,
                            Percentage = Math.Round(excessPct, 1),
                            AlertType = NutrientAlertType.ExceededCeiling,
                            Message = $"{def.Name} has exceeded the recommended clinical limit of {def.Rda} {def.Unit}.",
                            Suggestion = $"Reduce intake of foods high in {def.Name.ToLower()} to stay within healthy constraints."
                        });
                    }
                }
                else
                {
                    // For goals, trigger alert if intake is significantly below RDA (< 50% RDA)
                    if (status.Percentage < 50.0)
                    {
                        alerts.Add(new NutrientAlert
                        {
                            NutrientKey = def.Key,
                            NutrientName = def.Name,
                            CurrentIntake = Math.Round(status.Intake, 1),
                            TargetRda = Math.Round(def.Rda, 1),
                            Unit = def.Unit,
                            Percentage = Math.Round(status.Percentage, 1),
                            AlertType = NutrientAlertType.CriticalDeficit,
                            Message = $"{def.Name} intake is critically low ({status.Percentage:F1}% of RDA).",
                            Suggestion = $"Incorporate whole foods or supplements rich in {def.Name.ToLower()} to bridge this metabolic gap."
                        });
                    }
                }
            }

            return alerts;
        }
    }
}
