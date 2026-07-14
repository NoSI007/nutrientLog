using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Repositories
{
    /// <summary>
    /// EF Core implementation of the IFoodItemRepository.
    /// Provides asynchronous local SQLite storage capability for FoodItem cached responses.
    /// </summary>
    public class FoodItemRepository : IFoodItemRepository
    {
        private readonly Func<NutritionDbContext> _contextFactory;

        /// <summary>
        /// Instantiates the repository. Optionally takes a custom DbContext factory, 
        /// defaulting to creating new context instances (recommended for short-lived WPF context lifetimes).
        /// </summary>
        public FoodItemRepository(Func<NutritionDbContext>? contextFactory = null)
        {
            _contextFactory = contextFactory ?? (() => new NutritionDbContext());
        }

        public async Task<FoodItem?> GetByFdcIdAsync(string fdcId)
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.FoodItems
                    .Include(f => f.LogEntries)
                    .FirstOrDefaultAsync(f => f.FdcId == fdcId);
            }
        }

        public async Task<IEnumerable<FoodItem>> GetAllAsync()
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.FoodItems.ToListAsync();
            }
        }

        public async Task<IEnumerable<FoodItem>> SearchByNameAsync(string query)
        {
            if (string.IsNullOrWhiteSpace(query))
            {
                return Enumerable.Empty<FoodItem>();
            }

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                var normalizedQuery = query.Trim().ToLowerInvariant();
                return await context.FoodItems
                    .Where(f => f.Description.ToLower().Contains(normalizedQuery))
                    .ToListAsync();
            }
        }

        public async Task AddAsync(FoodItem foodItem)
        {
            if (foodItem == null) throw new ArgumentNullException(nameof(foodItem));

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                await context.FoodItems.AddAsync(foodItem);
                await context.SaveChangesAsync();
                
                NutritionDbContext.LogActivity("DB_FOOD_INSERT", $"Inserted food item '{foodItem.Description}' (FdcId: {foodItem.FdcId}) to database.");
            }
        }

        public async Task UpdateAsync(FoodItem foodItem)
        {
            if (foodItem == null) throw new ArgumentNullException(nameof(foodItem));

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                context.Entry(foodItem).State = EntityState.Modified;
                await context.SaveChangesAsync();

                NutritionDbContext.LogActivity("DB_FOOD_UPDATE", $"Updated food item '{foodItem.Description}' (FdcId: {foodItem.FdcId}) in database.");
            }
        }

        public async Task DeleteAsync(string fdcId)
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                var existing = await context.FoodItems.FindAsync(fdcId);
                if (existing != null)
                {
                    context.FoodItems.Remove(existing);
                    await context.SaveChangesAsync();

                    NutritionDbContext.LogActivity("DB_FOOD_DELETE", $"Deleted food item '{existing.Description}' (FdcId: {fdcId}) from database.");
                }
            }
        }

        public async Task<bool> ExistsAsync(string fdcId)
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.FoodItems.AnyAsync(f => f.FdcId == fdcId);
            }
        }
    }
}
