# NutriScribe Desktop — Clinical Intake Diagnostics

Welcome to the desktop port of **NutriScribe (Clinical Intake Diagnostics)**. This application has been fully ported into a modern, native **C# .NET WPF** desktop client using clean XAML layouts, strict Model-View-ViewModel (MVVM) patterns, and offline-first JSON persistence.

---

## 🎨 Ported Layout & Visual Experience
The WPF application mirrors the visually striking **Cosmic Slate Dark Theme** of the Android app, rendering beautifully with generous padding, high-contrast layouts, custom vector indicators, and native WPF progressive visual bars:
1. **Clinical Daily Dashboard**: Interactive progress dial bars tracking your core macros (*Calories*, *Carbohydrates*, *Protein*, *Total Fat*, and *Water*), alongside a scrollable table displaying status indicators (Optimal 🟢, Sub-Optimal 🟡, and Attention 🔴) for all **41 required clinical nutrients**.
2. **Consumables Log Journal**: An elegant meal journal subdivided into *Breakfast*, *Lunch*, *Dinner*, and *Snacks* with inline delete functionality, and an undo cache. It sports a top quick-add search dock featuring the clinical **fallback parse algorithm** to estimate nutrition values instantly.
3. **Editable RDA Directory**: A searchable, tabular directory of all 41 nutrients that allows you to click on any item, read its physiological clinical description, and type in a customized target limits/overrides value (or restore defaults).
4. **Historical Day Trends**: Natively drawn multi-column progress graph charts tracking daily calorie consistency and macronutrient distributions over the last 7 calendar days.
5. **Daily Supplements Tracklist**: A checklist tracking configured health cofactors (Vitamin D3, Omega-3 Fish Oil, Magnesium, Methyl-B12) that feed clinical values directly into your total daily inputs when ticked.
6. **RDA Biological Profiler**: An on-demand selector where you input your **Age**, **Sex**, and **Daily Activity Multiplier** to calculate and commit customized recommended dietary guidelines automatically.

---

## ⚙️ How to Build & Install on Your Desktop

To install and run **NutriScribe Desktop** on your PC:

### Step 1: Export Project as ZIP
1. In the **Google AI Studio** workspace interface (top right or settings menu), click the **ZIP Export** action.
2. Download the compressed file to your Windows PC and extract it in your folder of choice.

### Step 2: Open in Visual Studio
1. Navigate into the extracted workspace, look for the `/DesktopNutritionTracker/` directory, and double-click the Microsoft Visual Studio Solution file: **`DesktopNutritionTracker.sln`**.
2. Visual Studio 2019 or newer (including the free Community Edition) will instantly open and parse the solution.

### Step 3: Compile & Run
The project uses the modern, lightweight **SDK-style** project format. To ensure absolute local compatibility (e.g. on Visual Studio 2019 or 2022 without .NET 9 SDK), the project features an **automatic conditional TargetFramework**:
- **`.NET Framework 4.8` (Automatic on Windows)**: Active on local Windows systems to guarantee immediate offline compilation in any standard Visual Studio installation without throwing invalid property errors or XAML reference namespace issues.
- **`.NET 9.0-windows` (Automatic on Non-Windows/Server)**: Runs on development container systems for high-performance builds.

To run:
1. Press **`F5`** or click the green **Start** / **Debug** button at the top of the Visual Studio toolbar.
2. Visual Studio will download the lightweight `Newtonsoft.Json` package via NuGet automatically and configure the executable.
3. The app will launch! A portable record database file named `nutriscribe_db.json` is automatically handled in the executable folder to persist your personal data permanently and offline.

---

## 💾 Project Directory File Structure
For your reference, the key WPF project source files added to this workspace are:
- `DesktopNutritionTracker.sln` — Master Solution entry file.
- `DesktopNutritionTracker/DesktopNutritionTracker.csproj` — Clean SDK-style project file containing targets and NuGet JSON references.
- `DesktopNutritionTracker/App.xaml` & `App.xaml.cs` — WPF startup resource handlers.
- `DesktopNutritionTracker/MainWindow.xaml` & `MainWindow.xaml.cs` — The gorgeous, fully interactive multi-page dashboard UI and code-behind bindings.
- `DesktopNutritionTracker/Models/`
  - `Nutrients.cs` — Declarations for all 41 clinical nutrient RDA targets, groups, and units.
  - `NutrientDefinition.cs` & `NutrientStatus.cs` — Structures evaluating percentage thresholds and status color tags.
  - `FoodLogEntry.cs` & `Supplement.cs` — Log entry declarations.
  - `DayTrend.cs` — Trend variables container.
- `DesktopNutritionTracker/ViewModels/`
  - `NutritionViewModel.cs` — Core engine performing MVVM property change updates, biological profiler calculations, date selections, entry inserts/deletions, undo caches, daily aggregations, and clinical fallback parsings.
- `DesktopNutritionTracker/Services/`
  - `StorageService.cs` — Thread-safe portable JSON persistence simulating SQLite/Room natively.
  - `DateConverter.cs` — Databinding converter bridging string dates to standard calendar items.
