using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Repositories
{
    /// <summary>
    /// EF Core implementation of the ILogEntryRepository.
    /// Abstracts SQLite database read/write actions for LogEntry entities.
    /// </summary>
    public class LogEntryRepository : ILogEntryRepository
    {
        private readonly Func<NutritionDbContext> _contextFactory;

        /// <summary>
        /// Instantiates the repository. Optionally takes a custom DbContext factory.
        /// </summary>
        public LogEntryRepository(Func<NutritionDbContext>? contextFactory = null)
        {
            _contextFactory = contextFactory ?? (() => new NutritionDbContext());
        }

        public async Task<LogEntry?> GetByIdAsync(int id)
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.LogEntries
                    .Include(l => l.FoodItem)
                    .FirstOrDefaultAsync(l => l.Id == id);
            }
        }

        public async Task<IEnumerable<LogEntry>> GetByDateAsync(string date)
        {
            if (string.IsNullOrWhiteSpace(date))
            {
                return Enumerable.Empty<LogEntry>();
            }

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.LogEntries
                    .Include(l => l.FoodItem)
                    .Where(l => l.Date == date)
                    .OrderBy(l => l.CreatedAt)
                    .ToListAsync();
            }
        }

        public async Task<IEnumerable<LogEntry>> GetAllAsync()
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.LogEntries
                    .Include(l => l.FoodItem)
                    .OrderByDescending(l => l.Date)
                    .ToListAsync();
            }
        }

        public async Task<IEnumerable<LogEntry>> GetByDateRangeAsync(string startDate, string endDate)
        {
            if (string.IsNullOrWhiteSpace(startDate) || string.IsNullOrWhiteSpace(endDate))
            {
                return Enumerable.Empty<LogEntry>();
            }

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                return await context.LogEntries
                    .Include(l => l.FoodItem)
                    .Where(l => string.Compare(l.Date, startDate) >= 0 && string.Compare(l.Date, endDate) <= 0)
                    .OrderBy(l => l.Date)
                    .ToListAsync();
            }
        }

        public async Task AddAsync(LogEntry logEntry)
        {
            if (logEntry == null) throw new ArgumentNullException(nameof(logEntry));

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                await context.LogEntries.AddAsync(logEntry);
                await context.SaveChangesAsync();

                NutritionDbContext.LogActivity("DB_LOG_INSERT", $"Logged food entry '{logEntry.FoodName}' ({logEntry.Quantity} {logEntry.Unit}) for Date '{logEntry.Date}'.");
            }
        }

        public async Task UpdateAsync(LogEntry logEntry)
        {
            if (logEntry == null) throw new ArgumentNullException(nameof(logEntry));

            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                context.Entry(logEntry).State = EntityState.Modified;
                await context.SaveChangesAsync();

                NutritionDbContext.LogActivity("DB_LOG_UPDATE", $"Updated log entry '{logEntry.FoodName}' (ID: {logEntry.Id}) to quantity {logEntry.Quantity}.");
            }
        }

        public async Task DeleteAsync(int id)
        {
            using (var context = _contextFactory())
            {
                await context.Database.EnsureCreatedAsync();
                var existing = await context.LogEntries.FindAsync(id);
                if (existing != null)
                {
                    context.LogEntries.Remove(existing);
                    await context.SaveChangesAsync();

                    NutritionDbContext.LogActivity("DB_LOG_DELETE", $"Deleted food log entry '{existing.FoodName}' (ID: {id}) from database.");
                }
            }
        }
    }
}
