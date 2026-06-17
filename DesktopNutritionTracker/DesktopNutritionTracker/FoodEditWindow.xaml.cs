using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker
{
    /// <summary>
    /// Interaction logic for FoodEditWindow.xaml
    /// </summary>
    public partial class FoodEditWindow : Window
    {
        private readonly FoodLogEntry _entry;

        public FoodEditWindow(FoodLogEntry entry, List<string> mealTypes)
        {
            InitializeComponent();
            _entry = entry ?? throw new ArgumentNullException(nameof(entry));

            // Load Combos
            MealTypeCombo.ItemsSource = mealTypes ?? new List<string> { "Breakfast", "Lunch", "Dinner", "Snack" };

            // Populate current values
            PopulateFields();
        }

        private void PopulateFields()
        {
            FoodNameInput.Text = _entry.FoodName;
            MealTypeCombo.SelectedItem = _entry.MealType;
            QuantityInput.Text = _entry.Quantity.ToString("F1", CultureInfo.InvariantCulture);
            UnitInput.Text = _entry.Unit;

            // Load core nutrient overrides safely
            NutrientCalories.Text = GetNutrientValue("calories").ToString("F1", CultureInfo.InvariantCulture);
            NutrientWater.Text = GetNutrientValue("water").ToString("F1", CultureInfo.InvariantCulture);
            NutrientProtein.Text = GetNutrientValue("protein").ToString("F1", CultureInfo.InvariantCulture);
            NutrientCarbs.Text = GetNutrientValue("carbohydrates").ToString("F1", CultureInfo.InvariantCulture);
            NutrientFat.Text = GetNutrientValue("fat").ToString("F1", CultureInfo.InvariantCulture);
            NutrientFiber.Text = GetNutrientValue("fiber").ToString("F1", CultureInfo.InvariantCulture);
            NutrientSodium.Text = GetNutrientValue("sodium").ToString("F1", CultureInfo.InvariantCulture);
            NutrientPotassium.Text = GetNutrientValue("potassium").ToString("F1", CultureInfo.InvariantCulture);
        }

        private double GetNutrientValue(string key)
        {
            if (_entry.Nutrients != null && _entry.Nutrients.ContainsKey(key))
            {
                return _entry.Nutrients[key];
            }
            return 0.0;
        }

        private void Save_Click(object sender, RoutedEventArgs e)
        {
            ErrorText.Text = "";

            string name = FoodNameInput.Text.Trim();
            if (string.IsNullOrEmpty(name))
            {
                ErrorText.Text = "Food name cannot be empty.";
                return;
            }

            string mealType = MealTypeCombo.SelectedItem as string ?? "Breakfast";

            double quantity;
            if (!double.TryParse(QuantityInput.Text, NumberStyles.Any, CultureInfo.InvariantCulture, out quantity) || quantity <= 0.0)
            {
                ErrorText.Text = "Quantity must be a valid positive decimal.";
                return;
            }

            string unit = UnitInput.Text.Trim();
            if (string.IsNullOrEmpty(unit))
            {
                unit = "serving";
            }

            // Parse nutrient additions safely
            double cal, water, protein, carbs, fat, fiber, sodium, potassium;
            if (!TryParseNutrient(NutrientCalories.Text, out cal) ||
                !TryParseNutrient(NutrientWater.Text, out water) ||
                !TryParseNutrient(NutrientProtein.Text, out protein) ||
                !TryParseNutrient(NutrientCarbs.Text, out carbs) ||
                !TryParseNutrient(NutrientFat.Text, out fat) ||
                !TryParseNutrient(NutrientFiber.Text, out fiber) ||
                !TryParseNutrient(NutrientSodium.Text, out sodium) ||
                !TryParseNutrient(NutrientPotassium.Text, out potassium))
            {
                ErrorText.Text = "All nutrient inputs must be non-negative real numbers.";
                return;
            }

            // Write back to the entity
            _entry.FoodName = name;
            _entry.MealType = mealType;
            _entry.Quantity = quantity;
            _entry.Unit = unit;

            if (_entry.Nutrients == null)
            {
                _entry.Nutrients = new Dictionary<string, double>();
            }

            _entry.Nutrients["calories"] = cal;
            _entry.Nutrients["water"] = water;
            _entry.Nutrients["protein"] = protein;
            _entry.Nutrients["carbohydrates"] = carbs;
            _entry.Nutrients["fat"] = fat;
            _entry.Nutrients["fiber"] = fiber;
            _entry.Nutrients["sodium"] = sodium;
            _entry.Nutrients["potassium"] = potassium;

            DialogResult = true;
            Close();
        }

        private bool TryParseNutrient(string text, out double val)
        {
            val = 0.0;
            if (string.IsNullOrWhiteSpace(text)) return true; // Treat blank as 0.0
            if (double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out val))
            {
                return val >= 0.0;
            }
            return false;
        }

        private void Cancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }
    }
}
