using System.Collections.Generic;

namespace DesktopNutritionTracker.Models
{
    public class RdaProfileGroup
    {
        public string GroupCode { get; set; } = "";
        public string GroupName { get; set; } = "";
        public int MinAge { get; set; }
        public int MaxAge { get; set; }
        public string Gender { get; set; } = "";
        public Dictionary<string, double> NutrientTargets { get; set; } = new Dictionary<string, double>();

        public RdaProfileGroup() { }

        public RdaProfileGroup(string groupCode, string groupName, int minAge, int maxAge, string gender, Dictionary<string, double> nutrientTargets)
        {
            GroupCode = groupCode;
            GroupName = groupName;
            MinAge = minAge;
            MaxAge = maxAge;
            Gender = gender;
            NutrientTargets = nutrientTargets ?? new Dictionary<string, double>();
        }
    }
}
