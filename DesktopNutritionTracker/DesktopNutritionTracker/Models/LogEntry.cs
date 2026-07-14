using System;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace DesktopNutritionTracker.Models
{
    /// <summary>
    /// Entity Framework Core model representing a daily food intake log in the SQLite database.
    /// Maps directly to user records and tracks active consumption logs.
    /// </summary>
    [Table("LogEntries")]
    public class LogEntry
    {
        [Key]
        public int Id { get; set; }

        /// <summary>
        /// Log Date in format "YYYY-MM-DD".
        /// </summary>
        [Required]
        [MaxLength(10)]
        public string Date { get; set; } = "";

        [Required]
        [MaxLength(500)]
        public string FoodName { get; set; } = "";

        /// <summary>
        /// Meal Type (e.g. "Breakfast", "Lunch", "Dinner", "Snack").
        /// </summary>
        [Required]
        [MaxLength(50)]
        public string MealType { get; set; } = "";

        public double Quantity { get; set; } = 1.0;

        [Required]
        [MaxLength(50)]
        public string Unit { get; set; } = "serving";

        // Foreign Key referencing the related FoodItem (if logged from USDA/database lookup)
        public string? FoodItemFdcId { get; set; }

        [ForeignKey(nameof(FoodItemFdcId))]
        public virtual FoodItem? FoodItem { get; set; }

        public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    }
}
