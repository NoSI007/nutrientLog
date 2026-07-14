using System;
using System.Collections.Generic;

namespace DesktopNutritionTracker.Models
{
    /// <summary>
    /// Models the complete data state structure of the NutriScribe desktop application,
    /// storing all food journal entries, supplement protocols, custom RDA overrides, and user profile profiles.
    /// </summary>
    public class StorageData
    {
        public List<FoodLogEntry> FoodEntries { get; set; } = new List<FoodLogEntry>();
        
        public List<Supplement> Supplements { get; set; } = new List<Supplement>();
        
        public Dictionary<string, double> CustomRdaOverrides { get; set; } = new Dictionary<string, double>();
        
        public int ProfileAge { get; set; } = 30;
        
        public string ProfileActivity { get; set; } = "Lightly Active";
        
        public string ProfileSex { get; set; } = "Female";
        
        public int ObservationDaysLimit { get; set; } = 30;
        
        public List<string> TakenSupplementsForToday { get; set; } = new List<string>();
        
        public List<CustomDailyProfile> CustomDailyProfiles { get; set; } = new List<CustomDailyProfile>();
        
        public string ActiveProfileName { get; set; } = "Standard Baseline";
        
        public List<FoodLogEntry> RecurringFoods { get; set; } = new List<FoodLogEntry>();
        
        public string ServingUnit { get; set; } = "g";
        
        public bool HasPrepopulatedSampleLogs { get; set; } = false;
    }
}
