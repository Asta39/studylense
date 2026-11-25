using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace StudyLens;

/// <summary>The app window: first-run setup, history (list + detail) and settings.</summary>
public class MainWindow : Window
{
    readonly App app;
    Store Store => app.Store;
    readonly Grid root = new();
    string page = "history";
    long? selected;
    int filter; // 0 all, 1 one question, 2 full screen
    string search = "";

    public MainWindow(App app)
    {
        this.app = app;
        Title = "StudyLens"; Width = 1180; Height = 720; MinWidth = 900; MinHeight = 560;
        Background = Ui.Bg; FontFamily = Ui.Sans;
        Icon = BitmapFrame.Create(new Uri("pack://application:,,,/Assets/studylens.ico"));
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Content = root;
        Store.HistoryChanged += () => Dispatcher.Invoke(() => { if (page == "history") Render(); });
        // Closing just hides to the tray; the app keeps running.
        Closing += (_, e) => { if (Store.Settings.Onboarded) { e.Cancel = true; Hide(); } };
    }

    public void Navigate(string p, long? scan = null)
    {
        page = p == "setup" || !Store.Settings.Onboarded ? "setup" : p;
        if (scan != null) { selected = scan; filter = 0; }
        Render();
    }

    void Render()
    {
        root.Children.Clear(); root.ColumnDefinitions.Clear();
        if (page == "setup") { root.Children.Add(SetupPage()); return; }
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(210) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var side = Sidebar(); root.Children.Add(side);
        var content = page == "settings" ? SettingsPage() : HistoryPage();
        Grid.SetColumn(content, 1); root.Children.Add(content);
    }

    UIElement Sidebar()
    {
        var s = new DockPanel { Margin = new Thickness(12, 18, 12, 16), LastChildFill = false };
        var brand = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(8, 0, 0, 18) };
        brand.Children.Add(new Image { Source = Ui.Asset("app_icon.png"), Width = 28, Height = 28, Clip = new RectangleGeometry(new Rect(0, 0, 28, 28), 8, 8) });
        var bt = Ui.T("STUDYLENS", 12, bold: true, spacing: 0.08); bt.VerticalAlignment = VerticalAlignment.Center; bt.Margin = new Thickness(10, 0, 0, 0);
        brand.Children.Add(bt);
        DockPanel.SetDock(brand, Dock.Top); s.Children.Add(brand);
        foreach (var (id, label) in new[] { ("history", "History"), ("settings", "Settings") })
        {
            bool on = page == id;
            var item = Ui.Plain(new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(12, 10, 12, 10), Margin = new Thickness(0, 0, 0, 4), Background = on ? Ui.Ink : Brushes.Transparent, Child = Ui.T(label, 13, on ? Ui.White : Ui.Ink, bold: on) },
                () => { page = id; Render(); });
            DockPanel.SetDock(item, Dock.Top); s.Children.Add(item);
        }
        var tips = Ui.Box(Ui.Stack(4, Orientation.Vertical, Ui.T("Shortcuts", 12, bold: true), Ui.T("Ctrl+Alt+B · one question\nCtrl+Alt+Shift+B · full screen", 11.5, Ui.Sub)), Ui.Card, 14, 12);
        DockPanel.SetDock(tips, Dock.Bottom); s.Children.Add(tips);
        var note = Ui.T(Disclaimer.Short, 11, Ui.Sub); note.Margin = new Thickness(6, 0, 6, 10);
        DockPanel.SetDock(note, Dock.Bottom); s.Children.Add(note);
        return new Border { BorderBrush = new SolidColorBrush(Ui.Hex("#E0E0E4")), BorderThickness = new Thickness(0, 0, 1, 0), Child = s };
    }

    // ------------------------------------------------------------------ setup

    UIElement SetupPage()
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(440) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // Hero that fades into the window background instead of ending in a hard edge.
        var hero = new Image { Source = Ui.Asset("hero.jpg"), Stretch = Stretch.UniformToFill, VerticalAlignment = VerticalAlignment.Top };
        hero.OpacityMask = new LinearGradientBrush(new GradientStopCollection { new(Colors.Black, 0), new(Colors.Black, 0.6), new(Colors.Transparent, 1) }, new Point(0, 0), new Point(1, 0));
        g.Children.Add(hero);
        var brand = Ui.T("STUDYLENS", 12, bold: true, spacing: 0.08); brand.Margin = new Thickness(24, 18, 0, 0); brand.VerticalAlignment = VerticalAlignment.Top;
        g.Children.Add(brand);

        var form = new DockPanel { Margin = new Thickness(24, 30, 40, 32), LastChildFill = false };
        var key = new PasswordBox { Password = Store.Settings.ApiKey, FontSize = 14, Padding = new Thickness(12, 10, 12, 10), BorderBrush = new SolidColorBrush(Ui.Hex("#D6D6DA")), Background = Ui.White };
        var start = Ui.Pill("Start the lens  →", true, () => { }, 52);
        var agree = new CheckBox { Content = Ui.T("I'll use StudyLens to learn, not to cheat", 13, bold: true), IsChecked = Store.Settings.AgreedEducationalUse, Margin = new Thickness(0, 4, 0, 0) };

        var top = Ui.Stack(10, Orientation.Vertical,
            Ui.T("Point at a question.\nUnderstand the answer.", 40, serif: true),
            Ui.T("StudyLens lives in your system tray. Press a shortcut over any app and it explains the question on screen.", 14, Ui.Sub),
            Ui.Box(Ui.Stack(8, Orientation.Vertical, Ui.T("Groq API key", 14, bold: true), Ui.T("Free at console.groq.com/keys", 12, Ui.Sub), key)),
            Ui.Box(ShortcutGrid()),
            Ui.Box(Row("Start StudyLens with Windows", Ui.Toggle(Store.Settings.StartWithWindows, on => { Store.Settings.StartWithWindows = on; Store.Save(); AutoStart.Apply(on); }))),
            Ui.Box(Ui.Stack(8, Orientation.Vertical, Ui.T("Educational use only", 14, bold: true), Ui.T(Disclaimer.Full, 12, Ui.Sub), agree)));
        DockPanel.SetDock(top, Dock.Top); form.Children.Add(top);

        void Refresh() => start.IsEnabled = key.Password.Trim().Length > 10 && agree.IsChecked == true;
        key.PasswordChanged += (_, _) => Refresh(); agree.Checked += (_, _) => Refresh(); agree.Unchecked += (_, _) => Refresh(); Refresh();
        start.Click += (_, _) =>
        {
            Store.Settings.ApiKey = key.Password.Trim(); Store.Settings.Model = ""; Store.Settings.Onboarded = true; Store.Settings.AgreedEducationalUse = true; Store.Save();
            page = "history"; Render(); Hide();
            app.StartOneHint();
        };
        DockPanel.SetDock(start, Dock.Bottom); form.Children.Add(start);
        Grid.SetColumn(form, 1); g.Children.Add(form);
        return g;
    }

    static UIElement ShortcutGrid()
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition()); g.ColumnDefinitions.Add(new ColumnDefinition());
        var a = Ui.Stack(6, Orientation.Vertical, Ui.T("One question", 12, Ui.Sub), Ui.KeyCap("Ctrl + Alt + B"));
        var b = Ui.Stack(6, Orientation.Vertical, Ui.T("Full screen", 12, Ui.Sub), Ui.KeyCap("Ctrl + Alt + Shift + B"));
        Grid.SetColumn(b, 1); g.Children.Add(a); g.Children.Add(b);
        return g;
    }

    static DockPanel Row(string label, UIElement trailing)
    {
        var d = new DockPanel { LastChildFill = true };
        if (trailing is FrameworkElement fe) fe.VerticalAlignment = VerticalAlignment.Center;
        DockPanel.SetDock(trailing, Dock.Right); d.Children.Add(trailing);
        var t = Ui.T(label, 14); t.FontWeight = FontWeights.Medium; t.VerticalAlignment = VerticalAlignment.Center; d.Children.Add(t);
        return d;
    }

    // ------------------------------------------------------------------ history

    UIElement HistoryPage()
    {
        var all = Store.History();
        var shown = all.Where(s => filter == 0 || (filter == 1 && !s.FullScreen) || (filter == 2 && s.FullScreen))
                       .Where(s => search.Length == 0 || s.Answers.Any(a => (a.Question + " " + a.Text + " " + a.AnswerText).Contains(search, StringComparison.OrdinalIgnoreCase)))
                       .ToList();
        if (selected == null || !shown.Any(s => s.Id == selected)) selected = shown.FirstOrDefault()?.Id;

        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(370) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var list = new StackPanel { Margin = new Thickness(16, 18, 16, 16) };
        list.Children.Add(Ui.T("History", 34, serif: true));
        var searchBox = new TextBox { Text = search, FontSize = 13, Padding = new Thickness(10, 8, 10, 8), Margin = new Thickness(0, 12, 0, 10), Background = Ui.Card, BorderThickness = new Thickness(0), ToolTip = "Search questions" };
        searchBox.TextChanged += (_, _) => { search = searchBox.Text; var caret = searchBox.CaretIndex; Render(); };
        list.Children.Add(searchBox);
        var chips = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 12) };
        string[] names = { "All", "One question", "Full screen" };
        for (int i = 0; i < 3; i++)
        {
            int idx = i; bool on = filter == i;
            var c = Ui.Plain(new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(12, 6, 12, 6), Margin = new Thickness(0, 0, 6, 0), Background = on ? Ui.Ink : Ui.Card, Child = Ui.T(names[i], 11.5, on ? Ui.White : Ui.Ink, bold: on) }, () => { filter = idx; Render(); });
            chips.Children.Add(c);
        }
        list.Children.Add(chips);
        if (shown.Count == 0) list.Children.Add(Ui.T("Nothing yet — press Ctrl+Alt+B over a question in any app.", 13, Ui.Sub));
        foreach (var grp in shown.GroupBy(s => DayLabel(s.Time)))
        {
            var day = Ui.T(grp.Key, 11.5, Ui.Sub); day.Margin = new Thickness(4, 6, 0, 6); list.Children.Add(day);
            var box = new StackPanel();
            foreach (var s in grp) box.Children.Add(ListRow(s));
            list.Children.Add(Ui.Box(box, Ui.Card, 16, 6));
        }
        var listScroll = new ScrollViewer { Content = list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        g.Children.Add(new Border { BorderBrush = new SolidColorBrush(Ui.Hex("#E0E0E4")), BorderThickness = new Thickness(0, 0, 1, 0), Child = listScroll });

        var detail = shown.FirstOrDefault(s => s.Id == selected);
        var right = detail == null ? (UIElement)new Border() : Detail(detail);
        Grid.SetColumn(right, 1); g.Children.Add(right);
        return g;
    }

    UIElement ListRow(Scan s)
    {
        bool on = s.Id == selected;
        var a = s.Answers.FirstOrDefault();
        Border badge = s.FullScreen && s.Answers.Count > 1 ? Ui.Circle($"×{s.Answers.Count}", 32, 12, Ui.Line, Ui.Ink)
            : a != null && a.IsLetter ? Ui.Circle(a.Text, 32, 13) : Ui.Circle(a?.Type == "work" ? "∑" : "Aa", 32, 12);
        var d = new DockPanel { Margin = new Thickness(6, 8, 6, 8) };
        DockPanel.SetDock(badge, Dock.Left); d.Children.Add(badge);
        var texts = new StackPanel { Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        var title = Ui.T(s.Title, 12.5, bold: on); title.TextWrapping = TextWrapping.NoWrap; title.TextTrimming = TextTrimming.CharacterEllipsis;
        texts.Children.Add(title);
        texts.Children.Add(Ui.T($"{(s.FullScreen ? "Full screen" : "One question")} · {s.Time:HH:mm}", 11, Ui.Sub));
        d.Children.Add(texts);
        return Ui.Plain(new Border { CornerRadius = new CornerRadius(10), Background = on ? Ui.White : Brushes.Transparent, Child = d }, () => { selected = s.Id; Render(); });
    }

    UIElement Detail(Scan s)
    {
        var p = new StackPanel { Margin = new Thickness(22, 18, 22, 22) };
        var head = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var btns = new StackPanel { Orientation = Orientation.Horizontal };
        btns.Children.Add(Ui.Pill("Copy all", false, () => Clipboard.SetText(string.Join("\n\n", s.Answers.Select(a => a.CopyText()))), 34));
        var del = Ui.Pill("Delete", false, () => { Store.Delete(s.Id); selected = null; Render(); }, 34); del.Margin = new Thickness(8, 0, 0, 0); btns.Children.Add(del);
        DockPanel.SetDock(btns, Dock.Right); head.Children.Add(btns);
        var ht = Ui.T($"{(s.FullScreen ? "Full screen" : "One question")} · {DayLabel(s.Time)} {s.Time:HH:mm}", 12.5, bold: true); ht.VerticalAlignment = VerticalAlignment.Center;
        head.Children.Add(ht);
        p.Children.Add(head);

        var path = Store.ShotPath(s.Id);
        if (File.Exists(path))
        {
            var img = new BitmapImage(); img.BeginInit(); img.CacheOption = BitmapCacheOption.OnLoad; img.UriSource = new Uri(path); img.DecodePixelWidth = 1400; img.EndInit();
            var shot = new Image { Source = img, Stretch = Stretch.Uniform, MaxHeight = 240, HorizontalAlignment = HorizontalAlignment.Left, Cursor = System.Windows.Input.Cursors.Hand, ToolTip = "Click to enlarge" };
            shot.MouseLeftButtonUp += (_, _) => shot.MaxHeight = shot.MaxHeight > 300 ? 240 : 2000;
            var frame = new Border { CornerRadius = new CornerRadius(16), BorderBrush = new SolidColorBrush(Ui.Hex("#DDDDE1")), BorderThickness = new Thickness(1), Background = Ui.White, Padding = new Thickness(6), Child = shot, Margin = new Thickness(0, 0, 0, 12), HorizontalAlignment = HorizontalAlignment.Left };
            p.Children.Add(frame);
        }

        var grid = new UniformGrid2(s.Answers.Count > 1 ? 2 : 1);
        for (int i = 0; i < s.Answers.Count; i++) grid.Add(Ui.AnswerBlock(s.Answers[i], i, true));
        p.Children.Add(grid.Panel);
        return new ScrollViewer { Content = p, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }

    /// <summary>Two columns of cards that keep their own heights (masonry-lite).</summary>
    class UniformGrid2
    {
        public Grid Panel { get; } = new();
        readonly StackPanel[] cols;
        int next;
        public UniformGrid2(int n)
        {
            cols = Enumerable.Range(0, n).Select(_ => new StackPanel()).ToArray();
            for (int i = 0; i < n; i++)
            {
                Panel.ColumnDefinitions.Add(new ColumnDefinition());
                if (i > 0) cols[i].Margin = new Thickness(10, 0, 0, 0);
                Grid.SetColumn(cols[i], i); Panel.Children.Add(cols[i]);
            }
        }
        public void Add(FrameworkElement e) { e.Margin = new Thickness(0, 0, 0, 10); cols[next++ % cols.Length].Children.Add(e); }
    }

    static string DayLabel(DateTime t) => t.Date == DateTime.Today ? "Today" : t.Date == DateTime.Today.AddDays(-1) ? "Yesterday" : t.ToString("dddd d MMM");

    // ------------------------------------------------------------------ settings

    UIElement SettingsPage()
    {
        var p = new StackPanel { Margin = new Thickness(40, 18, 40, 30), MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        p.Children.Add(Ui.T("Settings", 34, serif: true));

        UIElement Section(string name, params UIElement[] rows)
        {
            var box = new StackPanel();
            for (int i = 0; i < rows.Length; i++)
            {
                if (rows[i] is FrameworkElement fe) fe.Margin = new Thickness(0, 11, 0, 11);
                box.Children.Add(rows[i]);
                if (i < rows.Length - 1) box.Children.Add(Ui.Divider(0, 0));
            }
            var label = Ui.T(name, 12, Ui.Sub); label.Margin = new Thickness(8, 18, 0, 6);
            var b = Ui.Box(box, Ui.Card, 18, 0); b.Padding = new Thickness(16, 2, 16, 2);
            return Ui.Stack(0, Orientation.Vertical, label, b);
        }

        var k = Store.Settings.ApiKey;
        var keyText = Ui.T(k.Length > 6 ? "gsk_••••" + k[^3..] : "Not set", 13, Ui.Sub); keyText.Margin = new Thickness(0, 0, 12, 0); keyText.VerticalAlignment = VerticalAlignment.Center;
        var keyEdit = new PasswordBox { FontSize = 13, Padding = new Thickness(10, 7, 10, 7), Width = 260, Visibility = Visibility.Collapsed };
        var change = Ui.Pill("Change", false, () => { }, 30);
        change.Click += (_, _) =>
        {
            if (keyEdit.Visibility == Visibility.Collapsed) { keyEdit.Visibility = Visibility.Visible; keyEdit.Focus(); ((StackPanel)change.Content).Children.OfType<TextBlock>().First().Text = "Save"; return; }
            if (keyEdit.Password.Trim().Length > 10) { Store.Settings.ApiKey = keyEdit.Password.Trim(); Store.Settings.Model = ""; Store.Save(); }
            Render();
        };
        var keyRight = new StackPanel { Orientation = Orientation.Horizontal }; keyRight.Children.Add(keyText); keyRight.Children.Add(keyEdit); keyRight.Children.Add(change);

        var placement = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var (label, next) in new[] { ("Next to question", true), ("Right side panel", false) })
        {
            bool on = Store.Settings.CardNextToQuestion == next;
            var c = Ui.Plain(new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(12, 6, 12, 6), Margin = new Thickness(6, 0, 0, 0), Background = on ? Ui.Ink : Ui.Line, Child = Ui.T(label, 12, on ? Ui.White : Ui.Ink, bold: on) },
                () => { Store.Settings.CardNextToQuestion = next; Store.Save(); Render(); });
            placement.Children.Add(c);
        }

        bool confirm = false;
        var clearLabel = Ui.T("Clear history", 14); clearLabel.FontWeight = FontWeights.Medium;
        var clear = Ui.Plain(clearLabel, () =>
        {
            if (!confirm) { confirm = true; clearLabel.Text = "Click again to delete all history"; return; }
            Store.Clear(); Render();
        });

        p.Children.Add(Section("AI",
            Row("Groq API key", keyRight),
            Row("Model", Ui.T(Store.Settings.Model.Length > 0 ? "Auto · " + Store.Settings.Model.Split('/').Last() : "Auto", 13, Ui.Sub))));
        p.Children.Add(Section("Shortcuts",
            Row("One question", Ui.KeyCap("Ctrl + Alt + B")),
            Row("Full screen", Ui.KeyCap("Ctrl + Alt + Shift + B"))));
        p.Children.Add(Section("Answers",
            Row("Include explanation", Ui.Toggle(Store.Settings.Explanations, on => { Store.Settings.Explanations = on; Store.Save(); })),
            Row("Answer card position", placement),
            clear));
        p.Children.Add(Section("App",
            Row("Start StudyLens with Windows", Ui.Toggle(Store.Settings.StartWithWindows, on => { Store.Settings.StartWithWindows = on; Store.Save(); AutoStart.Apply(on); })),
            Row("Quit StudyLens", Ui.Pill("Quit", false, app.Quit, 30))));
        p.Children.Add(Section("Educational use only", Ui.T(Disclaimer.Full, 13, Ui.Sub)));
        return new ScrollViewer { Content = p, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    }
}
