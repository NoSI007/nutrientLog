package com.example.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.FoodLogEntry
import com.example.data.NutrientGroup
import com.example.data.Nutrients
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContract

class SafeCreateDocument(private val mimeType: String) : ActivityResultContract<String, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent {
        return android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(android.content.Intent.EXTRA_TITLE, input)
    }

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? {
        return if (intent == null || resultCode != android.app.Activity.RESULT_OK) null else intent.data
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionTrackerMainScreen(viewModel: NutritionViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val currentDate by viewModel.currentDate.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Synchronize Snackbar feedback
    LaunchedEffect(key1 = operationMessage) {
        operationMessage?.let {
            if (it.contains("Deleted") || it.contains("Cleared")) {
                val result = snackbarHostState.showSnackbar(
                    message = it,
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoDelete()
                }
            } else {
                snackbarHostState.showSnackbar(
                    message = it,
                    duration = SnackbarDuration.Short
                )
            }
            viewModel.clearMessage()
        }
    }

    val datesRangeList = remember {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()

        val list = mutableListOf<Pair<String, String>>() // Pair<DateString, DayName>
        val dayNameFormat = SimpleDateFormat("E d", Locale.US) // "Mon 25"

        for (i in 0 until 7) {
            list.add(Pair(format.format(cal.time), dayNameFormat.format(cal.time)))
            cal.add(Calendar.DATE, -1)
        }
        list.reverse()
        list
    }

    var showAddFoodDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "NutriScribe Tracker",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Evaluating 41 Core Essential Nutrients",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Status light
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isLoading) Color.Yellow else Color.Green)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isLoading) "Analyzing..." else "AI Standard Mode",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Date quick switcher bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        datesRangeList.forEach { (dateStr, label) ->
                            val isSelected = dateStr == currentDate
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.selectDate(dateStr) }
                                    .padding(vertical = 8.dp)
                                    .testTag("date_tab_$dateStr"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val parts = label.split(" ")
                                    Text(
                                        text = parts.firstOrNull() ?: "",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = parts.getOrNull(1) ?: "",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Navigation Tabs
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.testTag("tab_dashboard"),
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Dashboard")
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.testTag("tab_journal"),
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Log Journal")
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            modifier = Modifier.testTag("tab_reports"),
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reports & Trends")
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            modifier = Modifier.testTag("tab_nutrients"),
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("RDA 41 Directory")
                                }
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showAddFoodDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_food_fab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Food")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Log Food")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(viewModel)
                1 -> JournalTab(viewModel)
                2 -> PeriodicReportsTab(viewModel)
                3 -> NutrientsListTab(viewModel)
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Querying Nutritional Database...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Extracting 41 vitamins, minerals, lipids and active macronutrients",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddFoodDialog) {
        AddFoodDialog(
            onDismiss = { showAddFoodDialog = false },
            onConfirm = { food, mealType ->
                showAddFoodDialog = false
                viewModel.addFoodLog(food, mealType)
            },
            onConfirmBulk = { bulkContent ->
                showAddFoodDialog = false
                viewModel.addBulkFoodLog(bulkContent)
            }
        )
    }
}

enum class RecommendationType {
    WARNING,
    TARGET,
    SUCCESS,
    INFO
}

data class RecommendationItem(
    val title: String,
    val description: String,
    val type: RecommendationType
)

@Composable
fun DashboardTab(viewModel: NutritionViewModel) {
    val macroSpread by viewModel.macroSpread.collectAsState()
    val dailyNutrients by viewModel.dailyNutrients.collectAsState()
    val warnings by viewModel.warnings.collectAsState()
    val trends by viewModel.sevenDayTrends.collectAsState()
    val currentDate by viewModel.currentDate.collectAsState()

    val calorieStatus = dailyNutrients.find { it.definition.key == "calories" }
    val caloriesIntake = calorieStatus?.intake ?: 0.0
    val caloriesRda = calorieStatus?.definition?.rda ?: 2000.0
    val caloriesPercentage = calorieStatus?.percentage ?: 0.0

    // Dynamic state to expand or collapse standard vs all 41 nutrients in running totals list
    var showAllNutrients by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    // Primary nutrients of interest to show in compact view
    val primaryKeys = remember {
        listOf(
            "calories", "protein", "carbohydrates", "fat", "fiber", "water", 
            "saturated_fat", "sodium", "sugars", "calcium", "iron", "vitamin_c", "potassium"
        )
    }

    // Filter status objects
    val displayNutrients = remember(dailyNutrients, showAllNutrients) {
        if (showAllNutrients) {
            dailyNutrients
        } else {
            dailyNutrients.filter { it.definition.key in primaryKeys }
        }
    }

    // Dynamic suggestions based on current progress towards RDA goals and limit safeguards
    val recommendations = remember(dailyNutrients) {
        val list = mutableListOf<RecommendationItem>()
        
        // 1. Check if no food logged yet for today
        if (dailyNutrients.all { it.intake == 0.0 }) {
            list.add(
                RecommendationItem(
                    title = "Your Journal is Empty Today",
                    description = "Start logging your meals! Our AI parser will instantly break down your foods into 41 nutrients and guide your subsequent choices.",
                    type = RecommendationType.INFO
                )
            )
        } else {
            // 2. Caloric analysis
            val calStatus = dailyNutrients.find { it.definition.key == "calories" }
            if (calStatus != null) {
                val remain = calStatus.definition.rda - calStatus.intake
                if (remain > 250) {
                    list.add(
                        RecommendationItem(
                            title = "Calorie Room: ${remain.toInt()} kcal left",
                            description = "You can happily include a balanced snack or moderate meal with complex carbs and fresh lean proteins to hit target.",
                            type = RecommendationType.TARGET
                        )
                    )
                } else if (remain < 0) {
                    list.add(
                        RecommendationItem(
                            title = "Caloric Cap Reached",
                            description = "Your daily standard calorie target of ${calStatus.definition.rda.toInt()} kcal is fully met. To manage hunger, focus on staying hydrated and enjoying fresh fibrous baby greens.",
                            type = RecommendationType.WARNING
                        )
                    )
                }
            }

            // 3. Maximum threshold excesses (limits that should not be violated)
            val limitKeys = listOf("saturated_fat", "sodium", "sugars", "trans_fat")
            for (key in limitKeys) {
                val status = dailyNutrients.find { it.definition.key == key }
                if (status != null && status.intake > status.definition.rda) {
                    val excess = status.intake - status.definition.rda
                    val desc = when (key) {
                        "saturated_fat" -> "Saturated fat limit exceeded by ${String.format(Locale.US, "%.1f", excess)}g. For further lipids today, select healthy unsaturated fats (extra virgin olive oil, olives, nuts, and avocados)."
                        "sodium" -> "Sodium is ${excess.toInt()}mg above recommended limit. Drink 2 tall glasses of clean water to flush excess sodium. Limit processed salt and packaged foods for the rest of today."
                        "sugars" -> "Sugars are above recommended limit by ${excess.toInt()}g. Satisfy subsequent sweet impulses using raw berries, Ceylon cinnamon, or vanilla fruit tea rather than processed sugars."
                        else -> "Upper allowance limit of ${status.definition.name} exceeded. Lean on unprocessed raw ingredients for remaining meals."
                    }
                    list.add(
                        RecommendationItem(
                            title = "Limit Overload: ${status.definition.name}",
                            description = desc,
                            type = RecommendationType.WARNING
                        )
                    )
                }
            }

            // 4. Critical deficiencies (< 50% target allowance)
            val coreDeficiencies = listOf("protein", "fiber", "calcium", "iron", "vitamin_c", "potassium", "vitamin_d", "magnesium", "water")
            for (key in coreDeficiencies) {
                val status = dailyNutrients.find { it.definition.key == key }
                if (status != null && !status.definition.isMaxLimit && status.percentage < 50.0) {
                    val desc = when (key) {
                        "protein" -> "Protein intake is trailing (${status.percentage.toInt()}% met). Rebuild muscle tissue and boost satiety with low-fat Greek yogurt, turkey slices, egg whites, or organic tofu."
                        "fiber" -> "Dietary Fiber is low (${status.percentage.toInt()}% met). Add fiber-rich elements like chia seeds, flax seeds, black beans, split peas, or oatmeal to what you eat next."
                        "calcium" -> "Calcium is lacking (${status.percentage.toInt()}% met). Integrate full-calcium inputs like Greek yogurt, fortified almond milk, sesame seeds, or leafy greens."
                        "iron" -> "Iron levels are low (${status.percentage.toInt()}% met). Eat clean spinach salad, iron-rich beans, or red steak paired with lemon juice to accelerate uptake."
                        "vitamin_c" -> "Vitamin C is below benchmarks (${status.percentage.toInt()}% met). Enjoy fresh oranges, sweet red bell peppers, strawberries, or kiwi."
                        "potassium" -> "Potassium is lagging (${status.percentage.toInt()}% met). Choose a fresh medium banana, half an avocado, red gold potatoes, or rich coconut water."
                        "vitamin_d" -> "Vitamin D is deficient. Look to fortified dairy, whole egg yolks, salmon filets, or enjoy a quick 10-minute sun exposure."
                        "magnesium" -> "Magnesium is deficient. Snack on rich dark chocolate (70%+), pumpkin seeds, raw almonds, or brown quinoa."
                        "water" -> "Daily hydration is low. Sip clean water, sparkling wellness waters, or herbal tea infusions."
                        else -> "Target intake of ${status.definition.name} is trailing. Look for whole food sources containing this micronutrient."
                    }
                    list.add(
                        RecommendationItem(
                            title = "Trailing Allowance: ${status.definition.name}",
                            description = desc,
                            type = RecommendationType.TARGET
                        )
                    )
                }
            }

            // 5. Default success feedback
            if (list.isEmpty()) {
                list.add(
                    RecommendationItem(
                        title = "Nutritional Balance Perfect!",
                        description = "Your current logged intakes align wonderfully! All limits are respected and micronutrient goals are progressing cleanly.",
                        type = RecommendationType.SUCCESS
                    )
                )
            }
        }
        list
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Calories Progress Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("calories_progress_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Daily Calorie Balance",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "${caloriesIntake.toInt()}",
                                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = " / ${caloriesRda.toInt()} kcal",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                                )
                            }
                        }

                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { (caloriesPercentage / 100.0).toFloat().safeCoerce(0f, 1f) },
                                modifier = Modifier.size(54.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 6.dp
                            )
                            Text(
                                text = "${caloriesPercentage.toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { (caloriesPercentage / 100.0).toFloat().safeCoerce(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Day Nutrition Insights Report Trigger Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("day_report_button_card")
                    .clickable { showReportDialog = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Daily Health & Nutrients Report",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "View macro pie chart & clinical trend warnings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Open Report Map",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Macronutrients spread card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("macro_spread_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Macronutrient Energy Spread",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Aggregated contribution to daily caloric intake",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (macroSpread.totalCaloriesCalculated > 0) {
                        // Stacked multi-color calorie progress bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val carbsW = macroSpread.carbsPercent.toFloat()
                            val protW = macroSpread.proteinPercent.toFloat()
                            val fatW = macroSpread.fatPercent.toFloat()

                            if (carbsW > 0) Box(modifier = Modifier.weight(carbsW).fillMaxHeight().background(Color(0xFF3897F5)))
                            if (protW > 0) Box(modifier = Modifier.weight(protW).fillMaxHeight().background(Color(0xFFFF9800)))
                            if (fatW > 0) Box(modifier = Modifier.weight(fatW).fillMaxHeight().background(Color(0xFF4CAF50)))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MacroIndicator(
                                label = "Carbs",
                                grams = macroSpread.carbsGrams,
                                calories = macroSpread.carbsGrams * 4.0,
                                percent = macroSpread.carbsPercent * 100,
                                color = Color(0xFF3897F5)
                            )
                            MacroIndicator(
                                label = "Protein",
                                grams = macroSpread.proteinGrams,
                                calories = macroSpread.proteinGrams * 4.0,
                                percent = macroSpread.proteinPercent * 100,
                                color = Color(0xFFFF9800)
                            )
                            MacroIndicator(
                                label = "Fat",
                                grams = macroSpread.fatGrams,
                                calories = macroSpread.fatGrams * 9.0,
                                percent = macroSpread.fatPercent * 100,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No foods logged for today yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Daily Running Totals RDA Balance Planner
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("rda_running_balance_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Daily RDA Balance Planner",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "TARGETS",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "Review running totals compared with targets to decide your subsequent entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        displayNutrients.forEach { status ->
                            val def = status.definition
                            val isLimit = def.isMaxLimit
                            val barColor = when {
                                isLimit -> if (status.intake > def.rda) Color(0xFFE53E3E) else Color(0xFF319795)
                                status.percentage >= 100.0 -> Color(0xFF38A169)
                                status.percentage >= 50.0 -> Color(0xFFDD6B20)
                                else -> Color(0xFFE53E3E)
                            }
                            val progressFactor = (status.percentage / 100.0).toFloat().safeCoerce(0f, 1f)

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = def.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isLimit) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = "UPPER LIMIT",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${status.intake.toInt()} / ${def.rda.toInt()} ${def.unit} (${status.percentage.toInt()}%)",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                LinearProgressIndicator(
                                    progress = { progressFactor },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                val balanceMessage = remember(status.intake, def.rda, isLimit) {
                                    if (isLimit) {
                                        if (status.intake > def.rda) {
                                            "Exceeded limit by ${(status.intake - def.rda).toInt()} ${def.unit}!"
                                        } else {
                                            "${(def.rda - status.intake).toInt()} ${def.unit} remaining safe limit allowance"
                                        }
                                    } else {
                                        if (status.percentage >= 100.0) {
                                            "Target goal fully met!"
                                        } else {
                                            "${(def.rda - status.intake).toInt()} ${def.unit} remaining to meet daily RDA"
                                        }
                                    }
                                }

                                Text(
                                    text = balanceMessage,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (isLimit && status.intake > def.rda) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { showAllNutrients = !showAllNutrients },
                        modifier = Modifier.fillMaxWidth().testTag("toggle_rda_nutrients_button"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = if (showAllNutrients) "Collapse back to Top Nutrients" else "Show All 41 RDA Baselines",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Active Decision Planner card based on RDA Status
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("next_decision_guide_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Planner",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "What to Drink or Eat Next?",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = "Dynamic advice generated in real-time based on your remaining targets and exceeded limits:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        recommendations.forEach { recommendation ->
                            val (bgColor, borderColor, itemIcon, iconColor, textColor) = when (recommendation.type) {
                                RecommendationType.WARNING -> listOf(Color(0xFFFFF5F5), Color(0xFFFEB2B2), Icons.Default.Warning, Color(0xFFE53E3E), Color(0xFF742A2A))
                                RecommendationType.TARGET -> listOf(Color(0xFFFFFAF0), Color(0xFFFEEBC8), Icons.Default.Add, Color(0xFFDD6B20), Color(0xFF7B341E))
                                RecommendationType.SUCCESS -> listOf(Color(0xFFF0FFF4), Color(0xFFC6F6D5), Icons.Default.CheckCircle, Color(0xFF38A169), Color(0xFF22543D))
                                RecommendationType.INFO -> listOf(Color(0xFFEBF8FF), Color(0xFFBEE3F8), Icons.Default.Info, Color(0xFF3182CE), Color(0xFF2A4365))
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = bgColor as Color),
                                border = BorderStroke(1.dp, borderColor as Color)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = itemIcon as androidx.compose.ui.graphics.vector.ImageVector,
                                        contentDescription = null,
                                        tint = iconColor as Color,
                                        modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = recommendation.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = textColor as Color
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = recommendation.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = textColor.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Deficiency & Exceeding Warnings List
        item {
            Column {
                Text(
                    text = "Daily Deficiencies & Risk Warners",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (warnings.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD4EDDA).copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, Color(0xFFC3E6CB))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF28A745))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Nutritional Profile Perfect!",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF155724)
                                )
                                Text(
                                    "No severe safety alerts or exceeded limits logged.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF155724).copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        warnings.forEach { warning ->
                            val alertColor = if (warning.isExceededLimit) Color(0xFFE02424) else Color(0xFFF0932B)
                            val alertBg = if (warning.isExceededLimit) Color(0xFFFDE8E8) else Color(0xFFFEF3C7)
                            val alertBorder = if (warning.isExceededLimit) Color(0xFFFBD5D5) else Color(0xFFFDE68A)
                            val textHex = if (warning.isExceededLimit) Color(0xFF9B1C1C) else Color(0xFF92400E)

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = alertBg),
                                border = BorderStroke(1.dp, alertBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = alertColor, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = warning.name + " alerts",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = textHex
                                        )
                                        Text(
                                            text = warning.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = textHex.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Long term 7 day trends chart
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Historical Calorie Trends",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Total daily energy totals over the past 7 days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        trends.forEach { trend ->
                            val isActive = trend.dateString == currentDate
                            val barHeightRatio = (trend.calories / 2500.0).safeCoerce(0.05, 1.0).toFloat()

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (trend.calories > 0) "${trend.calories.toInt()}" else "0",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .fillMaxHeight(barHeightRatio)
                                        .clip(RoundedCornerShape(t1 = 4.dp, t2 = 4.dp, b1 = 0.dp, b2 = 0.dp))
                                        .background(
                                            if (isActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = trend.displayDate,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReportDialog) {
        DayReportDialog(
            currentDate = currentDate,
            dailyNutrients = dailyNutrients,
            macroSpread = macroSpread,
            onDismiss = { showReportDialog = false }
        )
    }
}

// RoundedCornerShape extensions to avoid compilation issues in old Compose versions
private fun RoundedCornerShape(t1: androidx.compose.ui.unit.Dp, t2: androidx.compose.ui.unit.Dp, b1: androidx.compose.ui.unit.Dp, b2: androidx.compose.ui.unit.Dp): RoundedCornerShape {
    return RoundedCornerShape(topStart = t1, topEnd = t2, bottomStart = b1, bottomEnd = b2)
}

@Composable
fun MacroIndicator(label: String, grams: Double, calories: Double, percent: Double, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = "${grams.toInt()}g (${percent.toInt()}%)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp)
        )
        Text(
            text = "${calories.toInt()} kcal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
fun JournalTab(viewModel: NutritionViewModel) {
    val entries by viewModel.currentDateEntries.collectAsState()
    val currentDate by viewModel.currentDate.collectAsState()
    val mealTypes = listOf("Breakfast", "Lunch", "Dinner", "Snack")
    var editingEntry by remember { mutableStateOf<FoodLogEntry?>(null) }
    var showCopyDialog by remember { mutableStateOf(false) }

    val datesRangeList = remember {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()

        val list = mutableListOf<Pair<String, String>>() // Pair<DateString, DayName>
        val dayNameFormat = SimpleDateFormat("E d", Locale.US) // "Mon 25"

        for (i in 0 until 7) {
            list.add(Pair(format.format(cal.time), dayNameFormat.format(cal.time)))
            cal.add(Calendar.DATE, -1)
        }
        list.reverse()
        list
    }

    if (showCopyDialog) {
        CopyDayLogDialog(
            currentDate = currentDate,
            datesList = datesRangeList,
            onDismiss = { showCopyDialog = false },
            onCopy = { source, target ->
                viewModel.copyDayLog(source, target)
            }
        )
    }

    if (entries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Journal is Empty Today",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Log Food' below to input breakfast, lunch, or dinner, or clone/copy another day's complete journal log as a starting baseline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showCopyDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.testTag("empty_state_copy_day_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Copy from another day",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy from another day")
                    }
                }
            }

            ImportExportSection(viewModel = viewModel)
            Spacer(modifier = Modifier.height(32.dp))
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Today's Log Journal",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showCopyDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.testTag("copy_day_log_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy Day Log",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Day", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = { viewModel.clearAllForDate(currentDate) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.testTag("clear_all_for_day_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear All Entries",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear Day", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            mealTypes.forEach { mealType ->
                val mealEntries = entries.filter { it.mealType == mealType }
                if (mealEntries.isNotEmpty()) {
                    item {
                        Column {
                            // Meal Sub-heading
                            val mealCalories = mealEntries.sumOf {
                                (it.nutrients["calories"] ?: 0.0) * it.quantity
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mealType,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${mealCalories.toInt()} kcal",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                mealEntries.forEach { entry ->
                                    var expanded by remember { mutableStateOf(false) }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("food_entry_card_${entry.id}")
                                            .clickable { expanded = !expanded },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = entry.foodName,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "1 portion/serving",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "${((entry.nutrients["calories"] ?: 0.0) * entry.quantity).toInt()} kcal",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    IconButton(
                                                        onClick = { editingEntry = entry },
                                                        modifier = Modifier.testTag("edit_food_button_${entry.id}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit entry",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    IconButton(
                                                        onClick = { viewModel.deleteLog(entry) },
                                                        modifier = Modifier.testTag("delete_food_button_${entry.id}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete entry",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Extended nutrient view on item tap
                                            AnimatedVisibility(
                                                visible = expanded,
                                                enter = fadeIn() + slideInVertically(),
                                                exit = fadeOut() + shrinkVertically()
                                            ) {
                                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                                    Text(
                                                        "Major Nutrients Breakdowns:",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    NutrientMiniGrid(entry = entry)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                ImportExportSection(viewModel = viewModel)
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    editingEntry?.let { entry ->
        EditFoodDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onConfirmManual = { updated ->
                viewModel.updateFoodLogEntry(updated)
                editingEntry = null
            },
            onConfirmReanalyze = { id, text, meal, date ->
                viewModel.reanalyzeAndReplaceEntry(id, text, meal, date)
                editingEntry = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NutrientMiniGrid(entry: FoodLogEntry) {
    val itemsToShow = listOf(
        Pair("carbohydrates", "Carbs"),
        Pair("protein", "Protein"),
        Pair("fat", "Fat"),
        Pair("fiber", "Fiber"),
        Pair("saturated_fat", "Sat Fat"),
        Pair("sodium", "Sodium"),
        Pair("vitamin_c", "Vit C"),
        Pair("calcium", "Calcium")
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        maxItemsInEachRow = 4
    ) {
        itemsToShow.forEach { (key, label) ->
            val value = entry.nutrients[key] ?: 0.0
            val def = Nutrients.getByKey(key)
            val unit = def?.unit ?: "g"

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(vertical = 4.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${value.formatInt()} $unit",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun CopyDayLogDialog(
    currentDate: String,
    datesList: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onCopy: (source: String, target: String) -> Unit
) {
    var copyDirectionIsToTarget by remember { mutableStateOf(false) } // Default: Pull in logs to current date
    var selectedOtherDate by remember { mutableStateOf(datesList.firstOrNull { it.first != currentDate }?.first ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Copy Day Journal Log",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Replicate entire breakfast, lunch, dinner, or snack lists across different dates to keep logging fast and easy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Direction selection cards
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Copy Operation Mode:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Option 1: Copy from other day to current day
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { copyDirectionIsToTarget = false }
                                .testTag("direction_copy_in"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (!copyDirectionIsToTarget) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (!copyDirectionIsToTarget) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Pull In Log",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (!copyDirectionIsToTarget) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Another Day ➔ Today",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!copyDirectionIsToTarget) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Option 2: Copy current day to another day
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { copyDirectionIsToTarget = true }
                                .testTag("direction_copy_out"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (copyDirectionIsToTarget) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (copyDirectionIsToTarget) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Push Out Log",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (copyDirectionIsToTarget) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Today ➔ Another Day",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (copyDirectionIsToTarget) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Selected Target/Source Date List
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (copyDirectionIsToTarget) "Select Destination Date:" else "Select Source Date:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        datesList.forEach { (dateStr, label) ->
                            if (dateStr != currentDate) {
                                val isSelected = selectedOtherDate == dateStr
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer 
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                        )
                                        .clickable { selectedOtherDate = dateStr }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary 
                                                else Color.Transparent, 
                                                CircleShape
                                            )
                                            .border(
                                                width = 1.5.dp, 
                                                color = if (isSelected) MaterialTheme.colorScheme.primary 
                                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), 
                                                shape = CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "$label ($dateStr)",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Footer Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("dismiss_copy_log_dialog")
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (selectedOtherDate.isNotEmpty()) {
                                if (copyDirectionIsToTarget) {
                                    onCopy(currentDate, selectedOtherDate)
                                } else {
                                    onCopy(selectedOtherDate, currentDate)
                                }
                                onDismiss()
                            }
                        },
                        enabled = selectedOtherDate.isNotEmpty(),
                        modifier = Modifier.testTag("confirm_copy_log_button")
                    ) {
                        Text("Copy Entries")
                    }
                }
            }
        }
    }
}

private fun Double.format(digits: Int): String {
    if (!this.isFinite()) return "0"
    return String.format(Locale.US, "%.${digits}f", this)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PeriodicReportsTab(viewModel: NutritionViewModel) {
    val allLogEntries by viewModel.allLogEntries.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var selectedDays by remember { mutableStateOf(7) }
    var selectedLabel by remember { mutableStateOf("Weekly") }
    
    val report = remember(allLogEntries, selectedDays, selectedLabel) {
        viewModel.getPeriodicReport(selectedDays, selectedLabel)
    }

    var pendingHtmlReportText by remember { mutableStateOf<String?>(null) }

    val htmlReportLauncher = rememberLauncherForActivityResult(
        contract = SafeCreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            val htmlToWrite = pendingHtmlReportText
            if (htmlToWrite != null) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(htmlToWrite.toByteArray(Charsets.UTF_8))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pendingHtmlReportText = null
                    }
                }
            } else {
                // fallback if callback happens but state was lost or not set yet
                val htmlString = viewModel.generateHtmlReport(report)
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(htmlString.toByteArray(Charsets.UTF_8))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Period switcher headers
        Text(
            text = "Periodic Health Reports",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Analyze your macro and micronutrient patterns across long-term durations to pinpoint consistent deficiencies or cumulative excesses.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Custom segmented pill selectors
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(7, "Weekly", "Weekly"),
                    Triple(30, "Monthly", "Monthly"),
                    Triple(365, "Annual", "Annual")
                ).forEach { (days, label, dispText) ->
                    val isSelected = selectedDays == days
                    val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(containerColor)
                            .clickable {
                                selectedDays = days
                                selectedLabel = label
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dispText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = contentColor
                        )
                    }
                }
            }
        }
        
        if (report.daysWithLogs == 0) {
            // High quality empty state
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .testTag("reports_empty_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "No Logs Found",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No Nutrition Logs Prepared",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "We couldn't detect any logged items in the selected past $selectedDays days. Change the date range or log some food entries on the 'Log Journal' tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // 2. Overview Stats Card
            Card(
                modifier = Modifier.fillMaxWidth().testTag("periodic_report_summary_card"),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$selectedLabel Assessment Overview",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Values are averaged across ${report.daysWithLogs} day(s) that had logged food items in your journal, exposing your actual dietary behaviors.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    pendingHtmlReportText = viewModel.generateHtmlReport(report)
                                    htmlReportLauncher.launch("nutriscribe-report-$selectedLabel.html")
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, "System File Picker not available. Please use 'Copy HTML to Clipboard' instead.", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open File Picker: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("button_export_html_report"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export HTML Report",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export Desktop HTML", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    val htmlString = viewModel.generateHtmlReport(report)
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("NutriScribe HTML Report", htmlString)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "HTML Report copied to clipboard!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cloud / System Clipboard error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.testTag("button_copy_html_report"),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy HTML",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy HTML to Clipboard", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${report.avgCaloriesPerDay.toInt()} kcal",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "daily average",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 3. Macronutrient Energy Pie Chart
            Card(
                modifier = Modifier.fillMaxWidth().testTag("periodic_report_macro_pie_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Average Energy Balance Spread",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = "Weekly/Monthly ratio of carbs, protein, and fat based on calories",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val carbsW = report.avgMacros.carbsPercent.toFloat().safeCoerce(0f, 1f)
                    val protW = report.avgMacros.proteinPercent.toFloat().safeCoerce(0f, 1f)
                    val fatW = report.avgMacros.fatPercent.toFloat().safeCoerce(0f, 1f)
                    val totalProp = carbsW + protW + fatW

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(140.dp)
                    ) {
                        Canvas(modifier = Modifier.size(130.dp)) {
                            if (totalProp > 0f) {
                                val carbsAngle = (carbsW / totalProp) * 360f
                                val protAngle = (protW / totalProp) * 360f
                                val fatAngle = (fatW / totalProp) * 360f

                                var startAngle = -90f

                                // Carbohydrates segment (Blue)
                                drawArc(
                                    color = Color(0xFF3897F5),
                                    startAngle = startAngle,
                                    sweepAngle = carbsAngle,
                                    useCenter = true
                                )
                                startAngle += carbsAngle

                                // Protein segment (Orange)
                                drawArc(
                                    color = Color(0xFFFF9800),
                                    startAngle = startAngle,
                                    sweepAngle = protAngle,
                                    useCenter = true
                                )
                                startAngle += protAngle

                                // Total Fat segment (Green)
                                drawArc(
                                    color = Color(0xFF4CAF50),
                                    startAngle = startAngle,
                                    sweepAngle = fatAngle,
                                    useCenter = true
                                )
                            } else {
                                drawCircle(
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    radius = size.minDimension / 2f
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MacroIndicator(
                            label = "Carbs",
                            grams = report.avgMacros.carbsGrams,
                            calories = report.avgMacros.carbsGrams * 4.0,
                            percent = report.avgMacros.carbsPercent * 100,
                            color = Color(0xFF3897F5)
                        )
                        MacroIndicator(
                            label = "Protein",
                            grams = report.avgMacros.proteinGrams,
                            calories = report.avgMacros.proteinGrams * 4.0,
                            percent = report.avgMacros.proteinPercent * 100,
                            color = Color(0xFFFF9800)
                        )
                        MacroIndicator(
                            label = "Fat",
                            grams = report.avgMacros.fatGrams,
                            calories = report.avgMacros.fatGrams * 9.0,
                            percent = report.avgMacros.fatPercent * 100,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
            
            // 4. Trend Diagnostic Warnings
            Card(
                modifier = Modifier.fillMaxWidth().testTag("periodic_report_diagnostics_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "$selectedLabel Diagnostics & Insights",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Exposing recurring dietary risks or deficiencies observed over the past $selectedDays days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (report.insights.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Perfect Status",
                                tint = Color(0xFF2E7D32)
                            )
                            Text(
                                text = "Perfect balance: Your daily averages are completely compliant with all tracked nutritional recommendation benchmarks!",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF2E7D32)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            report.insights.forEach { insight ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (insight.isExcess) Color(0xFFFFEBEE) else Color(0xFFFFF3E0)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${insight.name} -> ${if (insight.isExcess) "Excess Warning" else "Deficit Warning"}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (insight.isExcess) Color(0xFFC62828) else Color(0xFFEF6C00)
                                        )
                                        Text(
                                            text = "${insight.intakeString}/day vs target ${insight.rdaString}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = "• Short-term Effect:\n  ${insight.shortTermRisk}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "• Long-term Cumulative Risk:\n  ${insight.longTermRisk}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // 5. Nutrient List
            var showAllNutrients by remember { mutableStateOf(false) }
            var filterWarningsOnly by remember { mutableStateOf(false) }

            val primaryKeys = remember {
                listOf(
                    "calories", "protein", "carbohydrates", "fat", "fiber", "water", 
                    "sodium", "sugars", "saturated_fat", "trans_fat", "cholesterol",
                    "vitamin_c", "vitamin_d", "calcium", "iron", "potassium", "vitamin_a", "vitamin_b12"
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nutrient Daily Averages",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { filterWarningsOnly = !filterWarningsOnly }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(2.dp)
                                .background(if (filterWarningsOnly) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        )
                        Text(
                            text = "Warnings only",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                val listedNutrients = remember(report.averageNutrients, filterWarningsOnly) {
                    if (filterWarningsOnly) {
                        report.averageNutrients.filter { 
                            it.status == StatusColor.RED || (it.definition.isMaxLimit && it.intake > it.definition.rda)
                        }
                    } else {
                        report.averageNutrients
                    }
                }

                val itemsToDisplay = remember(listedNutrients, showAllNutrients) {
                    if (showAllNutrients) {
                        listedNutrients
                    } else {
                        listedNutrients.filter { primaryKeys.contains(it.definition.key) }
                    }
                }

                if (itemsToDisplay.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                    ) {
                        Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No warnings in evaluated nutrients!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsToDisplay.forEach { status ->
                            val unitStr = status.definition.unit
                            val formattedIntake = if (status.definition.key == "calories") status.intake.toInt().toString() else status.intake.format(1)
                            val formattedRda = status.definition.rda.toInt().toString()

                            val colorStyle = when (status.status) {
                                StatusColor.GREEN -> Color(0xFF2E7D32)
                                StatusColor.YELLOW -> Color(0xFFEF6C00)
                                StatusColor.RED -> Color(0xFFC62828)
                            }

                            val backgroundStyle = when (status.status) {
                                StatusColor.GREEN -> Color(0xFFE8F5E9)
                                StatusColor.YELLOW -> Color(0xFFFFF3E0)
                                StatusColor.RED -> Color(0xFFFFEBEE)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("report_nutrient_${status.definition.key}"),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = status.definition.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = status.definition.group.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "$formattedIntake / $formattedRda $unitStr",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(backgroundStyle)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (status.definition.isMaxLimit) {
                                                        if (status.intake > status.definition.rda) "Over Limit" else "Within Limit"
                                                    } else {
                                                        "${status.percentage.toInt()}%"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = colorStyle
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    val pct = (status.percentage / 100.0).toFloat().safeCoerce(0f, 1f)
                                    val barColor = when (status.status) {
                                        StatusColor.GREEN -> MaterialTheme.colorScheme.primary
                                        StatusColor.YELLOW -> Color(0xFFFF9800)
                                        StatusColor.RED -> MaterialTheme.colorScheme.error
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(pct)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(barColor)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!filterWarningsOnly) {
                        Button(
                            onClick = { showAllNutrients = !showAllNutrients },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .testTag("report_toggle_all_nutrients_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(if (showAllNutrients) "Collapse to Key Minerals & Vitamins" else "Display All 41 Evaluated Nutrients")
                        }
                    }
                }
            }
        }
    }
}

private fun Double.formatInt(): String {
    if (!this.isFinite()) return "0"
    return if (this % 1.0 == 0.0) this.toInt().toString() else String.format(Locale.US, "%.1f", this)
}

@Composable
fun NutrientsListTab(viewModel: NutritionViewModel) {
    val dailyNutrients by viewModel.dailyNutrients.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<NutrientGroup?>(null) }

    var selectedNutrientStatus by remember { mutableStateOf<NutrientStatus?>(null) }

    val filteredList = dailyNutrients.filter { status ->
        val matchQuery = status.definition.name.contains(searchQuery, ignoreCase = true)
        val matchGroup = selectedGroup == null || status.definition.group == selectedGroup
        matchQuery && matchGroup
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search 41 tracked nutrients...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Category filter chips
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val groupsList = listOf(null) + NutrientGroup.values().toList()
            groupsList.forEach { grp ->
                val label = grp?.displayName ?: "All"
                val isSelected = selectedGroup == grp

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { selectedGroup = grp }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Nutrient List Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredList) { status ->
                val isLimit = status.definition.isMaxLimit
                val colorHex = when (status.status) {
                    StatusColor.GREEN -> Color(0xFF2E7D32)
                    StatusColor.YELLOW -> Color(0xFFEF6C00)
                    StatusColor.RED -> Color(0xFFC62828)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nutrient_row_${status.definition.key}")
                        .clickable { selectedNutrientStatus = status },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, colorHex.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = status.definition.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "info",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "${status.intake.formatInt()} ${status.definition.unit}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = colorHex
                                )
                                Text(
                                    text = if (isLimit) "Limit: ${status.definition.rda.toInt()}" else "Goal: ${status.definition.rda.toInt()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colorHex.copy(alpha = 0.12f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${status.percentage.toInt()}%",
                                    color = colorHex,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Bottom Sheet modal on grid click
    if (selectedNutrientStatus != null) {
        val currStatus = selectedNutrientStatus!!
        NutrientDetailBottomSheet(
            status = currStatus,
            onDismiss = { selectedNutrientStatus = null },
            onSaveTarget = { viewModel.updateCustomRda(currStatus.definition.key, it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutrientDetailBottomSheet(
    status: NutrientStatus,
    onDismiss: () -> Unit,
    onSaveTarget: (Double) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val def = status.definition
    val isLimit = def.isMaxLimit
    val colorHex = when (status.status) {
        StatusColor.GREEN -> Color(0xFF2E7D32)
        StatusColor.YELLOW -> Color(0xFFEF6C00)
        StatusColor.RED -> Color(0xFFC62828)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    .padding(vertical = 8.dp)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = def.group.displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = def.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // RDA analysis box
            Card(
                colors = CardDefaults.cardColors(containerColor = colorHex.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, colorHex.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                "Your Daily Intake",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${status.intake.formatInt()} ${def.unit}",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = colorHex
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (isLimit) "Recommended Limit" else "Target Allowance (RDA)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${def.rda.toInt()} ${def.unit}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { (status.percentage / 100).toFloat().safeCoerce(0f, 1f) },
                        color = colorHex,
                        trackColor = colorHex.copy(alpha = 0.1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Achieved ${status.percentage.toInt()}% of the standard baseline daily reference constraint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Biomedical Role & Importance",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = def.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Customize Daily Target / Limit",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tailor this value to your personalized health goals (e.g., lowering Sodium target for a zero/low salt diet, adjusting Carbohydrates range, or raising Potassium target for KCl replacements).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            var targetInputValue by remember(def.rda) { mutableStateOf(def.rda.formatInt()) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = targetInputValue,
                    onValueChange = { targetInputValue = it },
                    label = { Text("Daily target (${def.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("custom_target_input"),
                    singleLine = true
                )

                Button(
                    onClick = {
                        val parsedVal = targetInputValue.toDoubleOrNull()
                        if (parsedVal != null && parsedVal >= 0.0) {
                            onSaveTarget(parsedVal)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.testTag("save_custom_target_button"),
                    enabled = targetInputValue.toDoubleOrNull() != null
                ) {
                    Text("Apply")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dismiss Details")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onConfirmBulk: (String) -> Unit
) {
    var isMultiDayMode by remember { mutableStateOf(false) }
    var foodDescription by remember { mutableStateOf("") }
    val mealTypes = listOf("Breakfast", "Lunch", "Dinner", "Snack")
    var selectedMeal by remember { mutableStateOf("Lunch") }
    var expandedMealDropdown by remember { mutableStateOf(false) }

    var bulkDescription by remember { mutableStateOf("") }
    var logLengthDays by remember { mutableStateOf(3f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Log Food via AI Engine",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Scribe details using our precise AI parser to calculate all 41 vitamins & nutrients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Tab Switcher between Single Meal and Multi-day Logging
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isMultiDayMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { isMultiDayMode = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Single Meal",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (!isMultiDayMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isMultiDayMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { isMultiDayMode = true }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Multi-Day Log",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isMultiDayMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isMultiDayMode) {
                    Text(
                        text = "Specify multiple days of meals. Relative time phrases (e.g. 'Yesterday Breakfast', 'May 23 Lunch') will automatically map to actual calendar dates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = bulkDescription,
                        onValueChange = { bulkDescription = it },
                        label = { Text("Food Journal (Multiple Days)") },
                        placeholder = { Text("e.g.\nYesterday Breakfast: eggs and bacon\nYesterday Lunch: grilled salmon\nToday Lunch: turkey sandwich") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .testTag("bulk_food_input_field"),
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Suggested Log Span: ${logLengthDays.toInt()} Days",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = logLengthDays,
                        onValueChange = { logLengthDays = it },
                        valueRange = 1f..14f,
                        steps = 12,
                        modifier = Modifier.fillMaxWidth().testTag("log_length_slider")
                    )

                } else {
                    // Description Input
                    OutlinedTextField(
                        value = foodDescription,
                        onValueChange = { foodDescription = it },
                        label = { Text("What did you eat?") },
                        placeholder = { Text("e.g., 1 medium banana and a cup of yogurt") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag("food_input_field"),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Segmented Meal Selection
                    Text(
                        text = "Meal Category",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        mealTypes.forEach { type ->
                            val isSelected = selectedMeal == type
                            val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

                            Surface(
                                onClick = { selectedMeal = type },
                                modifier = Modifier.weight(1f).height(38.dp).testTag("meal_chip_$type"),
                                shape = RoundedCornerShape(8.dp),
                                color = containerColor,
                                contentColor = contentColor,
                                border = if (isSelected) BorderStroke(1.dp, borderColor) else null
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = type,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isMultiDayMode) {
                                if (bulkDescription.isNotBlank()) {
                                    onConfirmBulk(bulkDescription)
                                }
                            } else {
                                if (foodDescription.isNotBlank()) {
                                    onConfirm(foodDescription, selectedMeal)
                                }
                            }
                        },
                        enabled = if (isMultiDayMode) bulkDescription.isNotBlank() else foodDescription.isNotBlank(),
                        modifier = Modifier.testTag("submit_food_button")
                    ) {
                        Text(if (isMultiDayMode) "Analyze & Import" else "Analyze & Log")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditFoodDialog(
    entry: FoodLogEntry,
    onDismiss: () -> Unit,
    onConfirmManual: (FoodLogEntry) -> Unit,
    onConfirmReanalyze: (Int, String, String, String) -> Unit
) {
    var foodName by remember { mutableStateOf(entry.foodName) }
    val mealTypes = listOf("Breakfast", "Lunch", "Dinner", "Snack")
    var selectedMeal by remember { mutableStateOf(entry.mealType) }
    var expandedMealDropdown by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf(entry.quantity) }

    // Direct nutrients
    var calories by remember { mutableStateOf(entry.nutrients["calories"]?.formatInt() ?: "0") }
    var protein by remember { mutableStateOf(entry.nutrients["protein"]?.formatInt() ?: "0") }
    var carbs by remember { mutableStateOf(entry.nutrients["carbohydrates"]?.formatInt() ?: "0") }
    var fat by remember { mutableStateOf(entry.nutrients["fat"]?.formatInt() ?: "0") }
    var fiber by remember { mutableStateOf(entry.nutrients["fiber"]?.formatInt() ?: "0") }
    var water by remember { mutableStateOf(entry.nutrients["water"]?.formatInt() ?: "0") }
    var sodium by remember { mutableStateOf(entry.nutrients["sodium"]?.formatInt() ?: "0") }
    var sugars by remember { mutableStateOf(entry.nutrients["sugars"]?.formatInt() ?: "0") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Edit Log Entry",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Update the details, customize portion size, or manually edit key metrics. Alternatively, edit the description and select 'Re-Analyze with AI' to re-calculate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Food Description input
                OutlinedTextField(
                    value = foodName,
                    onValueChange = { foodName = it },
                    label = { Text("Food Description (AI Source)") },
                    modifier = Modifier.fillMaxWidth().testTag("edit_food_name_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented Meal Selection
                Text(
                    text = "Meal Category",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mealTypes.forEach { type ->
                        val isSelected = selectedMeal == type
                        val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

                        Surface(
                            onClick = { selectedMeal = type },
                            modifier = Modifier.weight(1f).height(38.dp).testTag("edit_meal_chip_$type"),
                            shape = RoundedCornerShape(8.dp),
                            color = containerColor,
                            contentColor = contentColor,
                            border = if (isSelected) BorderStroke(1.dp, borderColor) else null
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                              ) {
                                Text(
                                    text = type,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Portion Size Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Portion Size / Quantity",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { if (quantity > 0.25) quantity -= 0.25 },
                            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).testTag("edit_qty_dec")
                        ) {
                            Text(
                                "-",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${String.format(Locale.US, "%.2f", quantity)}x",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp).testTag("edit_qty_value")
                        )
                        IconButton(
                            onClick = { quantity += 0.25 },
                            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).testTag("edit_qty_inc")
                        ) {
                            Text(
                                "+",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Key nutrients direct edit fields
                Text(
                    text = "Manual Nutrient Overrides",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Render fields in nice Grid form
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = calories,
                            onValueChange = { calories = it },
                            label = { Text("Calories (kcal)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_calories_input"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = protein,
                            onValueChange = { protein = it },
                            label = { Text("Protein (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_protein_input"),
                            singleLine = true
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = carbs,
                            onValueChange = { carbs = it },
                            label = { Text("Carbs (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_carbs_input"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = fat,
                            onValueChange = { fat = it },
                            label = { Text("Fat (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_fat_input"),
                            singleLine = true
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fiber,
                            onValueChange = { fiber = it },
                            label = { Text("Fiber (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_fiber_input"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = water,
                            onValueChange = { water = it },
                            label = { Text("Water (ml)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_water_input"),
                            singleLine = true
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sodium,
                            onValueChange = { sodium = it },
                            label = { Text("Sodium (mg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_sodium_input"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sugars,
                            onValueChange = { sugars = it },
                            label = { Text("Sugars (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("edit_sugars_input"),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save or re-analyze triggers
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (foodName.isNotBlank()) {
                                onConfirmReanalyze(entry.id, foodName, selectedMeal, entry.date)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("edit_reanalyze_button"),
                        enabled = foodName.isNotBlank() && foodName != entry.foodName,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Re-Analyze prompt with AI")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (foodName.isNotBlank()) {
                                    val updatedNutrients = entry.nutrients.toMutableMap().apply {
                                        put("calories", calories.toDoubleOrNull() ?: 0.0)
                                        put("protein", protein.toDoubleOrNull() ?: 0.0)
                                        put("carbohydrates", carbs.toDoubleOrNull() ?: 0.0)
                                        put("fat", fat.toDoubleOrNull() ?: 0.0)
                                        put("fiber", fiber.toDoubleOrNull() ?: 0.0)
                                        put("water", water.toDoubleOrNull() ?: 0.0)
                                        put("sodium", sodium.toDoubleOrNull() ?: 0.0)
                                        put("sugars", sugars.toDoubleOrNull() ?: 0.0)
                                    }
                                    val updatedEntry = entry.copy(
                                        foodName = foodName,
                                        mealType = selectedMeal,
                                        quantity = quantity,
                                        nutrients = updatedNutrients
                                    )
                                    onConfirmManual(updatedEntry)
                                }
                            },
                            enabled = foodName.isNotBlank(),
                            modifier = Modifier.testTag("save_edit_button")
                        ) {
                            Text("Save Changes")
                        }
                    }
                }
            }
        }
    }
}

private fun Double.safeCoerce(minimumValue: Double, maximumValue: Float): Double {
    if (this.isNaN()) return minimumValue
    return this.coerceIn(minimumValue, maximumValue.toDouble())
}

data class DiagnosticInsight(
    val name: String,
    val intakeString: String,
    val rdaString: String,
    val isExcess: Boolean,
    val shortTermRisk: String,
    val longTermRisk: String
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DayReportDialog(
    currentDate: String,
    dailyNutrients: List<NutrientStatus>,
    macroSpread: MacroSpreadRatio,
    onDismiss: () -> Unit
) {
    var filterWarningsOnly by remember { mutableStateOf(false) }

    val insights = remember(dailyNutrients) {
        getInsights(dailyNutrients)
    }

    val displayDateFormatted = remember(currentDate) {
        try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val formatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
            parser.parse(currentDate)?.let { formatter.format(it) } ?: currentDate
        } catch (e: Exception) {
            currentDate
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily Nutrient Report",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = displayDateFormatted,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_report_dialog_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close Report")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Pie Chart of Macro Spread
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("day_report_macro_pie_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Macronutrient Energy Spread Pie",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                text = "Proportional contribution based on logged calories",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Start)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Pie Chart Draw
                            val carbsW = macroSpread.carbsPercent.toFloat().safeCoerce(0f, 1f)
                            val protW = macroSpread.proteinPercent.toFloat().safeCoerce(0f, 1f)
                            val fatW = macroSpread.fatPercent.toFloat().safeCoerce(0f, 1f)
                            val totalProp = carbsW + protW + fatW

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(140.dp)
                            ) {
                                Canvas(modifier = Modifier.size(130.dp)) {
                                    if (totalProp > 0f) {
                                        val carbsAngle = (carbsW / totalProp) * 360f
                                        val protAngle = (protW / totalProp) * 360f
                                        val fatAngle = (fatW / totalProp) * 360f

                                        var startAngle = -90f

                                        // Carbohydrates segment (Blue)
                                        drawArc(
                                            color = Color(0xFF3897F5),
                                            startAngle = startAngle,
                                            sweepAngle = carbsAngle,
                                            useCenter = true
                                        )
                                        startAngle += carbsAngle

                                        // Protein segment (Orange)
                                        drawArc(
                                            color = Color(0xFFFF9800),
                                            startAngle = startAngle,
                                            sweepAngle = protAngle,
                                            useCenter = true
                                        )
                                        startAngle += protAngle

                                        // Total Fat segment (Green)
                                        drawArc(
                                            color = Color(0xFF4CAF50),
                                            startAngle = startAngle,
                                            sweepAngle = fatAngle,
                                            useCenter = true
                                        )
                                    } else {
                                        // Empty neutral state circle
                                        drawCircle(
                                            color = Color.LightGray.copy(alpha = 0.5f),
                                            radius = size.minDimension / 2f
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Custom Legend with metrics
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MacroIndicator(
                                    label = "Carbs",
                                    grams = macroSpread.carbsGrams,
                                    calories = macroSpread.carbsGrams * 4.0,
                                    percent = macroSpread.carbsPercent * 100,
                                    color = Color(0xFF3897F5)
                                )
                                MacroIndicator(
                                    label = "Protein",
                                    grams = macroSpread.proteinGrams,
                                    calories = macroSpread.proteinGrams * 4.0,
                                    percent = macroSpread.proteinPercent * 100,
                                    color = Color(0xFFFF9800)
                                )
                                MacroIndicator(
                                    label = "Fat",
                                    grams = macroSpread.fatGrams,
                                    calories = macroSpread.fatGrams * 9.0,
                                    percent = macroSpread.fatPercent * 100,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }

                    // 2. Short & Long-Term Trend Risks
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("day_report_trend_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Trend Health Warnings & Risks",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Evaluating short and long-term diagnostic alerts based on intake deviations",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            if (insights.isEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Perfect Status",
                                        tint = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = "Excellent balance: All evaluated primary nutrients are within healthy ranges!",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    insights.forEach { insight ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (insight.isExcess) Color(0xFFFFEBEE) else Color(0xFFFFF3E0)
                                                )
                                                .padding(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${insight.name} -> ${if (insight.isExcess) "Excess Warning" else "Deficit Warning"}",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (insight.isExcess) Color(0xFFC62828) else Color(0xFFEF6C00)
                                                )
                                                Text(
                                                    text = "${insight.intakeString} vs Target ${insight.rdaString}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = "• Short-term Effect:\n  ${insight.shortTermRisk}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = "• Long-term Risk:\n  ${insight.longTermRisk}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Complete List of All Nutrients (Colorized by Consumption)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "All 41 Evaluated Nutrients",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { filterWarningsOnly = !filterWarningsOnly }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                        .background(if (filterWarningsOnly) MaterialTheme.colorScheme.primary else Color.Transparent)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Alerts Only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val listedNutrients = remember(dailyNutrients, filterWarningsOnly) {
                            if (filterWarningsOnly) {
                                dailyNutrients.filter { it.status == StatusColor.RED || it.percentage > 180.0 }
                            } else {
                                dailyNutrients
                            }
                        }

                        if (listedNutrients.isEmpty()) {
                            Text(
                                text = "No matching nutrients with alerts currently.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            listedNutrients.forEach { status ->
                                val def = status.definition
                                val statusColor = when (status.status) {
                                    StatusColor.GREEN -> Color(0xFF2E7D32)
                                    StatusColor.YELLOW -> Color(0xFFEF6C00)
                                    StatusColor.RED -> Color(0xFFC62828)
                                }
                                val statusBg = when (status.status) {
                                    StatusColor.GREEN -> Color(0xFFE8F5E9)
                                    StatusColor.YELLOW -> Color(0xFFFFF3E0)
                                    StatusColor.RED -> Color(0xFFFFEBEE)
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1.3f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(statusColor)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = def.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            Text(
                                                text = def.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            Text(
                                                text = "${status.intake.formatInt()} / ${def.rda.formatInt()} ${def.unit}",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = statusColor
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(statusBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (def.isMaxLimit) {
                                                        if (status.intake > def.rda) "Over Limit" else "Within Limit"
                                                    } else {
                                                        "${status.percentage.toInt()}% met"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = statusColor,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().testTag("close_day_report_button")
                ) {
                    Text("OK, Keep tracking")
                }
            }
        }
    }
}

fun getInsights(dailyNutrients: List<NutrientStatus>): List<DiagnosticInsight> {
    val list = mutableListOf<DiagnosticInsight>()
    for (status in dailyNutrients) {
        val def = status.definition
        val key = def.key
        val intake = status.intake
        val pct = status.percentage

        if (def.isMaxLimit) {
            if (intake > def.rda) {
                val insight = when (key) {
                    "sodium" -> DiagnosticInsight(
                        name = "Sodium (Excess)",
                        intakeString = "${intake.toInt()} mg",
                        rdaString = "${def.rda.toInt()} mg",
                        isExcess = true,
                        shortTermRisk = "Causes temporary water retention, peripheral bloating, elevated circulatory volume, and sudden blood pressure spikes.",
                        longTermRisk = "Chronic excess sodium intake promotes elevated arterial hypertension, blood vessel thickening, high kidney filtration strain, kidney stones, and chronic stroke risk."
                    )
                    "saturated_fat" -> DiagnosticInsight(
                        name = "Saturated Fat (Excess)",
                        intakeString = "${intake.formatInt()} g",
                        rdaString = "${def.rda.formatInt()} g",
                        isExcess = true,
                        shortTermRisk = "Slows general digestion and transiently spikes circulating triglycerides and lipids.",
                        longTermRisk = "Gradually elevates Atherosclerotic Cardiovascular Disease (ASCVD) risks, raises total blood LDL cholesterol levels, and drives arterial wall plaque buildup."
                    )
                    "sugars" -> DiagnosticInsight(
                        name = "Sugars (Excess)",
                        intakeString = "${intake.formatInt()} g",
                        rdaString = "${def.rda.formatInt()} g",
                        isExcess = true,
                        shortTermRisk = "Creates rapid system blood glucose spikes followed by steep crash-induced drowsiness and sugar cravings.",
                        longTermRisk = "Promotes chronic low-grade cellular inflammation, progressive insulin resistance (Diabetes Type 2 risk), and fat accumulation in the liver."
                    )
                    "trans_fat" -> DiagnosticInsight(
                        name = "Trans Fat (Excess)",
                        intakeString = "${intake.formatInt()} g",
                        rdaString = "${def.rda.formatInt()} g",
                        isExcess = true,
                        shortTermRisk = "Spikes immediate vascular inflammatory responses.",
                        longTermRisk = "Drastically alters cholesterol ratios (lowering beneficial HDL and raising hazardous LDL), significantly escalating overall long-term coronary heart disease hazards."
                    )
                    else -> null
                }
                if (insight != null) list.add(insight)
            }
        } else {
            if (pct < 50.0 && intake > 0.0) {
                val insight = when (key) {
                    "vitamin_a" -> DiagnosticInsight(
                        name = "Vitamin A (Deficient)",
                        intakeString = "${intake.toInt()} mcg",
                        rdaString = "${def.rda.toInt()} mcg",
                        isExcess = false,
                        shortTermRisk = "Dry, itchy eyes, dry skin, and mild impairment of night adaptation.",
                        longTermRisk = "May lead to xerophthalmia (cornea damage/scarring), progressive night blindness, and structural scaling of mucous membranes."
                    )
                    "vitamin_b12" -> DiagnosticInsight(
                        name = "Vitamin B12 (Deficient)",
                        intakeString = "${String.format(Locale.US, "%.1f", intake)} mcg",
                        rdaString = "${def.rda.formatInt()} mcg",
                        isExcess = false,
                        shortTermRisk = "Temporary cognitive slowness, mild fatigue, and potential pins-and-needles feelings.",
                        longTermRisk = "Shortage of Vitamin B12 over a long time causes neurological risks like irreversible myelination loss (nerve damage), pernicious anemia, memory deficits, and neurological decay."
                    )
                    "vitamin_c" -> DiagnosticInsight(
                        name = "Vitamin C (Deficient)",
                        intakeString = "${intake.toInt()} mg",
                        rdaString = "${def.rda.toInt()} mg",
                        isExcess = false,
                        shortTermRisk = "Easy bleeding/bruising, skin dryness, and lowered antioxidant protection.",
                        longTermRisk = "Severe deficit leads to Scurvy, characterized by bleeding gums, general tissue breakdown of collagen support, tooth loss, and severe painful joints."
                    )
                    "vitamin_d" -> DiagnosticInsight(
                        name = "Vitamin D (Deficient)",
                        intakeString = "${intake.formatInt()} mcg",
                        rdaString = "${def.rda.formatInt()} mcg",
                        isExcess = false,
                        shortTermRisk = "Lower muscle tone, mild fatigue, and poorer immune defense against cold/viruses.",
                        longTermRisk = "Progressive bone softening (osteomalacia), osteoporosis (severe skeletal porousness), and rickets in growing children due to failed calcium integration."
                    )
                    "iron" -> DiagnosticInsight(
                        name = "Iron (Deficient)",
                        intakeString = "${intake.formatInt()} mg",
                        rdaString = "${def.rda.formatInt()} mg",
                        isExcess = false,
                        shortTermRisk = "Poor body heat regulation (permanently cold extremities), shortness of breath, and tiredness.",
                        longTermRisk = "Chronic microcytic Iron Deficiency Anemia, severely starving skeletal muscles and organs of necessary oxygen delivery."
                    )
                    "calcium" -> DiagnosticInsight(
                        name = "Calcium (Deficient)",
                        intakeString = "${intake.toInt()} mg",
                        rdaString = "${def.rda.toInt()} mg",
                        isExcess = false,
                        shortTermRisk = "Muscle twitches, finger tremors, and painful cramps.",
                        longTermRisk = "Chronic extraction of minerals from bones to stabilize blood chemistry, triggering severe osteopenia, teeth rotting, and osteoporosis."
                    )
                    "potassium" -> DiagnosticInsight(
                        name = "Potassium (Deficient)",
                        intakeString = "${intake.toInt()} mg",
                        rdaString = "${def.rda.toInt()} mg",
                        isExcess = false,
                        shortTermRisk = "Early physical exhaustion, cramp episodes, and muscle sluggishness.",
                        longTermRisk = "Chronic cardiac rhythm vulnerabilities, elevated arterial blood pressure, and a high frequency of kidney stone formation."
                    )
                    "fiber" -> DiagnosticInsight(
                        name = "Dietary Fiber (Deficient)",
                        intakeString = "${intake.formatInt()} g",
                        rdaString = "${def.rda.formatInt()} g",
                        isExcess = false,
                        shortTermRisk = "Constipation, sluggish gut motility, and rapid post-prandial blood sugar spikes.",
                        longTermRisk = "Long-term low fiber elevates vulnerability to colon disorders, hemorrhoidal distress, and raises circulating plasma cholesterol."
                    )
                    "water" -> DiagnosticInsight(
                        name = "Water/Hydration (Deficient)",
                        intakeString = "${intake.toInt()} ml",
                        rdaString = "${def.rda.toInt()} ml",
                        isExcess = false,
                        shortTermRisk = "Headaches, thirst, high core temp during exercise, and dry mouth.",
                        longTermRisk = "Worsens renal kidney stone hazard, impairs optimal glomerular filtration, and causes chronic skin dehydration."
                    )
                    else -> null
                }
                if (insight != null) list.add(insight)
            } else if (pct > 250.0 && !def.isMaxLimit) {
                if (key == "vitamin_a") {
                    list.add(
                        DiagnosticInsight(
                            name = "Vitamin A (Excessive)",
                            intakeString = "${intake.toInt()} mcg",
                            rdaString = "${def.rda.toInt()} mcg",
                            isExcess = true,
                            shortTermRisk = "Dizziness and system head pressure at high acute doses.",
                            longTermRisk = "⚠️ Vitamin A excess over a long time is stored in the liver. It cannot be easily excreted and can cause chronic liver damage, bone pain, hair loss, and visual changes."
                        )
                    )
                } else if (key == "vitamin_d") {
                    list.add(
                        DiagnosticInsight(
                            name = "Vitamin D (Excessive)",
                            intakeString = "${intake.formatInt()} mcg",
                            rdaString = "${def.rda.formatInt()} mcg",
                            isExcess = true,
                            shortTermRisk = "Could cause temporary nausea or mild loss of appetite.",
                            longTermRisk = "⚠️ Chronic high levels can cause hypercalcemia, which is excessive calcium loading in the blood, potentially calcium-depositing in the kidneys or cardiovascular arteries."
                        )
                    )
                }
            }
        }
    }
    return list
}

private fun Float.safeCoerce(minimumValue: Float, maximumValue: Float): Float {
    if (this.isNaN()) return minimumValue
    return this.coerceIn(minimumValue, maximumValue)
}

private fun Double.safeCoerce(minimumValue: Double, maximumValue: Double): Double {
    if (this.isNaN()) return minimumValue
    return this.coerceIn(minimumValue, maximumValue)
}

@Composable
fun ImportExportSection(viewModel: NutritionViewModel, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var clipboardText by remember { mutableStateOf("") }
    var pasteInputVisible by remember { mutableStateOf(false) }

    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    // SAF CreateDocument Launcher for Export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = SafeCreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val jsonToWrite = pendingExportJson
            if (jsonToWrite != null) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(jsonToWrite.toByteArray(Charsets.UTF_8))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pendingExportJson = null
                    }
                }
            } else {
                // fallback
                viewModel.exportLogs { jsonString ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    // SAF OpenDocument Launcher for Import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val jsonString = inputStream.bufferedReader().use { it.readText() }
                        viewModel.importLogs(jsonString) { _, _ -> }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("import_export_section_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Data Transfer",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Backup, Import & Export Logs",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Back up or migrate your entire historical journals securely. Choose between complete JSON files or portable clipboard text backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section: File Backups
            Text(
                text = "Backup Files (JSON)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                pendingExportJson = viewModel.getExportString()
                                exportLauncher.launch("nutriscribe-journal-backup.json")
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "System File Picker not available. Please use 'Copy Code' under Portable Clipboard Backups instead.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open File Picker: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("button_export_file"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text("Export file", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        try {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "System File Picker not available. Please use 'Paste Code' under Portable Clipboard Backups instead.", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open File Picker: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("button_import_file"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Text("Import file", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Copy / Paste Tab Actions
            Text(
                text = "Portable Clipboard Backups",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.exportLogs { jsonString ->
                            try {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("NutriScribe Log Journal Backup", jsonString)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Journal backup copied to clipboard!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cloud / System Clipboard error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("button_export_clipboard"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("Copy Code", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = { pasteInputVisible = !pasteInputVisible },
                    modifier = Modifier.weight(1f).testTag("button_toggle_paste"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                ) {
                    Text(if (pasteInputVisible) "Close Paste" else "Paste Code", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (pasteInputVisible) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = clipboardText,
                    onValueChange = { clipboardText = it },
                    label = { Text("Paste exported JSON journal logs here") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .testTag("paste_logs_input_field"),
                    maxLines = 5,
                    placeholder = { Text("[ { \"date\": \"2026-05-26\", ... } ]") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (clipboardText.isNotBlank()) {
                            viewModel.importLogs(clipboardText) { success, message ->
                                if (success) {
                                    clipboardText = ""
                                    pasteInputVisible = false
                                    Toast.makeText(context, "Journal logs imported successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Import failed: $message", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("button_submit_pasted_logs"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Parse & Restore Logs List", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
