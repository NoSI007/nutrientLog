using System.Collections.Generic;
using System.Linq;
using DesktopNutritionTracker.Services;

namespace DesktopNutritionTracker.Models
{
    public static class Nutrients
    {
        private static readonly INutrientBaselineService _baselineService = new NutrientBaselineService();

        public static readonly List<NutrientDefinition> Definitions = _baselineService.GetBaselineNutrients();

        public static NutrientDefinition? GetByKey(string key)
        {
            return _baselineService.GetNutrientByKey(key);
        }
    }
}
