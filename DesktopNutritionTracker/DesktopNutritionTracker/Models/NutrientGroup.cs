namespace DesktopNutritionTracker.Models
{
    public enum NutrientGroup
    {
        MACROS,
        LIPIDS,
        VITAMINS,
        MINERALS,
        OTHERS
    }

    public static class NutrientGroupExtensions
    {
        public static string GetDisplayName(this NutrientGroup group)
        {
            switch (group)
            {
                case NutrientGroup.MACROS: return "Macronutrients";
                case NutrientGroup.LIPIDS: return "Fats & Cholesterol";
                case NutrientGroup.VITAMINS: return "Vitamins";
                case NutrientGroup.MINERALS: return "Minerals";
                case NutrientGroup.OTHERS: return "Other Nutrients";
                default: return "Nutrients";
            }
        }
    }
}
