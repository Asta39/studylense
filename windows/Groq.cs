using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace StudyLens;

public class GroqException : Exception { public GroqException(string m) : base(m) { } }

/// <summary>Groq's OpenAI-compatible API: picks an image-capable model and turns screenshots into answers.</summary>
public class Groq
{
    const string Base = "https://api.groq.com/openai/v1";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(70) };
    readonly Store store;
    public Groq(Store store) { this.store = store; }

    public async Task<List<Answer>> AnswerAsync(Bitmap image, bool fullScreen)
    {
        var key = store.Settings.ApiKey;
        if (string.IsNullOrWhiteSpace(key)) throw new GroqException("Add your Groq API key in StudyLens settings first.");
        var jpg = Encode(image);
        var models = store.Settings.Model.Length > 0 ? new List<string> { store.Settings.Model } : await VisionModelsAsync(key);
        Exception last = new GroqException("No model available");
        foreach (var m in models)
        {
            try
            {
                var result = await AskAsync(jpg, m, key, fullScreen);
                store.Settings.Model = m; store.Save();
                return result;
            }
            catch (GroqException e)
            {
                last = e; store.Settings.Model = ""; store.Save();
                if (e.Message.Contains("rate limit", StringComparison.OrdinalIgnoreCase)) throw;
            }
        }
        throw last;
    }

    async Task<List<string>> VisionModelsAsync(string key)
    {
        var json = await SendAsync(HttpMethod.Get, $"{Base}/models", key, null);
        var ids = json["data"]?.AsArray().Select(d => d?["id"]?.GetValue<string>() ?? "").ToList() ?? new();
        var prefs = new[] { "llama-4-maverick", "llama-4-scout", "llama-4", "vision", "-vl", "gemma-3", "qwen3.8", "qwen" };
        var outList = new List<string>();
        foreach (var p in prefs) foreach (var id in ids) if (id.ToLowerInvariant().Contains(p) && !outList.Contains(id)) outList.Add(id);
        if (outList.Count == 0) throw new GroqException("Your Groq key has no model that can read images. Models: " + string.Join(", ", ids));
        return outList;
    }

    async Task<List<Answer>> AskAsync(string jpg, string model, string key, bool fullScreen)
    {
        var explain = store.Settings.Explanations ? "one short sentence on why it is correct" : "";
        var scope = fullScreen
            ? "Find EVERY question visible in the screenshot (often 2-3, may have parts (i), (ii)…) and answer each one."
            : "The screenshot is cropped around ONE question (it may have parts (i), (ii)…). Answer it.";
        var prompt = $$"""
            {{scope}}
            Solve carefully and check your work BEFORE writing. Output only final, verified results — never write corrections, "wait", or recalculations.
            Reply with JSON only, no markdown fences, in exactly this shape:
            {"answers":[{"number":"1","question":"<question, max 15 words>","type":"choice|short|work","answer":"<see below>","answerText":"<option text for choice, else empty>","steps":["<step>"],"explanation":"{{explain}}"}]}
            type "choice": multiple choice — answer is the letter(s) only, e.g. "C" or "A, C".
            type "short": a word/phrase/definition — answer is that short text.
            type "work": a calculation or multi-part problem — answer is the final result(s), one per part separated by "; " like "(i) π = 45q − 2q² + 16; (ii) q = 11.25". steps = 2-5 short worked steps.
            If there are no questions, reply {"answers":[]}.
            """;
        var body = new JsonObject
        {
            ["model"] = model,
            ["temperature"] = 0.2,
            ["max_completion_tokens"] = fullScreen ? 950 : 700,
            ["messages"] = new JsonArray(new JsonObject
            {
                ["role"] = "user",
                ["content"] = new JsonArray(
                    new JsonObject { ["type"] = "text", ["text"] = prompt },
                    new JsonObject { ["type"] = "image_url", ["image_url"] = new JsonObject { ["url"] = "data:image/jpeg;base64," + jpg } })
            })
        };
        var json = await SendAsync(HttpMethod.Post, $"{Base}/chat/completions", key, body);
        var text = json["choices"]?[0]?["message"]?["content"]?.GetValue<string>() ?? throw new GroqException("Unexpected response from Groq");
        return Parse(text);
    }

    static List<Answer> Parse(string raw)
    {
        var text = Regex.Replace(raw, "(?s)<think>.*?</think>", "").Replace("```json", "").Replace("```", "").Trim();
        int s = text.IndexOf('{'), e = text.LastIndexOf('}');
        if (s >= 0 && e > s)
        {
            try
            {
                var arr = JsonNode.Parse(text[s..(e + 1)])?["answers"]?.AsArray();
                if (arr != null)
                {
                    if (arr.Count == 0) throw new GroqException("No questions found on screen. Try One question (Ctrl+Alt+B) and drag a box around it.");
                    return arr.Select(a => new Answer
                    {
                        Number = a?["number"]?.ToString() ?? "",
                        Question = a?["question"]?.ToString() ?? "",
                        Type = a?["type"]?.ToString() ?? "choice",
                        Text = a?["answer"]?.ToString() ?? "",
                        AnswerText = a?["answerText"]?.ToString() ?? "",
                        Steps = a?["steps"]?.AsArray().Select(x => x?.ToString() ?? "").Where(x => x.Length > 0).ToList() ?? new(),
                        Explanation = a?["explanation"]?.ToString() ?? "",
                    }).ToList();
                }
            }
            catch (GroqException) { throw; }
            catch { /* fall through */ }
        }
        return new List<Answer> { new() { Number = "1", Type = "short", Text = "See explanation", Explanation = text } };
    }

    static async Task<JsonNode> SendAsync(HttpMethod method, string url, string key, JsonObject? body)
    {
        using var req = new HttpRequestMessage(method, url);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (body != null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var resp = await Http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        JsonNode json;
        try { json = JsonNode.Parse(text) ?? new JsonObject(); } catch { json = new JsonObject(); }
        if (!resp.IsSuccessStatusCode)
        {
            var msg = json["error"]?["message"]?.ToString() ?? $"HTTP {(int)resp.StatusCode}";
            throw new GroqException((int)resp.StatusCode == 429 ? $"Groq rate limit reached — wait a minute. ({msg})" : "Groq error: " + msg);
        }
        return json;
    }

    /// <summary>JPEG, longest side at most 2400px so small text stays readable.</summary>
    static string Encode(Bitmap src)
    {
        double scale = Math.Min(1.0, 2400.0 / Math.Max(src.Width, src.Height));
        using var bmp = scale < 1 ? new Bitmap(src, (int)(src.Width * scale), (int)(src.Height * scale)) : new Bitmap(src);
        using var ms = new MemoryStream();
        var enc = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        var ps = new EncoderParameters(1) { Param = { [0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 85L) } };
        bmp.Save(ms, enc, ps);
        return Convert.ToBase64String(ms.ToArray());
    }
}
