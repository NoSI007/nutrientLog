using System;
using System.Collections.Generic;

namespace DesktopNutritionTracker.Models
{
    public class FoodLogEntry
    {
        public int Id { get; set; }
        public string Date { get; set; } = ""; // Format: "YYYY-MM-DD"
        public string FoodName { get; set; } = "";
        public string MealType { get; set; } = ""; // "Breakfast", "Lunch", "Dinner", "Snack"
        public double Quantity { get; set; } = 1.0;
        public string Unit { get; set; } = "serving";
        public Dictionary<string, double> Nutrients { get; set; } = new Dictionary<string, double>();

        public FoodLogEntry() { }

        public FoodLogEntry(string date, string foodName, string mealType, double quantity, string unit, Dictionary<string, double> nutrients)
        {
            Date = date;
            FoodName = foodName;
            MealType = mealType;
            Quantity = quantity;
            Unit = unit;
            Nutrients = nutrients;
        }

        public double Calories => Nutrients.ContainsKey("calories") ? Nutrients["calories"] * Quantity : 0.0;
    }
}
