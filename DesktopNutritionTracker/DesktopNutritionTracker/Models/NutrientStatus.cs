namespace DesktopNutritionTracker.Models
{
    public enum StatusColor
    {
        RED,    // Critical (deficient or limit exceeded)
        YELLOW, // Sub-optimal (50-99% for goals)
        GREEN   // Healthy (Met or within healthy limit)
    }

    public class NutrientStatus
    {
        public NutrientDefinition Definition { get; set; } = new NutrientDefinition();
        public double Intake { get; set; }
        public double Percentage { get; set; }
        public StatusColor Status { get; set; }

        public NutrientStatus() { }

        public NutrientStatus(NutrientDefinition definition, double intake, double percentage, StatusColor status)
        {
            Definition = definition;
            Intake = intake;
            Percentage = percentage;
            Status = status;
        }

        public string DescriptionAndDef => $"{Definition.Name}: {Intake:F1} / {Definition.Rda:F1} {Definition.Unit} ({Percentage:F0}%)";
        public bool IsWarning => !Definition.IsMaxLimit && Percentage < 80.0 && Definition.Rda > 0;
    }
}
