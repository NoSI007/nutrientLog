package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FoodLogEntry
import com.example.data.FoodRepository
import com.example.data.NutrientDefinition
import com.example.data.NutrientGroup
import com.example.data.Nutrients
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class NutrientStatus(
    val definition: NutrientDefinition,
    val intake: Double,
    val percentage: Double, // intake / rda * 100
    val status: StatusColor // RED, YELLOW, GREEN
)

enum class StatusColor {
    RED,    // Critical (deficient or limit exceeded)
    YELLOW, // Sub-optimal (50-99% for goals)
    GREEN   // Healthy (Met or within healthy limit)
}

data class DayTrend(
    val dateString: String, // "YYYY-MM-DD"
    val displayDate: String, // "Mon", "Tue" etc.
    val calories: Double,
    val carbsGrams: Double,
    val proteinGrams: Double,
    val fatGrams: Double
)

class NutritionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FoodRepository(application)
    private val sharedPrefs = application.getSharedPreferences("nutrition_targets", Context.MODE_PRIVATE)

    private val _currentDate = MutableStateFlow(getTodayDateString())
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _customRdaOverrides = MutableStateFlow<Map<String, Double>>(emptyMap())
    val customRdaOverrides: StateFlow<Map<String, Double>> = _customRdaOverrides.asStateFlow()

    val allLogEntries: StateFlow<List<FoodLogEntry>> = repository.allEntries
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Load custom RDA overrides
        val saved = mutableMapOf<String, Double>()
        sharedPrefs.all.forEach { (key, value) ->
            when (value) {
                is Float -> saved[key] = value.toDouble()
                is Double -> saved[key] = value
                is Int -> saved[key] = value.toDouble()
                is Long -> saved[key] = value.toDouble()
                is String -> value.toDoubleOrNull()?.let { saved[key] = it }
            }
        }
        _customRdaOverrides.value = saved

        viewModelScope.launch {
            // Check if DB is empty, pre-populate if true to show trend curves immediately
            val existing = repository.allEntries.first()
            if (existing.isEmpty()) {
                prepopulateSampleLogs()
            }
        }
    }

    fun updateCustomRda(key: String, value: Double) {
        val current = _customRdaOverrides.value.toMutableMap()
        if (value <= 0.0) {
            current.remove(key)
            sharedPrefs.edit().remove(key).apply()
        } else {
            current[key] = value
            sharedPrefs.edit().putFloat(key, value.toFloat()).apply()
        }
        _customRdaOverrides.value = current
        _operationMessage.value = "Updated target for ${Nutrients.getByKey(key)?.name ?: key} to ${value.toInt()} ${Nutrients.getByKey(key)?.unit ?: ""}"
    }

    val currentDateEntries: StateFlow<List<FoodLogEntry>> = combine(allLogEntries, currentDate) { entries, date ->
        entries.filter { it.date == date }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 1. Nutrient summary list for the currently selected date
    val dailyNutrients: StateFlow<List<NutrientStatus>> = currentDateEntries.mapToNutrientStatus()

    // 2. Macro spreads calculations
    val macroSpread: StateFlow<MacroSpreadRatio> = currentDateEntries.mapToMacroSpread()

    // 3. Deficiency and risk warnings
    val warnings: StateFlow<List<DeficiencyWarning>> = dailyNutrients.mapToWarnings()

    // 4. Overarching 7-day trend values
    val sevenDayTrends: StateFlow<List<DayTrend>> = allLogEntries.mapToTrends()

    fun selectDate(dateString: String) {
        _currentDate.value = dateString
    }

    fun clearMessage() {
        _operationMessage.value = null
    }

    private var lastDeletedEntry: FoodLogEntry? = null
    private var lastDeletedBatch: List<FoodLogEntry> = emptyList()

    fun deleteLog(entry: FoodLogEntry) {
        viewModelScope.launch {
            lastDeletedEntry = entry
            lastDeletedBatch = emptyList()
            repository.deleteEntry(entry)
            _operationMessage.value = "Deleted ${entry.foodName}"
        }
    }

    fun undoDelete() {
        viewModelScope.launch {
            val single = lastDeletedEntry
            val batch = lastDeletedBatch
            if (single != null) {
                repository.insertEntry(single)
                lastDeletedEntry = null
                _operationMessage.value = "Restored: ${single.foodName}"
            } else if (batch.isNotEmpty()) {
                batch.forEach { repository.insertEntry(it) }
                lastDeletedBatch = emptyList()
                _operationMessage.value = "Restored ${batch.size} entries"
            }
        }
    }

    fun clearAllForDate(dateString: String) {
        viewModelScope.launch {
            val currentEntries = allLogEntries.value.filter { it.date == dateString }
            if (currentEntries.isNotEmpty()) {
                lastDeletedBatch = currentEntries
                lastDeletedEntry = null
                repository.deleteEntriesForDate(dateString)
                _operationMessage.value = "Cleared all ${currentEntries.size} entries for this day"
            }
        }
    }

    fun updateFoodLogEntry(entry: FoodLogEntry) {
        viewModelScope.launch {
            try {
                repository.insertEntry(entry)
                _operationMessage.value = "Updated entry: ${entry.foodName}"
            } catch (e: Exception) {
                _operationMessage.value = "Failed to update entry"
            }
        }
    }

    fun reanalyzeAndReplaceEntry(entryId: Int, newDescription: String, mealType: String, date: String) {
        if (newDescription.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteEntryById(entryId)
                val result = repository.parseAndLogFood(newDescription, date, mealType)
                if (result.isSuccess) {
                    _operationMessage.value = "Re-analyzed & logged: ${result.getOrNull()?.foodName}"
                } else {
                    _operationMessage.value = "Updated with default fallback composition."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error re-analyzing item."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addFoodLog(input: String, mealType: String) {
        if (input.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.parseAndLogFood(input, _currentDate.value, mealType)
                if (result.isSuccess) {
                    val entry = result.getOrNull()
                    _operationMessage.value = "Logged: ${entry?.foodName}"
                } else {
                    _operationMessage.value = "Failed to parse. Logged standard estimate."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error: Use offline fallback estimates."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun copyDayLog(sourceDate: String, targetDate: String) {
        viewModelScope.launch {
            val sourceEntries = allLogEntries.value.filter { it.date == sourceDate }
            if (sourceEntries.isNotEmpty()) {
                sourceEntries.forEach { entry ->
                    repository.insertEntry(entry.copy(id = 0, date = targetDate))
                }
                _operationMessage.value = "Copied ${sourceEntries.size} entries to target date."
            } else {
                _operationMessage.value = "No entries to copy from source date."
            }
        }
    }

    fun addBulkFoodLog(input: String) {
        if (input.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.parseAndLogMultiDayFood(input, getTodayDateString())
                if (result.isSuccess) {
                    val entries = result.getOrNull() ?: emptyList()
                    if (entries.isNotEmpty()) {
                        _operationMessage.value = "Successfully logged ${entries.size} items across your journal!"
                    } else {
                        _operationMessage.value = "No food items parsed from your input."
                    }
                } else {
                    _operationMessage.value = "Fallback parser extracted standard estimates."
                }
            } catch (e: Exception) {
                _operationMessage.value = "Error parsing multi-day logs."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportLogs(onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportToJson()
            onSuccess(json)
        }
    }

    fun generateHtmlReport(report: PeriodicReport): String {
        val totalDays = report.daysWithLogs
        val divisor = if (totalDays > 0) totalDays.toDouble() else 1.0
        val avgCal = report.avgCaloriesPerDay.toInt()
        val carbPct = (report.avgMacros.carbsPercent * 100).toInt()
        val protPct = (report.avgMacros.proteinPercent * 100).toInt()
        val fatPct = (report.avgMacros.fatPercent * 100).toInt()

        var aboveCount = 0
        var belowCount = 0
        var okayCount = 0
        var nodataCount = 0

        val tableRows = StringBuilder()
        for (status in report.averageNutrients) {
            val def = status.definition
            val intake = status.intake
            val totalValueStr = if (def.key == "calories") {
                (intake * divisor).toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", intake * divisor)
            }
            val averageValueStr = if (def.key == "calories") {
                intake.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", intake)
            }
            val targetValueStr = if (def.key == "calories") {
                def.rda.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", def.rda)
            }

            val rowClass = when (def.unit.lowercase(Locale.US)) {
                "g" -> "macroBg"
                "mcg" -> "traceBg"
                "mg" -> "microBg"
                else -> "otherBg"
            }

            val statusClass = when {
                def.isMaxLimit -> {
                    if (intake > def.rda) {
                        aboveCount++
                        "above"
                    } else {
                        okayCount++
                        "okay"
                    }
                }
                def.rda <= 0.0 -> {
                    nodataCount++
                    "nodata"
                }
                else -> {
                    val pct = status.percentage
                    when {
                        pct >= 100.0 -> {
                            okayCount++
                            "okay"
                        }
                        pct >= 50.0 -> {
                            belowCount++
                            "below"
                        }
                        else -> {
                            belowCount++
                            "below"
                        }
                    }
                }
            }

            val percentStr = if (def.rda > 0.0) "${status.percentage.toInt()}%" else ""
            val ulStr = if (def.isMaxLimit) targetValueStr else ""

            tableRows.append(
                """
                <tr class="row $rowClass">
                    <td class="col-sm-2 Nutrient">${def.name}</td>
                    <td class="col-sm-1 Units">${def.unit}</td>
                    <td class="col-sm-1 Value">$totalValueStr</td>
                    <td class="$statusClass">$averageValueStr</td>
                    <td class="col-sm-1 BoA_RNIpc">$percentStr</td>
                    <td class="col-sm-1 Rni">$targetValueStr</td>
                    <td class="col-sm-1 Ul">$ulStr</td>
                </tr>
                """.trimIndent()
            )
        }

        val diagnosticsBuilder = StringBuilder()
        if (report.insights.isNotEmpty()) {
            diagnosticsBuilder.append(
                """
                <div class="panel panel-warning" style="margin-top: 15px;">
                    <div class="panel-heading">
                        <h3 class="panel-title" style="font-weight: bold;">Periodic Diagnostics & warnings/Insights</h3>
                    </div>
                    <div class="panel-body" style="max-height: 480px; overflow-y: auto;">
                        <ul class="list-group" style="margin-bottom: 0;">
                """.trimIndent()
            )

            for (insight in report.insights) {
                val typeText = if (insight.isExcess) "EXCESS WARNING" else "DEFICIT WARNING"
                val typeClass = if (insight.isExcess) "label-danger" else "label-warning"
                diagnosticsBuilder.append(
                    """
                    <li class="list-group-item">
                        <h4 style="margin-top:0;"><span class="label $typeClass">$typeText</span> <strong>${insight.name}</strong></h4>
                        <p style="font-size: 13px;"><strong>Observed Average:</strong> ${insight.intakeString} vs target ${insight.rdaString}</p>
                        <p style="font-size: 13px; color: #555;"><strong>Short-term Risk:</strong> ${insight.shortTermRisk}</p>
                        <p style="font-size: 13px; color: #c0392b;"><strong>Long-term Cumulative Risk:</strong> ${insight.longTermRisk}</p>
                    </li>
                    """.trimIndent()
                )
            }

            diagnosticsBuilder.append(
                """
                        </ul>
                    </div>
                </div>
                """.trimIndent()
            )
        } else {
            diagnosticsBuilder.append(
                """
                <div class="alert alert-success" style="margin-top: 15px; font-size: 15px;">
                    <strong>Perfect Balance achieved!</strong> Your average intake patterns fulfill all critical dietary standards.
                </div>
                """.trimIndent()
            )
        }

        val format = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)
        val generationTime = format.format(Calendar.getInstance().time)

        return """
            <!DOCTYPE html>
            <html lang="en" xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <meta charset="utf-8" />
                <title>${report.periodName} Log report</title>
                <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css"/>
                <script src="https://ajax.googleapis.com/ajax/libs/jquery/1.12.4/jquery.min.js"></script>
                <script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js"></script>
                <script type="text/javascript" >
                $(document).ready(function ()
                {
                    $("#xGram").click(function () {
                        $("#xres .macroBg").toggle();
                    });
                    $("#xmcg").click(function () {
                        $("#xres .traceBg").toggle();
                    });
                    $("#xmg").click(function () {
                        $("#xres .microBg").toggle();
                    });
                });
                </script>
                <script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>
                <script type="text/javascript">
                  google.charts.load("current", {packages:["corechart"]});
                  google.charts.setOnLoadCallback(drawChart);
                  var X1 = $protPct;
                  var X2 = $fatPct;
                  var X3 = $carbPct;
             
                  function drawChart() 
                  {
                    var data = google.visualization.arrayToDataTable([
                      ['Title', 'Macro Nutrient Balance'],
                      ['Protein',  X1],
                      ['Fat',  X2],
                      ['Carb', X3]         
                    ]);

                    var options = 
                    {
                       pieSliceTextStyle: 
                        {
                            color: 'white',
                            bold:true,
                          },
                       slices: {
                            0: { color: 'Green' },
                            1: { color: 'Red' },
                            2: { color: 'Orange'}
                          },
                        title: 'Macro Nutrient Balance',
                        is3D: false,
                        legend: 'bottom',
                        pieStartAngle: 0,
                    };
                    var chart = new google.visualization.PieChart(document.getElementById('piechart'));
                    chart.draw(data, options);
                  }
                </script>
                <style>
                    body {
                        padding: 30px;
                        background-color: #f8fafc;
                        color: #334155;
                        font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
                    }
                    .h-branding {
                        background-color: #1e293b;
                        color: #ffffff;
                        padding: 24px;
                        border-radius: 8px;
                        margin-bottom: 24px;
                        box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1);
                    }
                    .h-branding h1 { margin-top: 0; font-weight: bold; }
                    .FooTab { width: 100%; border-radius: 8px; overflow: hidden; }
                    .above {
                        background-color: #fecaca !important;
                        color: #991b1b !important;
                        font-weight: bold;
                    }
                    .below {
                        background-color: #fef08a !important;
                        color: #854d0e !important;
                        font-weight: bold;
                    }
                    .okay {
                        background-color: #bbf7d0 !important;
                        color: #166534 !important;
                        font-weight: bold;
                    }
                    .nodata {
                        background-color: #f1f5f9 !important;
                        color: #475569 !important;
                    }
                    .NBtotals td {
                        font-size: 16px;
                        font-weight: bold;
                    }
                    #piechart {
                        height: 260px;
                        margin-bottom: 20px;
                    }
                    .btn-toggle-grp { margin-bottom: 20px; }
                    .footer {
                        margin-top: 40px;
                        padding-top: 20px;
                        border-top: 1px solid #e2e8f0;
                        font-size: 12px;
                    }
                </style>
            </head>
            <body>
            <div class="h-branding">
                <div class="row">
                    <div class="col-sm-8">
                        <h1>NutriScribe Log Report</h1>
                        <h3>Assessment Period: ${report.periodName} (${report.daysCount} Days Evaluation)</h3>
                        <h4>Log Generation Time: $generationTime</h4>
                        <h4>Analyzed across $totalDays active logged day(s)</h4>
                    </div>
                    <div class="col-sm-4 text-right" style="margin-top:20px;">
                        <h2 style="font-size:36px; font-weight:bold; margin:0;">$avgCal kcal</h2>
                        <span style="font-size:14px; opacity:0.8;">Average Daily Intake</span>
                    </div>
                </div>
            </div>

            <div class="row">
                <div class="col-md-6">
                    <div class="panel panel-default">
                        <div class="panel-heading" style="font-weight:bold;">Macro Nutrient Balance spread (Calories)</div>
                        <div class="panel-body">
                            <div id="piechart"></div>
                            <table class="table table-bordered table-condensed table-responsive centre FooTab">
                            <thead>
                            <tr class="row" style="font-weight: bold; background-color: #e2e8f0;">
                            <td class="col-sm-4 mNutrient">Nutrient</td>
                            <td class="col-sm-4 Achieved">Achieved (Avg/Day)</td>
                            <td class="col-sm-4 EXPECT">Ratio (%)</td>
                            </tr>
                            </thead>
                            <tbody>
                              <tr class="row">
                               <td class="col-sm-4 mNutrient">Protein</td>
                               <td class="col-sm-4 Achieved">${report.avgMacros.proteinGrams.toInt()} g</td>
                               <td class="col-sm-4 EXPECT">$protPct%</td>
                              </tr>
                              <tr class="row">
                               <td class="col-sm-4 mNutrient">Fat</td>
                               <td class="col-sm-4 Achieved">${report.avgMacros.fatGrams.toInt()} g</td>
                               <td class="col-sm-4 EXPECT">$fatPct%</td>
                              </tr>
                              <tr class="row">
                               <td class="col-sm-4 mNutrient">Carbohydrates</td>
                               <td class="col-sm-4 Achieved">${report.avgMacros.carbsGrams.toInt()} g</td>
                               <td class="col-sm-4 EXPECT">$carbPct%</td>
                              </tr>
                              <tr class="row" style="font-weight:bold;">
                               <td class="col-sm-4 mNutrient">Calories</td>
                               <td class="col-sm-4 Achieved">$avgCal kcal</td>
                               <td class="col-sm-4 EXPECT">100%</td>
                              </tr>
                            </tbody>
                            </table>
                        </div>
                    </div>
                </div>
                <div class="col-md-6">
                    ${diagnosticsBuilder.toString()}
                </div>
            </div>

            <hr/>
            <h2>Nutrients Sums, Averages, Reference Intake & Limits</h2>
            
            <div class="btn-toggle-grp">
                <span style="font-weight:bold; margin-right: 12px;">Display Filters:</span>
                <button type="button" value="Grams" id="xGram" class="btn btn-primary btn-sm">Grams</button>
                <button type="button" value="Mg" id="xmg" class="btn btn-info btn-sm">Mg</button>
                <button type="button" value="Mcg" id="xmcg" class="btn btn-warning btn-sm">Mcg</button>
            </div>

            <table class="table table-bordered table-condensed table-responsive centre NBtotals" style="max-width: 480px; margin-bottom: 24px;">
            <thead>
                <tr style="background-color: #f1f5f9; font-weight: bold;">
                    <td class="col-xs-3" style="color:#991b1b;">Excess Limits</td>
                    <td class="col-xs-3" style="color:#854d0e;">Warnings (Below target)</td>
                    <td class="col-xs-3" style="color:#166534;">Target Achieved</td>
                    <td class="col-xs-3" style="color:#475569;">No Target Set</td>
                </tr>
            </thead>
            <tbody>
                <tr class="row">
                    <td class="col-xs-3 above">$aboveCount</td>
                    <td class="col-xs-3 below">$belowCount</td>
                    <td class="col-xs-3 okay">$okayCount</td>
                    <td class="col-xs-3 nodata">$nodataCount</td>
                </tr>
            </tbody>
            </table>

            <table class="table table-bordered table-condensed table-responsive centre FooTab" id="xres">
                <thead>
                    <tr class="row table-info" style="font-weight: bold; background-color: #f1f5f9;">
                        <td class="col-sm-2 Nutrient">Nutrient</td>
                        <td class="col-sm-1">Units</td>
                        <td class="col-sm-1">Total Period Sum</td>
                        <td class="col-sm-2">Daily Average Intake</td>
                        <td class="col-sm-1">RDA Standard %</td>
                        <td class="col-sm-1">RDA Target Reference</td>
                        <td class="col-sm-1">Upper Limit (UL)</td>
                    </tr>
                </thead>
                <tbody>
                    ${tableRows.toString()}
                </tbody>
            </table>

            <section class="footer">
            <dl class="dl-horizontal">
            <dt>Daily Average Intake</dt>
            <dd>Calculated as average = sum / active logged days.</dd>
            <dt>Total Period Sum</dt>
            <dd>Total cumulative nutrition tracking captured across checked database range.</dd>
            <dt>RDA Standard %</dt>
            <dd>Percentage achieved relative to daily target guides.</dd>
            <dt>RDA Target Reference</dt>
            <dd>Recommended optimal intake benchmarks.</dd>
            <dt>Upper Limit (UL)</dt>
            <dd>Safe upper limit guidelines where applicable.</dd>
            </dl>
            <p class="text-center text-muted" style="margin-top: 32px;">Report exported from NutriScribe Daily Diet Tracker AI Platform © 2026</p>
            </section>
            </body>
            </html>
        """.trimIndent()
    }

    fun importLogs(jsonString: String, onFinished: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importFromJson(jsonString)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _operationMessage.value = "Imported $count log entries successfully"
                onFinished(true, "Successfully imported $count entries.")
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _operationMessage.value = "Import failed: $errorMsg"
                onFinished(false, "Import failed: $errorMsg")
            }
            _isLoading.value = false
        }
    }

    private fun getTodayDateString(): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return format.format(Calendar.getInstance().time)
    }

    private fun prepopulateSampleLogs() {
        viewModelScope.launch {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance()

            // Today: Balanced day
            val todayDate = format.format(cal.time)
            repository.insertEntry(
                FoodLogEntry(
                    date = todayDate,
                    foodName = "Greek yogurt with honey and chia seeds",
                    mealType = "Breakfast",
                    nutrients = repository.createFallbackEntry("greek yogurt chia honey", todayDate, "Breakfast").nutrients
                )
            )
            repository.insertEntry(
                FoodLogEntry(
                    date = todayDate,
                    foodName = "Grilled chicken breast, brown rice and broccoli",
                    mealType = "Lunch",
                    nutrients = repository.createFallbackEntry("grilled chicken brown rice broccoli", todayDate, "Lunch").nutrients
                )
            )
            repository.insertEntry(
                FoodLogEntry(
                    date = todayDate,
                    foodName = "Baked salmon with spinach salad",
                    mealType = "Dinner",
                    nutrients = repository.createFallbackEntry("baked salmon spinach salad", todayDate, "Dinner").nutrients
                )
            )

            // Yesterday (2026-05-24): High-sodium, low vitamin day
            cal.add(Calendar.DATE, -1)
            val yesterdayDate = format.format(cal.time)
            repository.insertEntry(
                FoodLogEntry(
                    date = yesterdayDate,
                    foodName = "Double bacon cheeseburger",
                    mealType = "Lunch",
                    nutrients = repository.createFallbackEntry("burger bacon cheese", yesterdayDate, "Lunch").nutrients.toMutableMap().apply {
                        put("sodium", 2600.0) // exceed limit
                        put("saturated_fat", 25.0) // exceed limit
                        put("calories", 950.0)
                        put("vitamin_c", 0.0) // zero
                        put("vitamin_d", 0.0)
                    }
                )
            )
            repository.insertEntry(
                FoodLogEntry(
                    date = yesterdayDate,
                    foodName = "French fries and regular soda",
                    mealType = "Snack",
                    nutrients = repository.createFallbackEntry("fries and soda", yesterdayDate, "Snack").nutrients.toMutableMap().apply {
                        put("sodium", 800.0)
                        put("sugars", 45.0)
                        put("calories", 400.0)
                        put("vitamin_c", 2.0)
                    }
                )
            )

            // 2 Days Ago (2026-05-23): Light hydration, low-iron day
            cal.add(Calendar.DATE, -1)
            val twoDaysAgoDate = format.format(cal.time)
            repository.insertEntry(
                FoodLogEntry(
                    date = twoDaysAgoDate,
                    foodName = "Oatmeal with sliced banana",
                    mealType = "Breakfast",
                    nutrients = repository.createFallbackEntry("oatmeal banana", twoDaysAgoDate, "Breakfast").nutrients
                )
            )
            repository.insertEntry(
                FoodLogEntry(
                    date = twoDaysAgoDate,
                    foodName = "Garden salad with olive oil",
                    mealType = "Lunch",
                    nutrients = repository.createFallbackEntry("salad oil", twoDaysAgoDate, "Lunch").nutrients.toMutableMap().apply {
                        put("iron", 0.2) // very deficient
                        put("protein", 2.0)
                        put("calories", 180.0)
                    }
                )
            )
            repository.insertEntry(
                FoodLogEntry(
                    date = twoDaysAgoDate,
                    foodName = "White rice with soy sauce",
                    mealType = "Dinner",
                    nutrients = repository.createFallbackEntry("rice soy sauce", twoDaysAgoDate, "Dinner").nutrients.toMutableMap().apply {
                        put("sodium", 1800.0)
                        put("protein", 3.0)
                        put("calories", 250.0)
                    }
                )
            )
        }
    }

    // Helper mapping extensions
    private fun StateFlow<List<FoodLogEntry>>.mapToNutrientStatus(): StateFlow<List<NutrientStatus>> {
        return combine(this, customRdaOverrides) { entries, overrides ->
            val totals = mutableMapOf<String, Double>()
            
            // Init all to 0.0
            for (definition in Nutrients.DEFINITIONS) {
                totals[definition.key] = 0.0
            }

            // Sum up
            for (entry in entries) {
                for ((key, value) in entry.nutrients) {
                    totals[key] = (totals[key] ?: 0.0) + (value * entry.quantity)
                }
            }

            Nutrients.DEFINITIONS.map { definition ->
                val rdaValue = overrides[definition.key] ?: definition.rda
                val sum = totals[definition.key] ?: 0.0
                val percent = if (rdaValue > 0) (sum / rdaValue) * 100.0 else 0.0

                val color = when {
                    definition.isMaxLimit -> {
                        if (sum > rdaValue) StatusColor.RED else StatusColor.GREEN
                    }
                    else -> {
                        when {
                            percent >= 100.0 -> StatusColor.GREEN
                            percent >= 50.0 -> StatusColor.YELLOW
                            else -> StatusColor.RED
                        }
                    }
                }

                val overriddenDefinition = if (rdaValue != definition.rda) {
                    definition.copy(rda = rdaValue)
                } else {
                    definition
                }

                NutrientStatus(overriddenDefinition, sum, percent, color)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun StateFlow<List<FoodLogEntry>>.mapToMacroSpread(): StateFlow<MacroSpreadRatio> {
        return combine(this) { wrapperList ->
            val entries = wrapperList[0]
            var totalCarbs = 0.0
            var totalProtein = 0.0
            var totalFat = 0.0

            for (entry in entries) {
                totalCarbs += (entry.nutrients["carbohydrates"] ?: 0.0) * entry.quantity
                totalProtein += (entry.nutrients["protein"] ?: 0.0) * entry.quantity
                totalFat += (entry.nutrients["fat"] ?: 0.0) * entry.quantity
            }

            val carbCal = totalCarbs * 4.0
            val protCal = totalProtein * 4.0
            val fatCal = totalFat * 9.0
            val aggregatedCal = carbCal + protCal + fatCal

            if (aggregatedCal > 0.0) {
                MacroSpreadRatio(
                    carbsPercent = carbCal / aggregatedCal,
                    proteinPercent = protCal / aggregatedCal,
                    fatPercent = fatCal / aggregatedCal,
                    totalCaloriesCalculated = aggregatedCal,
                    carbsGrams = totalCarbs,
                    proteinGrams = totalProtein,
                    fatGrams = totalFat
                )
            } else {
                MacroSpreadRatio(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MacroSpreadRatio(0.0,0.0,0.0,0.0,0.0,0.0,0.0))
    }

    private fun StateFlow<List<NutrientStatus>>.mapToWarnings(): StateFlow<List<DeficiencyWarning>> {
        return combine(this) { wrapperList ->
            val statuses = wrapperList[0]
            val warningList = mutableListOf<DeficiencyWarning>()

            for (status in statuses) {
                val sum = status.intake
                val def = status.definition

                if (def.isMaxLimit) {
                    if (sum > def.rda) {
                        warningList.add(
                            DeficiencyWarning(
                                nutrientKey = def.key,
                                name = def.name,
                                group = def.group,
                                message = "Limit Exceeded: Current intake is ${(sum - def.rda).format(1)} ${def.unit} above limits (${status.percentage.format(0)}%)",
                                isExceededLimit = true
                            )
                        )
                    }
                } else {
                    // Alert if intake is under 50% RDA
                    if (status.percentage < 50.0) {
                        warningList.add(
                            DeficiencyWarning(
                                nutrientKey = def.key,
                                name = def.name,
                                group = def.group,
                                message = "Severe Deficiency: Only achieved ${status.percentage.format(0)}% of your target allowance (${def.rda} ${def.unit})",
                                isExceededLimit = false
                            )
                        )
                    }
                }
            }
            warningList
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun StateFlow<List<FoodLogEntry>>.mapToTrends(): StateFlow<List<DayTrend>> {
        return combine(this) { wrapperList ->
            val entries = wrapperList[0]
            val groupedByDate = entries.groupBy { it.date }

            val datesList = mutableListOf<String>()
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val viewFormat = SimpleDateFormat("E", Locale.US) // e.g. "Mon"
            val calendar = Calendar.getInstance()

            // Let's populate last 7 days ending with today
            for (i in 0 until 7) {
                val dateStr = format.format(calendar.time)
                datesList.add(dateStr)
                calendar.add(Calendar.DATE, -1)
            }
            // Reverse so they display left-to-right Chronologically
            datesList.reverse()

            datesList.map { dateStr ->
                val dayEntries = groupedByDate[dateStr] ?: emptyList()
                var calSum = 0.0
                var carbsSum = 0.0
                var protSum = 0.0
                var fatSum = 0.0

                for (entry in dayEntries) {
                    calSum += (entry.nutrients["calories"] ?: 0.0) * entry.quantity
                    carbsSum += (entry.nutrients["carbohydrates"] ?: 0.0) * entry.quantity
                    protSum += (entry.nutrients["protein"] ?: 0.0) * entry.quantity
                    fatSum += (entry.nutrients["fat"] ?: 0.0) * entry.quantity
                }

                // parse display abbreviation
                val disp = try {
                    val parsedDate = format.parse(dateStr)
                    if (parsedDate != null) viewFormat.format(parsedDate) else ""
                } catch (e: Exception) {
                    ""
                }

                DayTrend(
                    dateString = dateStr,
                    displayDate = disp,
                    calories = calSum,
                    carbsGrams = carbsSum,
                    proteinGrams = protSum,
                    fatGrams = fatSum
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun Double.format(digits: Int): String {
        return String.format(Locale.US, "%.${digits}f", this)
    }

    fun getPeriodicReport(daysCount: Int, label: String): PeriodicReport {
        val entries = allLogEntries.value
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        val anchorDate = try {
            format.parse(getTodayDateString()) ?: Calendar.getInstance().time
        } catch(e: Exception) {
            Calendar.getInstance().time
        }
        
        val cal = Calendar.getInstance()
        cal.time = anchorDate
        cal.add(Calendar.DATE, -(daysCount - 1))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val cutoffTime = cal.timeInMillis

        val filteredEntries = entries.filter { entry ->
            try {
                val entryDate = format.parse(entry.date)
                entryDate != null && entryDate.time >= cutoffTime
            } catch (e: Exception) {
                false
            }
        }

        val groupedByDate = filteredEntries.groupBy { it.date }
        val daysWithLogs = groupedByDate.keys.size
        val divisor = if (daysWithLogs > 0) daysWithLogs.toDouble() else 1.0

        val totals = mutableMapOf<String, Double>()
        for (def in com.example.data.Nutrients.DEFINITIONS) {
            totals[def.key] = 0.0
        }

        for (entry in filteredEntries) {
            for ((key, value) in entry.nutrients) {
                totals[key] = (totals[key] ?: 0.0) + (value * entry.quantity)
            }
        }

        val averageDailyIntakes = mutableMapOf<String, Double>()
        for (def in com.example.data.Nutrients.DEFINITIONS) {
            averageDailyIntakes[def.key] = (totals[def.key] ?: 0.0) / divisor
        }

        val totalCalories = totals["calories"] ?: 0.0
        val avgCaloriesPerDay = totalCalories / divisor

        val totalCarbs = totals["carbohydrates"] ?: 0.0
        val totalProtein = totals["protein"] ?: 0.0
        val totalFat = totals["fat"] ?: 0.0

        val avgCarbs = totalCarbs / divisor
        val avgProtein = totalProtein / divisor
        val avgFat = totalFat / divisor

        val carbCal = avgCarbs * 4.0
        val protCal = avgProtein * 4.0
        val fatCal = avgFat * 9.0
        val aggregatedCal = carbCal + protCal + fatCal

        val macroRatio = if (aggregatedCal > 0.0) {
            MacroSpreadRatio(
                carbsPercent = carbCal / aggregatedCal,
                proteinPercent = protCal / aggregatedCal,
                fatPercent = fatCal / aggregatedCal,
                totalCaloriesCalculated = aggregatedCal,
                carbsGrams = avgCarbs,
                proteinGrams = avgProtein,
                fatGrams = avgFat
            )
        } else {
            MacroSpreadRatio(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val overrides = _customRdaOverrides.value
        val nutrientStatuses = com.example.data.Nutrients.DEFINITIONS.map { definition ->
            val rdaValue = overrides[definition.key] ?: definition.rda
            val avgIntake = averageDailyIntakes[definition.key] ?: 0.0
            val percent = if (rdaValue > 0) (avgIntake / rdaValue) * 100.0 else 0.0

            val color = when {
                definition.isMaxLimit -> {
                    if (avgIntake > rdaValue) StatusColor.RED else StatusColor.GREEN
                }
                else -> {
                    when {
                        percent >= 100.0 -> StatusColor.GREEN
                        percent >= 50.0 -> StatusColor.YELLOW
                        else -> StatusColor.RED
                    }
                }
            }

            val overriddenDefinition = if (rdaValue != definition.rda) {
                definition.copy(rda = rdaValue)
            } else {
                definition
            }

            NutrientStatus(overriddenDefinition, avgIntake, percent, color)
        }

        // Generate the diagnostics by calling public function getInsights in target screens
        val reportsInsights = getInsights(nutrientStatuses)

        return PeriodicReport(
            periodName = label,
            daysCount = daysCount,
            daysWithLogs = daysWithLogs,
            totalCalories = totalCalories,
            avgCaloriesPerDay = avgCaloriesPerDay,
            avgMacros = macroRatio,
            averageNutrients = nutrientStatuses,
            insights = reportsInsights
        )
    }
}

data class PeriodicReport(
    val periodName: String,
    val daysCount: Int,
    val daysWithLogs: Int,
    val totalCalories: Double,
    val avgCaloriesPerDay: Double,
    val avgMacros: MacroSpreadRatio,
    val averageNutrients: List<NutrientStatus>,
    val insights: List<DiagnosticInsight>
)

data class MacroSpreadRatio(
    val carbsPercent: Double,    // e.g. 0.45 (for 45%)
    val proteinPercent: Double,  // e.g. 0.20 (for 20%)
    val fatPercent: Double,      // e.g. 0.35 (for 35%)
    val totalCaloriesCalculated: Double,
    val carbsGrams: Double,
    val proteinGrams: Double,
    val fatGrams: Double
)

data class DeficiencyWarning(
    val nutrientKey: String,
    val name: String,
    val group: NutrientGroup,
    val message: String,
    val isExceededLimit: Boolean
)
