using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using DesktopNutritionTracker.Models;

namespace DesktopNutritionTracker.Services
{
    /// <summary>
    /// Self-hosted REST API endpoint for the WPF application.
    /// Spawns a background thread to serve aggregated nutrition data as JSON for frontend charting libraries.
    /// </summary>
    public class LocalHttpEndpointService
    {
        private readonly INutritionAggregationService _aggregationService;
        private readonly IFoodItemRepository _foodItemRepository;
        private readonly INutrientDeficiencyAnalysisService _deficiencyAnalysisService;
        private HttpListener? _listener;
        private Thread? _listenerThread;
        private bool _isRunning;
        private readonly int _port;

        /// <summary>
        /// Instantiates the API endpoint server. Uses port 5050 by default.
        /// </summary>
        public LocalHttpEndpointService(
            INutritionAggregationService? aggregationService = null, 
            IFoodItemRepository? foodItemRepository = null, 
            INutrientDeficiencyAnalysisService? deficiencyAnalysisService = null,
            int port = 5050)
        {
            _aggregationService = aggregationService ?? new NutritionAggregationService();
            _foodItemRepository = foodItemRepository ?? new FoodItemRepository();
            _deficiencyAnalysisService = deficiencyAnalysisService ?? new NutrientDeficiencyAnalysisService();
            _port = port;
        }

        /// <summary>
        /// Starts the API server on a background thread.
        /// </summary>
        public void Start()
        {
            if (_isRunning) return;

            try
            {
                _listener = new HttpListener();
                _listener.Prefixes.Add($"http://localhost:{_port}/");
                _listener.Start();

                _isRunning = true;
                _listenerThread = new Thread(ListenLoop)
                {
                    IsBackground = true,
                    Name = "LocalApiServerThread"
                };
                _listenerThread.Start();

                System.Diagnostics.Debug.WriteLine($"Local HTTP API endpoint started successfully on http://localhost:{_port}/");
                NutritionDbContext.LogActivity("API_SERVER_START", $"API server listening on port {_port}");
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to start local API server: {ex.Message}");
                NutritionDbContext.LogActivity("API_SERVER_ERROR", $"Failed to start server on port {_port}: {ex.Message}");
            }
        }

        /// <summary>
        /// Gracefully stops the API server and releases listener prefixes.
        /// </summary>
        public void Stop()
        {
            if (!_isRunning) return;

            _isRunning = false;
            try
            {
                if (_listener != null && _listener.IsListening)
                {
                    _listener.Stop();
                    _listener.Close();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Error stopping API listener: {ex.Message}");
            }

            System.Diagnostics.Debug.WriteLine("Local HTTP API server stopped.");
        }

        private void ListenLoop()
        {
            while (_isRunning && _listener != null && _listener.IsListening)
            {
                try
                {
                    var context = _listener.GetContext();
                    ThreadPool.QueueUserWorkItem(async state =>
                    {
                        try
                        {
                            await HandleRequestAsync((HttpListenerContext)state);
                        }
                        catch (Exception ex)
                        {
                            System.Diagnostics.Debug.WriteLine($"Error handling context: {ex.Message}");
                        }
                    }, context);
                }
                catch (HttpListenerException)
                {
                    // Triggered when listener is closed/stopped while waiting for connection
                    break;
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Exception in listen thread: {ex.Message}");
                    Thread.Sleep(500); // Prevent tight failure loop
                }
            }
        }

        private async Task HandleRequestAsync(HttpListenerContext context)
        {
            var request = context.Request;
            var response = context.Response;

            // Set up CORS headers so that client-side Web/JavaScript charting widgets can make requests
            response.Headers.Add("Access-Control-Allow-Origin", "*");
            response.Headers.Add("Access-Control-Allow-Methods", "GET, OPTIONS");
            response.Headers.Add("Access-Control-Allow-Headers", "Content-Type");

            // Handle preflight OPTIONS request
            if (request.HttpMethod == "OPTIONS")
            {
                response.StatusCode = (int)HttpStatusCode.OK;
                response.Close();
                return;
            }

            if (request.HttpMethod != "GET")
            {
                SendErrorResponse(response, HttpStatusCode.MethodNotAllowed, "Only GET requests are supported.");
                return;
            }

            string rawPath = request.Url?.AbsolutePath ?? "";
            var queryParams = ParseQueryString(request.Url?.Query ?? "");

            // Extract core dates parameters
            string todayStr = DateTime.UtcNow.ToString("yyyy-MM-dd");
            string sevenDaysAgoStr = DateTime.UtcNow.AddDays(-6).ToString("yyyy-MM-dd");

            string startDate = queryParams.ContainsKey("startDate") ? queryParams["startDate"] : sevenDaysAgoStr;
            string endDate = queryParams.ContainsKey("endDate") ? queryParams["endDate"] : todayStr;

            try
            {
                if (rawPath.Equals("/api/trends", StringComparison.OrdinalIgnoreCase))
                {
                    string[]? metrics = null;
                    if (queryParams.ContainsKey("metrics") && !string.IsNullOrWhiteSpace(queryParams["metrics"]))
                    {
                        metrics = queryParams["metrics"].Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries);
                    }

                    var chartData = await _aggregationService.GetFormattedChartDataAsync(startDate, endDate, metrics);
                    SendJsonResponse(response, chartData);
                }
                else if (rawPath.Equals("/api/daily-aggregates", StringComparison.OrdinalIgnoreCase))
                {
                    var aggregates = await _aggregationService.GetDailyAggregatesAsync(startDate, endDate);
                    SendJsonResponse(response, aggregates);
                }
                else if (rawPath.Equals("/api/meal-breakdown", StringComparison.OrdinalIgnoreCase))
                {
                    var breakdown = await _aggregationService.GetMealBreakdownAsync(startDate, endDate);
                    SendJsonResponse(response, breakdown);
                }
                else if (rawPath.Equals("/api/foods/search", StringComparison.OrdinalIgnoreCase) ||
                         rawPath.Equals("/api/food/search", StringComparison.OrdinalIgnoreCase) ||
                         rawPath.Equals("/api/foods", StringComparison.OrdinalIgnoreCase) ||
                         rawPath.Equals("/api/food", StringComparison.OrdinalIgnoreCase) ||
                         rawPath.Equals("/api/search", StringComparison.OrdinalIgnoreCase))
                {
                    string keyword = queryParams.ContainsKey("query") ? queryParams["query"] : 
                                     queryParams.ContainsKey("q") ? queryParams["q"] : 
                                     queryParams.ContainsKey("name") ? queryParams["name"] : 
                                     queryParams.ContainsKey("keyword") ? queryParams["keyword"] : "";

                    var results = await _foodItemRepository.SearchByNameAsync(keyword);
                    SendJsonResponse(response, results);
                }
                else if (rawPath.Equals("/api/deficiencies", StringComparison.OrdinalIgnoreCase) ||
                         rawPath.Equals("/api/deficiency", StringComparison.OrdinalIgnoreCase) ||
                         rawPath.Equals("/api/deficiency-report", StringComparison.OrdinalIgnoreCase))
                {
                    string targetDate = queryParams.ContainsKey("date") ? queryParams["date"] : 
                                       queryParams.ContainsKey("d") ? queryParams["d"] : todayStr;

                    var report = _deficiencyAnalysisService.AnalyzeDeficiencies(targetDate);
                    SendJsonResponse(response, report);
                }
                else if (rawPath.Equals("/api/status", StringComparison.OrdinalIgnoreCase))
                {
                    var status = new
                    {
                        AppName = "DesktopNutritionTracker",
                        Status = "Online",
                        LocalTime = DateTime.Now,
                        Version = "1.2"
                    };
                    SendJsonResponse(response, status);
                }
                else if (rawPath.Equals("/", StringComparison.OrdinalIgnoreCase) || 
                         rawPath.Equals("/dashboard", StringComparison.OrdinalIgnoreCase))
                {
                    SendHtmlResponse(response, GetDashboardHtml());
                }
                else
                {
                    SendErrorResponse(response, HttpStatusCode.NotFound, "Endpoint path not found. Try /, /dashboard, /api/trends, /api/daily-aggregates, /api/meal-breakdown, /api/deficiencies?date=YYYY-MM-DD, or /api/foods/search?query=...");
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"API endpoint error: {ex.Message}");
                SendErrorResponse(response, HttpStatusCode.InternalServerError, $"Internal Server Error: {ex.Message}");
            }
        }

        private static void SendHtmlResponse(HttpListenerResponse response, string html)
        {
            byte[] buffer = Encoding.UTF8.GetBytes(html);
            response.ContentType = "text/html";
            response.ContentEncoding = Encoding.UTF8;
            response.ContentLength64 = buffer.Length;
            response.StatusCode = (int)HttpStatusCode.OK;

            using (var output = response.OutputStream)
            {
                output.Write(buffer, 0, buffer.Length);
            }
        }

        private static string GetDashboardHtml()
        {
            return """
<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8" font-family="inherit">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Clinical Nutrition Trends & Analytics</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: {
                        sans: ['Plus Jakarta Sans', 'sans-serif'],
                        mono: ['JetBrains Mono', 'monospace'],
                    },
                    colors: {
                        darkBg: '#090d16',
                        cardBg: '#111827',
                        borderBg: '#1f2937'
                    }
                }
            }
        }
    </script>
    <style>
        body {
            background-color: #090d16;
            color: #f3f4f6;
            font-family: 'Plus Jakarta Sans', sans-serif;
        }
        .custom-card {
            background: rgba(17, 24, 39, 0.7);
            border: 1px solid rgba(255, 255, 255, 0.05);
            backdrop-filter: blur(16px);
        }
        .glow-green {
            box-shadow: 0 0 12px rgba(16, 185, 129, 0.35);
        }
        .glow-red {
            box-shadow: 0 0 12px rgba(239, 68, 68, 0.35);
        }
        /* Custom scrollbar */
        ::-webkit-scrollbar {
            width: 6px;
            height: 6px;
        }
        ::-webkit-scrollbar-track {
            background: #090d16;
        }
        ::-webkit-scrollbar-thumb {
            background: #374151;
            border-radius: 4px;
        }
        ::-webkit-scrollbar-thumb:hover {
            background: #4b5563;
        }
    </style>
</head>
<body class="p-4 md:p-8 min-h-screen">
    <div class="max-w-7xl mx-auto space-y-6">
        
        <!-- HEADER BOARD -->
        <header class="custom-card rounded-2xl p-6 flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
            <div class="space-y-1">
                <div class="flex items-center gap-3">
                    <span class="text-3xl">📊</span>
                    <h1 class="text-2xl font-bold bg-gradient-to-r from-emerald-400 via-teal-300 to-blue-500 bg-clip-text text-transparent">
                        Clinical Nutrition Trends & Analytics
                    </h1>
                </div>
                <p class="text-gray-400 text-sm">
                    Interactive 30-day multi-line visualization, aggregate statistics & live deficiency warning scanner
                </p>
            </div>
            
            <div class="flex items-center gap-3 bg-emerald-950/40 border border-emerald-900/50 px-4 py-2 rounded-xl">
                <span class="relative flex h-3.5 w-3.5">
                    <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                    <span class="relative inline-flex rounded-full h-3.5 w-3.5 bg-emerald-500 glow-green"></span>
                </span>
                <span class="text-emerald-300 font-mono text-xs font-semibold tracking-wide">METABOLIC ENGINE ONLINE</span>
            </div>
        </header>

        <!-- DASHBOARD CONTAINER GRID -->
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
            
            <!-- LEFT PANEL: TRENDS CHART & PRESETS (2 COLS) -->
            <div class="lg:col-span-2 space-y-6">
                
                <!-- CHART CARD -->
                <div class="custom-card rounded-2xl p-6 space-y-6">
                    <!-- Controls Header -->
                    <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                        <!-- Date range buttons -->
                        <div class="flex flex-wrap gap-1 bg-gray-900/80 p-1.5 rounded-xl border border-gray-800">
                            <button id="btn-7" onclick="updateDateRange(7)" class="px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all duration-200 text-gray-400 hover:text-white">
                                7 Days
                            </button>
                            <button id="btn-14" onclick="updateDateRange(14)" class="px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all duration-200 text-gray-400 hover:text-white">
                                14 Days
                            </button>
                            <button id="btn-30" onclick="updateDateRange(30)" class="px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all duration-200 bg-emerald-600 text-white glow-green">
                                30 Days
                            </button>
                            <button id="btn-90" onclick="updateDateRange(90)" class="px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all duration-200 text-gray-400 hover:text-white">
                                90 Days
                            </button>
                        </div>

                        <!-- Info/Legend Toggle -->
                        <div class="text-xs text-gray-400 font-mono" id="chart-date-label">
                            Loading date range...
                        </div>
                    </div>

                    <!-- Nutrient checkboxes filter line -->
                    <div class="flex flex-wrap gap-4 bg-gray-950/40 p-4 rounded-xl border border-gray-900">
                        <span class="text-xs font-bold text-gray-400 tracking-wider uppercase self-center mr-1">Plot Series:</span>
                        <label class="inline-flex items-center gap-2 cursor-pointer text-sm">
                            <input type="checkbox" id="chk-calories" checked onchange="toggleMetric('calories')" class="w-4 h-4 accent-amber-500 rounded focus:ring-0">
                            <span class="text-amber-400 font-semibold">Calories</span>
                        </label>
                        <label class="inline-flex items-center gap-2 cursor-pointer text-sm">
                            <input type="checkbox" id="chk-protein" checked onchange="toggleMetric('protein')" class="w-4 h-4 accent-blue-500 rounded focus:ring-0">
                            <span class="text-blue-400 font-semibold">Protein</span>
                        </label>
                        <label class="inline-flex items-center gap-2 cursor-pointer text-sm">
                            <input type="checkbox" id="chk-carbohydrates" checked onchange="toggleMetric('carbohydrates')" class="w-4 h-4 accent-emerald-500 rounded focus:ring-0">
                            <span class="text-emerald-400 font-semibold">Carbs</span>
                        </label>
                        <label class="inline-flex items-center gap-2 cursor-pointer text-sm">
                            <input type="checkbox" id="chk-fat" checked onchange="toggleMetric('fat')" class="w-4 h-4 accent-rose-500 rounded focus:ring-0">
                            <span class="text-rose-400 font-semibold">Fat</span>
                        </label>
                        <label class="inline-flex items-center gap-2 cursor-pointer text-sm">
                            <input type="checkbox" id="chk-fiber" checked onchange="toggleMetric('fiber')" class="w-4 h-4 accent-purple-500 rounded focus:ring-0">
                            <span class="text-purple-400 font-semibold">Fiber</span>
                        </label>
                    </div>

                    <!-- Canvas Wrapper -->
                    <div class="relative h-[320px] md:h-[380px] w-full">
                        <canvas id="trendsChart"></canvas>
                    </div>
                </div>

                <!-- BOTTOM STATS CARDS -->
                <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
                    <div class="custom-card rounded-xl p-4 space-y-1">
                        <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Avg Calories</span>
                        <div class="flex items-baseline gap-1.5">
                            <span id="stat-calories" class="text-xl font-bold text-amber-400 font-mono">0.0</span>
                            <span class="text-xs text-gray-400">kcal</span>
                        </div>
                    </div>
                    <div class="custom-card rounded-xl p-4 space-y-1">
                        <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Avg Protein</span>
                        <div class="flex items-baseline gap-1.5">
                            <span id="stat-protein" class="text-xl font-bold text-blue-400 font-mono">0.0</span>
                            <span class="text-xs text-gray-400">g</span>
                        </div>
                    </div>
                    <div class="custom-card rounded-xl p-4 space-y-1">
                        <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Avg Carbohydrates</span>
                        <div class="flex items-baseline gap-1.5">
                            <span id="stat-carbs" class="text-xl font-bold text-emerald-400 font-mono">0.0</span>
                            <span class="text-xs text-gray-400">g</span>
                        </div>
                    </div>
                    <div class="custom-card rounded-xl p-4 space-y-1">
                        <span class="text-xs font-bold text-gray-400 uppercase tracking-wider">Avg Fat</span>
                        <div class="flex items-baseline gap-1.5">
                            <span id="stat-fat" class="text-xl font-bold text-rose-400 font-mono">0.0</span>
                            <span class="text-xs text-gray-400">g</span>
                        </div>
                    </div>
                </div>

            </div>

            <!-- RIGHT PANEL: CLINICAL DEFICIENCY SCANNER (1 COL) -->
            <div class="space-y-6">
                
                <div class="custom-card rounded-2xl p-6 space-y-5">
                    <div class="space-y-1">
                        <h2 class="text-lg font-bold text-white flex items-center gap-2">
                            🔍 Clinical Deficiency Scanner
                        </h2>
                        <p class="text-xs text-gray-400">
                            Evaluate daily logs against full clinical RDA thresholds for any specific date
                        </p>
                    </div>

                    <!-- Date Selection Form -->
                    <div class="flex gap-2">
                        <input type="date" id="scan-date" class="bg-gray-900 border border-gray-800 text-sm rounded-xl px-3 py-2 text-white flex-grow focus:outline-none focus:ring-1 focus:ring-emerald-500 font-mono">
                        <button onclick="triggerScanner()" class="bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold px-4 py-2 rounded-xl transition duration-200 hover:scale-[1.02] flex items-center gap-1">
                            Scan
                        </button>
                    </div>

                    <hr class="border-gray-800">

                    <!-- RESULTS PANEL -->
                    <div id="scan-results" class="space-y-4">
                        
                        <!-- Percentage Score Banner -->
                        <div class="bg-gray-900/60 border border-gray-800/80 rounded-xl p-4 flex items-center justify-between">
                            <div class="space-y-0.5">
                                <span class="text-xs font-bold text-gray-400 uppercase tracking-wide">RDA Compliance</span>
                                <p id="compliance-summary" class="text-xs text-gray-400 font-semibold">Evaluating compliance...</p>
                            </div>
                            <div class="flex flex-col items-center justify-center h-14 w-14 rounded-full border-2 border-emerald-500 glow-green bg-emerald-950/10" id="compliance-ring">
                                <span id="compliance-percent" class="text-sm font-bold text-emerald-400 font-mono">100%</span>
                            </div>
                        </div>

                        <!-- Overall Summary text -->
                        <p id="overall-summary-text" class="text-xs text-gray-300 italic bg-gray-950/30 border border-gray-900/50 p-3 rounded-xl leading-relaxed">
                            Loading deficiency diagnostics...
                        </p>

                        <!-- Deficiency Warnings section -->
                        <div class="space-y-2">
                            <h3 class="text-xs font-bold text-rose-400 uppercase tracking-wider flex items-center gap-1.5">
                                ⚠️ Deficiency Warnings & Deficits
                            </h3>
                            <div id="deficiencies-container" class="max-h-[220px] overflow-y-auto space-y-1.5 pr-1">
                                <!-- Dynamic warns will be appended here -->
                            </div>
                        </div>

                        <!-- Met Targets section -->
                        <div class="space-y-2">
                            <h3 class="text-xs font-bold text-emerald-400 uppercase tracking-wider flex items-center gap-1.5">
                                 Clinical Targets Satisfied
                            </h3>
                            <div id="met-container" class="max-h-[160px] overflow-y-auto space-y-1.5 pr-1">
                                <!-- Dynamic success badges will be appended here -->
                            </div>
                        </div>

                    </div>

                </div>

            </div>

        </div>
    </div>

    <!-- MAIN JAVASCRIPT DASHBOARD LOGIC -->
    <script>
        // Set today's date picker
        const today = new Date().toISOString().split('T')[0];
        document.getElementById('scan-date').value = today;

        let myChart = null;
        let activeMetrics = ['calories', 'protein', 'carbohydrates', 'fat', 'fiber'];
        let activeDaysPreset = 30;

        // Color mapper for standard chart lines
        const metricColors = {
            'calories': { line: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },      // Amber
            'protein': { line: '#3b82f6', bg: 'rgba(59, 130, 246, 0.12)' },       // Blue
            'carbohydrates': { line: '#10b981', bg: 'rgba(16, 185, 129, 0.12)' }, // Emerald
            'fat': { line: '#f43f5e', bg: 'rgba(244, 63, 94, 0.12)' },            // Rose
            'fiber': { line: '#a855f7', bg: 'rgba(168, 85, 247, 0.12)' }          // Purple
        };

        // Initialize empty chart on load
        function initChart() {
            const ctx = document.getElementById('trendsChart').getContext('2d');
            myChart = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: [],
                    datasets: []
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            display: false // We use custom checkboxes for legend
                        },
                        tooltip: {
                            mode: 'index',
                            intersect: false,
                            backgroundColor: '#1f2937',
                            titleColor: '#ffffff',
                            bodyColor: '#e5e7eb',
                            borderColor: '#374151',
                            borderWidth: 1,
                            padding: 10,
                            titleFont: { family: 'Plus Jakarta Sans', weight: 'bold' },
                            bodyFont: { family: 'JetBrains Mono' }
                        }
                    },
                    scales: {
                        x: {
                            grid: {
                                color: 'rgba(255, 255, 255, 0.04)',
                                drawBorder: false
                            },
                            ticks: {
                                color: '#9ca3af',
                                font: { family: 'Plus Jakarta Sans', size: 10 }
                            }
                        },
                        y: {
                            grid: {
                                color: 'rgba(255, 255, 255, 0.04)',
                                drawBorder: false
                            },
                            ticks: {
                                color: '#9ca3af',
                                font: { family: 'Plus Jakarta Sans', size: 10 }
                            }
                        }
                    }
                }
            });
        }

        async function loadChart(days) {
            try {
                // Calculate Start & End Dates
                const endDate = new Date();
                const startDate = new Date();
                startDate.setDate(endDate.getDate() - (days - 1));

                const endStr = endDate.toISOString().split('T')[0];
                const startStr = startDate.toISOString().split('T')[0];

                document.getElementById('chart-date-label').innerText = `Range: ${startStr} to ${endStr}`;

                // Build query URL
                const activeMetricsStr = activeMetrics.join(',');
                const url = `/api/trends?startDate=${startStr}&endDate=${endStr}&metrics=${activeMetricsStr}`;

                const response = await fetch(url);
                if (!response.ok) throw new Error('Network response not ok');
                const trendData = await response.json();

                // Format Datasets for Chart.js
                const formattedDatasets = trendData.Datasets.map(ds => {
                    const cleanLabel = ds.Label.toLowerCase();
                    let colorObj = { line: '#94a3b8', bg: 'rgba(148, 163, 184, 0.1)' };
                    
                    if (cleanLabel.includes('calorie')) colorObj = metricColors['calories'];
                    else if (cleanLabel.includes('protein')) colorObj = metricColors['protein'];
                    else if (cleanLabel.includes('carbohydrate') || cleanLabel.includes('carb')) colorObj = metricColors['carbohydrates'];
                    else if (cleanLabel.includes('fat')) colorObj = metricColors['fat'];
                    else if (cleanLabel.includes('fiber')) colorObj = metricColors['fiber'];

                    return {
                        label: ds.Label,
                        data: ds.Data,
                        borderColor: colorObj.line,
                        backgroundColor: colorObj.bg,
                        borderWidth: 2.5,
                        tension: 0.35,
                        fill: true,
                        pointRadius: days > 14 ? 1.5 : 3,
                        pointHoverRadius: 6,
                        pointBackgroundColor: colorObj.line,
                        pointBorderColor: '#090d16'
                    };
                });

                myChart.data.labels = trendData.Labels;
                myChart.data.datasets = formattedDatasets;
                myChart.update();

                // Update Bottom Statistics Averages
                trendData.Datasets.forEach(ds => {
                    const cleanLabel = ds.Label.toLowerCase();
                    const avg = ds.Data.length ? (ds.Data.reduce((a,b) => a+b, 0) / ds.Data.length) : 0.0;
                    
                    if (cleanLabel.includes('calorie')) {
                        document.getElementById('stat-calories').innerText = avg.toFixed(1);
                    } else if (cleanLabel.includes('protein')) {
                        document.getElementById('stat-protein').innerText = avg.toFixed(1);
                    } else if (cleanLabel.includes('carbohydrate') || cleanLabel.includes('carb')) {
                        document.getElementById('stat-carbs').innerText = avg.toFixed(1);
                    } else if (cleanLabel.includes('fat')) {
                        document.getElementById('stat-fat').innerText = avg.toFixed(1);
                    }
                });

            } catch (err) {
                console.error('Failed to load nutrition trend chart:', err);
                document.getElementById('chart-date-label').innerText = 'Error fetching trends';
            }
        }

        async function scanDeficiencies(dateStr) {
            try {
                const response = await fetch(`/api/deficiencies?date=${dateStr}`);
                if (!response.ok) throw new Error('Deficiency fetch failure');
                const report = await response.json();

                // Update score ring & summary
                const score = report.AverageMetPercentage || 0.0;
                document.getElementById('compliance-percent').innerText = `${score}%`;
                
                const ring = document.getElementById('compliance-ring');
                if (score < 50) {
                    ring.className = "flex flex-col items-center justify-center h-14 w-14 rounded-full border-2 border-red-500 glow-red bg-red-950/10";
                    document.getElementById('compliance-percent').className = "text-sm font-bold text-red-400 font-mono";
                } else if (score < 80) {
                    ring.className = "flex flex-col items-center justify-center h-14 w-14 rounded-full border-2 border-amber-500 bg-amber-950/10";
                    document.getElementById('compliance-percent').className = "text-sm font-bold text-amber-400 font-mono";
                } else {
                    ring.className = "flex flex-col items-center justify-center h-14 w-14 rounded-full border-2 border-emerald-500 glow-green bg-emerald-950/10";
                    document.getElementById('compliance-percent').className = "text-sm font-bold text-emerald-400 font-mono";
                }

                document.getElementById('compliance-summary').innerText = `Clinical Target Score`;
                document.getElementById('overall-summary-text').innerText = report.OverallSummary || 'No data.';

                // Render Deficiencies
                const defContainer = document.getElementById('deficiencies-container');
                defContainer.innerHTML = '';
                if (!report.Deficiencies || report.Deficiencies.length === 0) {
                    defContainer.innerHTML = `<div class="text-xs text-emerald-400/80 font-semibold p-2 bg-emerald-950/20 border border-emerald-900/30 rounded-xl">No nutrient deficits found on this date!</div>`;
                } else {
                    report.Deficiencies.forEach(d => {
                        const statusColor = d.Status === 'Deficient' ? 'bg-red-950/40 border-red-900/60 text-red-300' : 'bg-amber-950/40 border-amber-900/60 text-amber-300';
                        const badgeColor = d.Status === 'Deficient' ? 'bg-red-500' : 'bg-amber-500';
                        
                        defContainer.innerHTML += `
                            <div class="flex items-center justify-between p-2 rounded-xl border ${statusColor} text-xs">
                                <div class="flex flex-col text-left">
                                    <span class="font-bold flex items-center gap-1.5">
                                        <span class="h-2 w-2 rounded-full ${badgeColor}"></span>
                                        ${d.NutrientName}
                                    </span>
                                    <span class="text-[10px] opacity-70">${d.Group}</span>
                                </div>
                                <div class="text-right font-mono">
                                    <div class="font-bold">${d.Intake}/${d.Rda} ${d.Unit}</div>
                                    <div class="text-[10px] opacity-75">${d.MetPercentage}% met</div>
                                </div>
                            </div>
                        `;
                    });
                }

                // Render Met Targets
                const metContainer = document.getElementById('met-container');
                metContainer.innerHTML = '';
                if (!report.MetNutrients || report.MetNutrients.length === 0) {
                    metContainer.innerHTML = `<div class="text-xs text-gray-500 italic p-2">No targets met on this date.</div>`;
                } else {
                    report.MetNutrients.forEach(m => {
                        metContainer.innerHTML += `
                            <div class="flex items-center justify-between p-2 rounded-xl border border-gray-800/80 bg-gray-900/30 text-xs text-gray-300">
                                <span class="font-medium flex items-center gap-1.5 text-gray-300">
                                    <span class="text-emerald-400 text-[10px]">✔</span>
                                    ${m.NutrientName}
                                </span>
                                <span class="font-mono text-[10px] text-gray-400">
                                    ${m.Intake} ${m.Unit}
                                </span>
                            </div>
                        `;
                    });
                }

            } catch (err) {
                console.error('Failed to run clinical deficiency scan:', err);
                document.getElementById('overall-summary-text').innerText = 'No daily food entries or taken supplements found to evaluate on this date.';
                document.getElementById('deficiencies-container').innerHTML = '';
                document.getElementById('met-container').innerHTML = '';
                document.getElementById('compliance-percent').innerText = '--';
            }
        }

        function updateDateRange(days) {
            activeDaysPreset = days;
            
            // Adjust active CSS style class on clicked preset
            [7, 14, 30, 90].forEach(d => {
                const btn = document.getElementById(`btn-${d}`);
                if (d === days) {
                    btn.className = "px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all duration-200 bg-emerald-600 text-white glow-green";
                } else {
                    btn.className = "px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all duration-200 text-gray-400 hover:text-white";
                }
            });

            loadChart(days);
        }

        function toggleMetric(metric) {
            const chk = document.getElementById(`chk-${metric}`);
            if (chk.checked) {
                if (!activeMetrics.includes(metric)) {
                    activeMetrics.push(metric);
                }
            } else {
                activeMetrics = activeMetrics.filter(m => m !== metric);
            }
            loadChart(activeDaysPreset);
        }

        function triggerScanner() {
            const dateVal = document.getElementById('scan-date').value;
            if (dateVal) {
                scanDeficiencies(dateVal);
            }
        }

        // Auto initialization
        window.addEventListener('DOMContentLoaded', () => {
            initChart();
            loadChart(30);
            scanDeficiencies(today);
        });
    </script>
</body>
</html>
""";
        }


        private static Dictionary<string, string> ParseQueryString(string query)
        {
            var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (string.IsNullOrEmpty(query)) return dict;

            if (query.StartsWith("?")) query = query.Substring(1);
            var pairs = query.Split('&');
            foreach (var pair in pairs)
            {
                var parts = pair.Split('=');
                if (parts.Length == 2)
                {
                    dict[Uri.UnescapeDataString(parts[0])] = Uri.UnescapeDataString(parts[1]);
                }
                else if (parts.Length == 1)
                {
                    dict[Uri.UnescapeDataString(parts[0])] = "";
                }
            }
            return dict;
        }

        private static void SendJsonResponse(HttpListenerResponse response, object data)
        {
            string json = JsonConvert.SerializeObject(data, Formatting.Indented);
            byte[] buffer = Encoding.UTF8.GetBytes(json);

            response.ContentType = "application/json";
            response.ContentEncoding = Encoding.UTF8;
            response.ContentLength64 = buffer.Length;
            response.StatusCode = (int)HttpStatusCode.OK;

            using (var output = response.OutputStream)
            {
                output.Write(buffer, 0, buffer.Length);
            }
        }

        private static void SendErrorResponse(HttpListenerResponse response, HttpStatusCode statusCode, string errorMessage)
        {
            var errorObj = new { Error = errorMessage, StatusCode = (int)statusCode };
            string json = JsonConvert.SerializeObject(errorObj);
            byte[] buffer = Encoding.UTF8.GetBytes(json);

            response.ContentType = "application/json";
            response.ContentLength64 = buffer.Length;
            response.StatusCode = (int)statusCode;

            using (var output = response.OutputStream)
            {
                output.Write(buffer, 0, buffer.Length);
            }
        }
    }
}
