<!-- Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago) -->
<p align="center">
  <img src="banner.png" alt="Jago Logo Banner" width="600px"/>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3ddc84?logo=android&logoColor=white&style=for-the-badge" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge" />
  <img src="https://img.shields.io/badge/AI-ONNX_Runtime-005CED?logo=onnx&logoColor=white&style=for-the-badge" />
  <img src="https://img.shields.io/badge/LLM-Gemini_3.5_Flash-1A73E8?logo=google-gemini&logoColor=white&style=for-the-badge" />
  <img src="https://img.shields.io/badge/Min_SDK-26_(Oreo)-green?style=for-the-badge" />
</p>

<h1 align="center">🗣️ Jago — AI Voice Assistant for Android</h1>

<p align="center">
  <b>Privacy-first · Offline Wake Word · Hinglish-Native · Multimodal Closed-Loop GUI Automation</b>
</p>

<p align="center">
  A fully voice-controlled Android assistant that natively understands <b>Hindi</b>, <b>English</b>, and <b>Hinglish</b>,<br/>
  detects trigger words <i>entirely offline on-device</i>, compiles user requests into native system intents or reflective actions,<br/>
  and autonomously drives app user interfaces using Accessibility-powered visual agents.
</p>

---

## ✨ System Features

### 🎙️ 1. On-Device Wake Word Engine — `"Jaagrut"`
Jago runs a highly optimized, three-stage **ONNX Runtime** pipeline in a foreground service for offline keyword detection, using zero cloud APIs:
- **Audio Capture**: Record raw 16kHz PCM audio from the device microphone, processed through an energy gate to discard silence.
- **Mel-Spectrogram Generation** (`melspectrogram.onnx`): Converts raw audio frames into time-frequency mel-spectrogram representations.
- **Feature Extraction** (`embedding_model.onnx`): Encodes spectrograms into compact 96-dimensional acoustic embeddings.
- **Keyword Spotting** (`jaag_ruut.onnx`): An LSTM classifier determines whether `"Jaagrut"` was spoken.
- **Rolling Hit Counter**: To prevent false triggers from similar words (like *"jaag"* or *"jaago"*), the engine uses a rolling buffer requiring $\ge 3$ out of 4 consecutive frames to pass a $>0.85$ confidence threshold. A 4.8s cooldown runs post-activation.
- **Hardware Trigger**: Double-pressing the Volume Down key intercepts Accessibility key events to trigger activation without speech.

---

### 🧠 2. Hybrid Natural Language Understanding (NLU)
When a command is captured, Jago routes it through a multi-tiered parsing pipeline:
1. **Fuzzy Command Matching** (`FuzzyCommandMatcher`): Matches exact and common Hinglish commands instantly (~1ms).
2. **Semantic Similarity Matching** (`SemanticCommandMatcher`): 
   - Uses local cosine similarity calculations against cached embeddings generated via the Gemini embedding API (`gemini-embedding-001`).
   - Includes an **Incompatibility Filter** (`isIncompatibleMatch`) that identifies removal/deletion verbs (`"remove"`, `"delete"`, `"clear"`, etc.) and prevents false-positive matches to constructive actions.
3. **Multimodal Intent Compiler** (`JagrutExecutionEngine`):
   - Compiles unresolved commands into structured JSON execution schemas.
   - Maps the intent dynamically to one of:
     - `NATIVE_INTENT`: Direct Android intent actions (e.g. settings screens, dialing).
     - `REFLECTIVE_CALL`: Whitelisted Java reflection calls on Android system services.
     - `SHELL_COMMAND`: Shell commands executed directly on the secure sandbox.
     - `AUTOMATION_SEQUENCE`: Multi-step accessibility macros.
4. **Conversational Fallback**: If no direct device action is possible, the query is answered by **Gemini 3.5 Flash** or **Sarvam conversational APIs**.

---

### ♿ 3. Accessibility-Powered UI Automation
Jago acts as a virtual user, driving interfaces directly using the Android Accessibility APIs:
- **State-Aware Click & Tap** (`performRobustClick`): Executes click actions by navigating the layout tree. If standard node clicks fail, it dispatches an absolute screen coordinate gesture fallback using current display metrics.
- **Closed-Loop Visual Agent** (`DynamicAgentEngine`):
   - When a macro is not pre-recorded, a closed-loop agent takes control.
   - It captures interactive elements (`ScreenElement`) from the live layout tree, presents the parsed list to Gemini, and executes sequential steps (`CLICK`, `TEXT_ENTRY`, `BACK`, `WAIT`, `FINISH`) dynamically.
- **WhatsApp Heuristics (Anti-Misclick)**:
   - Includes custom, robust resolvers specifically for WhatsApp:
     - `findWhatsAppProfileCardRow`: Bypasses dynamic layout shifts (like temporary notification banners at the top of settings) to locate the main profile settings trigger.
     - `findClickableProfileCardDescendant`: Traverses the profile layout and **explicitly ignores/filters out** the QR code icon (`qr_code`) and account switcher (`plus`, `multi_account`) buttons to guarantee it clicks on your name/profile card instead of opening QR codes or switching accounts.
     - `findWhatsAppProfilePhotoNode` & `findWhatsAppEditPhotoButton`: Automatically matches image nodes and edit pencil icons.
     - `findWhatsAppRemovePhotoButton`: Identifies removal items in bottom sheets and completes dialog confirmations (`findWhatsAppConfirmRemoveButton`).
- **Spotify Auto-Play Assist**: Traverses Spotify search layouts and clicks the top result. Resolves play/pause button state conflicts (swaps "Play" and "Pause" targets if the music is already in the opposite state to prevent double-toggle errors).
- **Auto-Send Polling**: Detects direct message screens on WhatsApp/Telegram, waits for text insertion, and automatically clicks send.

---

### 🛜 4. MongoDB Atlas DNS-over-HTTPS (DoH) Resolver
- **The Issue**: Standard Java MongoDB drivers use JNDI (`javax.naming.directory.InitialDirContext`) to resolve SRV records (`mongodb+srv://` URIs). However, Android's SDK does not support JNDI, causing immediate `NoClassDefFoundError` crashes on connection.
- **The Solution** (`MongoDBClient`):
  - Integrates a custom DNS resolver using Google's secure DNS-over-HTTPS API.
  - Queries `SRV` records for replica set host ports and `TXT` records for connection options.
  - Constructs a standard `mongodb://` connection string dynamically at runtime, allowing native Android connections to MongoDB Atlas clusters.

---

### 🖥️ 5. Gemini Execution Logs UI
- **Log Interceptor**: Captures the raw JSON execution payloads and latency from Gemini API calls.
- **Logs Page** (`GeminiActivity`): Provides a clean, dark glassmorphism interface displaying:
  - Voice queries with execution timestamps.
  - Total latency in milliseconds.
  - Beautifully formatted, syntax-highlighted code blocks of the raw JSON execution schema returned by the LLM.

---

### 📱 6. Notification & Contact Intelligence
- **Notification Capture** (`JagoNotificationListener`): Stores incoming notifications in a persistent `NotificationStore` (survives app process kills).
- **Interactive Reading**: Reads notifications aloud sequentially. Supports commands like *"next"* (read next), *"reply [message]"* (replies inline via WhatsApp), or *"stop"*.
- **5-Stage Fuzzy Contact Resolver** (`ContactResolver`):
  1. Exact Name match.
  2. Starts-with match.
  3. Contains substring.
  4. First-name contains match.
  5. Levenshtein fuzzy distance matching (threshold: `len/3`).
  - Automatically handles ambiguity by asking for clarification if multiple contacts are matched.

---

### ⏰ 7. Alarms, Reminders & Task Scheduler
- **Alarm Engine**: Custom full-screen alarm screen with vibration, dismiss, and snooze. Supports absolute times (e.g. *"7:30 AM"*) and relative times (e.g. *"alarm in 30 minutes"*).
- **Reminders**: Multi-turn dialog system that prompts for missing parameters (e.g., if you say *"remind me"*, it asks *"what should I remind you about?"* and *"when?"*).
- **Task Scheduler** (`ScheduledTaskEngine`): Persists tasks and schedules future commands for exact execution using Android's `AlarmManager`.

---

## 🏗️ System Architecture

```
┌──────────────────────────────────────────────────────────┐
│                     WakeWordService                      │
│  ┌─────────┐   ┌───────────┐   ┌──────────────────────┐ │
│  │ AudioRec│──▶│ ONNX Mel  │──▶│ ONNX Embedding       │ │
│  │ (16kHz) │   │ Spectro   │   │ (96-dim)             │ │
│  └─────────┘   └───────────┘   └──────────┬───────────┘ │
│                                            ▼             │
│                                ┌───────────────────────┐ │
│                                │ ONNX LSTM Wake Word   │ │
│                                │ (rolling hit-counter) │ │
│                                └────────┬──────────────┘ │
│                                         ▼                │
│                              ┌─────────────────────┐     │
│                              │  Android STT / Vosk │     │
│                              └────────┬────────────┘     │
│                                       ▼                  │
│  ┌────────────────┐   ┌──────────────────────────────┐   │
│  │HindiTranslator │──▶│       CommandParser           │   │
│  └────────────────┘   │ (Intent Seeds + Modifiers +   │   │
│                       │  Contextual + Scheduling)     │   │
│                       └──────────────┬───────────────┘   │
│                                      ▼                   │
│  ┌──────────────────────────┐ ┌─────────────────────┐    │
│  │    ActionExecutor        │ │   Gemini Client     │    │
│  │ (Device + App Control)   │ │  (Intent Compiler)  │    │
│  └────────────┬─────────────┘ └────────┬────────────┘    │
│               ▼                        ▼                 │
│  ┌────────────────────────┐  ┌────────────────────────┐  │
│  │ JagoAccessibilityServ  │  │      JagoTTS           │  │
│  │ (GUI / Visual Heuristics) │ (Bilingual Speech)    │  │
│  └────────────────────────┘  └────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites
- Android device running **Android 8.0+ (API 26)**
- **Android Studio** (Ladybug or newer)
- USB debugging enabled on a physical device (microphone access is required for wake word detection)

### Build & Run
1. Clone this repository.
2. Open the project in Android Studio.
3. Sync Gradle and compile the debug build.
4. Deploy the app to your device.
5. **Grant Required System Permissions**:
   - Microphone (for wake-word capture)
   - Contacts (for dialing and messaging resolution)
   - Phone (for placing calls)
   - Notification Listener Access (for notification reading)
   - Accessibility Service (must be manually enabled in Android Settings -> Accessibility -> Jago Assistant)

---

## 📁 Repository Structure

```
app/src/main/
├── assets/
│   ├── melspectrogram.onnx        # Mel-spectrogram model
│   ├── embedding_model.onnx       # Acoustic embedding model
│   └── jaag_ruut.onnx             # Wake word LSTM model
├── java/com/example/jago/
│   ├── MainActivity.kt            # Core entry point and dashboard
│   ├── GeminiActivity.kt          # UI for reviewing Gemini Execution Logs
│   ├── JagoApp.kt                 # Application subclass
│   ├── logic/
│   │   ├── JagrutExecutionEngine.kt # Handles routing (Fuzzy -> Semantic -> Gemini)
│   │   ├── ActionExecutor.kt      # Translates device actions to system functions
│   │   ├── SemanticCommandMatcher.kt # Pre-cached Gemini embeddings comparison
│   │   ├── DynamicAgentEngine.kt  # Closed-loop visual GUI agent
│   │   ├── GeminiExecutor.kt      # Direct execution of intents, shell scripts, reflection
│   │   ├── GeminiHistoryEngine.kt # Serializes execution logs to SharedPreferences
│   │   ├── MongoDBClient.kt       # Native DoH SRV replica set DNS resolver
│   │   ├── ContactResolver.kt     # Levenshtein fuzzy contacts pipeline
│   │   ├── HindiTranslator.kt     # Local Hinglish/Hindi command mapping
│   │   ├── BhashiniClient.kt      # Dhruva API wrapper for Translation and TTS
│   │   ├── JagoTTS.kt             # Dual-engine Hindi/English voice feedback
│   │   └── CalculatorEngine.kt    # Evaluates math expressions
│   ├── service/
│   │   ├── WakeWordService.kt     # Audio capture loop and ONNX executor
│   │   ├── JagoAccessibilityService.kt # Accessibility event listener and custom UI macros
│   │   ├── JagoNotificationListener.kt # Captures notifications
│   │   └── speech/                # Speech-to-text adapters (Vosk/Android SDK)
│   └── ui/                        # Layout bindings and custom drawing assets
└── res/                           # Layouts, themes, Drawables, XML configs
```

---

## 🛡️ Privacy & Security
- **Strictly Offline Wake Word**: The microphone stream is processed entirely in the local ONNX runtime. No audio data ever leaves the device.
- **Secure Sandbox Execution**: Android reflection calls are strictly filtered through a security prefix whitelist (`ALLOWED_CLASS_PREFIXES`) to prevent unauthorized class instantiation.
- **Local Credentials**: API keys are injected at compile time via `BuildConfig` and are never committed to version control.

---

<p align="center">
  Built with ❤️ in Kotlin · Powered by ONNX Runtime + Gemini 3.5 Flash
</p>
