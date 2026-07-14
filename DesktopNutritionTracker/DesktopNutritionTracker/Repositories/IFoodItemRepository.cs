using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Repositories
{
    /// <summary>
    /// Interface for abstraction of data access logic for FoodItem entities.
    /// Supports asynchronous CRUD operations and custom searching.
    /// </summary>
    public interface IFoodItemRepository
    {
        /// <summary>
        /// Retrieves a FoodItem by its unique FdcId.
        /// </summary>
        Task<FoodItem?> GetByFdcIdAsync(string fdcId);

        /// <summary>
        /// Retrieves all FoodItems in the local database.
        /// </summary>
        Task<IEnumerable<FoodItem>> GetAllAsync();

        /// <summary>
        /// Searches for cached FoodItems by partial match on description.
        /// </summary>
        Task<IEnumerable<FoodItem>> SearchByNameAsync(string query);

        /// <summary>
        /// Saves or inserts a new FoodItem into the database.
        /// </summary>
        Task AddAsync(FoodItem foodItem);

        /// <summary>
        /// Updates an existing FoodItem's fields.
        /// </summary>
        Task UpdateAsync(FoodItem foodItem);

        /// <summary>
        /// Deletes a FoodItem from the database by FdcId.
        /// </summary>
        Task DeleteAsync(string fdcId);

        /// <summary>
        /// Checks if a food item exists in the local database.
        /// </summary>
        Task<bool> ExistsAsync(string fdcId);
    }
}
