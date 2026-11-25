# StudyLens

A study helper that reads a question on your screen and explains the answer — on **macOS**, **Android** and **Windows**. Answers come from [Groq](https://console.groq.com) using a vision model (bring your own free API key).

| Platform | How you trigger it | Where the answer shows |
|---|---|---|
| macOS | Menu bar app, **⌃⌥B** | Answer card next to the cursor |
| Android | Floating lens bubble on the screen edge → *Full screen* or *One question* (drag a box) | Bottom sheet over the current app |
| Windows | Tray app, **Ctrl+Alt+B** (drag a box) or **Ctrl+Alt+Shift+B** (whole screen) | Card next to the question, or a side panel |

Every answer shows a letter badge for multiple choice, or an **Answer** box with numbered **Working** steps for calculations, plus a one-line explanation. Scans are kept in a local history.

## Repository layout

```
mac/        Swift menu bar app (single file, built with swiftc)
android/    Kotlin + Jetpack Compose app, overlay bubble service, accessibility screenshot service
windows/    C# / WPF tray app (.NET 8)
art/        4K app icon and hero artwork
fonts/      Licence for Agrave Pro (SIL Open Font License)
```

## Building

### macOS (14+)
```bash
cd mac && ./build.sh && open StudyLens.app
```
Grant **Screen Recording** in System Settings → Privacy & Security the first time, then reopen the app.

### Android (10+)
```bash
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
In the app: allow **Display over other apps**, turn on **StudyLens screenshots** in Accessibility (one screenshot per tap, no screen recording), paste your Groq key.
Sideloaded apps may need *Settings → Apps → StudyLens → ⋮ → Allow restricted settings* before the accessibility switch can be turned on.

### Windows (10/11, x64)
```bash
cd windows
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o publish
```
Run `publish/StudyLens.exe`. Unsigned builds show a SmartScreen prompt the first time (*More info → Run anyway*).

## Privacy

Screenshots are only taken when you trigger a capture. They are sent to Groq to get the answer and stored locally in the app's history (macOS: memory only; Android: app storage; Windows: `%AppData%\StudyLens`). Your Groq key stays on your device.

## Credits

Headings use **Agrave Pro**, licensed under the SIL Open Font License 1.1 (see `fonts/AgravePro-OFL.txt`).
