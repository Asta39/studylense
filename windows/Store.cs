using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace StudyLens;

/// <summary>Shown across the app: StudyLens is a learning tool, not a way to cheat.</summary>
public static class Disclaimer
{
    public const string Short = "For learning and practice only — never for cheating.";
    public const string Full = "StudyLens is an educational tool for studying, homework and practice. " +
        "Don't use it in exams, tests, quizzes or any graded work, or anywhere your school's rules don't allow it. " +
        "Using it to cheat is academic malpractice. AI answers can be wrong, so always check the working.";
}

public class Answer
{
    public string Number { get; set; } = "";
    public string Question { get; set; } = "";
    public string Type { get; set; } = "choice"; // choice | short | work
    public string Text { get; set; } = "";        // the answer: "C", "A, C", or a result
    public string AnswerText { get; set; } = "";  // option text for choice
    public List<string> Steps { get; set; } = new();
    public string Explanation { get; set; } = "";

    [JsonIgnore]
    public bool IsLetter => Type == "choice" && Text.Length <= 6 && Text.All(c => char.IsLetter(c) || c == ',' || c == ' ');

    [JsonIgnore]
    public List<string> Parts => Regex.Split(Text, @";\s*|\n").Select(p => p.Trim()).Where(p => p.Length > 0).ToList();

    public string CopyText()
    {
        var s = (Question.Length > 0 ? Question + "\n" : "") + Text + (AnswerText.Length > 0 ? " — " + AnswerText : "");
        for (int i = 0; i < Steps.Count; i++) s += $"\n{i + 1}. {Steps[i]}";
        if (Explanation.Length > 0) s += "\n" + Explanation;
        return s;
    }
}

public class Scan
{
    public long Id { get; set; }
    public bool FullScreen { get; set; }
    public List<Answer> Answers { get; set; } = new();

    [JsonIgnore]
    public DateTime Time => DateTimeOffset.FromUnixTimeMilliseconds(Id).LocalDateTime;

    [JsonIgnore]
    public string Title => FullScreen
        ? $"Full screen · {Answers.Count} question{(Answers.Count == 1 ? "" : "s")}"
        : (Answers.FirstOrDefault()?.Question is { Length: > 0 } q ? q : "One question");
}

public class Settings
{
    public string ApiKey { get; set; } = "";
    public string Model { get; set; } = "";
    public bool Onboarded { get; set; }
    public bool Explanations { get; set; } = true;
    public bool CardNextToQuestion { get; set; } = true;
    public bool StartWithWindows { get; set; } = true;
    public bool AgreedEducationalUse { get; set; }
}

/// <summary>Settings, history and screenshots under %AppData%\StudyLens.</summary>
public class Store
{
    static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "StudyLens");
    static string SettingsFile => Path.Combine(Dir, "settings.json");
    static string HistoryFile => Path.Combine(Dir, "history.json");
    static string ShotsDir => Path.Combine(Dir, "shots");

    public Settings Settings { get; private set; }
    public event Action? HistoryChanged;

    public Store()
    {
        Directory.CreateDirectory(ShotsDir);
        Settings = Load<Settings>(SettingsFile) ?? new Settings();
    }

    static T? Load<T>(string path)
    {
        try { return File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllText(path)) : default; }
        catch { return default; }
    }

    public void Save() => File.WriteAllText(SettingsFile, JsonSerializer.Serialize(Settings, Json));

    public List<Scan> History() => Load<List<Scan>>(HistoryFile) ?? new List<Scan>();

    public void Add(Scan scan, System.Drawing.Bitmap? shot)
    {
        var list = History(); list.Insert(0, scan);
        File.WriteAllText(HistoryFile, JsonSerializer.Serialize(list.Take(300).ToList(), Json));
        try { shot?.Save(ShotPath(scan.Id), System.Drawing.Imaging.ImageFormat.Jpeg); } catch { }
        HistoryChanged?.Invoke();
    }

    public string ShotPath(long id) => Path.Combine(ShotsDir, $"shot_{id}.jpg");

    public void Delete(long id)
    {
        File.WriteAllText(HistoryFile, JsonSerializer.Serialize(History().Where(s => s.Id != id).ToList(), Json));
        try { File.Delete(ShotPath(id)); } catch { }
        HistoryChanged?.Invoke();
    }

    public void Clear()
    {
        try { File.Delete(HistoryFile); foreach (var f in Directory.GetFiles(ShotsDir)) File.Delete(f); } catch { }
        HistoryChanged?.Invoke();
    }
}
