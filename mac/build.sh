#!/bin/zsh
# Builds StudyLens.app next to this script.
set -e
cd "$(dirname "$0")"
APP=StudyLens.app
rm -rf $APP
mkdir -p $APP/Contents/MacOS
# Universal binary: Apple silicon + Intel
swiftc -O -swift-version 5 -target arm64-apple-macos14.0 Sources/main.swift -o /tmp/studylens-arm64
swiftc -O -swift-version 5 -target x86_64-apple-macos14.0 Sources/main.swift -o /tmp/studylens-x86_64
lipo -create /tmp/studylens-arm64 /tmp/studylens-x86_64 -output $APP/Contents/MacOS/StudyLens
cat > $APP/Contents/Info.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>StudyLens</string>
<key>CFBundleIdentifier</key><string>local.studylens</string>
<key>CFBundleExecutable</key><string>StudyLens</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0.1</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>LSUIElement</key><true/>
</dict></plist>
EOF
# Signing: with a stable identity macOS keeps the Screen Recording permission across
# rebuilds. Put a code-signing keychain at signing/studylens.keychain-db (not committed)
# and set STUDYLENS_SIGN_ID to its identity hash; otherwise the app is signed ad-hoc.
KC="$(pwd)/signing/studylens.keychain-db"
if [ -f "$KC" ] && [ -n "$STUDYLENS_SIGN_ID" ]; then
  security unlock-keychain -p "${STUDYLENS_KEYCHAIN_PW:-studylens}" "$KC"
  codesign --force --keychain "$KC" --sign "$STUDYLENS_SIGN_ID" $APP
else
  codesign --force --sign - $APP
fi
echo "Built $(pwd)/$APP"
