using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;

namespace DesktopNutritionTracker.Controls
{
    public partial class MacroDonutChart : UserControl
    {
        public static readonly DependencyProperty ProteinProperty =
            DependencyProperty.Register(nameof(Protein), typeof(double), typeof(MacroDonutChart),
                new PropertyMetadata(0.0, OnValuesChanged));

        public static readonly DependencyProperty CarbsProperty =
            DependencyProperty.Register(nameof(Carbs), typeof(double), typeof(MacroDonutChart),
                new PropertyMetadata(0.0, OnValuesChanged));

        public static readonly DependencyProperty FatProperty =
            DependencyProperty.Register(nameof(Fat), typeof(double), typeof(MacroDonutChart),
                new PropertyMetadata(0.0, OnValuesChanged));

        public double Protein
        {
            get => (double)GetValue(ProteinProperty);
            set => SetValue(ProteinProperty, value);
        }

        public double Carbs
        {
            get => (double)GetValue(CarbsProperty);
            set => SetValue(CarbsProperty, value);
        }

        public double Fat
        {
            get => (double)GetValue(FatProperty);
            set => SetValue(FatProperty, value);
        }

        public MacroDonutChart()
        {
            InitializeComponent();
        }

        private static void OnValuesChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            ((MacroDonutChart)d).DrawChart();
        }

        private void OnSizeChanged(object sender, SizeChangedEventArgs e)
        {
            DrawChart();
        }

        public void DrawChart()
        {
            if (ChartCanvas == null) return;
            ChartCanvas.Children.Clear();

            double p = Protein;
            double c = Carbs;
            double f = Fat;

            // Update legends
            double total = p + c + f;
            if (total <= 0)
            {
                LegendCarbText.Text = "0.0g (0%)";
                LegendProteinText.Text = "0.0g (0%)";
                LegendFatText.Text = "0.0g (0%)";
                CenterLabelValue.Text = "0.0 g";
            }
            else
            {
                LegendCarbText.Text = $"{c:F1}g ({c / total * 100:F0}%)";
                LegendProteinText.Text = $"{p:F1}g ({p / total * 100:F0}%)";
                LegendFatText.Text = $"{f:F1}g ({f / total * 100:F0}%)";
                CenterLabelValue.Text = $"{total:F1} g";
            }

            double width = ChartCanvas.ActualWidth;
            double height = ChartCanvas.ActualHeight;

            if (width < 30 || height < 30) return;

            double radius = Math.Min(width, height) / 2.0 - 15.0; // Margin around donut
            if (radius <= 0) return;

            double centerX = width / 2.0;
            double centerY = height / 2.0;

            double strokeThickness = radius * 0.40; // Donut thickness matches the radius scale nicely
            if (strokeThickness < 12) strokeThickness = 12;

            var greyColor = (SolidColorBrush)new BrushConverter().ConvertFrom("#E5E7EB");
            var carbColor = (SolidColorBrush)new BrushConverter().ConvertFrom("#D97706");
            var proteinColor = (SolidColorBrush)new BrushConverter().ConvertFrom("#059669");
            var fatColor = (SolidColorBrush)new BrushConverter().ConvertFrom("#DC2626");

            if (total <= 0)
            {
                // Draw a simple grey full ring
                var emptyRing = new Ellipse
                {
                    Width = radius * 2,
                    Height = radius * 2,
                    Stroke = greyColor,
                    StrokeThickness = strokeThickness,
                    Fill = Brushes.Transparent
                };
                Canvas.SetLeft(emptyRing, centerX - radius);
                Canvas.SetTop(emptyRing, centerY - radius);
                ChartCanvas.Children.Add(emptyRing);
                return;
            }

            // Check if one macro occupies practically 100% of the daily total
            if (c / total >= 0.999)
            {
                DrawFullRing(centerX, centerY, radius, strokeThickness, carbColor);
                return;
            }
            if (p / total >= 0.999)
            {
                DrawFullRing(centerX, centerY, radius, strokeThickness, proteinColor);
                return;
            }
            if (f / total >= 0.999)
            {
                DrawFullRing(centerX, centerY, radius, strokeThickness, fatColor);
                return;
            }

            // Draw sectors of Carb, Protein, and Fat in sequence
            double currentFraction = 0.0;

            // Arcs to draw
            if (c > 0)
            {
                double nextFraction = currentFraction + (c / total);
                DrawArc(centerX, centerY, radius, strokeThickness, currentFraction, nextFraction, carbColor);
                currentFraction = nextFraction;
            }
            if (p > 0)
            {
                double nextFraction = currentFraction + (p / total);
                DrawArc(centerX, centerY, radius, strokeThickness, currentFraction, nextFraction, proteinColor);
                currentFraction = nextFraction;
            }
            if (f > 0)
            {
                double nextFraction = currentFraction + (f / total);
                DrawArc(centerX, centerY, radius, strokeThickness, currentFraction, nextFraction, fatColor);
                currentFraction = nextFraction;
            }
        }

        private void DrawFullRing(double cx, double cy, double radius, double strokeThickness, Brush brush)
        {
            var ring = new Ellipse
            {
                Width = radius * 2,
                Height = radius * 2,
                Stroke = brush,
                StrokeThickness = strokeThickness,
                Fill = Brushes.Transparent
            };
            Canvas.SetLeft(ring, cx - radius);
            Canvas.SetTop(ring, cy - radius);
            ChartCanvas.Children.Add(ring);
        }

        private void DrawArc(double cx, double cy, double radius, double strokeThickness, double startFraction, double endFraction, Brush brush)
        {
            double startAngle = -Math.PI / 2.0 + startFraction * 2.0 * Math.PI;
            double endAngle = -Math.PI / 2.0 + endFraction * 2.0 * Math.PI;

            double startX = cx + radius * Math.Cos(startAngle);
            double startY = cy + radius * Math.Sin(startAngle);
            double endX = cx + radius * Math.Cos(endAngle);
            double endY = cy + radius * Math.Sin(endAngle);

            var path = new Path
            {
                Stroke = brush,
                StrokeThickness = strokeThickness,
                Fill = Brushes.Transparent
            };

            var pathGeometry = new PathGeometry();
            var pathFigure = new PathFigure { StartPoint = new Point(startX, startY) };

            var arcSegment = new ArcSegment
            {
                Point = new Point(endX, endY),
                Size = new Size(radius, radius),
                SweepDirection = SweepDirection.Clockwise,
                IsLargeArc = (endFraction - startFraction) > 0.5
            };

            pathFigure.Segments.Add(arcSegment);
            pathGeometry.Figures.Add(pathFigure);
            path.Data = pathGeometry;

            ChartCanvas.Children.Add(path);
        }
    }
}
