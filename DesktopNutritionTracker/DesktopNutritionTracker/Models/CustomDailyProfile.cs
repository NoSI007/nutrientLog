using System;
using System.Collections.Generic;

namespace DesktopNutritionTracker.Models
{
    public class CustomDailyProfile
    {
        public string Name { get; set; } = "My Custom Profile";
        public string Description { get; set; } = "Personalized metabolic and nutrient adjustment profile.";
        
        // Maps nutrient Key (e.g. "calories", "sodium") to customized RDA target.
        public Dictionary<string, double> Targets { get; set; } = new Dictionary<string, double>();

        public CustomDailyProfile() { }

        public CustomDailyProfile(string name, string description)
        {
            Name = name;
            Description = description;
        }

        public CustomDailyProfile(string name, string description, Dictionary<string, double> targets)
        {
            Name = name;
            Description = description;
            Targets = targets ?? new Dictionary<string, double>();
        }
    }
}
