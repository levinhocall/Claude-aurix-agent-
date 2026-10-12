# AURIX security & reliability audit (P4.3 / P4.5)

Scope: static review of the repository plus unit tests. Nothing here was verified on a physical device.

## Credentials
- API keys live in `ProviderStore` → `SecureSettings` (EncryptedSharedPreferences). Known risk: the `security-crypto` library is an alpha artifact; keep it pinned and re-evaluate when a stable release exists.
- `Redactor.secrets` now runs on every persisted event (`AgentEvents.emit`) and every tool-call row (`ToolExecutor`), so a key that shows up in a tool argument or error body is stored as `[REDACTED]`. `Redactor.all` (adds OTP, card-like numbers, e-mail) is used for the audit CSV export.
- Prompts are never persisted. `model_calls` stores sizes, timings, model name and a routing note only.
- `android:allowBackup="false"`: Room data and prefs are not included in cloud/device-transfer backups.

## Network
- `network_security_config` allows cleartext at the platform level so LAN model servers (Ollama) work; `CleartextGuard` (OkHttp interceptor) rejects `http://` to any non-local host. Residual risk: non-OkHttp stacks (WebView, HttpURLConnection) are not covered by the guard; none are used for agent traffic today.
- Web tools go through `SafeFetcher` (private-address/SSRF checks).

## Exported components
| Component | Exported | Why / control |
|---|---|---|
| `MainActivity` | yes | launcher |
| `AurixAccessibilityService` | yes | required for AccessibilityService; protected by `BIND_ACCESSIBILITY_SERVICE` |
| `AurixNotificationListener` | yes | required; protected by `BIND_NOTIFICATION_LISTENER_SERVICE` |
| `ProactiveReceiver` | yes | listens only to protected system broadcasts (`BATTERY_LOW`, `ACL_CONNECTED`); apps cannot send these, handler also checks the action |
| `WakeWordService`, `FileProvider`, `InitializationProvider` | no | |

## Tool safety
- Every tool call passes `ToolExecutor`: required-field check, **new** `ArgValidator` (type/shape/size limits), risk gate, timeout, retry policy, logging, artifact verification.
- Approval policy is central (`ApprovalManager`); CRITICAL actions always ask. Approvals now expire (`ApprovalFreshness`: 15 min HIGH, 2 min CRITICAL) and are never replayed after a crash/restart.
- Non-idempotent tools (SMS, calls, alarms, taps, writes…) are journaled *before* running; after a crash an unresolved entry is never repeated automatically — the user is asked what happened.

## Prompt injection
- Output of outside-world tools (web, files, OCR, notifications, screen, build logs) is fenced in `<untrusted_data>` and the system prompt states it is data only. A closing tag inside the content cannot end the fence.
- `InjectionDetector` is a heuristic (not proof of safety). A hit emits a `SECURITY_NOTICE` and **taints the mission**: every non-LOW tool then asks for approval, even in Jarvis mode or with "always allow".

## Coding workflow
- Only an allow-list of build/test commands runs, with no shell (argv executed directly), shell metacharacters rejected, a scrubbed environment (no keys/tokens passed to project code), a run budget, and a repair-round budget.
- Edits are snapshotted, read-back verified, capped, refuse secrets/generated dirs, refuse stale reads and files with uncommitted user changes. Repairs cannot weaken security settings, disable/delete tests, or touch files unrelated to the failure.
- If the toolchain is not installed on the device the tool says so; a build or test result is never invented.

## Retention
- Finished missions and their tool calls, events, steps, files, checkpoints, side-effect journal and verification rows are purged after 30 days; `model_calls` after 14 days.

## Reliability review (P4.5)
- Removed the only `runBlocking` (settings reload) from app code.
- Network/disk work runs on `Dispatchers.IO`/provider coroutines; cancellation is rethrown in the runtime, executor and recorders.
- Retries are bounded everywhere: Failover (keys/rounds), tool retry policy, per-step attempts, replans, verify rounds, self-repair ledger, build-repair rounds, run budget.
- Context growth is bounded by `ContextBuilder`, `trimHistory`, and `ContextCompactor` on overflow.
- Large local models are never loaded automatically.

## Not done / open
- Room database is not encrypted (SQLCipher would be needed); data is protected by app-private storage only.
- No instrumented (on-device) tests for process-death recovery or the migration; the v3→v4 SQL is exercised on JVM SQLite only.
- Compose error states were not audited screen by screen.
