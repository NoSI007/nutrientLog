using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;
using DesktopNutritionTracker.Models;
using DesktopNutritionTracker.Repositories;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// EF Core database backed implementation of the aggregation service.
    /// Manages data aggregation for charts, trends, and reporting tools.
    /// </summary>
    public class NutritionAggregationService : INutritionAggregationService
    {
        private readonly ILogEntryRepository _logEntryRepository;

        /// <summary>
        /// Instantiates the service with the required log entry data repository.
        /// </summary>
        public NutritionAggregationService(ILogEntryRepository? logEntryRepository = null)
        {
            _logEntryRepository = logEntryRepository ?? new LogEntryRepository();
        }

        /// <summary>
        /// Retrieves aggregated daily nutrient totals (calories, macros, micros) for a given date range.
        /// </summary>
        public async Task<IEnumerable<DailyNutrientAggregate>> GetDailyAggregatesAsync(string startDate, string endDate)
        {
            if (string.IsNullOrWhiteSpace(startDate) || string.IsNullOrWhiteSpace(endDate))
            {
                return Enumerable.Empty<DailyNutrientAggregate>();
            }

            // 1. Retrieve all logs within the range (ordered by date)
            var logs = await _logEntryRepository.GetByDateRangeAsync(startDate, endDate);
            var logsList = logs.ToList();

            // 2. Generate a list of all date strings within the range to guarantee no gaps in trend plotting (Offline-first Zero-filling)
            var datesInRange = GetDateStringsInRange(startDate, endDate);
            var dailyAggregates = new List<DailyNutrientAggregate>();

            // 3. Group the retrieved logs by date for efficient lookup
            var logsGroupedByDate = logsList
                .GroupBy(l => l.Date)
                .ToDictionary(g => g.Key, g => g.ToList());

            foreach (var dateStr in datesInRange)
            {
                var aggregate = new DailyNutrientAggregate
                {
                    Date = dateStr,
                    DisplayLabel = FormatDisplayDate(dateStr)
                };

                if (logsGroupedByDate.TryGetValue(dateStr, out var dayLogs))
                {
                    double totalCalories = 0;
                    double totalCarbs = 0;
                    double totalProtein = 0;
                    double totalFat = 0;
                    double totalFiber = 0;
                    var microMap = new Dictionary<string, double>();

                    foreach (var log in dayLogs)
                    {
                        if (log.FoodItem != null)
                        {
                            double qty = log.Quantity;

                            totalCalories += log.FoodItem.Calories * qty;
                            totalCarbs += log.FoodItem.Carbohydrates * qty;
                            totalProtein += log.FoodItem.Protein * qty;
                            totalFat += log.FoodItem.Fat * qty;
                            totalFiber += log.FoodItem.Fiber * qty;

                            // Aggregate granular micro-nutrients if present in JSON dict
                            if (log.FoodItem.Nutrients != null)
                            {
                                foreach (var kvp in log.FoodItem.Nutrients)
                                {
                                    string key = kvp.Key.ToLowerInvariant();
                                    // Skip core macros already handled separately
                                    if (key == "calories" || key == "carbohydrates" || key == "protein" || key == "fat" || key == "fiber")
                                    {
                                        continue;
                                    }

                                    double scaledValue = kvp.Value * qty;
                                    if (microMap.ContainsKey(key))
                                    {
                                        microMap[key] += scaledValue;
                                    }
                                    else
                                    {
                                        microMap[key] = scaledValue;
                                    }
                                }
                            }
                        }
                    }

                    aggregate.Calories = Math.Round(totalCalories, 1);
                    aggregate.Carbohydrates = Math.Round(totalCarbs, 1);
                    aggregate.Protein = Math.Round(totalProtein, 1);
                    aggregate.Fat = Math.Round(totalFat, 1);
                    aggregate.Fiber = Math.Round(totalFiber, 1);
                    
                    // Round micro nutrients for cleaner UI delivery
                    aggregate.MicroNutrients = microMap.ToDictionary(
                        kvp => kvp.Key, 
                        kvp => Math.Round(kvp.Value, 2)
                    );
                }

                dailyAggregates.Add(aggregate);
            }

            return dailyAggregates;
        }

        /// <summary>
        /// Formats aggregated daily nutrient data into a standardized structure optimized for frontend charting libraries.
        /// Can return multiple data series (metrics) together.
        /// </summary>
        public async Task<ChartDataDto> GetFormattedChartDataAsync(string startDate, string endDate, string[]? metrics = null)
        {
            var dailyAggregates = (await GetDailyAggregatesAsync(startDate, endDate)).ToList();

            var chartData = new ChartDataDto
            {
                Labels = dailyAggregates.Select(a => a.DisplayLabel).ToList()
            };

            // Default to plotting calories and macronutrient breakdown if none specified
            if (metrics == null || metrics.Length == 0)
            {
                metrics = new[] { "calories", "protein", "carbohydrates", "fat" };
            }

            foreach (var metric in metrics)
            {
                var dataset = new ChartDatasetDto { Label = GetFriendlyMetricLabel(metric) };
                var hexColor = GetMetricColor(metric);
                dataset.ColorHex = hexColor;
                dataset.BackgroundColor = ConvertToRgba(hexColor, 0.15);

                var values = new List<double>();
                foreach (var day in dailyAggregates)
                {
                    double value = 0;
                    string normalizedMetric = metric.Trim().ToLowerInvariant();

                    switch (normalizedMetric)
                    {
                        case "calories":
                        case "energy":
                            value = day.Calories;
                            break;
                        case "protein":
                            value = day.Protein;
                            break;
                        case "carbohydrates":
                        case "carbs":
                            value = day.Carbohydrates;
                            break;
                        case "fat":
                        case "fats":
                            value = day.Fat;
                            break;
                        case "fiber":
                            value = day.Fiber;
                            break;
                        default:
                            // Try matching micro-nutrients from dictionary fallback
                            if (day.MicroNutrients.TryGetValue(normalizedMetric, out double microVal))
                            {
                                value = microVal;
                            }
                            break;
                    }
                    values.Add(Math.Round(value, 1));
                }

                dataset.Data = values;
                chartData.Datasets.Add(dataset);
            }

            return chartData;
        }

        /// <summary>
        /// Groups log entries by date and returns meal-wise breakdown of macronutrients and calories.
        /// </summary>
        public async Task<Dictionary<string, Dictionary<string, MealAggregate>>> GetMealBreakdownAsync(string startDate, string endDate)
        {
            var logs = await _logEntryRepository.GetByDateRangeAsync(startDate, endDate);
            var dates = GetDateStringsInRange(startDate, endDate);

            var outerResult = new Dictionary<string, Dictionary<string, MealAggregate>>();

            var logsGrouped = logs
                .GroupBy(l => l.Date)
                .ToDictionary(g => g.Key, g => g.ToList());

            foreach (var dateStr in dates)
            {
                var innerDict = new Dictionary<string, MealAggregate>(StringComparer.OrdinalIgnoreCase)
                {
                    { "Breakfast", new MealAggregate() },
                    { "Lunch", new MealAggregate() },
                    { "Dinner", new MealAggregate() },
                    { "Snacks", new MealAggregate() }
                };

                if (logsGrouped.TryGetValue(dateStr, out var dayLogs))
                {
                    foreach (var log in dayLogs)
                    {
                        if (log.FoodItem == null) continue;

                        string mealKey = NormalizeMealType(log.MealType);
                        if (!innerDict.TryGetValue(mealKey, out var mealAgg))
                        {
                            mealAgg = new MealAggregate();
                            innerDict[mealKey] = mealAgg;
                        }

                        double qty = log.Quantity;
                        mealAgg.Calories += Math.Round(log.FoodItem.Calories * qty, 1);
                        mealAgg.Carbohydrates += Math.Round(log.FoodItem.Carbohydrates * qty, 1);
                        mealAgg.Protein += Math.Round(log.FoodItem.Protein * qty, 1);
                        mealAgg.Fat += Math.Round(log.FoodItem.Fat * qty, 1);
                    }
                }

                outerResult[dateStr] = innerDict;
            }

            return outerResult;
        }

        #region Private Helpers

        private static List<string> GetDateStringsInRange(string startDate, string endDate)
        {
            var dateStrings = new List<string>();
            if (DateTime.TryParseExact(startDate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var start) &&
                DateTime.TryParseExact(endDate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var end))
            {
                for (var date = start.Date; date <= end.Date; date = date.AddDays(1))
                {
                    dateStrings.Add(date.ToString("yyyy-MM-dd"));
                }
            }
            else
            {
                // Fallback simply returns start & end if parsing fails
                dateStrings.Add(startDate);
                if (startDate != endDate) dateStrings.Add(endDate);
            }
            return dateStrings;
        }

        private static string FormatDisplayDate(string dateStr)
        {
            if (DateTime.TryParseExact(dateStr, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var dt))
            {
                return dt.ToString("ddd d"); // e.g. "Mon 29"
            }
            return dateStr;
        }

        private static string NormalizeMealType(string rawMealType)
        {
            if (string.IsNullOrWhiteSpace(rawMealType)) return "Snacks";
            
            string normalized = rawMealType.Trim().ToLowerInvariant();
            if (normalized.Contains("breakfast")) return "Breakfast";
            if (normalized.Contains("lunch")) return "Lunch";
            if (normalized.Contains("dinner")) return "Dinner";
            return "Snacks";
        }

        private static string GetFriendlyMetricLabel(string metric)
        {
            switch (metric.Trim().ToLowerInvariant())
            {
                case "calories":
                case "energy":
                    return "Calories (kcal)";
                case "protein":
                    return "Protein (g)";
                case "carbohydrates":
                case "carbs":
                    return "Carbs (g)";
                case "fat":
                case "fats":
                    return "Fat (g)";
                case "fiber":
                    return "Fiber (g)";
                case "sugars":
                    return "Sugars (g)";
                case "sodium":
                    return "Sodium (mg)";
                case "potassium":
                    return "Potassium (mg)";
                case "calcium":
                    return "Calcium (mg)";
                case "iron":
                    return "Iron (mg)";
                case "saturated_fat":
                    return "Saturated Fat (g)";
                default:
                    // Capitalize first letter as fallback label
                    var name = metric.Replace('_', ' ');
                    if (name.Length > 0)
                    {
                        return char.ToUpper(name[0]) + name.Substring(1);
                    }
                    return name;
            }
        }

        private static string GetMetricColor(string metric)
        {
            switch (metric.Trim().ToLowerInvariant())
            {
                case "calories":
                case "energy":
                    return "#E91E63"; // Deep pink / Rose
                case "protein":
                    return "#4CAF50"; // Green
                case "carbohydrates":
                case "carbs":
                    return "#2196F3"; // Blue
                case "fat":
                case "fats":
                    return "#9C27B0"; // Vibrant Purple
                case "fiber":
                    return "#795548"; // Earthy brown
                case "sugars":
                    return "#FFC107"; // Amber / Golden
                case "sodium":
                    return "#607D8B"; // Blue grey
                case "potassium":
                    return "#00BCD4"; // Cyan
                case "calcium":
                    return "#03A9F4"; // Light blue
                default:
                    return "#9E9E9E"; // Grey fallback
            }
        }

        private static string ConvertToRgba(string hexColor, double alpha)
        {
            try
            {
                if (hexColor.StartsWith("#"))
                {
                    hexColor = hexColor.Substring(1);
                }

                if (hexColor.Length == 6)
                {
                    int r = Convert.ToInt32(hexColor.Substring(0, 2), 16);
                    int g = Convert.ToInt32(hexColor.Substring(2, 2), 16);
                    int b = Convert.ToInt32(hexColor.Substring(4, 2), 16);

                    return $"rgba({r}, {g}, {b}, {alpha.ToString("F2", CultureInfo.InvariantCulture)})";
                }
            }
            catch { }

            return $"rgba(158, 158, 158, {alpha.ToString("F2", CultureInfo.InvariantCulture)})";
        }

        #endregion
    }
}
