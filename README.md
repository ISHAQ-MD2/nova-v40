# NOVA AI Assistant (com.nova.app)

Android AI Assistant built in Kotlin with live Gemini voice/chat, built-in NOVA Browser (`WebView`), Chrome Custom Tabs integration, `nova://assistant` deep link & Android Share receiver, live hacker wallpaper, Termux bridge, and companion Chrome extension.

## Install / Build (Termux — No PC)
1. `pkg install unzip && unzip NOVA-ULTRA.zip && cd NOVA-ULTRA`
2. `termux-setup-storage`
3. `bash build-termux.sh`  -> APK: `/sdcard/Download/NOVA-ULTRA.apk`

**Termux DNS fix (if `pkg` or `curl` cannot resolve hosts):**
```bash
echo "nameserver 8.8.8.8" > $PREFIX/etc/resolv.conf
```

## Build Locally (Gradle)
```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```
The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` and automatically copied to `apkv38/app-debug.apk`.

## GitHub Actions APK Build
Workflow file: `.github/workflows/build-apk.yml`  
Push to `main`/`master` or run `workflow_dispatch` to download `nova-debug-apk` from Actions artifacts or Releases.

## Chrome Integration
- **Android → Chrome**: `ChromeBridge.kt` opens URLs and search queries via Chrome Custom Tabs (`androidx.browser:browser`) with `Intent.ACTION_VIEW` fallback. Available in `BrowserActivity` ("Open in Chrome") and NOVA's `open_in_chrome` tool.
- **Chrome → NOVA (No PC / Mobile)**:
  - Use **Share → Ask NOVA** from Android Chrome on any page or selected text.
  - Or open a `nova://assistant?action=ask&text=HELLO&url=https://example.com` deep link.
- **Optional Desktop / Kiwi Browser Extension**: Located in `chrome-extension/` (Manifest V3). See `chrome-extension/README.md` for install steps.
