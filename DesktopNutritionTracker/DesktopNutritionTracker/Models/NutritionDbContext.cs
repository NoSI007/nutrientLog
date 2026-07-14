using System;
using System.ComponentModel.DataAnnotations;
using System.Linq;
using Microsoft.EntityFrameworkCore;

namespace DesktopNutritionTracker.Models
{
    /// <summary>
    /// Entity representing cached USDA API search results.
    /// </summary>
    public class CachedUsdaSearch
    {
        [Key]
        public int Id { get; set; }

        [Required]
        public string Query { get; set; } = "";

        [Required]
        public string JsonResponse { get; set; } = "";

        public DateTime Timestamp { get; set; } = DateTime.UtcNow;
    }

    /// <summary>
    /// Entity representing cached USDA API detailed nutrient results.
    /// </summary>
    public class CachedUsdaDetail
    {
        [Key]
        public string FdcId { get; set; } = "";

        [Required]
        public string JsonResponse { get; set; } = "";

        public DateTime Timestamp { get; set; } = DateTime.UtcNow;
    }

    /// <summary>
    /// Entity representing general user activity logs (searches, caching, database saves, etc.).
    /// </summary>
    public class UserActivityLog
    {
        [Key]
        public int Id { get; set; }

        public DateTime Timestamp { get; set; } = DateTime.UtcNow;

        [Required]
        public string ActionType { get; set; } = "";

        [Required]
        public string Details { get; set; } = "";
    }

    /// <summary>
    /// Local SQLite database context managed via Entity Framework Core.
    /// </summary>
    public class NutritionDbContext : DbContext
    {
        public DbSet<CachedUsdaSearch> CachedUsdaSearches { get; set; } = null!;
        public DbSet<CachedUsdaDetail> CachedUsdaDetails { get; set; } = null!;
        public DbSet<UserActivityLog> UserActivityLogs { get; set; } = null!;
        public DbSet<FoodItem> FoodItems { get; set; } = null!;
        public DbSet<LogEntry> LogEntries { get; set; } = null!;

        protected override void OnConfiguring(DbContextOptionsBuilder optionsBuilder)
        {
            if (!optionsBuilder.IsConfigured)
            {
                var appData = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NutriScribe");
                System.IO.Directory.CreateDirectory(appData);
                string dbPath = System.IO.Path.Combine(appData, "ef_nutrition_cache.sqlite");

                // Migration from legacy BaseDirectory to persistent LocalApplicationData
                try
                {
                    var legacySqlite = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "ef_nutrition_cache.sqlite");
                    if (System.IO.File.Exists(legacySqlite) && !System.IO.File.Exists(dbPath))
                    {
                        System.IO.File.Copy(legacySqlite, dbPath, true);
                    }
                }
                catch {}

                optionsBuilder.UseSqlite($"Data Source={dbPath}");
            }
        }

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            base.OnModelCreating(modelBuilder);

            // Configure FoodItem
            modelBuilder.Entity<FoodItem>(entity =>
            {
                entity.HasKey(e => e.FdcId);
                entity.HasIndex(e => e.Description);
            });

            // Configure LogEntry
            modelBuilder.Entity<LogEntry>(entity =>
            {
                entity.HasKey(e => e.Id);
                entity.HasIndex(e => e.Date);
                
                // Establish relationship with FoodItem (one-to-many)
                entity.HasOne(e => e.FoodItem)
                      .WithMany(f => f.LogEntries)
                      .HasForeignKey(e => e.FoodItemFdcId)
                      .OnDelete(DeleteBehavior.SetNull);
            });

            // Configure CachedUsdaSearch
            modelBuilder.Entity<CachedUsdaSearch>()
                .HasIndex(e => e.Query);

            // Configure CachedUsdaDetail
            modelBuilder.Entity<CachedUsdaDetail>()
                .HasKey(e => e.FdcId);
        }

        /// <summary>
        /// Logs user or system activity in the local database.
        /// </summary>
        public static void LogActivity(string actionType, string details)
        {
            try
            {
                using (var db = new NutritionDbContext())
                {
                    db.Database.EnsureCreated();
                    db.UserActivityLogs.Add(new UserActivityLog
                    {
                        ActionType = actionType,
                        Details = details,
                        Timestamp = DateTime.UtcNow
                    });
                    db.SaveChanges();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error logging activity: {ex.Message}");
            }
        }

        /// <summary>
        /// Attempts to load cached food search results. Returns null if expired or missing.
        /// </summary>
        public static string? GetCachedSearch(string query)
        {
            try
            {
                using (var db = new NutritionDbContext())
                {
                    db.Database.EnsureCreated();
                    var normalizedQuery = query.Trim().ToLowerInvariant();
                    var record = db.CachedUsdaSearches.FirstOrDefault(x => x.Query == normalizedQuery);

                    // Search cache valid for 7 days
                    if (record != null && (DateTime.UtcNow - record.Timestamp).TotalDays < 7)
                    {
                        return record.JsonResponse;
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error getting cached search: {ex.Message}");
            }
            return null;
        }

        /// <summary>
        /// Saves food search results to cache.
        /// </summary>
        public static void SaveCachedSearch(string query, string jsonResponse)
        {
            try
            {
                using (var db = new NutritionDbContext())
                {
                    db.Database.EnsureCreated();
                    var normalizedQuery = query.Trim().ToLowerInvariant();
                    var existing = db.CachedUsdaSearches.FirstOrDefault(x => x.Query == normalizedQuery);
                    if (existing != null)
                    {
                        existing.JsonResponse = jsonResponse;
                        existing.Timestamp = DateTime.UtcNow;
                    }
                    else
                    {
                        db.CachedUsdaSearches.Add(new CachedUsdaSearch
                        {
                            Query = normalizedQuery,
                            JsonResponse = jsonResponse,
                            Timestamp = DateTime.UtcNow
                        });
                    }
                    db.SaveChanges();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error saving cached search: {ex.Message}");
            }
        }

        /// <summary>
        /// Attempts to load cached detailed food profile. Returns null if missing.
        /// </summary>
        public static string? GetCachedDetail(string fdcId)
        {
            try
            {
                using (var db = new NutritionDbContext())
                {
                    db.Database.EnsureCreated();
                    var record = db.CachedUsdaDetails.FirstOrDefault(x => x.FdcId == fdcId);
                    
                    // Detailed profiles are extremely static, so no expiry is enforced.
                    if (record != null)
                    {
                        return record.JsonResponse;
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error getting cached detail: {ex.Message}");
            }
            return null;
        }

        /// <summary>
        /// Saves detailed food profile results to cache.
        /// </summary>
        public static void SaveCachedDetail(string fdcId, string jsonResponse)
        {
            try
            {
                using (var db = new NutritionDbContext())
                {
                    db.Database.EnsureCreated();
                    var existing = db.CachedUsdaDetails.FirstOrDefault(x => x.FdcId == fdcId);
                    if (existing != null)
                    {
                        existing.JsonResponse = jsonResponse;
                        existing.Timestamp = DateTime.UtcNow;
                    }
                    else
                    {
                        db.CachedUsdaDetails.Add(new CachedUsdaDetail
                        {
                            FdcId = fdcId,
                            JsonResponse = jsonResponse,
                            Timestamp = DateTime.UtcNow
                        });
                    }
                    db.SaveChanges();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error saving cached detail: {ex.Message}");
            }
        }
    }
}
