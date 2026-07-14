using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using DesktopNutritionTracker.Models;
using DesktopNutritionTracker.Services;
using DesktopNutritionTracker.ViewModels;

namespace DesktopNutritionTracker
{
    public partial class MainWindow : Window
    {
        private readonly NutritionViewModel _viewModel;
        private readonly LocalHttpEndpointService _apiEndpointService;
        private const string PlaceholderText = "Example: Steak, broccoli or eggs...";
        private bool _isRefreshing = false;

        public MainWindow()
        {
            InitializeComponent();
            _viewModel = new NutritionViewModel();
            this.DataContext = _viewModel;

            // Start local REST API server endpoint for frontend charting/external consumers
            _apiEndpointService = new LocalHttpEndpointService();
            _apiEndpointService.Start();

            // Gracefully stop the REST API listener when WPF window is closed
            this.Closed += (s, e) => _apiEndpointService.Stop();

            // Bind ViewModel messages update triggers
            _viewModel.PropertyChanged += ViewModel_PropertyChanged;

            // Initialize select list boxes & data views
            AddMealDropdown.ItemsSource = _viewModel.MealTypes;
            ProfileSexBox.ItemsSource = _viewModel.Sexes;
            ProfileActivityBox.ItemsSource = _viewModel.ActivityLevels;

            // Set initial state
            AddMealDropdown.SelectedIndex = 0;
            ProfileSexBox.SelectedItem = _viewModel.ProfileSex;
            ProfileActivityBox.SelectedItem = _viewModel.ProfileActivity;

            // Populate lists
            RefreshListViews();
        }

        private void ViewModel_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
        {
            if (e.PropertyName == nameof(NutritionViewModel.OperationMessage))
            {
                if (!string.IsNullOrEmpty(_viewModel.OperationMessage))
                {
                    ShowSnackbar(_viewModel.OperationMessage);
                    _viewModel.OperationMessage = null; // Clear to prevent double trigger
                }
            }
        }

        private void RefreshListViews()
        {
            _isRefreshing = true;
            try
            {
                NutrientsListView.ItemsSource = _viewModel.NutrientStatuses;
                FoodJournalListView.ItemsSource = _viewModel.CurrentDayFood;
                StapleListView.ItemsSource = null;
                StapleListView.ItemsSource = _viewModel.RecurringFoods;
                RdaListEditor.ItemsSource = Nutrients.Definitions;
                
                // Map the Supplements ListView with standard handlers
                SupplementsListView.ItemsSource = null;
                SupplementsListView.ItemsSource = _viewModel.Supplements;

                // Update Dashboard Dials numerical displays
                UpdateDashboardSumDisplays();
            }
            finally
            {
                _isRefreshing = false;
            }
        }

        private void UpdateDashboardSumDisplays()
        {
            // Extract core macros status to bind to ProgressBar inputs
            var caloriesStat = _viewModel.NutrientStatuses.FirstOrDefault(s => s.Definition.Key == "calories");
            if (caloriesStat != null)
            {
                DashCaloriesText.Text = $"{caloriesStat.Intake:F0} / {caloriesStat.Definition.Rda:F0} kcal";
                DashCaloriesProgress.Maximum = caloriesStat.Definition.Rda;
                DashCaloriesProgress.Value = caloriesStat.Intake;
            }

            var carbsStat = _viewModel.NutrientStatuses.FirstOrDefault(s => s.Definition.Key == "carbohydrates");
            if (carbsStat != null)
            {
                DashCarbsText.Text = $"{carbsStat.Intake:F0} / {carbsStat.Definition.Rda:F0} g";
                DashCarbsProgress.Maximum = carbsStat.Definition.Rda;
                DashCarbsProgress.Value = carbsStat.Intake;
            }

            var proteinStat = _viewModel.NutrientStatuses.FirstOrDefault(s => s.Definition.Key == "protein");
            if (proteinStat != null)
            {
                DashProteinText.Text = $"{proteinStat.Intake:F0} / {proteinStat.Definition.Rda:F0} g";
                DashProteinProgress.Maximum = proteinStat.Definition.Rda;
                DashProteinProgress.Value = proteinStat.Intake;
            }

            var fatStat = _viewModel.NutrientStatuses.FirstOrDefault(s => s.Definition.Key == "fat");
            if (fatStat != null)
            {
                DashFatText.Text = $"{fatStat.Intake:F0} / {fatStat.Definition.Rda:F0} g";
                DashFatProgress.Maximum = fatStat.Definition.Rda;
                DashFatProgress.Value = fatStat.Intake;
            }

            var waterStat = _viewModel.NutrientStatuses.FirstOrDefault(s => s.Definition.Key == "water");
            if (waterStat != null)
            {
                DashWaterText.Text = $"{waterStat.Intake:F0} / {waterStat.Definition.Rda:F0} ml";
                DashWaterProgress.Maximum = waterStat.Definition.Rda;
                DashWaterProgress.Value = waterStat.Intake;
            }

            // Update Macro Donut Chart Display
            if (DashMacroDonutChart != null)
            {
                DashMacroDonutChart.Carbs = carbsStat?.Intake ?? 0;
                DashMacroDonutChart.Protein = proteinStat?.Intake ?? 0;
                DashMacroDonutChart.Fat = fatStat?.Intake ?? 0;
                DashMacroDonutChart.DrawChart();
            }
        }

        // Left Navigation controller
        private void Sidebar_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (MainTabs == null) return;
            MainTabs.SelectedIndex = SidebarNavigation.SelectedIndex;
        }

        // Calendar Prev / Next click triggers
        private void PrevDay_Click(object sender, RoutedEventArgs e)
        {
            DateTime date;
            if (DateTime.TryParse(_viewModel.CurrentDateString, out date))
            {
                _viewModel.CurrentDateString = date.AddDays(-1).ToString("yyyy-MM-dd");
                RefreshListViews();
            }
        }

        private void NextDay_Click(object sender, RoutedEventArgs e)
        {
            DateTime date;
            if (DateTime.TryParse(_viewModel.CurrentDateString, out date))
            {
                _viewModel.CurrentDateString = date.AddDays(1).ToString("yyyy-MM-dd");
                RefreshListViews();
            }
        }

        private void Today_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.CurrentDateString = DateTime.Today.ToString("yyyy-MM-dd");
            RefreshListViews();
        }

        private void AddStapleToPlan_Click(object sender, RoutedEventArgs e)
        {
            var btn = sender as Button;
            if (btn != null && btn.DataContext is FoodLogEntry staple)
            {
                if (!_viewModel.SelectedMenuStaples.Contains(staple))
                {
                    _viewModel.SelectedMenuStaples.Add(staple);
                    _viewModel.AiPlannerStatusText = $"Added '{staple.FoodName}' as a usual staple food for today.";
                }
                else
                {
                    _viewModel.AiPlannerStatusText = $"'{staple.FoodName}' is already added to active menu staples.";
                }
            }
        }

        private void RemoveStapleFromPlan_Click(object sender, RoutedEventArgs e)
        {
            var btn = sender as Button;
            if (btn != null && btn.DataContext is FoodLogEntry staple)
            {
                if (_viewModel.SelectedMenuStaples.Contains(staple))
                {
                    _viewModel.SelectedMenuStaples.Remove(staple);
                    _viewModel.AiPlannerStatusText = $"Removed '{staple.FoodName}' from active menu staples.";
                }
            }
        }

        private async void GenerateAiMenu_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                await _viewModel.GenerateAiMenuPlanAsync();
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Planning failed: {ex.Message}");
            }
        }

        private void CommitPlannedMenu_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                _viewModel.CommitPlannedMenuToJournal();
                RefreshListViews();
                ShowSnackbar("Menu successfully saved to log journal!");
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Saving failed: {ex.Message}");
            }
        }

        // Shortcuts
        private void LogFoodShortcut_Click(object sender, RoutedEventArgs e)
        {
            SidebarNavigation.SelectedIndex = 1; // Swap tab to Log journal View
            AddFoodInput.Focus();
        }

        private void ExportReport_Click(object sender, RoutedEventArgs e)
        {
            var btn = sender as Button;
            if (btn != null && btn.ContextMenu != null)
            {
                btn.ContextMenu.PlacementTarget = btn;
                btn.ContextMenu.IsOpen = true;
            }
            else
            {
                ExportTodaySummaryCsv();
            }
        }

        private void ExportTodaySummaryCsv_Click(object sender, RoutedEventArgs e)
        {
            ExportTodaySummaryCsv();
        }

        private void ExportTodaySummaryCsv()
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_Summary_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".csv";
                sfd.Filter = "Comma-Separated Values (*.csv)|*.csv";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string reportContent = _viewModel.GenerateCsvReport();
                    System.IO.File.WriteAllText(sfd.FileName, reportContent, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Clinical summary report saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        private void ExportFullJournalCsv_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_FullFoodJournal_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".csv";
                sfd.Filter = "Comma-Separated Values (*.csv)|*.csv";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string reportContent = _viewModel.ExportAllLogsToCsv();
                    System.IO.File.WriteAllText(sfd.FileName, reportContent, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Full food log table saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        private void ExportTodaySummaryTxt_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_Report_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".txt";
                sfd.Filter = "Structured Plain Text (*.txt)|*.txt";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string reportContent = _viewModel.GenerateTextReport();
                    System.IO.File.WriteAllText(sfd.FileName, reportContent, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Text clinical report saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        private void ExportTodaySummaryHtml_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_Report_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".html";
                sfd.Filter = "HTML Document (*.html)|*.html";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string reportContent = _viewModel.GenerateHtmlReport();
                    System.IO.File.WriteAllText(sfd.FileName, reportContent, System.Text.Encoding.UTF8);
                    ShowSnackbar($"HTML clinical report saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                    
                    // Proactively prompt user if they want to view it in default browser
                    if (System.Windows.MessageBox.Show("Report exported successfully! Would you like to view this report in your default web browser?", "Open Report", System.Windows.MessageBoxButton.YesNo, System.Windows.MessageBoxImage.Question) == System.Windows.MessageBoxResult.Yes)
                    {
                        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(sfd.FileName) { UseShellExecute = true });
                    }
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        private void ExportWeeklySummaryHtml_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_Weekly_Report_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".html";
                sfd.Filter = "HTML Document (*.html)|*.html";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string reportContent = _viewModel.GenerateWeeklyHtmlReport();
                    System.IO.File.WriteAllText(sfd.FileName, reportContent, System.Text.Encoding.UTF8);
                    ShowSnackbar($"HTML clinical weekly report saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                    
                    if (System.Windows.MessageBox.Show("Weekly report exported successfully! Would you like to view this report in your default web browser?", "Open Weekly Report", System.Windows.MessageBoxButton.YesNo, System.Windows.MessageBoxImage.Question) == System.Windows.MessageBoxResult.Yes)
                    {
                        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(sfd.FileName) { UseShellExecute = true });
                    }
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Weekly export failed: {ex.Message}");
            }
        }

        private void DownloadTextReport_Click(object sender, RoutedEventArgs e)
        {
            ExportTodaySummaryTxt_Click(sender, e);
        }

        private void ImportJson_Click(object sender, RoutedEventArgs e)
        {
            var btn = sender as Button;
            if (btn != null)
            {
                if (btn.ContextMenu != null)
                {
                    btn.ContextMenu.PlacementTarget = btn;
                    btn.ContextMenu.IsOpen = true;
                }
                else
                {
                    ImportLocalJson();
                }
            }
        }

        private void ImportLocalJson_Click(object sender, RoutedEventArgs e)
        {
            ImportLocalJson();
        }

        private void ImportLocalJson()
        {
            try
            {
                var ofd = new Microsoft.Win32.OpenFileDialog();
                ofd.DefaultExt = ".json";
                ofd.Filter = "NutriScribe JSON backup / array file (*.json)|*.json";

                bool? result = ofd.ShowDialog();
                if (result == true)
                {
                    string rawJson = System.IO.File.ReadAllText(ofd.FileName, System.Text.Encoding.UTF8).Trim();
                    
                    if (string.IsNullOrEmpty(rawJson))
                    {
                        ShowSnackbar("The selected JSON file is empty.");
                        return;
                    }

                    string json = NormalizeJsonKeys(rawJson);

                    if (json.StartsWith("["))
                    {
                        // Validate array schema before deserialization
                        if (!DesktopNutritionTracker.Services.StorageService.ValidateJsonSchema(rawJson, out List<string> arrayErrors))
                        {
                            string errMsg = "JSON Array Schema Validation Failed:\n" + string.Join("\n", arrayErrors);
                            MessageBox.Show(errMsg, "Import Fault - Schema Mismatch", MessageBoxButton.OK, MessageBoxImage.Error);
                            ShowSnackbar("Import failed: JSON array schema mismatch.");
                            return;
                        }

                        // JSON Array - Could be Supplements/Nutrients or Food Log Entries!
                        bool parsedAsSupplements = false;
                        try
                        {
                            var listSupp = Newtonsoft.Json.JsonConvert.DeserializeObject<List<Supplement>>(json);
                            if (listSupp != null && listSupp.Count > 0 && listSupp.Any(s => !string.IsNullOrEmpty(s.Name)))
                            {
                                _viewModel.ImportSupplements(listSupp);
                                RefreshListViews();
                                ShowSnackbar($"Imported {listSupp.Count} Supplements/Nutrients successfully!");
                                parsedAsSupplements = true;
                            }
                        }
                        catch (Exception)
                        {
                            // Try food entries next
                        }

                        if (!parsedAsSupplements)
                        {
                            try
                            {
                                var listLogs = Newtonsoft.Json.JsonConvert.DeserializeObject<List<FoodLogEntry>>(json);
                                if (listLogs != null && listLogs.Count > 0 && listLogs.Any(f => !string.IsNullOrEmpty(f.FoodName)))
                                {
                                    _viewModel.ImportFoodLogs(listLogs);
                                    RefreshListViews();
                                    ShowSnackbar($"Imported {listLogs.Count} Food Journal entries successfully!");
                                }
                                else
                                {
                                    ShowSnackbar("Invalid JSON array shape. Must contain Supplement objects or FoodLogEntry objects.");
                                }
                            }
                            catch (Exception ex)
                            {
                                MessageBox.Show($"Failed to parse JSON array. Details: {ex.Message}", "Import Fault", MessageBoxButton.OK, MessageBoxImage.Error);
                                ShowSnackbar("Import failed: JSON array format was incorrect.");
                            }
                        }
                    }
                    else if (json.StartsWith("{"))
                    {
                        // JSON Object - Try Full Database Backup
                        try
                        {
                            _viewModel.ImportRawJsonData(rawJson);
                            RefreshListViews();
                            ShowSnackbar("Full Database Backup imported successfully!");
                        }
                        catch (Exception ex)
                        {
                            MessageBox.Show($"Failed to parse Database backup. Details: {ex.Message}", "Import Fault", MessageBoxButton.OK, MessageBoxImage.Error);
                            ShowSnackbar("Import failed: JSON schema mismatch.");
                        }
                    }
                    else
                    {
                        ShowSnackbar("Selected file does not appear to be a JSON structure (must start with [ or {).");
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"File load failure: {ex.Message}", "Import Fault", MessageBoxButton.OK, MessageBoxImage.Error);
                ShowSnackbar($"Import failed: {ex.Message}");
            }
        }

        private async void ExportFirestoreToJson_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                ShowSnackbar("Connecting to Firestore cloud to fetch current backup...");
                
                // Get the backup from Firestore
                var firestoreData = await _viewModel.GetFirestoreBackupDataAsync();
                
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_FirestoreBackup_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".json";
                sfd.Filter = "NutriScribe Database Backup (*.json)|*.json";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string json = Newtonsoft.Json.JsonConvert.SerializeObject(firestoreData, Newtonsoft.Json.Formatting.Indented);
                    System.IO.File.WriteAllText(sfd.FileName, json, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Firestore collection backup saved to {System.IO.Path.GetFileName(sfd.FileName)}!");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Firestore Export Fault: {ex.Message}", "Firestore Export Error", MessageBoxButton.OK, MessageBoxImage.Error);
                ShowSnackbar($"Firestore export failed: {ex.Message}");
            }
        }

        private async void RestoreFirestoreFromJson_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var ofd = new Microsoft.Win32.OpenFileDialog();
                ofd.DefaultExt = ".json";
                ofd.Filter = "NutriScribe Database Backup (*.json)|*.json";

                bool? result = ofd.ShowDialog();
                if (result == true)
                {
                    string rawJson = System.IO.File.ReadAllText(ofd.FileName, System.Text.Encoding.UTF8).Trim();
                    
                    if (string.IsNullOrEmpty(rawJson))
                    {
                        ShowSnackbar("The selected JSON file is empty.");
                        return;
                    }

                    string json = NormalizeJsonKeys(rawJson);

                    // Attempt to parse as StorageData
                    StorageData importedData;
                    try
                    {
                        importedData = Newtonsoft.Json.JsonConvert.DeserializeObject<StorageData>(json);
                        if (importedData == null || (importedData.Supplements?.Count == 0 && importedData.FoodEntries?.Count == 0))
                        {
                            throw new Exception("The parsed file contains no food entries or supplements.");
                        }
                    }
                    catch (Exception ex)
                    {
                        MessageBox.Show($"Selected file is not a valid database backup. Details: {ex.Message}", "Invalid Backup Schema", MessageBoxButton.OK, MessageBoxImage.Error);
                        ShowSnackbar("Restore cancelled: Invalid JSON schema.");
                        return;
                    }

                    // Show confirmation warning dialog
                    var confirmResult = MessageBox.Show(
                        "WARNING: This operation will completely OVERWRITE both your current cloud Firestore collection and your local database with the contents of this JSON backup file.\n\n" +
                        "This operation is irreversible.\n\n" +
                        "Are you sure you want to proceed?",
                        "Confirm Overwrite & Restore Collection",
                        MessageBoxButton.YesNo,
                        MessageBoxImage.Warning);

                    if (confirmResult == MessageBoxResult.Yes)
                    {
                        ShowSnackbar("Restoring & overwriting cloud Firestore collection...");
                        
                        // Perform overwrite & import
                        await _viewModel.OverwriteFirestoreCollectionAsync(importedData);
                        
                        RefreshListViews();
                        ShowSnackbar("Firestore collection & local database restored successfully!");
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Firestore Restore Fault: {ex.Message}", "Firestore Restore Error", MessageBoxButton.OK, MessageBoxImage.Error);
                ShowSnackbar($"Firestore restore failed: {ex.Message}");
            }
        }

        private void ExportJson_Click(object sender, RoutedEventArgs e)
        {
            var btn = sender as Button;
            if (btn != null)
            {
                if (btn.ContextMenu != null)
                {
                    btn.ContextMenu.PlacementTarget = btn;
                    btn.ContextMenu.IsOpen = true;
                }
                else
                {
                    ExportFullDb();
                }
            }
        }

        private void ExportFullDb_Click(object sender, RoutedEventArgs e)
        {
            ExportFullDb();
        }

        private void ExportFullDb()
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_FullBackup_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".json";
                sfd.Filter = "NutriScribe Database Backup (*.json)|*.json";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string json = Newtonsoft.Json.JsonConvert.SerializeObject(_viewModel.Database, Newtonsoft.Json.Formatting.Indented);
                    System.IO.File.WriteAllText(sfd.FileName, json, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Full backup saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        private void ExportSupplements_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_Supplements_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".json";
                sfd.Filter = "Supplements JSON List (*.json)|*.json";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string json = Newtonsoft.Json.JsonConvert.SerializeObject(_viewModel.Database.Supplements, Newtonsoft.Json.Formatting.Indented);
                    System.IO.File.WriteAllText(sfd.FileName, json, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Supplements list saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        private void ExportFoodLogs_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var sfd = new Microsoft.Win32.SaveFileDialog();
                sfd.FileName = $"NutriScribe_FoodLogs_{_viewModel.CurrentDateString}";
                sfd.DefaultExt = ".json";
                sfd.Filter = "Food Logs JSON List (*.json)|*.json";

                bool? result = sfd.ShowDialog();
                if (result == true)
                {
                    string json = Newtonsoft.Json.JsonConvert.SerializeObject(_viewModel.Database.FoodEntries, Newtonsoft.Json.Formatting.Indented);
                    System.IO.File.WriteAllText(sfd.FileName, json, System.Text.Encoding.UTF8);
                    ShowSnackbar($"Food logs saved to {System.IO.Path.GetFileName(sfd.FileName)}");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Export failed: {ex.Message}");
            }
        }

        // Add Food logs
        private async void CommitFood_Click(object sender, RoutedEventArgs e)
        {
            string foodName = AddFoodInput.Text.Trim();
            if (string.IsNullOrEmpty(foodName) || foodName == PlaceholderText)
            {
                ShowSnackbar("Please type a valid description first.");
                return;
            }

            string mealType = AddMealDropdown.SelectedItem as string ?? "Breakfast";
            double quantity = 1.0;
            double.TryParse(AddServingCount.Text, NumberStyles.Any, CultureInfo.InvariantCulture, out quantity);

            if (quantity <= 0.0) quantity = 1.0;

            // Save visual states and disable controls during AI analysis
            bool wasInputEnabled = AddFoodInput.IsEnabled;
            AddFoodInput.IsEnabled = false;
            AddMealDropdown.IsEnabled = false;
            AddServingCount.IsEnabled = false;

            try
            {
                await _viewModel.AddFoodLogAsync(foodName, mealType, quantity, _viewModel.CurrentServingUnit);
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Error: {ex.Message}");
            }
            finally
            {
                AddFoodInput.IsEnabled = wasInputEnabled;
                AddMealDropdown.IsEnabled = true;
                AddServingCount.IsEnabled = true;
            }

            // Reset inputs of TextBox
            AddFoodInput.Text = PlaceholderText;
            AddFoodInput.Foreground = System.Windows.Media.Brushes.Gray;
            AddServingCount.Text = "1.0";

            RefreshListViews();
        }

        private void DeleteFood_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is FoodLogEntry entry)
            {
                _viewModel.DeleteFoodLog(entry);
                RefreshListViews();
            }
        }

        private void ToggleWarningBanner_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.IsWarningBannerExpanded = !_viewModel.IsWarningBannerExpanded;
        }

        private void PinToStaples_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is FoodLogEntry entry)
            {
                _viewModel.PinFoodLogAsStaple(entry);
                ShowSnackbar(_viewModel.OperationMessage ?? "Saved to Favorites!");
                RefreshListViews();
            }
        }

        private void EditFoodLog_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is FoodLogEntry entry)
            {
                // Create copy to edit, allowing revert if cancel
                var copy = new FoodLogEntry
                {
                    Id = entry.Id,
                    Date = entry.Date,
                    FoodName = entry.FoodName,
                    MealType = entry.MealType,
                    Quantity = entry.Quantity,
                    Unit = entry.Unit,
                    Nutrients = new Dictionary<string, double>(entry.Nutrients ?? new Dictionary<string, double>())
                };

                var diag = new FoodEditWindow(copy, _viewModel.MealTypes) { Owner = this };
                if (diag.ShowDialog() == true)
                {
                    _viewModel.UpdateFoodLog(copy);
                    ShowSnackbar(_viewModel.OperationMessage ?? "Food log details updated.");
                    RefreshListViews();
                }
            }
        }

        private void AddStapleToToday_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is FoodLogEntry staple)
            {
                _viewModel.AddRecurringFoodToToday(staple);
                ShowSnackbar(_viewModel.OperationMessage ?? "Favorite added to today's log!");
                RefreshListViews();
            }
        }

        private void EditMasterStaple_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is FoodLogEntry staple)
            {
                var copy = new FoodLogEntry
                {
                    Id = staple.Id,
                    Date = staple.Date,
                    FoodName = staple.FoodName,
                    MealType = staple.MealType,
                    Quantity = staple.Quantity,
                    Unit = staple.Unit,
                    Nutrients = new Dictionary<string, double>(staple.Nutrients ?? new Dictionary<string, double>())
                };

                var diag = new FoodEditWindow(copy, _viewModel.MealTypes) { Owner = this };
                if (diag.ShowDialog() == true)
                {
                    _viewModel.SaveStapleTemplate(copy);
                    ShowSnackbar(_viewModel.OperationMessage ?? "Favorite details updated.");
                    RefreshListViews();
                }
            }
        }

        private void DeleteStaple_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is FoodLogEntry staple)
            {
                var result = MessageBox.Show($"Are you sure you want to remove '{staple.FoodName}' from your Favorites & Presets permanently?", "Confirm Deletion", MessageBoxButton.YesNo, MessageBoxImage.Question);
                if (result == System.Windows.MessageBoxResult.Yes)
                {
                    _viewModel.DeleteStaple(staple);
                    ShowSnackbar(_viewModel.OperationMessage ?? "Favorite removed.");
                    RefreshListViews();
                }
            }
        }

        private void CreateCustomStaple_Click(object sender, RoutedEventArgs e)
        {
            var newStaple = new FoodLogEntry
            {
                Id = 0,
                Date = "",
                FoodName = "New Favorite Preset",
                MealType = "Breakfast",
                Quantity = 1.0,
                Unit = "serving",
                Nutrients = new Dictionary<string, double>()
            };

            var diag = new FoodEditWindow(newStaple, _viewModel.MealTypes) { Owner = this };
            if (diag.ShowDialog() == true)
            {
                _viewModel.AddCustomStaple(newStaple.FoodName, newStaple.MealType, newStaple.Quantity, newStaple.Unit, newStaple.Nutrients);
                ShowSnackbar(_viewModel.OperationMessage ?? "Favorite preset created!");
                RefreshListViews();
            }
        }

        private async void FetchAiNutrition_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(_viewModel.AIQueryFoodName))
            {
                ShowSnackbar("Please type a food description first.");
                return;
            }

            double qty;
            if (!double.TryParse(_viewModel.AIQueryServingSizeText, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out qty) || qty <= 0)
            {
                ShowSnackbar("Please enter a valid positive multiplier (e.g. 1.0, 1.5).");
                return;
            }

            try
            {
                await _viewModel.FetchNutritionPreviewWithAiAsync();
                ShowSnackbar(_viewModel.AIFetchStatusText);
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Fetch failed: {ex.Message}");
            }
        }

        private void CopyEngineStatus_Click(object sender, RoutedEventArgs e)
        {
            if (!string.IsNullOrEmpty(_viewModel.AIFetchStatusText))
            {
                try
                {
                    Clipboard.SetText(_viewModel.AIFetchStatusText);
                    ShowSnackbar("Engine status copied to clipboard!");
                }
                catch (Exception ex)
                {
                    ShowSnackbar($"Clipboard copy failed: {ex.Message}");
                }
            }
            else
            {
                ShowSnackbar("No engine status text to copy.");
            }
        }

        private void CommitFetchedLog_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                bool success = _viewModel.CommitAiFetchedResultToToday();
                if (success)
                {
                    ShowSnackbar(_viewModel.OperationMessage ?? "Logged successfully!");
                    RefreshListViews();
                }
                else
                {
                    ShowSnackbar("Failed to commit log. Preview result is null.");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Failed to log: {ex.Message}");
            }
        }

        private void ClearFetchedLog_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.AIFetchedPreviewResult = null;
            _viewModel.AIQueryFoodName = "";
            _viewModel.AIFetchStatusText = "Cleared. Ready for next query!";
            ShowSnackbar("Preview cleared.");
        }

        private async void UsdaSearch_Click(object sender, RoutedEventArgs e)
        {
            await _viewModel.SearchUsdaFoodsAsync();
        }

        private async void UsdaSearchInput_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (e.Key == System.Windows.Input.Key.Enter)
            {
                await _viewModel.SearchUsdaFoodsAsync();
            }
        }

        private async void UsdaResultsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (sender is ListView listView && listView.SelectedItem is UsdaNutrientProfile selectedItem)
            {
                await _viewModel.LoadSelectedUsdaDetailAsync(selectedItem);
            }
        }

        private void CommitUsdaLog_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                bool success = _viewModel.CommitUsdaResultToToday();
                if (success)
                {
                    ShowSnackbar(_viewModel.OperationMessage ?? "USDA Food logged successfully!");
                    RefreshListViews();
                }
                else
                {
                    ShowSnackbar("Failed to log food. Selected food is null.");
                }
            }
            catch (Exception ex)
            {
                ShowSnackbar($"Failed to log: {ex.Message}");
            }
        }

        private void ClearUsdaLog_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.SelectedUsdaResult = null;
            _viewModel.UsdaSearchQuery = "";
            _viewModel.UsdaSearchResults.Clear();
            _viewModel.UsdaSearchStatusText = "Cleared. Ready for next query!";
            ShowSnackbar("USDA selection cleared.");
        }

        // Text Placeholders helpers
        private void TextBoxPlaceholder_GotFocus(object sender, RoutedEventArgs e)
        {
            if (AddFoodInput.Text == PlaceholderText)
            {
                AddFoodInput.Text = "";
                AddFoodInput.Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextPrimary");
            }
        }

        private void TextBoxPlaceholder_LostFocus(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(AddFoodInput.Text))
            {
                AddFoodInput.Text = PlaceholderText;
                AddFoodInput.Foreground = System.Windows.Media.Brushes.DimGray;
            }
        }

        // Supplement Logging Handlers
        private void SupplementTaken_Checked(object sender, RoutedEventArgs e)
        {
            if (_isRefreshing) return;
            if (sender is CheckBox cb)
            {
                if (cb.Tag is Supplement sup)
                {
                    int index = _viewModel.Supplements.IndexOf(sup);
                    System.Diagnostics.Debug.WriteLine($"[SupplementSelection] CHECKED: Supplement '{sup.Name}' (ID: {sup.Id}) at list index {index}. Total list count: {_viewModel.Supplements.Count}. Key nutrients logged: {string.Join(", ", sup.Nutrients.Keys)}");
                    _viewModel.ToggleSupplementTaken(sup, true);
                    RefreshListViews();
                }
                else
                {
                    System.Diagnostics.Debug.WriteLine($"[SupplementSelection] WARNING: Checked sender CheckBox has a null or invalid Tag: {cb.Tag?.GetType()?.FullName ?? "null"}");
                }
            }
            else
            {
                System.Diagnostics.Debug.WriteLine($"[SupplementSelection] WARNING: Checked sender is not a CheckBox: {sender?.GetType()?.FullName ?? "null"}");
            }
        }

        private void SupplementTaken_Unchecked(object sender, RoutedEventArgs e)
        {
            if (_isRefreshing) return;
            if (sender is CheckBox cb)
            {
                if (cb.Tag is Supplement sup)
                {
                    int index = _viewModel.Supplements.IndexOf(sup);
                    System.Diagnostics.Debug.WriteLine($"[SupplementSelection] UNCHECKED: Supplement '{sup.Name}' (ID: {sup.Id}) at list index {index}. Total list count: {_viewModel.Supplements.Count}");
                    _viewModel.ToggleSupplementTaken(sup, false);
                    RefreshListViews();
                }
                else
                {
                    System.Diagnostics.Debug.WriteLine($"[SupplementSelection] WARNING: Unchecked sender CheckBox has a null or invalid Tag: {cb.Tag?.GetType()?.FullName ?? "null"}");
                }
            }
            else
            {
                System.Diagnostics.Debug.WriteLine($"[SupplementSelection] WARNING: Unchecked sender is not a CheckBox: {sender?.GetType()?.FullName ?? "null"}");
            }
        }

        // RDA directory selections changed helper
        private void RdaListEditor_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (RdaListEditor.SelectedItem is NutrientDefinition def)
            {
                EditPanelContainer.Visibility = Visibility.Visible;
                EditNutrientName.Text = def.Name;
                EditNutrientGroup.Text = def.Group.ToString();
                EditNutrientDesc.Text = def.Description;
                EditRdaUnit.Text = def.Unit;

                // Load custom target if overrides exists in viewmodel database
                double activeRda = def.Rda;
                // Since _viewModel caches custom overrides locally
                var status = _viewModel.NutrientStatuses.FirstOrDefault(s => s.Definition.Key == def.Key);
                if (status != null)
                {
                    activeRda = status.Definition.Rda;
                }
                EditRdaInput.Text = activeRda.ToString(CultureInfo.InvariantCulture);
            }
            else
            {
                EditPanelContainer.Visibility = Visibility.Collapsed;
            }
        }

        // Save Custom target details override
        private void SaveRdaOverride_Click(object sender, RoutedEventArgs e)
        {
            if (RdaListEditor.SelectedItem is NutrientDefinition def)
            {
                double val;
                if (double.TryParse(EditRdaInput.Text, NumberStyles.Any, CultureInfo.InvariantCulture, out val))
                {
                    _viewModel.UpdateCustomRda(def.Key, val);
                    RefreshListViews();
                }
                else
                {
                    ShowSnackbar("Please type a valid numeric number.");
                }
            }
        }

        private void ResetRdaOverride_Click(object sender, RoutedEventArgs e)
        {
            if (RdaListEditor.SelectedItem is NutrientDefinition def)
            {
                _viewModel.UpdateCustomRda(def.Key, 0.0); // 0.0 signals removal of override
                RefreshListViews();

                // Refresh input box content
                EditRdaInput.Text = def.Rda.ToString(CultureInfo.InvariantCulture);
            }
        }

        // MODAL PROFILER CONTROLS
        private void OpenCustomProfileSettings_Click(object sender, RoutedEventArgs e)
        {
            var win = new CustomProfileWindow(_viewModel);
            win.Owner = this;
            win.ShowDialog();
            RefreshListViews();
        }

        private void OpenProfiler_Click(object sender, RoutedEventArgs e)
        {
            ProfilerModal.Visibility = Visibility.Visible;
            ProfileSexBox.SelectedItem = _viewModel.ProfileSex;
            ProfileAgeBox.Text = _viewModel.ProfileAge.ToString();
            ProfileActivityBox.SelectedItem = _viewModel.ProfileActivity;
        }

        private void CloseProfiler_Click(object sender, RoutedEventArgs e)
        {
            ProfilerModal.Visibility = Visibility.Collapsed;
        }

        private void CommitProfiler_Click(object sender, RoutedEventArgs e)
        {
            string sex = ProfileSexBox.SelectedItem as string ?? "Female";
            string activity = ProfileActivityBox.SelectedItem as string ?? "Lightly Active";
            
            if (int.TryParse(ProfileAgeBox.Text, out int age) && age > 0)
            {
                _viewModel.ApplyRdaProfile(age, activity, sex);
                ProfilerModal.Visibility = Visibility.Collapsed;
                RefreshListViews();
            }
            else
            {
                MessageBox.Show("Please enter a valid age above 0.", "Validation Error", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void ResetProfiler_Click(object sender, RoutedEventArgs e)
        {
            var result = MessageBox.Show("Restore all 41 goals to baseline national medical standard defaults?", "Reset Standards", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (result == MessageBoxResult.Yes)
            {
                _viewModel.ResetRdaToDefaults();
                ProfilerModal.Visibility = Visibility.Collapsed;
                RefreshListViews();
            }
        }

        // SNACKBAR HELPER ACTIONS
        private void ShowSnackbar(string message)
        {
            SnackbarText.Text = message;
            NotificationSnackbar.Visibility = Visibility.Visible;

            // Simple auto-dismiss after 4 seconds (without threading blocks)
            var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            timer.Tick += (s, ev) =>
            {
                NotificationSnackbar.Visibility = Visibility.Collapsed;
                timer.Stop();
            };
            timer.Start();
        }

        private void SnackbarDismiss_Click(object sender, RoutedEventArgs e)
        {
            NotificationSnackbar.Visibility = Visibility.Collapsed;
        }

        private void ScanDailyAlerts_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.CheckNutrientAlerts(autoShow: true);
        }

        private void DismissAlerts_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.IsAlertModalVisible = false;
        }

        private static string NormalizeJsonKeys(string json)
        {
            return DesktopNutritionTracker.Services.StorageService.NormalizeJsonKeys(json);
        }
    }
}
