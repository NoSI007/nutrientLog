using System.Collections.Generic;
using Newtonsoft.Json;

namespace DesktopNutritionTracker.Models
{
    public class Supplement
    {
        public int Id { get; set; }
        public string Name { get; set; } = "";
        public string Dosage { get; set; } = "";
        public string Frequency { get; set; } = ""; // "Once Daily", "Twice Daily", "Weekly", "Alternate Days"
        public string DaysOfWeek { get; set; } = ""; // E.g., "Monday,Thursday"
        public string TimeOfDay { get; set; } = "Morning"; // "Morning", "Afternoon", "Evening", "Night"
        public string Notes { get; set; } = "";
        public Dictionary<string, double> Nutrients { get; set; } = new Dictionary<string, double>();

        [JsonIgnore]
        public bool IsTaken { get; set; }

        public Supplement() { }

        public Supplement(string name, string dosage, string frequency, string daysOfWeek, string timeOfDay, string notes, Dictionary<string, double> nutrients)
        {
            Name = name;
            Dosage = dosage;
            Frequency = frequency;
            DaysOfWeek = daysOfWeek;
            TimeOfDay = timeOfDay;
            Notes = notes;
            Nutrients = nutrients;
        }
    }
}
