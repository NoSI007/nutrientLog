using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Repositories
{
    /// <summary>
    /// Interface for abstraction of data access logic for LogEntry entities.
    /// Manages client-side local consumption entries and food logging history.
    /// </summary>
    public interface ILogEntryRepository
    {
        /// <summary>
        /// Retrieves a LogEntry by its primary integer ID.
        /// </summary>
        Task<LogEntry?> GetByIdAsync(int id);

        /// <summary>
        /// Retrieves all logged food consumptions for a specific day ("YYYY-MM-DD").
        /// </summary>
        Task<IEnumerable<LogEntry>> GetByDateAsync(string date);

        /// <summary>
        /// Retrieves all logged food consumptions across all dates.
        /// </summary>
        Task<IEnumerable<LogEntry>> GetAllAsync();

        /// <summary>
        /// Retrieves logged food consumptions between start and end dates (inclusive).
        /// </summary>
        Task<IEnumerable<LogEntry>> GetByDateRangeAsync(string startDate, string endDate);

        /// <summary>
        /// Logs a new food entry in the database.
        /// </summary>
        Task AddAsync(LogEntry logEntry);

        /// <summary>
        /// Updates an existing log entry (e.g. quantity, meal type, food name).
        /// </summary>
        Task UpdateAsync(LogEntry logEntry);

        /// <summary>
        /// Deletes a logged consumption entry.
        /// </summary>
        Task DeleteAsync(int id);
    }
}
