using System;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Contract for handling offline-first local storage, caching user logs,
    /// and tracking synchronization state with cloud databases like Firestore.
    /// </summary>
    public interface ILocalStorageService
    {
        /// <summary>
        /// Gets or sets a value indicating whether the application is running in offline mode.
        /// </summary>
        bool IsOfflineMode { get; set; }

        /// <summary>
        /// Gets or sets a value indicating whether there are pending local changes that need syncing to Firestore.
        /// </summary>
        bool HasPendingSync { get; set; }

        /// <summary>
        /// Loads user nutrition and supplement logs from the local cache (SQLite with JSON fallback).
        /// </summary>
        StorageData LoadLocal();

        /// <summary>
        /// Saves user nutrition and supplement logs to the local cache (SQLite with JSON fallback).
        /// </summary>
        void SaveLocal(StorageData data);

        /// <summary>
        /// Logs an offline-specific system or user action.
        /// </summary>
        void LogOfflineActivity(string actionType, string details);

        /// <summary>
        /// Determines if the device currently has active network connectivity.
        /// </summary>
        bool CheckNetworkConnectivity();
    }
}
