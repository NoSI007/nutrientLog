# NUTRISCRIBE TRACKER — SYSTEM DESIGN & FUNCTIONAL AUDIT SPECIFICATION
**Product Specification & Complete Feature Enumeration Document**  
*Document Status: Complete & Verified Archive*  
*Date of Audit: May 27, 2026*  
*Target Platforms: Android (Kotlin & Jetpack Compose), WPF Desktop (Design Phase)*

---

## EXECUTIVE SUMMARY

**NutriScribe Tracker** is a high-precision, clinically-focused nutritional journal and diagnostic assistant designed to monitor and optimize intake across **41 Core Essential Nutrients** (including advanced lipids, vitamins, minerals, and macronutrients). 

Unlike standard caloric counters that limit analyses to basic macros (Fat, Protein, Carbs), NutriScribe employs an intelligent parsing engine that decomposes food inputs into high-fidelity biochemical structures, evaluates daily intakes against Recommended Daily Allowances (RDA) and Upper Limits (UL), and implements dynamic clinical coaching, local data porting, and therapeutic trajectory monitoring.

This document serves as the formal specification and directory of the application’s complete functional scope, organized logically by core system categories and sub-categories.

---

## 1. DATA ENVIRONMENT & BIOCHEMICAL MODELS

### 1.1 Core Database Entity (FoodLogEntry)
Defines the local schema governing the persistent storage of tracked foods.
*   **Meal Category Anchors:** Every entry is strictly categorized under a dynamic meal session identifier: `Breakfast`, `Lunch`, `Dinner`, or `Snack`.
*   **Volumetric Scales:** Supports customizable serving multipliers (`quantity`) and descriptive logging units (`unit`, e.g., "serving", "grams", "oz", "slice").
*   **Temporal Anchors:** Stored with local date keys formatted as `YYYY-MM-DD` for rapid historical retrieval and chronologically aligned aggregation.
*   **Nutrient Association Map:** Contains a rich map of `String` nutrient keys to calculated `Double` mass values, tracking up to 41 distinct variables per food item.

### 1.2 The 41-Nutrient Directory Matrix
NutriScribe tracks and models 41 distinct essential nutrients. Each is bound to standard targets, limits, unit representations, and human physiological descriptions:

#### Category A: Macronutrients (6 Core Elements)
1.  **Calories (kcal):** Serves as the thermal metabolic energy ceiling (Default Daily RDA: 2,000.0 kcal).
2.  **Carbohydrates (g):** Primary fast-acting metabolic source for cellular activity (Default Daily RDA: 275.0 g).
3.  **Protein (g):** Nitrogenous amino acid building blocks for protein synthesis and hormone balance (Default Daily RDA: 56.0 g).
4.  **Total Fat (g):** Hydrophobic lipids for cellular integrity and lipid-soluble vitamin processing (Default Daily RDA: 70.0 g).
5.  **Dietary Fiber (g):** Indigestible carbohydrates vital for gut health, glycemic stabilization, and bowel motility (Default Daily RDA: 28.0 g).
6.  **Water/Hydration (ml):** Essential solvent, cellular medium, heat regulator, and joint lubricant (Default Daily RDA: 2,500.0 ml).

#### Category B: Lipids & Fat Breakdown (7 Elements)
7.  **Saturated Fat (g):** Saturated fatty acids; targeted as a maximum safe ceiling (Default UL: 20.0 g).
8.  **Trans Fat (g):** Non-natural hydrogenated lipids; strictly treated as a critical risk factor (Default UL: 2.0 g).
9.  **Monounsaturated Fat (g):** Oleic acid groups linked with positive vascular outcomes (Default Daily RDA: 25.0 g).
10. **Polyunsaturated Fat (g):** Essential multi-double bond lipids (Default Daily RDA: 17.0 g).
11. **Omega-3 Fatty Acids (g):** Anti-inflammatory lipids supporting neural myelin health (Default Daily RDA: 1.6 g).
12. **Omega-6 Fatty Acids (g):** Pro-inflammatory structural cellular pathways (Default Daily RDA: 17.0 g).
13. **Cholesterol (mg):** Dermal precursor and endocrine base; targeted as a cardiovascular ceiling (Default UL: 300.0 mg).

#### Category C: Essential Vitamins (14 Vitamins)
14. **Vitamin A (mcg):** Retinoids governing dark adaptation, optical rod pigments, and immune defense (Default Daily RDA: 900.0 mcg).
15. **Vitamin C (mg):** Ascorbic acid antioxidant vital for collagen assembly and tissue healing (Default Daily RDA: 90.0 mg).
16. **Vitamin D (mcg):** Secosteroid regulator of bone calcification and calcium uptake pathways (Default Daily RDA: 15.0 mcg).
17. **Vitamin E (mg):** D-alpha-tocopherol lipid-soluble radical scavenger protecting membranes (Default Daily RDA: 15.0 mg).
18. **Vitamin K (mcg):** Phylloquinone co-factor governing coagulation and safe osteoclast binding (Default Daily RDA: 120.0 mcg).
19. **Thiamin / Vitamin B1 (mg):** Essential enzymatic component in pyruvate-to-acetyl-CoA conversion (Default Daily RDA: 1.2 mg).
20. **Riboflavin / Vitamin B2 (mg):** Core riboflavin cofactor for flavoproteins (FAD/FMN) in respiratory chains (Default Daily RDA: 1.3 mg).
21. **Niacin / Vitamin B3 (mg):** Precursor for cellular reduction/oxidation processes via NAD+/NADH (Default Daily RDA: 16.0 mg).
22. **Pantothenic Acid / Vitamin B5 (mg):** Essential base structure of Coenzyme A (CoA) governing lipid cycles (Default Daily RDA: 5.0 mg).
23. **Vitamin B6 (mg):** Pyridoxal-5-phosphate governing transamination and neurotransmission (Default Daily RDA: 1.3 mg).
24. **Biotin / Vitamin B7 (mcg):** Carbon dioxide carrier participating in gluconeogenesis and fat synthesis (Default Daily RDA: 30.0 mcg).
25. **Folate / Vitamin B9 (mcg):** Crucial cofactor for nucleic acid synthesis and cell division (Default Daily RDA: 400.0 mcg).
26. **Vitamin B12 (mcg):** Cobalamin molecule enabling nerve myelin synthesis and hematopoiesis (Default Daily RDA: 2.4 mcg).
27. **Choline (mg):** Phospholipid signaling precursor for acetylcholine and cellular membranes (Default Daily RDA: 550.0 mg).

#### Category D: Ionic Minerals & Electrolytes (12 Elements)
28. **Calcium (mg):** Hydroxyapatite bone matrix element governing muscle contraction and vascular pace (Default Daily RDA: 1,000.0 mg).
29. **Iron (mg):** Active gas-binding component of red blood cell hemoglobin (Default Daily RDA: 18.0 mg).
30. **Magnesium (mg):** Crucial ionic chelator governing over 300 enzyme chains and skeletal stability (Default Daily RDA: 400.0 mg).
31. **Phosphorus (mg):** Foundational for cell phosphorus energy backbones (ATP) and cell double layers (Default Daily RDA: 700.0 mg).
32. **Potassium (mg):** Chief intracellular electrolyte pacing electrical membrane action potentials (Default Daily RDA: 3,400.0 mg).
33. **Sodium (mg):** Chief extracellular fluid volume regulator; styled as a cardiovascular safety ceiling (Default UL: 2,300.0 mg).
34. **Zinc (mg):** Essential cofactor for structural zinc finger proteins, transcription, and immunological cells (Default Daily RDA: 11.0 mg).
35. **Copper (mg):** Critical component in iron absorption, connective tissue assembly, and dopamine conversion (Default Daily RDA: 0.9 mg).
36. **Manganese (mg):** Key mitochondrial antioxidant cofactor (superoxide dismutase) and carbohydrate regulator (Default Daily RDA: 2.3 mg).
37. **Selenium (mcg):** Active constituent in glutathione peroxidase defending cells from peroxide stress (Default Daily RDA: 55.0 mcg).
38. **Chromium (mcg):** Chromium-insulin coordination complex optimizing insulin active sensitivity (Default Daily RDA: 35.0 mcg).
39. **Molybdenum (mcg):** Essential element of multiple oxidases metabolizing sulfite and nitrogen waste (Default Daily RDA: 45.0 mcg).

#### Category E: Secondary Regulators (2 Elements)
40. **Sugars (g):** Simple carbohydrates; mapped as a metabolic safety limit (Default UL: 50.0 g).
41. **Iodine (mcg):** Essential mineral directly embedded in Triiodothyronine (T3) and Thyroxine (T4) thyroid hormones (Default Daily RDA: 150.0 mcg).

---

## 2. PARSING & DATA MANIPULATION SYSTEM

### 2.1 Food Interpretation Engine
*   **Offline Estimations:** Standard local dictionary processing for offline entries when network or API access is disabled.
*   **Composite Re-analysis Tool:** High-utility capability permettant a user to re-analyze/replace a logged food description retrospectively (deletes old entry and processes the new description on the fly).

### 2.2 Bulk Entry Processor
*   **Multi-Day Text Parsing:** Allows users to paste large logs (e.g., historical transcripts, multi-day meal diaries, notes) and process them collectively. The system extracts dates and multiple food names, logging them systematically into the database.

### 2.3 Journal Editing & State Sync
*   **CRUD Operations:** Instant inserts, updates, and individual deletions of entries.
*   **Copy Entire Logged Day:** Facilitates rapid copying of all meal logs from a "source date" to a "target date" with a single click (clones all associated 41-nutrient profiles).
*   **Clear All Entries for Date:** Enables clearing of whole days of tracking.
*   **Instant Undo Engine:** Deleting single items or clearing entire days triggers a Toast/Snackbar offering a 5-second window to restore deleted database rows without state loss.

---

## 3. DESIGN & USER NAVIGATION ARCHITECTURE

The user experience in NutriScribe is organized into five interactive operational tabs under a unified top bar with adaptive structures:

### Tab 1: Interactive Dashboard (The Core Command)
*   **Caloric Radial Gauge:** Features a Material 3 radial progress dial of calories consumed versus target, with dual linear representations.
*   **Therapeutic Coaching Engine:** Provides targeted dietary instructions based on RDA progression and safety ceilings. Examples include:
    *   *Sodium Excess WARNING:* Instructs users to drink 2 glasses of water to promote sodium excretion.
    *   *Fiber Deficit Indicator:* Suggests chia/flax seeds or black beans.
    *   *Protein Deficit Alert:* Mentions Greek yogurt, egg whites, or tofu.
*   **Caloric Macronutrient Energy Spread:** Stacked multi-color calorie progress bar representing Carbs (Blue), Protein (Orange), and Fat (Green) and their direct metabolic calorie contribution ratios.
*   **RDA Balance Checklist:** Clean progress indicators for tracked nutrients, categorized under expandable/collapsible Material 3 layouts:
    *   *Expand Macros Button*
    *   *Expand Lipids Button*
    *   *Expand Vitamins Button*
    *   *Expand Minerals Button*
    *   *Expand Others Button*

### Tab 2: Log Journal (Continuous Daily Logging)
*   **Meal Category Groups:** Collapsible panels corresponding to *Breakfast*, *Lunch*, *Dinner*, and *Snack* containing exact items logged.
*   **Dynamic Actions Drawer:** Allows sliding actions or immediate interactive icons to edit descriptions, delete entries, or re-evaluate them.
*   **Day Utility Panel:** Includes quick-action shortcuts to *Copy Entire Day* or *Clear All Entries* to speed up journal maintenance.

### Tab 3: Reports & Trends Analyzer
*   **Aggregations & Date Filters:** Users can analyze trends across custom date spans: **Last 7 Days**, **Last 14 Days**, **Last 30 Days**, or **Last 90 Days**.
*   **Calculated Key Indicators:** Combines historical rows to display:
    *   *Total Calories Logged*
    *   *Average Daily Intake*
    *   *Active Days Measured*
*   **Periodic Clinical Warning Box:** Lists long-term nutritional deficit and excess warning modules, flagging recurring trends.
*   **RDA Assessment Table:** Complete list of all 41 nutrients showing cumulative sums and daily averages over the selected time frame.

### Tab 4: RDA 41 Directory & Override Control
*   **RDA Target Override System:** A vital, high-accessibility layout where users can modify reference targets (e.g., custom protein targets during muscle loading, custom sodium caps for hypertension therapy).
*   **Dynamic Values Sync:** Modifying a target automatically updates progress bars, thresholds, html reports, and warnings across all screens.
*   **Failsafe Revert Actions:** Offers instant buttons to restore original clinical targets/caps instantly.

### Tab 5: Therapeutic Timeline (Observation Log)
*   **Single-Nutrient isolation:** Selection panel allowing users to select any of the 41 parameters to track independently.
*   **Chronological Matrix (Fasting vs. Feasting):** Lists every day in a timeline detailing:
    *   *Intake Achieved* versus *Target Target*
    *   *Relative Difference (Deficit or Excess)*
    *   *Fasting status:* Highlights whether the day was under or over allowance.
*   **Cumulative Running Balances:** Important for specialized medical tracking, showing the running cumulative total difference over time.

---

## 4. REPORTING, PORTABILITY & INTEGRATION

### 4.1 Interactive HTML Report Compiler (NutriScribe Reports)
Generates highly formatted, stand-alone report files styled with embedded scripts and charts:
*   **Google Pie Chart API integration:** Automatically embeds interactive HTML5 vector pie charts illustrating average macronutrient percentages.
*   **Dynamic Table Display Flags:** Interactive buttons in the HTML view (Grams, Mg, Mcg) that allow users to show or hide parts of the nutrient list for better readability.
*   **Periodic Diagnostics Panel:** Summarizes critical warnings, short-term health risks, and cumulative risks for chronic deficiencies directly in the document.
*   **Diagnostic Color-Coding Table:** Cells are dynamically colored (Red = limit exceeded, Yellow = trailing target, Green = target achieved) based on RDA definitions.

### 4.2 Data Portability Engine
*   **High-Fidelity JSON Exporter:** Converts local SQL/Room database tables into standard JSON files for external backup.
*   **Robust JSON Importer:** Parses external system backups, validates schema structures, and performs merge/replace database imports, updating UI states instantly.

---

## 5. TECHNICAL ARCHITECTURE SPECIFICATION

*   **Platform Runtime:** Android (Kotlin with Jetpack Compose UI)
*   **Architecture Pattern:** MVVM (Model-View-ViewModel) + Clean Data Repository Layer
*   **Database Engine:** Room persistence library (SQLite backend)
*   **Reactive Flow Hooks:** Core StateFlow and shared SharedFlow architectures to update calculations immediately upon database transactions.
*   **UI System:** Material Design 3 guidelines featuring responsive adaptive containers.

---
*End of Specifications Document. Set for deployment review.*
