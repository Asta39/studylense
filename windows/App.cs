using System;
using System.Drawing;
using System.Threading;
using System.Windows;
using System.Windows.Interop;
using Forms = System.Windows.Forms;

namespace StudyLens;

public static class Program
{
    [STAThread]
    public static void Main()
    {
        using var single = new Mutex(true, "StudyLens.SingleInstance", out bool first);
        if (!first) return; // already running in the tray
        new App().Run();
    }
}

/// <summary>Tray app: owns the tray icon, the global shortcuts and the capture → Groq → answer flow.</summary>
public class App : Application
{
    const int HotOne = 1, HotFull = 2;
    const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_NOREPEAT = 0x4000, VK_B = 0x42;

    public Store Store { get; } = new();
    Forms.NotifyIcon? icon;
    HwndSource? hotkeySink;
    MainWindow? main;
    bool busy;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnExplicitShutdown;

        icon = new Forms.NotifyIcon
        {
            Icon = new Icon(GetResourceStream(new Uri("pack://application:,,,/Assets/studylens.ico")).Stream),
            Text = "StudyLens — Ctrl+Alt+B / Ctrl+Alt+Shift+B",
            Visible = true,
        };
        icon.MouseUp += (_, ev) => { if (ev.Button == Forms.MouseButtons.Left || ev.Button == Forms.MouseButtons.Right) ShowTrayMenu(); };
        icon.DoubleClick += (_, _) => OpenMain("history");

        RegisterHotkeys();
        AutoStart.Apply(Store.Settings.StartWithWindows);

        if (!Store.Settings.Onboarded || Store.Settings.ApiKey.Length == 0) OpenMain("setup");
        else icon.ShowBalloonTip(2500, "StudyLens is ready", "Ctrl+Alt+B for one question · Ctrl+Alt+Shift+B for the full screen", Forms.ToolTipIcon.None);
    }

    void RegisterHotkeys()
    {
        var p = new HwndSourceParameters("StudyLensHotkeys") { Width = 0, Height = 0, WindowStyle = 0 };
        hotkeySink = new HwndSource(p);
        hotkeySink.AddHook((IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled) =>
        {
            if (msg == 0x0312) // WM_HOTKEY
            {
                handled = true;
                if (wParam.ToInt32() == HotOne) StartOne(); else if (wParam.ToInt32() == HotFull) StartFull();
            }
            return IntPtr.Zero;
        });
        bool ok1 = Native.RegisterHotKey(hotkeySink.Handle, HotOne, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, VK_B);
        bool ok2 = Native.RegisterHotKey(hotkeySink.Handle, HotFull, MOD_CONTROL | MOD_ALT | MOD_SHIFT | MOD_NOREPEAT, VK_B);
        if (!ok1 || !ok2)
            icon?.ShowBalloonTip(4000, "Shortcut already in use", "Another app is using Ctrl+Alt+B. Use the tray menu instead.", Forms.ToolTipIcon.Warning);
    }

    /// <summary>Shown right after setup so people know where the app went.</summary>
    public void StartOneHint() =>
        icon?.ShowBalloonTip(4000, "StudyLens is in your tray", "Press Ctrl+Alt+B over a question, or Ctrl+Alt+Shift+B for the whole screen.", Forms.ToolTipIcon.None);

    void ShowTrayMenu() => Overlays.ShowTrayMenu(Store, StartOne, StartFull, () => OpenMain("history"), () => OpenMain("settings"), Quit);

    public void OpenMain(string page, long? scanId = null)
    {
        if (main == null || !main.IsLoaded) { main = new MainWindow(this); main.Closed += (_, _) => main = null; }
        main.Navigate(page, scanId);
        main.Show(); if (main.WindowState == WindowState.Minimized) main.WindowState = WindowState.Normal;
        main.Activate();
    }

    bool Ready()
    {
        if (busy) return false;
        if (Store.Settings.ApiKey.Length == 0) { OpenMain("setup"); return false; }
        return true;
    }

    public void StartOne()
    {
        if (!Ready()) return;
        Overlays.CloseAll();
        // Let our own windows disappear before the screenshot.
        Dispatcher.BeginInvoke(new Action(async () =>
        {
            await System.Threading.Tasks.Task.Delay(120);
            var (shot, bounds) = Capture.MonitorUnderCursor();
            var sel = new SelectWindow(shot, bounds);
            sel.ShowDialog();
            if (sel.Result is not { } r) { shot.Dispose(); return; }
            using var crop = shot.Clone(r.crop, shot.PixelFormat);
            shot.Dispose();
            await Ask(new Bitmap(crop), false, r.anchor);
        }));
    }

    public void StartFull()
    {
        if (!Ready()) return;
        Overlays.CloseAll();
        Dispatcher.BeginInvoke(new Action(async () =>
        {
            await System.Threading.Tasks.Task.Delay(120);
            var (shot, _) = Capture.MonitorUnderCursor();
            await Ask(shot, true, Forms.Cursor.Position);
        }));
    }

    async System.Threading.Tasks.Task Ask(Bitmap image, bool full, System.Drawing.Point anchor)
    {
        busy = true;
        Overlays.ShowReading(full ? "Reading the whole screen…" : "Reading the question…");
        try
        {
            var answers = await new Groq(Store).AnswerAsync(image, full);
            var scan = new Scan { Id = DateTimeOffset.Now.ToUnixTimeMilliseconds(), FullScreen = full, Answers = answers };
            Store.Add(scan, image);
            Overlays.HideReading();
            if (full || answers.Count > 1 || !Store.Settings.CardNextToQuestion)
                Overlays.ShowPanel(scan, StartFull, id => OpenMain("history", id));
            else
                Overlays.ShowCard(scan, anchor, StartOne);
        }
        catch (Exception ex)
        {
            Overlays.HideReading();
            Overlays.ShowError(ex is GroqException ? ex.Message : "Couldn’t reach Groq: " + ex.Message);
        }
        finally { busy = false; image.Dispose(); }
    }

    public void Quit()
    {
        if (hotkeySink != null) { Native.UnregisterHotKey(hotkeySink.Handle, HotOne); Native.UnregisterHotKey(hotkeySink.Handle, HotFull); }
        if (icon != null) { icon.Visible = false; icon.Dispose(); }
        Shutdown();
    }
}

/// <summary>"Start with Windows" via the per-user Run key.</summary>
public static class AutoStart
{
    const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public static void Apply(bool on)
    {
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(Key, true);
            if (k == null) return;
            if (on) k.SetValue("StudyLens", $"\"{Environment.ProcessPath}\"");
            else k.DeleteValue("StudyLens", false);
        }
        catch { }
    }
}
