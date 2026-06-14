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

<h1 align="center">🗣️ Jago — Production-Grade AI Voice Assistant for Android</h1>

<p align="center">
  <b>Privacy-First · Offline Wake Word · Hinglish-Native · Multimodal Closed-Loop GUI Automation · Hybrid TTS & STT · MongoDB Vector Search</b>
</p>

<p align="center">
  A highly advanced, fully voice-controlled Android assistant that natively parses and understands <b>Hindi</b>, <b>English</b>, and <b>Hinglish</b> (mixed Hindi-English),
  detects trigger words <i>entirely offline on-device</i> using a multi-stage ONNX pipeline, compiles complex user requests into system intents or whitelisted reflection calls,
  and autonomously drives third-party applications using Accessibility-powered visual agents.
</p>

---

## 📖 Table of Contents
1. [System Architecture](#-system-architecture)
2. [Key Capabilities & Sub-Engines](#-key-capabilities--sub-engines)
   - [On-Device Wake Word Engine](#1-on-device-wake-word-engine-jaagrut)
   - [Hierarchical Hybrid NLU Pipeline](#2-hierarchical-hybrid-nlu-pipeline)
   - [Accessibility-Powered UI Automation](#3-accessibility-powered-ui-automation)
   - [DNS-over-HTTPS MongoDB Atlas Resolver](#4-dns-over-https-mongodb-atlas-resolver)
   - [Bilingual Speech & Translation Engine](#5-bilingual-speech--translation-engine)
   - [Alarms, Reminders & Task Scheduler](#6-alarms-reminders--task-scheduler)
   - [Daily Research Reporting Module](#7-daily-research-reporting-module)
3. [Repository Structure](#-repository-structure)
4. [Command Lexicon](#-command-lexicon)
5. [Setup & Installation](#-setup--installation)
6. [Security & Privacy Whitelisting](#-security--privacy-whitelisting)
7. [Manual Device Verification Plan](#-manual-device-verification-plan)

---

## 🏗️ System Architecture

Jago uses a hierarchical routing architecture designed to process speech commands in real-time, executing localized commands instantly (~1ms) while offloading complex multimodal tasks to a Gemini-powered intent compiler.

```mermaid
graph TD
    subgraph WakeWordService ["WakeWordService (Foreground Service)"]
        AudioRecord["AudioRecord 16kHz PCM"] --> EnergyGate["Energy Gate / Silence Rejection"]
        EnergyGate --> MelSpectrogram[melspectrogram.onnx]
        MelSpectrogram --> EmbeddingModel[embedding_model.onnx]
        EmbeddingModel --> WakeWordClassifier["jaag_ruut.onnx LSTM Classifier"]
        WakeWordClassifier --> RollingHit["Rolling Hit Counter (3/4 frames >= 0.85)"]
        RollingHit -->|Detected / Double Volume Down| STTSelect{Speech Adapter Selection}
    end

    subgraph SpeechToText ["Speech-to-Text Adapters"]
        STTSelect -->|English| AndroidSTT["Android STT Adapter"]
        STTSelect -->|Hindi / Capturing Field| SarvamSTT["Sarvam STT Adapter"]
        STTSelect -->|Offline Fallback| VoskSTT["Vosk Offline STT Adapter"]
    end

    AndroidSTT --> Translators["Translation & Matching Pipeline"]
    SarvamSTT --> Translators
    VoskSTT --> Translators

    subgraph Translators ["Translation & Matching Pipeline"]
        HindiTrans["Hindi/Hinglish Translator"] --> FuzzyMatch["Fuzzy Command Matcher (Synonyms & Levenshtein)"]
        FuzzyMatch -->|Cache Hit| ExecuteDirect["Direct Action Executor"]
        FuzzyMatch -->|Cache Miss| GeminiIntentCompiler["Gemini Intent Compiler"]
    end

    GeminiIntentCompiler -->|NATIVE_INTENT / REFLECTIVE_CALL / SHELL_COMMAND| GeminiExecutor["Gemini Executor"]
    GeminiIntentCompiler -->|AUTOMATION_SEQUENCE| AutomationSelect{Macro Cache Check}
    AutomationSelect -->|Macro Cached / Generated Steps| PlayMacro["JagoAccessibilityService (Play Macro)"]

    GeminiIntentCompiler -->|UNKNOWN / Conversational| FallbackSelect{Conversational Fallback}
    
    FallbackSelect -->|Sarvam Chat API| SarvamChat["Sarvam AI (sarvam-30b)"]
    FallbackSelect -->|Sarvam Fail / Offline| GeminiChat["Gemini 3.5 Flash Chat"]

    GeminiExecutor --> ActionPlayback["Android System Actions / Reflection / Shell Sandbox"]
    PlayMacro --> ActionPlayback

    subgraph TTS ["Bilingual TTS Output"]
        SpeechOutput["JagoTTS Hybrid Voice Output"]
        SarvamChat --> SpeechOutput
        GeminiChat --> SpeechOutput
        ActionPlayback --> SpeechOutput
    end
```

---

## ⚡ Key Capabilities & Sub-Engines

### 🎙️ 1. On-Device Wake Word Engine ("Jaagrut")
Jago runs a highly optimized, three-stage **ONNX Runtime** pipeline in a persistent foreground service for offline keyword detection, using zero cloud APIs to protect user privacy:
- **Audio Capture & Energy Gating**: Captures raw 16kHz PCM mono audio from the device microphone. Processes audio through a local energy threshold gate to discard silence immediately, reducing CPU cycles.
- **Mel-Spectrogram Generation** (`melspectrogram.onnx`): Converts raw audio frames into time-frequency mel-spectrogram representations on the fly.
- **Feature Extraction** (`embedding_model.onnx`): Encodes spectrograms into compact 96-dimensional acoustic embeddings.
- **Keyword Spotting** (`jaag_ruut.onnx`): An LSTM classifier determines whether `"Jaagrut"` (or `"Hey Jago"`) was spoken.
- **Rolling Hit Counter**: To prevent false triggers from similar sounds, the engine uses a rolling buffer requiring $\ge 3$ out of 4 consecutive frames to pass a $>0.85$ confidence threshold. A 4.8s cooldown runs post-activation.
- **Hardware Trigger**: Double-pressing the Volume Down key intercepts Accessibility key events to trigger activation without speech.

### 🧠 2. Hierarchical Hybrid NLU Pipeline
When a command is captured, Jago routes it through a multi-tiered parsing pipeline:
1. **Fuzzy Command Matching** (`FuzzyCommandMatcher`): Matches exact and common Hinglish commands instantly (~1ms). Uses a synonym dictionary mapping Hindi/Hinglish words to canonical English seed words (e.g. `"batti"` -> `"flashlight"`, `"kam"` -> `"decrease"`) and applies token-level Levenshtein distance (threshold = 2) to correct STT errors.
2. **Multimodal Intent Compiler** (`JagrutExecutionEngine`):
   - Compiles unresolved commands into structured JSON execution schemas using Gemini.
   - Maps the intent dynamically to one of:
     - `NATIVE_INTENT`: Direct Android intent actions (e.g. opening settings, launching apps).
     - `REFLECTIVE_CALL`: Whitelisted Java reflection calls on Android system services (e.g. adjusting volume or toggling DND).
     - `SHELL_COMMAND`: Shell commands executed directly in the secure application sandbox.
     - `AUTOMATION_SEQUENCE`: Multi-step accessibility macros.
3. **Conversational Fallback**: If no direct device action is possible, the query is answered by **Sarvam AI's Conversational API (`sarvam-30b`)** or falls back to **Gemini 3.5 Flash** if the connection is slow or fails.

### ♿ 3. Accessibility-Powered UI Automation
Jago acts as a virtual user, driving interfaces directly using the Android Accessibility APIs:
- **State-Aware Click & Tap** (`performRobustClick`): Executes click actions by navigating the layout tree. If standard node clicks fail, it dispatches an absolute screen coordinate gesture fallback using current display metrics.
- **Visual Macro Recorder**: Intercepts accessibility events (clicks, text entries) to record custom reproducible sequences. It stores screen-relative percentages (`xPercent`, `yPercent`) to adapt to different layouts and screen sizes, supporting mid-macro app transitions.
- **WhatsApp Heuristics (Anti-Misclick)**:
   - Includes custom, robust resolvers specifically for WhatsApp:
     - `findWhatsAppProfileCardRow`: Bypasses dynamic layout shifts (like temporary notification banners at the top of settings) to locate the main profile settings trigger.
     - `findClickableProfileCardDescendant`: Traverses the profile layout and **explicitly ignores/filters out** the QR code icon (`qr_code`) and account switcher (`plus`, `multi_account`) buttons to guarantee it clicks on your name/profile card instead of opening QR codes or switching accounts.
     - `findWhatsAppProfilePhotoNode` & `findWhatsAppEditPhotoButton`: Automatically matches image nodes and edit pencil icons.
     - `findWhatsAppRemovePhotoButton`: Identifies removal items in bottom sheets and completes dialog confirmations (`findWhatsAppConfirmRemoveButton`).
- **Spotify Auto-Play Assist**: Traverses Spotify search layouts and clicks the top result. Resolves play/pause button state conflicts (swaps "Play" and "Pause" targets if the music is already in the opposite state to prevent double-toggle errors).
- **Auto-Send Polling**: Detects direct message screens on WhatsApp/Telegram, waits for text insertion, and automatically clicks send.

### 🛜 4. DNS-over-HTTPS MongoDB Atlas Resolver
- **The Issue**: Standard Java MongoDB drivers use JNDI (`javax.naming.directory.InitialDirContext`) to resolve SRV records (`mongodb+srv://` URIs). However, Android's SDK does not support JNDI, causing immediate `NoClassDefFoundError` crashes on connection.
- **The Solution** (`MongoDBClient`):
  - Integrates a custom DNS resolver using Google's secure DNS-over-HTTPS API.
  - Queries `SRV` records for replica set host ports and `TXT` records for connection options.
  - Constructs a standard `mongodb://` connection string dynamically at runtime, allowing native Android connections to MongoDB Atlas clusters.
  - Implements **MongoDB Vector Search** (using the `$vectorSearch` aggregation pipeline) to sync and pull voice macros based on query embedding similarities (similarity threshold $\ge 0.82$).

### 🗣️ 5. Bilingual Speech & Translation Engine
- **Multi-Adapter Speech-to-Text (STT)**:
  - `AndroidSTTAdapter`: Uses Google's standard SpeechRecognizer.
  - `SarvamSTTAdapter`: Uses Sarvam AI's speech-to-text API (multipart upload) for high-accuracy Hinglish and Hindi commands.
  - `VoskAdapter`: Uses a fully offline Vosk voice recognition model (`model-en-us`) unpacked locally to process queries offline.
- **Hybrid Text-to-Speech (TTS)**:
  - Hindi voice requests use **Sarvam TTS** (voice "meera") or **ElevenLabs Multilingual TTS** (Rachel), falling back to native Android System TTS if offline.
  - English voice requests prioritize **ElevenLabs TTS**, falling back to **Sarvam TTS (en-IN)** or Android System TTS.
- **Hinglish/Hindi Translator** (`HindiTranslator`):
  - Translates Devanagari Hindi or Hinglish phrases into English equivalents (e.g. `"awaaz badhao"` -> `"volume up"`, `"torch jalao"` -> `"flashlight on"`).
  - Uses **Message Protection heuristics**: identifies message markers (e.g., `whatsapp message bhejo`, `message karo`) and translates only the command prefix, leaving the actual message body untranslated so names and dictated messages remain verbatim.

### ⏰ 6. Alarms, Reminders & Task Scheduler
- **Alarm Engine**: Custom full-screen alarm screen (`AlarmActivity`) with vibration, dismiss, and snooze. Supports custom alarm ringtone uploads (`custom_alarm.mp3`).
- **Reminders**: Multi-turn dialog system that prompts for missing parameters (e.g., if you say *"remind me"*, it asks *"what should I remind you about?"* and *"when?"* before scheduling).
- **Task Scheduler** (`ScheduledTaskEngine`): Persists tasks and schedules future commands for exact execution using Android's `AlarmManager`.

### 📰 7. Daily Research Reporting Module
- **Workflow Synchronization**: Syncs the desired research topic and interval directly with an external **n8n orchestration server** (`https://lazydracko.app.n8n.cloud`).
- **Research Receiver**: `ResearchReceiver` listens to `"com.example.jago.ADD_RESEARCH"` broadcasts to parse and save research PDF files (as Base64 encoded byte arrays).
- **Notification Alerts**: Triggers system notifications to alert the user of new research reports.
- **Report Dashboard**: `ResearchActivity` displays saved reports and allows users to read them in-app using an integrated PDF viewer.

---

## 📁 Repository Structure

```
app/src/main/
├── assets/
│   ├── melspectrogram.onnx        # ONNX Mel-spectrogram model
│   ├── embedding_model.onnx       # ONNX Acoustic embedding model
│   └── jaag_ruut.onnx             # ONNX Wake word LSTM model
├── java/com/example/jago/
│   ├── MainActivity.kt            # Dashboard ViewPager2 controller (Home, Macros, Logs, Security)
│   ├── GeminiActivity.kt          # UI for reviewing Gemini Execution Logs
│   ├── CommandListActivity.kt     # UI for listing system voice commands
│   ├── ResearchActivity.kt        # UI for research syncing & PDF viewing
│   ├── JagoApp.kt                 # Application class: initialises TTS, MongoDB, and Bhashini
│   ├── logic/
│   │   ├── JagrutExecutionEngine.kt # Central router (Semantic Cache -> Fuzzy -> Gemini)
│   │   ├── ActionExecutor.kt      # Translates device actions to system functions
│   │   ├── GeminiExecutor.kt      # Runs NATIVE_INTENT, REFLECTIVE_CALL, SHELL_COMMAND, or macros
│   │   ├── GeminiHistoryEngine.kt # Saves execution logs to SharedPreferences
│   │   ├── MongoDBClient.kt       # Secure DNS-over-HTTPS SRV connection resolver & Vector Search
│   │   ├── ContactResolver.kt     # 5-stage Levenshtein fuzzy contacts pipeline
│   │   ├── HindiTranslator.kt     # Hinglish/Hindi command translator with message protection
│   │   ├── BhashiniClient.kt      # Bhashini pipeline config, translation, and TTS endpoints
│   │   ├── JagoTTS.kt             # Hybrid TTS coordinator (Sarvam / ElevenLabs / Android System)
│   │   └── CalculatorEngine.kt    # Shunting-Yard math expression parser
│   ├── service/
│   │   ├── WakeWordService.kt     # Audio capture loop and ONNX execution service
│   │   ├── JagoAccessibilityService.kt # Accessibility listener, macro playback, and WhatsApp heuristics
│   │   ├── JagoNotificationListener.kt # Captures notifications into a persistent NotificationStore
│   │   ├── JagoAdminReceiver.kt   # Intercepts Device Admin activation and locks screen
│   │   ├── alarm/
│   │   │   ├── AlarmReceiver.kt   # Broadcaster receiving scheduled alarm intents
│   │   │   └── AlarmEngine.kt     # Custom alarm scheduler using Android AlarmManager
│   │   └── speech/
│   │       ├── SpeechAdapter.kt   # Interface class
│   │       ├── AndroidSTTAdapter.kt # Standard Google SpeechRecognizer adapter
│   │       ├── SarvamSTTAdapter.kt  # Sarvam AI STT API multipart upload adapter
│   │       └── VoskAdapter.kt     # Local offline Vosk Speech-to-Text adapter
│   └── ui/                        # Layout bindings and custom drawing assets
└── res/                           # Layouts, themes, drawables, XML configurations
```

---

## 🗣️ Command Lexicon

The following table showcases some of the major Hinglish, Hindi, and English commands mapped to their canonical `CommandType`:

| Command Type | Example English Command | Example Hinglish/Hindi Command | System Execution |
| :--- | :--- | :--- | :--- |
| **CALL** | "Call Mummy" | "Mummy ko call lagao" | Contacts resolver match -> Direct dial intent placement |
| **SEND_WHATSAPP_MESSAGE** | "Message Rishabh saying Hi" | "Rishabh ko WhatsApp karo aur bolo hello" | Fuzzy contacts match -> Direct WhatsApp text send |
| **FLASHLIGHT_ON** / **OFF** | "Turn on flashlight" | "Torch jalao" | Reflective call on Camera API |
| **VOLUME_UP** / **DOWN** | "Increase volume" | "Awaaz badhao" | Adjusts Android media stream volume |
| **BRIGHTNESS_INCREASE** | "Screen is too dark" | "Screen chamak badhao" | Increments screen brightness setting |
| **BATTERY_CHECK** | "Check battery level" | "Battery kitni hai" | Reads battery status receiver values |
| **SET_ALARM** | "Set alarm for 7:30 AM" | "Subah saat baje ka alarm lagao" | AlarmManager exact trigger registration |
| **PLAY_SPOTIFY** | "Play music on Spotify" | "Spotify chalao" | Launches Spotify -> Performs Accessibility Autoplay |
| **READ_SCREEN** | "Read my screen" | "Screen padhao" | Accessibility node hierarchy text extraction |
| **READ_NOTIFICATIONS** | "Read my notifications" | "Notification padho" | Reads incoming notifications stored in local db |
| **TAKE_SCREENSHOT** | "Take a screenshot" | "Screenshot lo" | Captures screen -> Saves to device media storage |
| **SET_LANGUAGE** | "Speak in Hindi" | "Hindi mein bolo" | Toggles system speech output preference |
| **CALCULATE** | "Calculate 15 + sqrt(144)" | "Calculate karo 15 + sqrt(144)" | Shunting-Yard expression evaluator |
| **TRIGGER_N8N_WORKFLOW** | "Run workflow test" | "Workflow test chalao" | Webhook trigger post to n8n server |

---

## 🚀 Setup & Installation

### 1. Prerequisites
- Physical Android device running **Android 8.0+ (API 26)** (Physical microphone access is required for wake-word detection).
- **Android Studio** (Ladybug or newer).
- JDK 17 configured.

### 2. Configure API Keys & Secrets
Create or edit your `local.properties` file in the root directory and populate it with your keys:

```properties
# Android SDK location
sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk

# Gemini API Configurations
GEMINI_API_KEY=your_gemini_api_key

# Bhashini Translation Configurations
BHASHINI_USER_ID=your_bhashini_user_id
BHASHINI_ULCA_API_KEY=your_bhashini_ulca_api_key

# Sarvam AI Configurations
SARVAM_API_KEY=your_sarvam_api_key

# ElevenLabs Speech Configurations
ELEVENLABS_API_KEY=your_elevenlabs_api_key

# MongoDB Atlas Sync Configurations
MONGODB_CONNECTION_STRING=mongodb+srv://username:password@cluster.mongodb.net/?appName=Cluster0
MONGODB_DATABASE=jagrut_db
MONGODB_COLLECTION=macros
MONGODB_VECTOR_INDEX=vector_index
```

### 3. Build & Run
1. Open the project in Android Studio.
2. Sync the project with Gradle files.
3. Sync and run the app on your physical device.
4. **Grant System Permissions** at runtime:
   - **Microphone**: Required for offline ONNX wake-word processing.
   - **Contacts**: Required for dialing and messaging resolving.
   - **Phone**: Required for placing direct calls.
   - **Notification Listener Access**: Enable in Android Settings -> Special App Access -> Notification Listeners for Jago.
   - **Accessibility Service**: Manually enable in Android Settings -> Accessibility -> Jago Assistant (Required to run macro playbacks and dynamic agent exploration).
   - **Device Admin**: Request Admin exemption in the permissions tab (Required for lock screen features).
   - **Draw Over Other Apps**: Allow overlay permissions (Required for overlay screens).

---

## 🛡️ Security & Privacy Whitelisting

Jago implements strict security controls to protect the user's phone:
- **Strictly Offline Wake Word**: The microphone stream is processed entirely in the local ONNX runtime. No audio data ever leaves the device.
- **Reflection Whitelist**: Android reflection calls are strictly filtered through a security prefix whitelist (`ALLOWED_CLASS_PREFIXES`) to prevent unauthorized class instantiation:
  ```kotlin
  private val ALLOWED_CLASS_PREFIXES = listOf(
      "android.media.",
      "android.hardware.",
      "android.net.wifi.",
      "android.provider.Settings"
  )
  ```
- **Local Credentials**: API keys are injected at compile time via `BuildConfig` and are never committed to version control.

---

## 🧪 Manual Device Verification Plan

To verify all system features, follow these testing guidelines on a physical device:

### 1. Wake Word Latency
*Say "Hey Jago" (or "Jaagrut")*  
**Expected**: The UI overlay opens in $< 500\text{ms}$. Verify that the LSTM rolling hit counter activates the service scope cleanly.

### 2. Multi-Stage Contact Resolution
*Create contacts `Mummy`, `Rishabh`, `Rishabh ki Mummy`.*  
- **Test Case 1 (Exact Match)**: Say *"Call Mummy"*. **Expected**: Calls `Mummy`. Does not trigger ambiguity with `Rishabh ki Mummy`.
- **Test Case 2 (Fuzzy Match)**: Say *"Call Mammy"*. **Expected**: Correctly resolves to `Mummy` using Levenshtein distance.
- **Test Case 3 (Ambiguity)**: Say *"Call Rishabh"*. **Expected**: Says *"I found multiple contacts: Rishabh or Rishabh ki Mummy. Please say the full name."*

### 3. WhatsApp Messaging (Anti-Misclick & Auto-Send)
*Say "Message Rishabh ki Mummy saying hello".*  
**Expected**: WhatsApp opens to the chat, inputs the message, and automatically clicks send. If opening settings, verify it clicks your name while ignoring QR/account switcher buttons.

### 4. Direct Settings & Reflective Calls
*Say "Turn on Flashlight" or "Mute Volume".*  
**Expected**: Action executes immediately via reflective calls on system services. If system settings (Write Settings) permissions are revoked, verify Jago gracefully redirects you to enable them.

### 5. Conversational Fallbacks
*Say "Tell me a joke" or ask general knowledge.*  
**Expected**: The request is routed to Sarvam AI (English/Hindi) or Gemini 3.5 Flash, returning a concise 1-2 sentence voice reply.

---

<p align="center">
  Built with ❤️ in Kotlin · Powered by ONNX Runtime + Gemini 3.5 Flash
</p>
