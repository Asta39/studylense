// StudyLens — menu bar helper: hotkey captures the screen around the cursor,
// sends it to Groq's vision model, and shows the answer + explanation in a popover.
import AppKit
import Carbon.HIToolbox
import ScreenCaptureKit
import SwiftUI

// MARK: - Model

@MainActor
final class AppState: ObservableObject {
    struct Entry: Identifiable { let id = UUID(); let date = Date(); let image: NSImage; let text: String }
    @Published var status = "Press ⌃⌥B with your cursor over a question."
    @Published var busy = false
    @Published var history: [Entry] = []

    var apiKey: String {
        get { UserDefaults.standard.string(forKey: "groqKey") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "groqKey"); objectWillChange.send() }
    }
}

// MARK: - Groq

enum Groq {
    nonisolated(unsafe) static var cachedModel: String?

    /// Image-capable models this key can use, best first.
    static func visionModels(key: String) async throws -> [String] {
        var req = URLRequest(url: URL(string: "https://api.groq.com/openai/v1/models")!)
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await URLSession.shared.data(for: req)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let ids = ((json?["data"] as? [[String: Any]]) ?? []).compactMap { $0["id"] as? String }
        let prefs = ["llama-4-maverick", "llama-4-scout", "llama-4", "vision", "-vl", "gemma-3", "qwen3.8", "qwen"]
        var out: [String] = []
        for p in prefs { for id in ids where id.lowercased().contains(p) && !out.contains(id) { out.append(id) } }
        AppDelegate.log("vision candidates: \(out) (all: \(ids))")
        if out.isEmpty { throw err("No image-capable model on this Groq key. Models: \(ids.joined(separator: ", "))") }
        return out
    }

    static func ask(image: NSImage, key: String) async throws -> String {
        let jpg = try encode(image)
        let models: [String]
        if let c = cachedModel { models = [c] } else { models = try await visionModels(key: key) }
        var lastError: Error = err("No model tried")
        for m in models {
            do {
                let text = try await ask(jpg: jpg, model: m, key: key)
                cachedModel = m
                AppDelegate.log("model used: \(m)")
                return text
            } catch { lastError = error; AppDelegate.log("model \(m) failed: \(error.localizedDescription)"); cachedModel = nil }
        }
        throw lastError
    }

    /// JPEG, downscaled so the longest side is at most 1280px.
    static func encode(_ image: NSImage) throws -> Data {
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { throw err("Could not read screenshot") }
        let scale = min(1, 1280 / CGFloat(max(cg.width, cg.height)))
        let w = Int(CGFloat(cg.width) * scale), h = Int(CGFloat(cg.height) * scale)
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { throw err("Could not resize screenshot") }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let small = ctx.makeImage(),
              let jpg = NSBitmapImageRep(cgImage: small).representation(using: .jpeg, properties: [.compressionFactor: 0.75])
        else { throw err("Could not encode screenshot") }
        return jpg
    }

    static func ask(jpg: Data, model: String, key: String) async throws -> String {

        let prompt = """
        The image shows part of a screen. Find the question nearest the center of the image \
        (where the user's cursor was). Answer it, then explain the reasoning step by step so \
        the user can learn it. Format:
        Answer: <short answer>
        Explanation: <clear explanation>
        """
        let body: [String: Any] = [
            "model": model,
            "temperature": 0.2,
            "max_completion_tokens": 700,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "text", "text": prompt],
                    ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64," + jpg.base64EncodedString()]],
                ],
            ]],
        ]
        var req = URLRequest(url: URL(string: "https://api.groq.com/openai/v1/chat/completions")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 60

        let (data, resp) = try await URLSession.shared.data(for: req)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        if let code = (resp as? HTTPURLResponse)?.statusCode, code != 200 {
            let msg = (json?["error"] as? [String: Any])?["message"] as? String ?? "HTTP \(code)"
            throw err("Groq error: \(msg)")
        }
        guard let choices = json?["choices"] as? [[String: Any]],
              let msg = choices.first?["message"] as? [String: Any],
              let text = msg["content"] as? String
        else { throw err("Unexpected response from Groq") }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func err(_ s: String) -> NSError { NSError(domain: "StudyLens", code: 1, userInfo: [NSLocalizedDescriptionKey: s]) }
}

// MARK: - Screen capture around cursor

enum Capture {
    static func aroundCursor(width: CGFloat = 1000, height: CGFloat = 560) async throws -> NSImage {
        let mouse = NSEvent.mouseLocation // Cocoa coords, origin bottom-left of main screen
        guard let screen = NSScreen.screens.first(where: { NSMouseInRect(mouse, $0.frame, false) }) ?? NSScreen.main,
              let displayID = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID
        else { throw Groq.err("No screen found") }

        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first(where: { $0.displayID == displayID })
        else { throw Groq.err("Display not available for capture") }

        // Convert cursor to display-local, top-left-origin coordinates.
        let f = screen.frame
        let localX = mouse.x - f.minX
        let localY = f.maxY - mouse.y
        var rect = CGRect(x: localX - width / 2, y: localY - height / 2, width: width, height: height)
        rect = rect.intersection(CGRect(origin: .zero, size: f.size))

        let filter = SCContentFilter(display: display, excludingWindows: [])
        let config = SCStreamConfiguration()
        config.sourceRect = rect
        config.width = Int(rect.width * screen.backingScaleFactor)
        config.height = Int(rect.height * screen.backingScaleFactor)
        config.showsCursor = false
        let cg = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
        return NSImage(cgImage: cg, size: rect.size)
    }
}

// MARK: - UI

struct PopoverView: View {
    @ObservedObject var state: AppState
    @State private var keyField = ""
    var onAsk: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("StudyLens").font(.headline)
                Spacer()
                if state.busy { ProgressView().controlSize(.small) }
                Button("Ask now", action: onAsk).disabled(state.busy || state.apiKey.isEmpty)
            }
            if state.apiKey.isEmpty {
                Text("Paste your Groq API key (console.groq.com/keys):").font(.caption)
                HStack {
                    SecureField("gsk_…", text: $keyField)
                    Button("Save") { state.apiKey = keyField.trimmingCharacters(in: .whitespaces) }
                }
            }
            Text(state.status).font(.caption).foregroundStyle(.secondary)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(state.history) { e in
                        VStack(alignment: .leading, spacing: 6) {
                            Image(nsImage: e.image).resizable().scaledToFit().frame(maxHeight: 110)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                            Text(e.text).textSelection(.enabled).font(.system(size: 13))
                            Text(e.date.formatted(date: .omitted, time: .shortened)).font(.caption2).foregroundStyle(.secondary)
                        }
                        Divider()
                    }
                }
            }
            HStack {
                if !state.apiKey.isEmpty { Button("Change key") { state.apiKey = "" } }
                Button("Clear history") { state.history.removeAll() }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }.font(.caption)
        }
        .padding(14)
        .frame(width: 420, height: 520)
    }
}

struct AnswerCard: View {
    let answer: String, reason: String
    var onOpen: () -> Void, onClose: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(answer).font(.system(size: 15, weight: .semibold)).lineLimit(2)
                if !reason.isEmpty { Text(reason).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(4) }
                Text("Click for full explanation").font(.caption2).foregroundStyle(.tertiary)
            }
            .frame(width: 300, alignment: .leading)
            Button(action: onClose) { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                .buttonStyle(.plain)
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }
}

// MARK: - App delegate / menu bar

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let state = AppState()
    var item: NSStatusItem!
    var window: NSWindow!
    var hotKeyRef: EventHotKeyRef?

    func applicationDidFinishLaunching(_ n: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setIcon("text.viewfinder")
        item.button?.action = #selector(toggle)
        item.button?.target = self

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 420, height: 520),
                          styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "StudyLens"
        window.isReleasedWhenClosed = false
        window.level = .floating
        window.contentViewController = NSHostingController(rootView: PopoverView(state: state) { [weak self] in
            self?.window.orderOut(nil); Task { await self?.run() }
        })
        window.center()
        registerHotKey()
        if state.apiKey.isEmpty { toggle() }
    }

    func setIcon(_ symbol: String) {
        item.button?.image = NSImage(systemSymbolName: symbol, accessibilityDescription: "StudyLens")
    }

    @objc func toggle() {
        if window.isVisible && window.isKeyWindow { window.orderOut(nil); return }
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    nonisolated static func log(_ s: String) {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/StudyLens.log")
        let line = "\(Date()) \(s)\n"
        if let h = try? FileHandle(forWritingTo: url) { h.seekToEndOfFile(); h.write(line.data(using: .utf8)!); try? h.close() }
        else { try? line.write(to: url, atomically: true, encoding: .utf8) }
    }

    func run() async {
        Self.log("hotkey/run triggered (busy=\(state.busy), hasKey=\(!state.apiKey.isEmpty))")
        guard !state.busy else { return }
        guard !state.apiKey.isEmpty else { toggle(); return }
        state.busy = true; state.status = "Capturing and asking Groq…"; setIcon("hourglass")
        defer { state.busy = false }
        do {
            let img = try await Capture.aroundCursor()
            let text = try await Groq.ask(image: img, key: state.apiKey)
            state.history.insert(.init(image: img, text: text), at: 0)
            state.status = "Answer ready."
            setIcon("checkmark.circle")
            Self.log("OK: \(text.prefix(80).replacingOccurrences(of: "\n", with: " "))")
            showCard(text)
        } catch {
            state.status = error.localizedDescription
            setIcon("exclamationmark.triangle")
            Self.log("ERROR: \(error.localizedDescription)")
            showCard("Answer: ⚠︎ Something went wrong\nExplanation: \(error.localizedDescription)")
        }
    }

    // MARK: Answer card near the cursor

    var card: NSPanel?

    func showCard(_ text: String) {
        card?.orderOut(nil)
        let (answer, reason) = Self.summarize(text)
        let view = AnswerCard(answer: answer, reason: reason,
            onOpen: { [weak self] in self?.closeCard(); self?.toggle() },
            onClose: { [weak self] in self?.closeCard() })
        let host = NSHostingView(rootView: view)
        host.frame.size = NSSize(width: 350, height: 10)
        host.layoutSubtreeIfNeeded()
        let size = NSSize(width: 350, height: min(max(host.fittingSize.height, 60), 220))
        Self.log("card size \(size)")
        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                            styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        panel.contentView = host
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .statusBar
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false

        // Place just below-right of the cursor, kept on screen.
        let m = NSEvent.mouseLocation
        let vf = (NSScreen.screens.first { NSMouseInRect(m, $0.frame, false) } ?? NSScreen.main!).visibleFrame
        var o = NSPoint(x: m.x + 16, y: m.y - 16 - size.height)
        o.x = min(max(o.x, vf.minX + 8), vf.maxX - size.width - 8)
        o.y = min(max(o.y, vf.minY + 8), vf.maxY - size.height - 8)
        panel.setFrameOrigin(o)
        panel.orderFrontRegardless()
        card = panel
    }

    func closeCard() { card?.orderOut(nil); card = nil; setIcon("text.viewfinder") }

    /// Pulls "Answer:" and the first sentence of "Explanation:" out of the model reply.
    static func summarize(_ text: String) -> (String, String) {
        func after(_ label: String) -> String? {
            guard let r = text.range(of: label, options: .caseInsensitive) else { return nil }
            return String(text[r.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        var answer = after("Answer:").map { $0.components(separatedBy: "\n").first ?? $0 } ?? text
        answer = answer.replacingOccurrences(of: "*", with: "").trimmingCharacters(in: .whitespaces)
        var reason = after("Explanation:") ?? ""
        reason = reason.replacingOccurrences(of: "*", with: "")
        if let end = reason.firstIndex(where: { ".!?\n".contains($0) }) { reason = String(reason[...end]) }
        return (answer, reason.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    // Global hotkey ⌃⌥B.
    func registerHotKey() {
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let ptr = Unmanaged.passUnretained(self).toOpaque()
        InstallEventHandler(GetApplicationEventTarget(), { _, _, ud in
            let me = Unmanaged<AppDelegate>.fromOpaque(ud!).takeUnretainedValue()
            Task { @MainActor in await me.run() }
            return noErr
        }, 1, &spec, ptr, nil)
        let id = EventHotKeyID(signature: OSType(0x53544c4e), id: 1)
        RegisterEventHotKey(UInt32(kVK_ANSI_B), UInt32(controlKey | optionKey), id,
                            GetApplicationEventTarget(), 0, &hotKeyRef)
    }
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = AppDelegate()
    app.delegate = delegate
    app.setActivationPolicy(.accessory)
    // Edit menu so ⌘X/⌘C/⌘V/⌘A work in text fields.
    let main = NSMenu(), editItem = NSMenuItem(), edit = NSMenu(title: "Edit")
    edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
    edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
    edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
    edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
    editItem.submenu = edit
    main.addItem(editItem)
    app.mainMenu = main
    app.run()
}
