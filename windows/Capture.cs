using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using Rectangle = System.Drawing.Rectangle;
using Forms = System.Windows.Forms;

namespace StudyLens;

/// <summary>Win32 helpers for placing windows in physical pixels (works across mixed-DPI monitors).</summary>
public static class Native
{
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr h, int id, uint mods, uint vk);
    [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr h, int id);
    const uint NOZORDER = 0x0004, NOACTIVATE = 0x0010, NOSIZE = 0x0001;

    public static void Cover(Window w, Rectangle px)
    {
        var h = new WindowInteropHelper(w).EnsureHandle();
        SetWindowPos(h, IntPtr.Zero, px.Left, px.Top, px.Width, px.Height, NOZORDER | NOACTIVATE);
    }

    public static void MoveTo(Window w, int x, int y)
    {
        var h = new WindowInteropHelper(w).EnsureHandle();
        SetWindowPos(h, IntPtr.Zero, x, y, 0, 0, NOZORDER | NOSIZE | NOACTIVATE);
    }

    /// <summary>Size of a shown window in physical pixels.</summary>
    public static System.Drawing.Size PixelSize(Window w)
    {
        var src = PresentationSource.FromVisual(w);
        double sx = src?.CompositionTarget?.TransformToDevice.M11 ?? 1, sy = src?.CompositionTarget?.TransformToDevice.M22 ?? 1;
        return new System.Drawing.Size((int)(w.ActualWidth * sx), (int)(w.ActualHeight * sy));
    }

    /// <summary>Place a window next to a physical point, kept inside that monitor's work area.</summary>
    public static void PlaceNear(Window w, System.Drawing.Point p, int gap = 12)
    {
        var size = PixelSize(w);
        var wa = Forms.Screen.FromPoint(p).WorkingArea;
        int x = p.X + gap, y = p.Y - size.Height / 3;
        if (x + size.Width > wa.Right) x = p.X - gap - size.Width;
        x = Math.Clamp(x, wa.Left, Math.Max(wa.Left, wa.Right - size.Width));
        y = Math.Clamp(y, wa.Top, Math.Max(wa.Top, wa.Bottom - size.Height));
        MoveTo(w, x, y);
    }
}

public static class Capture
{
    /// <summary>Screenshot of the monitor under the cursor (physical pixels).</summary>
    public static (Bitmap shot, Rectangle bounds) MonitorUnderCursor()
    {
        var bounds = Forms.Screen.FromPoint(Forms.Cursor.Position).Bounds;
        var bmp = new Bitmap(bounds.Width, bounds.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);
        return (bmp, bounds);
    }

    public static BitmapSource ToSource(Bitmap b)
    {
        var h = b.GetHbitmap();
        try { return Imaging.CreateBitmapSourceFromHBitmap(h, IntPtr.Zero, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions()); }
        finally { DeleteObject(h); }
    }
    [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);
}

/// <summary>Full-monitor overlay over a frozen screenshot: drag a box around one question.</summary>
public class SelectWindow : Window
{
    readonly Bitmap shot;
    readonly Rectangle monitor;
    readonly Canvas canvas = new();
    readonly System.Windows.Shapes.Path dim = new() { Fill = new SolidColorBrush(System.Windows.Media.Color.FromArgb(130, 20, 20, 22)) };
    readonly System.Windows.Shapes.Rectangle frame = new() { Stroke = System.Windows.Media.Brushes.White, StrokeThickness = 2, RadiusX = 12, RadiusY = 12, Visibility = Visibility.Collapsed };
    readonly StackPanel actions;
    System.Windows.Point start; Rect sel = Rect.Empty; bool dragging;

    /// <summary>Crop in screenshot pixels and the selection's top-right corner on screen (pixels), or null if cancelled.</summary>
    public (Rectangle crop, System.Drawing.Point anchor)? Result { get; private set; }

    public SelectWindow(Bitmap shot, Rectangle monitor)
    {
        this.shot = shot; this.monitor = monitor;
        WindowStyle = WindowStyle.None; ResizeMode = ResizeMode.NoResize; Topmost = true; ShowInTaskbar = false;
        Cursor = Cursors.Cross; Background = System.Windows.Media.Brushes.Black;

        var root = new Grid();
        root.Children.Add(new System.Windows.Controls.Image { Source = Capture.ToSource(shot), Stretch = Stretch.Fill });
        canvas.Background = System.Windows.Media.Brushes.Transparent;
        canvas.Children.Add(dim); canvas.Children.Add(frame);
        root.Children.Add(canvas);

        var hintRow = new StackPanel { Orientation = Orientation.Horizontal };
        var mark = new System.Windows.Controls.Image { Source = Ui.Asset("mark.png"), Width = 20, Height = 20, Clip = new EllipseGeometry(new System.Windows.Point(10, 10), 10, 10), Margin = new Thickness(0, 0, 10, 0) };
        hintRow.Children.Add(mark);
        hintRow.Children.Add(Ui.T("Drag a box around one question", 13, bold: true));
        var esc = Ui.Box(Ui.T("Esc to cancel", 11, Ui.Sub), Ui.Line, 6, 3); esc.Margin = new Thickness(10, 0, 0, 0); esc.Padding = new Thickness(7, 2, 7, 2);
        hintRow.Children.Add(esc);
        var hint = Ui.Box(hintRow, Ui.Card, 20, 10);
        hint.Padding = new Thickness(18, 9, 18, 9); hint.HorizontalAlignment = HorizontalAlignment.Center; hint.VerticalAlignment = VerticalAlignment.Top; hint.Margin = new Thickness(0, 22, 0, 0);
        root.Children.Add(hint);

        actions = new StackPanel { Orientation = Orientation.Horizontal, Visibility = Visibility.Collapsed };
        actions.Children.Add(Ui.Pill("Cancel", false, Cancel));
        var go = Ui.Pill("Answer this", true, Done, hint: "Enter"); go.Margin = new Thickness(8, 0, 0, 0);
        actions.Children.Add(go);
        canvas.Children.Add(actions);

        Content = root;
        SourceInitialized += (_, _) => Native.Cover(this, monitor);
        Loaded += (_, _) => { Activate(); Focus(); UpdateDim(); };
        KeyDown += (_, e) => { if (e.Key == Key.Escape) Cancel(); if (e.Key == Key.Enter) Done(); };
        canvas.MouseLeftButtonDown += (_, e) => { start = e.GetPosition(canvas); dragging = true; actions.Visibility = Visibility.Collapsed; canvas.CaptureMouse(); };
        canvas.MouseMove += (_, e) => { if (!dragging) return; var p = e.GetPosition(canvas); sel = new Rect(start, p); UpdateDim(); };
        canvas.MouseLeftButtonUp += (_, e) =>
        {
            dragging = false; canvas.ReleaseMouseCapture();
            if (sel.Width > 30 && sel.Height > 18)
            {
                actions.Visibility = Visibility.Visible;
                Canvas.SetLeft(actions, Math.Min(sel.Left, ActualWidth - 260));
                Canvas.SetTop(actions, sel.Bottom + 14 > ActualHeight - 60 ? sel.Top - 56 : sel.Bottom + 14);
            }
        };
    }

    void UpdateDim()
    {
        var all = new RectangleGeometry(new Rect(0, 0, ActualWidth, ActualHeight));
        if (sel.IsEmpty) { dim.Data = all; frame.Visibility = Visibility.Collapsed; return; }
        dim.Data = new CombinedGeometry(GeometryCombineMode.Exclude, all, new RectangleGeometry(sel, 12, 12));
        frame.Visibility = Visibility.Visible; frame.Width = sel.Width; frame.Height = sel.Height;
        Canvas.SetLeft(frame, sel.Left); Canvas.SetTop(frame, sel.Top);
    }

    void Cancel() { Result = null; Close(); }

    void Done()
    {
        if (sel.IsEmpty || sel.Width < 30 || sel.Height < 18) return;
        double kx = shot.Width / ActualWidth, ky = shot.Height / ActualHeight;
        int x = Math.Clamp((int)(sel.Left * kx), 0, shot.Width - 1), y = Math.Clamp((int)(sel.Top * ky), 0, shot.Height - 1);
        int w = Math.Clamp((int)(sel.Width * kx), 1, shot.Width - x), h = Math.Clamp((int)(sel.Height * ky), 1, shot.Height - y);
        Result = (new Rectangle(x, y, w, h), new System.Drawing.Point(monitor.Left + x + w, monitor.Top + y + h / 3));
        Close();
    }
}
