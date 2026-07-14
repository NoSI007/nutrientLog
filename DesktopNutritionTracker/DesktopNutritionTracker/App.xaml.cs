using System.Windows;

namespace DesktopNutritionTracker
{
    /// <summary>
    /// Interaction logic for App.xaml
    /// </summary>
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            
            // Execute the automated deserialization and key mapping unit tests
            DesktopNutritionTracker.Services.ImportDataTests.RunTests();
        }
    }
}
