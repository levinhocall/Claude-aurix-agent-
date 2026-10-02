# AURIX — Phase 1 (core runtime)

Package `com.aurix.agent` · minSdk 26 · Kotlin 2.0 · Compose M3 · Hilt · Room · OkHttp

## Build
- Android Studio: open folder → Sync → Run. (Gradle wrapper jar not included: Studio generates it, or run `gradle wrapper --gradle-version 8.9`.)
- CI: push to GitHub → Actions → "Build APK" → artifact `aurix-debug-apk`.
- CLI: `gradle testDebugUnitTest assembleDebug` → `app/build/outputs/apk/debug/`

## First run
Settings → base URL + model + API key (stored via EncryptedSharedPreferences / Keystore master key). Then type an objective on Home.

## What Phase 1 really does
- Mission persisted in Room (missions, steps, event log). States per spec.
- AgentRuntime: plan → execute step-by-step → verify → complete; retry → replan on failure; limits (iterations, time, tokens, loop detection, replans, verify rounds); pause/resume/cancel.
- Provider abstraction (`AiProvider`, `AiProviderManager`) with one OpenAI-compatible provider + bounded retry for network/timeout/rate-limit.
- UI: command-center home, mission detail (timeline, controls, result, event log), settings.

## What it does NOT do yet (deliberately, no fake features)
- **No tools.** Prompts tell the model it has none; steps needing web/files are reported "blocked", then replanned. Tools = Phase 2.
- Verification is an LLM self-check only; file verification arrives with file tools.
- Runs inside the app process. If Android kills it, mission shows PAUSED on next launch → tap Resume. WorkManager/foreground service = Phase 4.
- Single API key; key pool, cooldown, routing, Anthropic native API = Phase 3.
- No approvals/memory/chat-mode/offline queue yet (Phase 4–5). No launcher icon yet.

Unverified: this source was written without a compiler. Expect to fix a few build errors on first sync — paste them back.
