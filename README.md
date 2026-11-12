# NYRO

**Your phone. Your intelligence.**

NYRO is a native Android AI companion that lives on your phone as a floating entity. You talk to it (or type), and it operates the phone for you: opens apps, reads and understands the screen, taps, types, scrolls, answers device questions and runs multi-step tasks. You can watch everything it does on screen.

- No login, no account, no backend. Install the APK and use it.
- Reasoning runs on **your own Azure AI deployment**, called directly from the phone over HTTPS.
- Everything else runs **on the phone**: overlay, voice, device info, accessibility control, screen capture, tool execution, memory, history and settings.

Target: TECNO POVA 5 Pro 5G, Android 13 (API 33) and newer.

## Install and first run

1. Download the APK: GitHub → **Actions** → latest *Android build* run → artifact `nyro-apk`. Or build it yourself (see below).
2. Install it (allow "install unknown apps" for your browser/file manager).
3. Open NYRO → **Connect your Azure AI** → enter endpoint, key and deployment → **Test connection**.
4. **Grant permissions**: Display over other apps, Microphone, Notifications, **NYRO Phone Control** (Accessibility).
   - Android 13+ shows "Restricted setting" for sideloaded apps. Open *App info → ⋮ → Allow restricted settings*, then enable NYRO Phone Control again. The Permissions screen has a button for this.
   - On TECNO/HiOS, also set battery to *Unrestricted* so the system doesn't kill NYRO Mode.
5. Turn on **NYRO Mode**. The orb appears over every app.

### Using the orb

| Gesture | Action |
|---|---|
| Tap | Open the panel and start listening (tap again while listening to stop) |
| Long-press | Emergency stop: cancels the task, speech and listening |
| Drag | Move it; it snaps to the nearest edge |
| Red × badge | Stop the running task |

The notification also has **Talk**, **Stop task** and **Normal Mode** buttons. While NYRO is acting, a glowing border, a status pill at the top of the screen, tap ripples, swipe trails and element highlights show exactly what it's doing.

## Azure configuration

Three request contracts are supported (Settings → AI Provider):

| Provider type | Request | Notes |
|---|---|---|
| Azure OpenAI (deployment + api-version) | `POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version=2024-10-21` | Classic route. Header `api-key`. |
| Azure OpenAI v1 API | `POST {endpoint}/openai/v1/chat/completions`, body `model={deployment}` | No api-version needed. |
| Azure AI Foundry Models | `POST {endpoint}/models/chat/completions?api-version=2024-05-01-preview`, body `model={deployment}` | For non-OpenAI models deployed in Foundry. |

You can paste either the resource URL or a full request URL; NYRO normalizes it.

The deployment must support **tool/function calling** (e.g. GPT-4o, GPT-4.1 or newer). Image input is needed for screenshot understanding. If your main deployment has no vision, set a separate **vision deployment**. **Test connection** sends real requests and reports tool calling, streaming, vision and latency. Errors are mapped to clear messages: wrong key (401/403), deployment not found, wrong path or api-version, rate limit vs quota exhaustion, content filter, network, timeout.

**Key storage:** the API key is encrypted with an AES-256-GCM key held in the Android Keystore. Only ciphertext is stored, it is never logged, and it is excluded from backups. A key on a personal device is still not fully safe on a rooted or compromised phone, so use a key you can rotate and set spending limits in Azure.

## What NYRO can do

**Agent loop** (`agent/Agent.kt`): request → local context (device summary, foreground app, memories, recent conversation) → Azure picks tools → each call is **validated locally** (known tool, JSON schema, requirements, risk) → executed with Android APIs → the observed result (including a fresh screen snapshot after UI actions) goes back to Azure → next action or final answer. Up to 30 steps. Streaming replies are spoken sentence by sentence. Stop cancels immediately.

**Tools** (`tools/`):
- Screen: `read_screen`, `find_elements`, `tap_element`, `tap_text`, `long_press_element`, `type_text` (with submit), `scroll`, `swipe`, `tap_point`, `press_back`, `press_home`, `open_recents`, `open_notification_shade`, `open_quick_settings`, `lock_screen`, `wait`, `take_screenshot` (vision).
- Apps & system: `list_apps`, `open_app` (verifies the app came to the front), `open_settings` (25+ pages and panels), `open_url`, `web_search`, `set_alarm`, `set_timer`, `open_dialer`, `compose_message`, `share_text`, `set_flashlight`, `set_volume`, `media_control`, `copy_to_clipboard`.
- Device: `get_device_info`, `get_battery`, `get_network_status`, `list_sensors`, `read_sensor`.
- Files (with media permission): `search_files`, `open_file`, `share_file`.
- Notifications (with notification access): `get_notifications`, `reply_to_notification`, `open_notification`.
- Memory: `remember`, `recall`, `forget`.

UI interaction prefers accessibility element actions, then falls back to gestures at the element's bounds. Raw coordinates are used only when needed. Every UI action waits for the screen to settle and reports whether it changed, so the model can verify each step.

**Safety:** low-risk actions run directly. Medium-risk actions (send, share, post, reply, change a setting toggle, delete a memory) ask first; this is on by default and can be switched off. High-risk actions (pay, buy, order, transfer, delete, uninstall, reset, security changes, anything inside a banking or payment app) **always** ask. NYRO refuses to type into password fields and never stores passwords, OTPs, PINs or card numbers (`data/Redactor.kt`).

**Local mode:** without Azure, or when it's unreachable, NYRO still answers battery, storage, RAM, network, sensor and device questions, opens apps, goes home or back, and toggles the flashlight.

**Voice:** Android `SpeechRecognizer`, on-device when the phone supports it (`isOnDeviceRecognitionAvailable`). If it's missing, NYRO says so and offers an explicit opt-in fallback to the phone's default recognizer, which may process audio online. NYRO never uploads audio itself; Azure only receives recognized text. Speech output uses Android `TextToSpeech` with rate, pitch and voice settings.

**Screen understanding:** the accessibility tree is used first. Screenshots use `AccessibilityService.takeScreenshot` (no extra prompt), falling back to MediaProjection with Android's consent dialog when accessibility is off. Screenshots are taken only on demand, JPEG-compressed to at most 1280 px, and sent to Azure only when the task needs vision. A notice shows each time. Secure windows can't be captured.

## Modes

- **Normal Mode:** no service, no overlay, no microphone, no capture. TTS isn't even bound.
- **NYRO Mode:** a foreground service (`overlay/NyroModeService.kt`) owns the orb, panel and effects windows. The persisted flag is the source of truth: the sticky service, the app and the accessibility service each restore the mode after process death or reboot.

## Not possible on stock Android without root

- Toggling Wi-Fi, Bluetooth, mobile data or airplane mode programmatically. NYRO opens the setting or panel and taps the switch.
- Capturing or reading secure windows (banking, DRM video, password fields).
- Bypassing lock screen, biometrics or permission dialogs, or filling passwords/OTPs. NYRO hands these to you.
- Always-on background wake word (Android restricts background mic use). Use the orb, notification or dashboard.
- Silent calls/SMS. NYRO prepares them and taps Call/Send only after you confirm.
- Apps that draw custom UIs (games, some Flutter/Unity apps) expose little to accessibility; NYRO falls back to screenshots and coordinate taps.
- Reading non-media files from other apps in Downloads (scoped storage); media files are searchable with permission.

## Build

Requirements: JDK 17, Android SDK 35.

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # minified, signed with the local debug key (personal use)
```

Stack: Kotlin 2.1, Jetpack Compose + Material 3, Coroutines/Flow, Room, DataStore, Android Keystore, OkHttp (SSE streaming), kotlinx.serialization. No Firebase, no Supabase, no backend, no analytics.

## Project layout

```
app/src/main/java/com/nyro/companion/
  NyroApp.kt, core/AppGraph.kt     manual DI + Normal/NYRO mode controller
  ai/AzureClient.kt                Azure contracts, SSE streaming, tool-call assembly, errors, connection test
  agent/Agent.kt                   observe-plan-act loop, confirmations, task log, context compaction
  agent/LocalCommands.kt           offline capability mode
  tools/                           tool schemas, risk classification, implementations
  access/                          AccessibilityService, screen reader, MediaProjection, notification listener
  device/                          device info (battery, CPU, RAM, storage, network, sensors), app catalog
  voice/VoiceEngine.kt             on-device STT + streaming TTS
  overlay/                         NYRO Mode service, orb/panel windows, on-screen effects
  data/                            Room DB, DataStore settings, Keystore key store, redaction
  ui/                              Compose screens: dashboard, chat, tasks, device, history, memory, settings, provider, permissions
```
