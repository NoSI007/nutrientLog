using System;
using System.Globalization;
using System.Windows.Data;

namespace DesktopNutritionTracker.Services
{
    public class DateConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            DateTime date;
            if (value is string dateStr && DateTime.TryParseExact(dateStr, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out date))
            {
                return date;
            }
            return DateTime.Today;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is DateTime date)
            {
                return date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            }
            return DateTime.Today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        }
    }

    public class MultiplyConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            double scale = 1.0;
            if (parameter != null)
            {
                string paramStr = parameter.ToString();
                paramStr = paramStr.Replace(',', '.');
                double parsedParam;
                if (double.TryParse(paramStr, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out parsedParam))
                {
                    scale = parsedParam;
                }
            }

            if (value != null)
            {
                string valueStr = value.ToString().Replace(',', '.');
                double numericVal;
                if (double.TryParse(valueStr, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out numericVal))
                {
                    double result = numericVal * scale;
                    if (result < 4) return 4.0;
                    if (result > 300) return 300.0;
                    return result;
                }
            }
            return 10.0;
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }
}
