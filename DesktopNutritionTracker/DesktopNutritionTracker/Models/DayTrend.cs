namespace DesktopNutritionTracker.Models
{
    public class DayTrend
    {
        public string DateString { get; set; } = ""; // "YYYY-MM-DD"
        public string DisplayDate { get; set; } = ""; // "Mon", "Tue", etc.
        public double Calories { get; set; }
        public double CarbsGrams { get; set; }
        public double ProteinGrams { get; set; }
        public double FatGrams { get; set; }

        public DayTrend() { }

        public DayTrend(string dateString, string displayDate, double calories, double carbsGrams, double proteinGrams, double fatGrams)
        {
            DateString = dateString;
            DisplayDate = displayDate;
            Calories = calories;
            CarbsGrams = carbsGrams;
            ProteinGrams = proteinGrams;
            FatGrams = fatGrams;
        }
    }
}
