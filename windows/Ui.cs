using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace StudyLens;

/// <summary>The StudyLens look (from the mockups) as small WPF building blocks.</summary>
public static class Ui
{
    public static readonly Color BgC = Hex("#ECECEE");
    public static readonly Brush Bg = new SolidColorBrush(BgC);
    public static readonly Brush Card = new SolidColorBrush(Hex("#F7F7F8"));
    public static readonly Brush Ink = new SolidColorBrush(Hex("#1C1C1E"));
    public static readonly Brush Sub = new SolidColorBrush(Hex("#55555A"));
    public static readonly Brush Line = new SolidColorBrush(Hex("#E2E2E5"));
    public static readonly Brush Body = new SolidColorBrush(Hex("#3A3A3F"));
    public static readonly Brush White = Brushes.White;
    public static readonly FontFamily Serif = new(new Uri("pack://application:,,,/"), "./Assets/#Agrave Pro");
    public static readonly FontFamily Sans = new("Segoe UI Variable Text, Segoe UI");

    public static Color Hex(string h) => (Color)ColorConverter.ConvertFromString(h);
    public static BitmapImage Asset(string name) => new(new Uri($"pack://application:,,,/Assets/{name}"));

    public static TextBlock T(string text, double size = 14, Brush? color = null, bool bold = false, bool serif = false, double spacing = 0) => new()
    {
        Text = text, FontSize = size, Foreground = color ?? Ink, TextWrapping = TextWrapping.Wrap,
        FontFamily = serif ? Serif : Sans, FontWeight = bold && !serif ? FontWeights.SemiBold : FontWeights.Normal,
        LineHeight = size * 1.4, LineStackingStrategy = LineStackingStrategy.BlockLineHeight,
        Margin = new Thickness(0),
    };

    public static TextBlock Label(string text) => T(text.ToUpperInvariant(), 10.5, Sub, bold: true, spacing: 0.07);

    public static Border Box(UIElement child, Brush? bg = null, double radius = 18, double pad = 14) => new()
    {
        Background = bg ?? Card, CornerRadius = new CornerRadius(radius), Padding = new Thickness(pad), Child = child,
    };

    public static StackPanel Stack(double gap = 8, Orientation o = Orientation.Vertical, params UIElement[] items)
    {
        var s = new StackPanel { Orientation = o };
        for (int i = 0; i < items.Length; i++)
        {
            if (items[i] is FrameworkElement fe && i > 0)
                fe.Margin = o == Orientation.Vertical ? new Thickness(fe.Margin.Left, gap, fe.Margin.Right, fe.Margin.Bottom) : new Thickness(gap, fe.Margin.Top, fe.Margin.Right, fe.Margin.Bottom);
            s.Children.Add(items[i]);
        }
        return s;
    }

    /// <summary>A rounded pill button: dark for the main action, light for the rest.</summary>
    public static Button Pill(string text, bool dark, Action onClick, double height = 40, string? hint = null)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        content.Children.Add(T(text, 13, dark ? White : Ink, bold: true));
        if (hint != null) { var h = T(hint, 10.5, new SolidColorBrush(Hex(dark ? "#C4C4C9" : "#8A8A90"))); h.Margin = new Thickness(8, 1, 0, 0); content.Children.Add(h); }
        var b = new Button { Content = content, Height = height, Cursor = Cursors.Hand, Padding = new Thickness(16, 0, 16, 0) };
        b.Template = PillTemplate(dark ? Ink : Card, height / 2);
        b.Click += (_, _) => onClick();
        return b;
    }

    static ControlTemplate PillTemplate(Brush bg, double radius)
    {
        var t = new ControlTemplate(typeof(Button));
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.BackgroundProperty, bg);
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius));
        border.SetValue(Border.PaddingProperty, new TemplateBindingExtension(Control.PaddingProperty));
        var cp = new FrameworkElementFactory(typeof(ContentPresenter));
        cp.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        cp.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(cp);
        t.VisualTree = border;
        var hover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(UIElement.OpacityProperty, 0.85));
        t.Triggers.Add(hover);
        return t;
    }

    /// <summary>Plain clickable row/area without button chrome.</summary>
    public static Button Plain(UIElement content, Action onClick)
    {
        var b = new Button { Content = content, Cursor = Cursors.Hand, HorizontalContentAlignment = HorizontalAlignment.Stretch, Background = Brushes.Transparent, BorderThickness = new Thickness(0), Padding = new Thickness(0) };
        var t = new ControlTemplate(typeof(Button));
        var cp = new FrameworkElementFactory(typeof(ContentPresenter));
        cp.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Stretch);
        t.VisualTree = cp; b.Template = t;
        b.Click += (_, _) => onClick();
        return b;
    }

    public static Border Circle(string text, double size, double font, Brush? bg = null, Brush? fg = null, bool serif = false)
    {
        var tb = T(text, text.Length > 2 ? font * 0.62 : font, fg ?? White, bold: !serif, serif: serif);
        tb.HorizontalAlignment = HorizontalAlignment.Center; tb.VerticalAlignment = VerticalAlignment.Center; tb.TextWrapping = TextWrapping.NoWrap;
        return new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = bg ?? Ink, Child = tb };
    }

    /// <summary>A switch that looks like the mockups' pill toggles.</summary>
    public static Border Toggle(bool on, Action<bool> changed)
    {
        var knob = new Ellipse { Width = 22, Height = 22, Fill = White };
        var b = new Border { Width = 46, Height = 28, CornerRadius = new CornerRadius(14), Padding = new Thickness(3), Cursor = Cursors.Hand, Child = knob };
        void Paint() { b.Background = on ? Ink : new SolidColorBrush(Hex("#C9C9CE")); knob.HorizontalAlignment = on ? HorizontalAlignment.Right : HorizontalAlignment.Left; }
        Paint();
        b.MouseLeftButtonUp += (_, _) => { on = !on; Paint(); changed(on); };
        return b;
    }

    public static Border Divider(double top = 10, double bottom = 10) => new() { Height = 1, Background = Line, Margin = new Thickness(0, top, 0, bottom) };

    public static Border KeyCap(string text) => new()
    {
        Background = White, BorderBrush = new SolidColorBrush(Hex("#DDDDE1")), BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(8), Padding = new Thickness(10, 5, 10, 5), Child = T(text, 12.5, bold: true),
        HorizontalAlignment = HorizontalAlignment.Left,
    };

    /// <summary>One answered question: a letter badge for multiple choice, an answer box + working for the rest.</summary>
    public static Border AnswerBlock(Answer a, int index, bool showNumber)
    {
        var s = new StackPanel();
        if (showNumber) { var l = Label($"Question {(a.Number.Length > 0 ? a.Number : (index + 1).ToString())}{(a.Type == "work" ? " · worked" : "")}"); l.Margin = new Thickness(0, 0, 0, 4); s.Children.Add(l); }
        if (a.Question.Length > 0) { var q = T(a.Question, 12.5, Sub); q.Margin = new Thickness(0, 0, 0, 10); s.Children.Add(q); }

        if (a.IsLetter)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(Circle(a.Text, 42, 21, serif: true));
            var t = T(a.AnswerText.Length > 0 ? a.AnswerText : "Option " + a.Text, 21, serif: true);
            t.VerticalAlignment = VerticalAlignment.Center; t.Margin = new Thickness(12, 0, 0, 0); t.MaxWidth = 280;
            row.Children.Add(t);
            s.Children.Add(row);
        }
        else
        {
            var box = new StackPanel();
            var l = Label("Answer"); l.FontSize = 10; l.Margin = new Thickness(0, 0, 0, 4); box.Children.Add(l);
            var parts = a.Parts; if (parts.Count == 0) parts.Add(a.Text);
            foreach (var p in parts) { var t = T(p, 14, bold: true); t.Margin = new Thickness(0, 2, 0, 2); box.Children.Add(t); }
            s.Children.Add(Box(box, White, 14, 12));
        }

        if (a.Steps.Count > 0)
        {
            var l = Label("Working"); l.FontSize = 10; l.Margin = new Thickness(0, 14, 0, 4); s.Children.Add(l);
            for (int i = 0; i < a.Steps.Count; i++)
            {
                var row = new DockPanel { Margin = new Thickness(0, 3, 0, 3) };
                var c = Circle((i + 1).ToString(), 18, 10); c.VerticalAlignment = VerticalAlignment.Top; c.Margin = new Thickness(0, 1, 10, 0);
                DockPanel.SetDock(c, Dock.Left); row.Children.Add(c);
                row.Children.Add(T(a.Steps[i], 12.5, Body));
                s.Children.Add(row);
            }
        }
        if (a.Explanation.Length > 0) { s.Children.Add(Divider(12, 10)); s.Children.Add(T(a.Explanation, 12.5, Sub)); }
        return Box(s, Card, 18, 14);
    }

    /// <summary>Borderless rounded window with a soft shadow, used for overlays.</summary>
    public static Window Floating(double width, UIElement content, bool topmost = true)
    {
        var w = new Window
        {
            WindowStyle = WindowStyle.None, AllowsTransparency = true, Background = Brushes.Transparent,
            ShowInTaskbar = false, Topmost = topmost, Width = width, SizeToContent = SizeToContent.Height, ResizeMode = ResizeMode.NoResize,
        };
        var shell = new Border
        {
            Background = Bg, CornerRadius = new CornerRadius(20), Padding = new Thickness(12), Margin = new Thickness(18),
            Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 30, ShadowDepth = 8, Opacity = 0.25, Direction = 270 },
            Child = content,
        };
        w.Content = shell;
        w.KeyDown += (_, e) => { if (e.Key == Key.Escape) w.Close(); };
        return w;
    }

    public static ScrollViewer Scroll(UIElement content, double maxHeight) => new()
    {
        Content = content, MaxHeight = maxHeight, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
    };
}
