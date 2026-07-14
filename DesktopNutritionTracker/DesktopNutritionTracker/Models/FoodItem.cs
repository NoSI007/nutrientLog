using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;
using Newtonsoft.Json;

namespace DesktopNutritionTracker.Models
{
    /// <summary>
    /// Entity Framework Core model representing a Food Item in the SQLite database.
    /// Maps nutritional profiles derived from USDA FoodData Central or custom entries.
    /// </summary>
    [Table("FoodItems")]
    public class FoodItem
    {
        [Key]
        [DatabaseGenerated(DatabaseGeneratedOption.None)] // Uses the FdcId as the unique string key
        public string FdcId { get; set; } = "";

        [Required]
        [MaxLength(500)]
        public string Description { get; set; } = "";

        [MaxLength(250)]
        public string? BrandOwner { get; set; }

        public double ServingSize { get; set; } = 100.0;

        [MaxLength(50)]
        public string ServingSizeUnit { get; set; } = "g";

        // Core macronutrients mapped directly for indexing and rapid querying
        public double Calories { get; set; }
        public double Carbohydrates { get; set; }
        public double Protein { get; set; }
        public double Fat { get; set; }
        public double Fiber { get; set; }

        /// <summary>
        /// Backing field storing the full dictionary of essential nutrients serialized as a JSON string.
        /// Perfect for storing all 41 nutrients in a flexible, lightweight SQLite schema.
        /// </summary>
        [Required]
        public string NutrientsJson { get; set; } = "{}";

        /// <summary>
        /// Not-mapped dictionary property for seamless runtime operations matching existing models.
        /// </summary>
        [NotMapped]
        public Dictionary<string, double> Nutrients
        {
            get => string.IsNullOrEmpty(NutrientsJson) 
                ? new Dictionary<string, double>() 
                : JsonConvert.DeserializeObject<Dictionary<string, double>>(NutrientsJson) ?? new Dictionary<string, double>();
            set => NutrientsJson = JsonConvert.SerializeObject(value ?? new Dictionary<string, double>());
        }

        // Navigation property for related logs
        public virtual ICollection<LogEntry> LogEntries { get; set; } = new List<LogEntry>();
    }
}
