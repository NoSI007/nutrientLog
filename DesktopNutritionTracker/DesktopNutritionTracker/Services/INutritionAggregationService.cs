using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Service contract for aggregating nutritional LogEntry data by date range 
    /// and formatting it for frontend charting libraries.
    /// </summary>
    public interface INutritionAggregationService
    {
        /// <summary>
        /// Retrieves aggregated daily nutrient totals (calories, macros, micros) for a given date range.
        /// </summary>
        Task<IEnumerable<DailyNutrientAggregate>> GetDailyAggregatesAsync(string startDate, string endDate);

        /// <summary>
        /// Formats aggregated daily nutrient data into a standardized structure optimized for frontend charting libraries.
        /// Can return data for all macronutrients or focus on a single specific nutrient/calorie metric.
        /// </summary>
        Task<ChartDataDto> GetFormattedChartDataAsync(string startDate, string endDate, string[]? metrics = null);

        /// <summary>
        /// Groups log entries by date and returns meal-wise breakdown of macronutrients and calories.
        /// </summary>
        Task<Dictionary<string, Dictionary<string, MealAggregate>>> GetMealBreakdownAsync(string startDate, string endDate);
    }

    /// <summary>
    /// DTO representing aggregated nutrients for a single day.
    /// </summary>
    public class DailyNutrientAggregate
    {
        public string Date { get; set; } = ""; // "YYYY-MM-DD"
        public string DisplayLabel { get; set; } = ""; // e.g. "Mon 29"
        public double Calories { get; set; }
        public double Carbohydrates { get; set; }
        public double Protein { get; set; }
        public double Fat { get; set; }
        public double Fiber { get; set; }
        public Dictionary<string, double> MicroNutrients { get; set; } = new Dictionary<string, double>();
    }

    /// <summary>
    /// DTO representing aggregated nutrients for a specific meal type (Breakfast, Lunch, etc.).
    /// </summary>
    public class MealAggregate
    {
        public double Calories { get; set; }
        public double Carbohydrates { get; set; }
        public double Protein { get; set; }
        public double Fat { get; set; }
    }

    /// <summary>
    /// Unified DTO expected by modern charting libraries like Chart.js, Highcharts, or LiveCharts.
    /// </summary>
    public class ChartDataDto
    {
        /// <summary>
        /// The labels for the X-axis (typically date strings or short weekday descriptions).
        /// </summary>
        public List<string> Labels { get; set; } = new List<string>();

        /// <summary>
        /// Distinct data series datasets to be plotted (e.g. Calories line, Protein bar).
        /// </summary>
        public List<ChartDatasetDto> Datasets { get; set; } = new List<ChartDatasetDto>();
    }

    /// <summary>
    /// Represents a single series / line / bar dataset within a chart.
    /// </summary>
    public class ChartDatasetDto
    {
        /// <summary>
        /// Name of the metric / dataset series (e.g., "Protein (g)").
        /// </summary>
        public string Label { get; set; } = "";

        /// <summary>
        /// Sequential data values corresponding 1:1 with the parent chart's Labels.
        /// </summary>
        public List<double> Data { get; set; } = new List<double>();

        /// <summary>
        /// Suggested primary color in HEX format for frontend canvas/line coloring (e.g. "#4CAF50").
        /// </summary>
        public string ColorHex { get; set; } = "";

        /// <summary>
        /// Secondary fill color / alpha background color (e.g. "rgba(76, 175, 80, 0.2)").
        /// </summary>
        public string BackgroundColor { get; set; } = "";
    }
}
