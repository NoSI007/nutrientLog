namespace DesktopNutritionTracker.Models
{
    public class NutrientDefinition
    {
        public string Key { get; set; } = "";
        public string Name { get; set; } = "";
        public NutrientGroup Group { get; set; }
        public double Rda { get; set; }
        public string Unit { get; set; } = "";
        public bool IsMaxLimit { get; set; } = false;
        public string Description { get; set; } = "";

        public NutrientDefinition() { }

        public NutrientDefinition(string key, string name, NutrientGroup group, double rda, string unit, bool isMaxLimit = false, string description = "")
        {
            Key = key;
            Name = name;
            Group = group;
            Rda = rda;
            Unit = unit;
            IsMaxLimit = isMaxLimit;
            Description = description;
        }
    }
}
