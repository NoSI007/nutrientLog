using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using DesktopNutritionTracker.Models;
using DesktopNutritionTracker.ViewModels;

namespace DesktopNutritionTracker
{
    /// <summary>
    /// Interaction logic for CustomProfileWindow.xaml
    /// </summary>
    public partial class CustomProfileWindow : Window
    {
        private readonly NutritionViewModel _viewModel;
        private List<NutrientTargetRow> _targetRows = new List<NutrientTargetRow>();
        private bool _isUpdatingUi = false;

        public CustomProfileWindow(NutritionViewModel viewModel)
        {
            InitializeComponent();
            _viewModel = viewModel ?? throw new ArgumentNullException(nameof(viewModel));
            
            LoadProfilesList();
            SelectActiveOrCreateDefault();
        }

        private void LoadProfilesList()
        {
            _isUpdatingUi = true;
            try
            {
                var originalSelection = ProfileTemplateCombo.SelectedItem as string;

                ProfileTemplateCombo.Items.Clear();
                // Standard baseline option
                ProfileTemplateCombo.Items.Add("Standard Baseline");

                foreach (var profile in _viewModel.CustomDailyProfiles)
                {
                    ProfileTemplateCombo.Items.Add(profile.Name);
                }

                // Add create new template helper text option
                ProfileTemplateCombo.Items.Add("+ Create New Custom Profile");

                if (!string.IsNullOrEmpty(originalSelection) && ProfileTemplateCombo.Items.Contains(originalSelection))
                {
                    ProfileTemplateCombo.SelectedItem = originalSelection;
                }
                else
                {
                    string currentActive = _viewModel.ActiveProfileName ?? "Standard Baseline";
                    if (ProfileTemplateCombo.Items.Contains(currentActive))
                    {
                        ProfileTemplateCombo.SelectedItem = currentActive;
                    }
                    else
                    {
                        ProfileTemplateCombo.SelectedIndex = 0;
                    }
                }
            }
            finally
            {
                _isUpdatingUi = false;
            }
        }

        private void SelectActiveOrCreateDefault()
        {
            UpdateFormForSelectedProfile();
        }

        private void UpdateFormForSelectedProfile()
        {
            if (ProfileTemplateCombo.SelectedItem == null) return;
            
            string selectedName = ProfileTemplateCombo.SelectedItem.ToString() ?? "";

            // Reset status message
            StatusMessageText.Text = $"Loaded: {selectedName}";

            // Active indicator
            string activeName = _viewModel.ActiveProfileName ?? "Standard Baseline";
            if (selectedName == activeName)
            {
                ActiveProfileBanner.Visibility = Visibility.Visible;
                ApplyProfileBtn.Visibility = Visibility.Collapsed; // already active
            }
            else
            {
                ActiveProfileBanner.Visibility = Visibility.Collapsed;
                ApplyProfileBtn.Visibility = Visibility.Visible;
            }

            if (selectedName == "Standard Baseline")
            {
                ProfileNameInput.Text = "Standard Baseline";
                ProfileNameInput.IsEnabled = false;
                ProfileDescInput.Text = "Standard nutritional guidelines recommended by national dietary registries.";
                ProfileDescInput.IsEnabled = false;

                SaveProfileBtn.IsEnabled = false;
                DeleteProfileBtn.IsEnabled = false;

                // Load baseline targets with no overrides
                PopulateNutrientTargets(null);
            }
            else if (selectedName == "+ Create New Custom Profile")
            {
                ProfileNameInput.Text = "New Customized Profile";
                ProfileNameInput.IsEnabled = true;
                ProfileDescInput.Text = "A personalized dietary targets container mapped to custom clinical benchmarks.";
                ProfileDescInput.IsEnabled = true;

                SaveProfileBtn.IsEnabled = true;
                DeleteProfileBtn.IsEnabled = false;

                // Load with empty overrides
                PopulateNutrientTargets(null);
            }
            else
            {
                // Find custom profile
                var profile = _viewModel.CustomDailyProfiles.FirstOrDefault(p => p.Name == selectedName);
                if (profile != null)
                {
                    ProfileNameInput.Text = profile.Name;
                    ProfileNameInput.IsEnabled = true;
                    ProfileDescInput.Text = profile.Description;
                    ProfileDescInput.IsEnabled = true;

                    SaveProfileBtn.IsEnabled = true;
                    DeleteProfileBtn.IsEnabled = true;

                    PopulateNutrientTargets(profile.Targets);
                }
            }
        }

        private void PopulateNutrientTargets(Dictionary<string, double>? overrides)
        {
            _targetRows.Clear();
            foreach (var def in Nutrients.Definitions)
            {
                double targetVal = def.Rda;
                string customText = "";

                double ovr;
                if (overrides != null && overrides.TryGetValue(def.Key, out ovr))
                {
                    customText = ovr.ToString("F1");
                }

                _targetRows.Add(new NutrientTargetRow
                {
                    Key = def.Key,
                    Name = def.Name,
                    Group = def.Group.ToString(),
                    BaseTarget = def.Rda,
                    Unit = def.Unit,
                    CustomTargetText = customText
                });
            }

            NutrientTargetsItemsControl.ItemsSource = null;
            NutrientTargetsItemsControl.ItemsSource = _targetRows;
        }

        private void ProfileTemplateCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_isUpdatingUi) return;
            UpdateFormForSelectedProfile();
        }

        private void ApplyProfileBtn_Click(object sender, RoutedEventArgs e)
        {
            if (ProfileTemplateCombo.SelectedItem == null) return;
            string selectedName = ProfileTemplateCombo.SelectedItem.ToString() ?? "";

            if (selectedName == "+ Create New Custom Profile")
            {
                MessageBox.Show("Please save this custom profile first before activating it.", "Initialize Template", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            _viewModel.ApplyCustomProfile(selectedName);
            StatusMessageText.Text = $"✓ Activated and loaded: {selectedName}";
            
            // Re-update form details (active status banner, buttons visibility, etc)
            UpdateFormForSelectedProfile();
        }

        private void SaveProfileBtn_Click(object sender, RoutedEventArgs e)
        {
            string name = ProfileNameInput.Text.Trim();
            string desc = ProfileDescInput.Text.Trim();

            if (string.IsNullOrEmpty(name) || name == "Standard Baseline" || name == "+ Create New Custom Profile")
            {
                MessageBox.Show("Please enter a valid, unique profile name.", "Save Failure", MessageBoxButton.OK, MessageBoxImage.Error);
                return;
            }

            // Read edited dictionary targets from inputs control source
            var targetsInput = new Dictionary<string, double>();
            foreach (var row in _targetRows)
            {
                if (!string.IsNullOrEmpty(row.CustomTargetText))
                {
                    if (double.TryParse(row.CustomTargetText, out double val))
                    {
                        if (val > 0.0)
                        {
                            targetsInput[row.Key] = val;
                        }
                    }
                }
            }

            _viewModel.CreateCustomProfile(name, desc, targetsInput);
            StatusMessageText.Text = $"✓ Custom daily profile '{name}' saved successfully.";

            // Reload templates list in combo control with exact matching selection
            _isUpdatingUi = true;
            try
            {
                LoadProfilesList();
                ProfileTemplateCombo.SelectedItem = name;
            }
            finally
            {
                _isUpdatingUi = false;
            }

            UpdateFormForSelectedProfile();
        }

        private void DeleteProfileBtn_Click(object sender, RoutedEventArgs e)
        {
            if (ProfileTemplateCombo.SelectedItem == null) return;
            string selectedName = ProfileTemplateCombo.SelectedItem.ToString() ?? "";

            if (selectedName == "Standard Baseline" || selectedName == "+ Create New Custom Profile") return;

            var result = MessageBox.Show($"Are you sure you want to permanently delete custom daily profile '{selectedName}'?", "Confirm Deletion", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (result == MessageBoxResult.Yes)
            {
                _viewModel.DeleteCustomProfile(selectedName);
                StatusMessageText.Text = $"Removed Custom Daily Profile: {selectedName}";

                // Reload templates to defaults
                LoadProfilesList();
                ProfileTemplateCombo.SelectedIndex = 0;
                UpdateFormForSelectedProfile();
            }
        }

        private void CloseButton_Click(object sender, RoutedEventArgs e)
        {
            this.Close();
        }
    }

    public class NutrientTargetRow
    {
        public string Key { get; set; } = "";
        public string Name { get; set; } = "";
        public string Group { get; set; } = "";
        public double BaseTarget { get; set; }
        public string Unit { get; set; } = "";
        public string CustomTargetText { get; set; } = "";
    }
}
