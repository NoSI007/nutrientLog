using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using DesktopNutritionTracker.ViewModels;

namespace DesktopNutritionTracker.Controls
{
    public partial class NutrientTrendChart : UserControl
    {
        public static readonly DependencyProperty PointsProperty =
            DependencyProperty.Register(nameof(Points), typeof(IEnumerable<TrendPoint>), typeof(NutrientTrendChart),
                new PropertyMetadata(null, OnPointsChanged));

        public static readonly DependencyProperty TargetRdaProperty =
            DependencyProperty.Register(nameof(TargetRda), typeof(double), typeof(NutrientTrendChart),
                new PropertyMetadata(0.0, OnVisualParameterChanged));

        public static readonly DependencyProperty UnitProperty =
            DependencyProperty.Register(nameof(Unit), typeof(string), typeof(NutrientTrendChart),
                new PropertyMetadata("", OnVisualParameterChanged));

        public static readonly DependencyProperty IsMaxLimitProperty =
            DependencyProperty.Register(nameof(IsMaxLimit), typeof(bool), typeof(NutrientTrendChart),
                new PropertyMetadata(false, OnVisualParameterChanged));

        public IEnumerable<TrendPoint> Points
        {
            get => (IEnumerable<TrendPoint>)GetValue(PointsProperty);
            set => SetValue(PointsProperty, value);
        }

        public double TargetRda
        {
            get => (double)GetValue(TargetRdaProperty);
            set => SetValue(TargetRdaProperty, value);
        }

        public string Unit
        {
            get => (string)GetValue(UnitProperty);
            set => SetValue(UnitProperty, value);
        }

        public bool IsMaxLimit
        {
            get => (bool)GetValue(IsMaxLimitProperty);
            set => SetValue(IsMaxLimitProperty, value);
        }

        public NutrientTrendChart()
        {
            InitializeComponent();
        }

        private static void OnPointsChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            var chart = (NutrientTrendChart)d;

            if (e.OldValue is INotifyCollectionChanged oldCollection)
            {
                oldCollection.CollectionChanged -= chart.OnPointsCollectionChanged;
            }

            if (e.NewValue is INotifyCollectionChanged newCollection)
            {
                newCollection.CollectionChanged += chart.OnPointsCollectionChanged;
            }

            chart.DrawChart();
        }

        private void OnPointsCollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
        {
            DrawChart();
        }

        private static void OnVisualParameterChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            ((NutrientTrendChart)d).DrawChart();
        }

        private void OnSizeChanged(object sender, SizeChangedEventArgs e)
        {
            DrawChart();
        }

        public void DrawChart()
        {
            if (MainCanvas == null) return;
            MainCanvas.Children.Clear();

            var pointsList = Points?.ToList() ?? new List<TrendPoint>();
            if (pointsList.Count < 1)
            {
                ShowNoDataText();
                return;
            }

            double width = ActualWidth;
            double height = ActualHeight;

            if (width < 50 || height < 50) return;

            // Compute bounds and scale
            double maxVal = pointsList.Max(p => p.Value);
            double maxY = Math.Max(maxVal, TargetRda);
            if (maxY <= 0) maxY = 1.0;

            // Give grease margin at top of chart for visual clarity
            maxY *= 1.15;

            // Margins for axes
            double leftMargin = 60.0;
            double rightMargin = 20.0;
            double topMargin = 25.0;
            double bottomMargin = 30.0;

            double drawWidth = width - leftMargin - rightMargin;
            double drawHeight = height - topMargin - bottomMargin;

            if (drawWidth <= 0 || drawHeight <= 0) return;

            // 1. Draw horizontal gridlines and Y-axis scale markers
            int gridSeparation = 4;
            for (int i = 0; i <= gridSeparation; i++)
            {
                double ratio = (double)i / gridSeparation;
                double val = maxY * ratio;
                double y = topMargin + drawHeight - (ratio * drawHeight);

                // Draw Grid line
                var gridLine = new Line
                {
                    X1 = leftMargin,
                    Y1 = y,
                    X2 = width - rightMargin,
                    Y2 = y,
                    Stroke = new SolidColorBrush(Color.FromRgb(46, 46, 61)), // #2E2E3D
                    StrokeThickness = 1,
                    SnapsToDevicePixels = true
                };
                MainCanvas.Children.Add(gridLine);

                // Label
                var label = new TextBlock
                {
                    Text = $"{val:F1}",
                    Foreground = new SolidColorBrush(Color.FromRgb(156, 163, 175)), // #9CA3AF
                    FontSize = 9.5,
                    TextAlignment = TextAlignment.Right,
                    Width = leftMargin - 10,
                };
                Canvas.SetLeft(label, 5);
                Canvas.SetTop(label, y - 7);
                MainCanvas.Children.Add(label);
            }

            // 2. Draw Target RDA line
            if (TargetRda > 0)
            {
                double targetRatio = TargetRda / maxY;
                double targetY = topMargin + drawHeight - (targetRatio * drawHeight);

                if (targetY >= topMargin && targetY <= topMargin + drawHeight)
                {
                    var rdaLine = new Line
                    {
                        X1 = leftMargin,
                        Y1 = targetY,
                        X2 = width - rightMargin,
                        Y2 = targetY,
                        Stroke = new SolidColorBrush(IsMaxLimit ? Color.FromRgb(234, 92, 112) : Color.FromRgb(16, 185, 129)), // Pink/Red if Max Limit, Green otherwise
                        StrokeThickness = 1.5,
                        StrokeDashArray = new DoubleCollection { 4, 3 }
                    };
                    MainCanvas.Children.Add(rdaLine);

                    // RDA Text Identifier tag
                    var rdaLabel = new TextBlock
                    {
                        Text = $"{(IsMaxLimit ? "LIMIT" : "RDA")}: {TargetRda:F1} {Unit}",
                        Foreground = rdaLine.Stroke,
                        FontSize = 9,
                        FontWeight = FontWeights.Bold
                    };
                    Canvas.SetLeft(rdaLabel, leftMargin + 10);
                    Canvas.SetTop(rdaLabel, targetY - 14);
                    MainCanvas.Children.Add(rdaLabel);
                }
            }

            // 3. Draw Plot Path coordinates
            var points = new List<Point>();
            double xStep = pointsList.Count > 1 ? drawWidth / (pointsList.Count - 1) : drawWidth;

            for (int i = 0; i < pointsList.Count; i++)
            {
                double ratioY = pointsList[i].Value / maxY;
                double x = leftMargin + (i * xStep);
                double y = topMargin + drawHeight - (ratioY * drawHeight);
                points.Add(new Point(x, y));
            }

            // A. Shaded area below the graph line
            if (points.Count > 0)
            {
                var areaGeometry = new PathGeometry();
                var areaFigure = new PathFigure { StartPoint = new Point(leftMargin, topMargin + drawHeight), IsClosed = true };
                
                areaFigure.Segments.Add(new LineSegment(points[0], true));
                for (int i = 1; i < points.Count; i++)
                {
                    areaFigure.Segments.Add(new LineSegment(points[i], true));
                }
                areaFigure.Segments.Add(new LineSegment(new Point(points.Last().X, topMargin + drawHeight), true));
                areaGeometry.Figures.Add(areaFigure);

                var areaPath = new Path
                {
                    Data = areaGeometry,
                    Fill = new LinearGradientBrush
                    {
                        StartPoint = new Point(0, 0),
                        EndPoint = new Point(0, 1),
                        GradientStops = new GradientStopCollection
                        {
                            new GradientStop(Color.FromArgb(90, 80, 118, 236), 0),   // Semitransparent AccentPrimary
                            new GradientStop(Color.FromArgb(0, 80, 118, 236), 1)     // Transparent
                        }
                    }
                };
                MainCanvas.Children.Add(areaPath);
            }

            // B. Actual Trend Connection Line
            if (points.Count > 1)
            {
                var lineGeometry = new PathGeometry();
                var lineFigure = new PathFigure { StartPoint = points[0], IsClosed = false };
                for (int i = 1; i < points.Count; i++)
                {
                    lineFigure.Segments.Add(new LineSegment(points[i], true));
                }
                lineGeometry.Figures.Add(lineFigure);

                var trendLine = new Path
                {
                    Data = lineGeometry,
                    Stroke = new SolidColorBrush(Color.FromRgb(80, 118, 236)), // #5076EC (AccentPrimary)
                    StrokeThickness = 2.5
                };
                MainCanvas.Children.Add(trendLine);
            }

            // 4. Highlight Circles / Data Nodes with Tooltips and text hovering
            for (int i = 0; i < points.Count; i++)
            {
                var pt = points[i];
                var data = pointsList[i];

                // Node Circle Indicator
                var node = new Ellipse
                {
                    Width = 8,
                    Height = 8,
                    Fill = new SolidColorBrush(Color.FromRgb(245, 245, 247)), // #F5F5F7
                    Stroke = new SolidColorBrush(data.IsTargetMet ? Color.FromRgb(10, 185, 129) : Color.FromRgb(234, 92, 112)),
                    StrokeThickness = 2,
                    Cursor = System.Windows.Input.Cursors.Hand
                };

                Canvas.SetLeft(node, pt.X - 4);
                Canvas.SetTop(node, pt.Y - 4);

                // Design Tooltip
                var tooltip = new ToolTip
                {
                    Background = new SolidColorBrush(Color.FromRgb(29, 29, 36)),
                    BorderBrush = new SolidColorBrush(Color.FromRgb(46, 46, 61)),
                    BorderThickness = new Thickness(1),
                    Foreground = Brushes.White,
                    Padding = new Thickness(10, 8, 10, 8),
                    Content = new StackPanel
                    {
                        Children =
                        {
                            new TextBlock { Text = $"{data.DateString} Tracking", FontWeight = FontWeights.Bold, Margin = new Thickness(0,0,0,4), Foreground = new SolidColorBrush(Color.FromRgb(156, 163, 175)) },
                            new TextBlock { Text = $"Intake: {data.Value:F1} {Unit}", FontSize = 12, FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(Color.FromRgb(80, 118, 236)) },
                            new TextBlock { Text = $"RDA Met: {(data.IsTargetMet ? "Yes ✓" : "No ✗")}", FontSize = 10.5, Foreground = data.IsTargetMet ? new SolidColorBrush(Color.FromRgb(10, 185, 129)) : new SolidColorBrush(Color.FromRgb(234, 92, 112)) },
                            new Separator { Background = new SolidColorBrush(Color.FromRgb(46, 46, 61)), Margin = new Thickness(0,4,0,4) },
                            new TextBlock { Text = $"Day Stats: {data.Calories:F0} kcal | C: {data.Carbs:F0}g | P: {data.Protein:F0}g | F: {data.Fat:F0}g", FontSize = 9.5, Foreground = Brushes.LightGray }
                        }
                    }
                };
                node.ToolTip = tooltip;
                MainCanvas.Children.Add(node);

                // Add date labels under nodes for readability (limited for Weekly intervals or spaced out)
                bool showDateText = (pointsList.Count <= 7) || (i % (pointsList.Count / 6) == 0) || (i == pointsList.Count - 1);
                if (showDateText)
                {
                    // Draw a thin tick
                    var tick = new Line
                    {
                        X1 = pt.X,
                        Y1 = topMargin + drawHeight,
                        X2 = pt.X,
                        Y2 = topMargin + drawHeight + 4,
                        Stroke = new SolidColorBrush(Color.FromRgb(46, 46, 61)),
                        StrokeThickness = 1
                    };
                    MainCanvas.Children.Add(tick);

                    var textBlock = new TextBlock
                    {
                        Text = data.DisplayDate,
                        Foreground = new SolidColorBrush(Color.FromRgb(156, 163, 175)),
                        FontSize = 9,
                        HorizontalAlignment = HorizontalAlignment.Center
                    };
                    
                    // Simple alignment correction
                    textBlock.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                    Canvas.SetLeft(textBlock, pt.X - (textBlock.DesiredSize.Width / 2));
                    Canvas.SetTop(textBlock, topMargin + drawHeight + 6);
                    MainCanvas.Children.Add(textBlock);
                }
            }
        }

        private void ShowNoDataText()
        {
            var noDataLabel = new TextBlock
            {
                Text = "No historical clinical logs available to perform visual trace projections.",
                Foreground = new SolidColorBrush(Color.FromRgb(156, 163, 175)),
                FontSize = 13,
                FontStyle = FontStyles.Italic,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
            MainCanvas.Children.Add(noDataLabel);

            // Center it inside canvas
            noDataLabel.SizeChanged += (s, e) =>
            {
                Canvas.SetLeft(noDataLabel, (ActualWidth - noDataLabel.ActualWidth) / 2);
                Canvas.SetTop(noDataLabel, (ActualHeight - noDataLabel.ActualHeight) / 2);
            };
        }
    }
}
