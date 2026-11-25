using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Forms = System.Windows.Forms;

namespace StudyLens;

/// <summary>Everything that appears over other apps: the reading pill, answer card, full-screen panel and tray menu.</summary>
public static class Overlays
{
    static Window? current;   // answer card / panel / error
    static Window? pill;
    static Window? tray;

    public static void CloseAll() { current?.Close(); current = null; pill?.Close(); pill = null; tray?.Close(); tray = null; }

    static StackPanel Header(UIElement title, Window w)
    {
        var dock = new DockPanel { LastChildFill = true, Margin = new Thickness(4, 0, 0, 10) };
        var close = Ui.Pill("✕", false, () => w.Close(), 30); close.Width = 30; close.Padding = new Thickness(0);
        DockPanel.SetDock(close, Dock.Right); dock.Children.Add(close);
        if (title is FrameworkElement fe) fe.VerticalAlignment = VerticalAlignment.Center;
        dock.Children.Add(title);
        var s = new StackPanel(); s.Children.Add(dock); return s;
    }

    static UIElement MarkTitle(string text)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(new System.Windows.Controls.Image { Source = Ui.Asset("mark.png"), Width = 20, Height = 20, Clip = new EllipseGeometry(new Point(10, 10), 10, 10), Margin = new Thickness(0, 0, 8, 0) });
        var l = Ui.Label(text); l.VerticalAlignment = VerticalAlignment.Center; row.Children.Add(l);
        return row;
    }

    static TextBlock Note()
    {
        var t = Ui.T(Disclaimer.Short, 11, Ui.Sub); t.TextAlignment = TextAlignment.Center; t.Margin = new Thickness(0, 10, 0, 0);
        return t;
    }

    public static void ShowReading(string text)
    {
        pill?.Close();
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(new ProgressBar { IsIndeterminate = true, Width = 40, Height = 4, Foreground = Ui.White, Background = new SolidColorBrush(Color.FromArgb(60, 255, 255, 255)), BorderThickness = new Thickness(0), VerticalAlignment = VerticalAlignment.Center });
        var t = Ui.T(text, 13, Ui.White, bold: true); t.Margin = new Thickness(12, 0, 0, 0); row.Children.Add(t);
        var w = Ui.Floating(320, row);
        ((Border)w.Content).Background = Ui.Ink; ((Border)w.Content).CornerRadius = new CornerRadius(22); ((Border)w.Content).Padding = new Thickness(18, 12, 18, 12);
        w.SizeToContent = SizeToContent.WidthAndHeight; w.ShowActivated = false;
        w.Loaded += (_, _) =>
        {
            var wa = Forms.Screen.FromPoint(Forms.Cursor.Position).WorkingArea;
            var sz = Native.PixelSize(w);
            Native.MoveTo(w, wa.Left + (wa.Width - sz.Width) / 2, wa.Top + 12);
        };
        w.Show(); pill = w;
    }

    public static void HideReading() { pill?.Close(); pill = null; }

    /// <summary>One answer in a small card next to the question.</summary>
    public static void ShowCard(Scan scan, System.Drawing.Point anchor, Action next)
    {
        current?.Close();
        var a = scan.Answers.First();
        Window w = null!;
        var body = new StackPanel();
        w = Ui.Floating(420, body);
        body.Children.Add(Header(MarkTitle("One question"), w));
        body.Children.Add(Ui.Scroll(Ui.AnswerBlock(a, 0, false), 520));
        var row = new Grid { Margin = new Thickness(0, 10, 0, 0) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.4, GridUnitType.Star) });
        var copy = Ui.Pill("Copy", false, () => Clipboard.SetText(a.CopyText())); copy.Margin = new Thickness(0, 0, 8, 0);
        var nxt = Ui.Pill("Next question", true, () => { w.Close(); next(); }, hint: "Ctrl+Alt+B");
        Grid.SetColumn(nxt, 1); row.Children.Add(copy); row.Children.Add(nxt);
        body.Children.Add(row);
        body.Children.Add(Note());
        w.Loaded += (_, _) => Native.PlaceNear(w, anchor);
        w.Show(); w.Activate(); current = w;
    }

    /// <summary>All answers from a full-screen scan, docked on the right of the monitor.</summary>
    public static void ShowPanel(Scan scan, Action again, Action<long> openInApp)
    {
        current?.Close();
        var wa = Forms.Screen.FromPoint(Forms.Cursor.Position).WorkingArea;
        Window w = null!;
        var body = new DockPanel { LastChildFill = true };
        w = Ui.Floating(440, body);
        w.SizeToContent = SizeToContent.Manual;

        var n = scan.Answers.Count;
        var title = Ui.T($"{n} answer{(n == 1 ? "" : "s")} found", 26, serif: true);
        var head = Header(title, w);
        var chips = new WrapPanel { Margin = new Thickness(2, 0, 0, 10) };
        for (int i = 0; i < n; i++)
        {
            var a = scan.Answers[i];
            var tag = a.IsLetter ? a.Text : a.Type == "work" ? "worked" : a.Type == "short" ? "answer" : a.Text;
            var c = Ui.Box(Ui.T($"{(a.Number.Length > 0 ? a.Number : (i + 1).ToString())} · {tag}", 11.5, Ui.White, bold: true), Ui.Ink, 14, 6);
            c.Padding = new Thickness(11, 5, 11, 5); c.Margin = new Thickness(0, 0, 6, 6); chips.Children.Add(c);
        }
        head.Children.Add(chips);
        DockPanel.SetDock(head, Dock.Top); body.Children.Add(head);

        var buttons = new Grid { Margin = new Thickness(0, 10, 0, 0) };
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.3, GridUnitType.Star) });
        var open = Ui.Pill("Open in app", false, () => { w.Close(); openInApp(scan.Id); }, 42); open.Margin = new Thickness(0, 0, 8, 0);
        var scanAgain = Ui.Pill("Scan again", true, () => { w.Close(); again(); }, 42, "Ctrl+Alt+Shift+B");
        Grid.SetColumn(scanAgain, 1); buttons.Children.Add(open); buttons.Children.Add(scanAgain);
        var note = Note(); DockPanel.SetDock(note, Dock.Bottom); body.Children.Add(note);
        DockPanel.SetDock(buttons, Dock.Bottom); body.Children.Add(buttons);

        var list = new StackPanel();
        for (int i = 0; i < n; i++) { var b = Ui.AnswerBlock(scan.Answers[i], i, true); b.Margin = new Thickness(0, 0, 0, 10); list.Children.Add(b); }
        body.Children.Add(new ScrollViewer { Content = list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });

        w.SourceInitialized += (_, _) =>
        {
            // Right side of the work area, full height.
            var px = new System.Drawing.Rectangle(wa.Right - 0, wa.Top, 0, wa.Height);
            var src = PresentationSource.FromVisual(w);
            double k = src?.CompositionTarget?.TransformToDevice.M11 ?? 1;
            int width = (int)(w.Width * k);
            Native.Cover(w, new System.Drawing.Rectangle(wa.Right - width, wa.Top, width, wa.Height));
        };
        w.Show(); w.Activate(); current = w;
    }

    public static void ShowError(string message)
    {
        current?.Close();
        Window w = null!;
        var body = new StackPanel();
        w = Ui.Floating(400, body);
        body.Children.Add(Header(Ui.T("Something went wrong", 22, serif: true), w));
        body.Children.Add(Ui.Box(Ui.T(message, 13, Ui.Sub)));
        var ok = Ui.Pill("Close", true, () => w.Close()); ok.Margin = new Thickness(0, 10, 0, 0);
        body.Children.Add(ok);
        w.Loaded += (_, _) => Native.PlaceNear(w, Forms.Cursor.Position);
        w.Show(); w.Activate(); current = w;
    }

    /// <summary>The custom tray menu from the mockups, opened above the tray icon.</summary>
    public static void ShowTrayMenu(Store store, Action one, Action full, Action history, Action settings, Action quit)
    {
        tray?.Close();
        Window w = null!;
        var s = new StackPanel();
        w = Ui.Floating(320, s);
        ((Border)w.Content).Background = Ui.Card; ((Border)w.Content).Padding = new Thickness(8);

        var head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(8, 6, 8, 10) };
        head.Children.Add(new System.Windows.Controls.Image { Source = Ui.Asset("app_icon.png"), Width = 30, Height = 30, Clip = new RectangleGeometry(new Rect(0, 0, 30, 30), 8, 8) });
        var titles = new StackPanel { Margin = new Thickness(10, 0, 0, 0) };
        titles.Children.Add(Ui.T("STUDYLENS", 12.5, bold: true, spacing: 0.06));
        titles.Children.Add(Ui.T(store.Settings.ApiKey.Length == 0 ? "Add your Groq key to start" : "Ready" + (store.Settings.Model.Length > 0 ? " · " + store.Settings.Model.Split('/').Last() : ""), 11, Ui.Sub));
        head.Children.Add(titles);
        s.Children.Add(head);

        UIElement Row(string label, string keys, bool dark, Action act)
        {
            var dock = new DockPanel { Margin = new Thickness(10, 9, 10, 9) };
            var icon = Ui.Circle(dark ? "⛶" : "⬚", 32, 14, dark ? Ui.Ink : Ui.Line, dark ? Ui.White : Ui.Ink);
            DockPanel.SetDock(icon, Dock.Left); dock.Children.Add(icon);
            var k = Ui.Box(Ui.T(keys, 11, Ui.Sub), Ui.Bg, 6, 3); k.Padding = new Thickness(7, 2, 7, 2); k.VerticalAlignment = VerticalAlignment.Center;
            DockPanel.SetDock(k, Dock.Right); dock.Children.Add(k);
            var t = Ui.T(label, 13, bold: true); t.VerticalAlignment = VerticalAlignment.Center; t.Margin = new Thickness(12, 0, 0, 0); dock.Children.Add(t);
            return Ui.Plain(new Border { Child = dock, CornerRadius = new CornerRadius(12), Background = dark ? Brushes.Transparent : Ui.White }, () => { w.Close(); act(); });
        }
        s.Children.Add(Row("One question", "Ctrl+Alt+B", false, one));
        s.Children.Add(Row("Full screen", "Ctrl+Alt+Shift+B", true, full));
        s.Children.Add(Ui.Divider(4, 4));
        UIElement Link(string label, Action act, bool muted = false) =>
            Ui.Plain(new Border { Padding = new Thickness(10, 8, 10, 8), Background = Brushes.Transparent, Child = Ui.T(label, 13, muted ? Ui.Sub : Ui.Ink) }, () => { w.Close(); act(); });
        s.Children.Add(Link("History", history));
        s.Children.Add(Link("Settings", settings));
        s.Children.Add(Link("Quit StudyLens", quit, true));

        w.Deactivated += (_, _) => w.Close();
        w.Loaded += (_, _) =>
        {
            var wa = Forms.Screen.FromPoint(Forms.Cursor.Position).WorkingArea;
            var sz = Native.PixelSize(w);
            Native.MoveTo(w, wa.Right - sz.Width - 4, wa.Bottom - sz.Height - 4);
        };
        w.Show(); w.Activate(); tray = w;
    }
}
