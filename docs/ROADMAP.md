# NYRO roadmap: 75 features in phases

Status key: **Exists** (working in the app now), **Partial** (some of it is built), **New** (not built), **Blocked** (needs a decision or an Android capability we don't have yet).

Model fallback (#66) is removed. NYRO uses one Azure endpoint and one deployment for reasoning, plus an optional vision deployment that you configure.

Each phase ships as its own PR, and CI must pass before the next phase starts.

## Phase 1: Trust, control and cost (first PR after this roadmap)

Safety, visibility and reliability come first. Every later feature depends on them.

| # | Feature | Status | Notes |
|---|---|---|---|
| 1 | Smart permission setup | Partial | Permissions screen exists. Add a guided flow in order: overlay, mic, accessibility, notifications, battery. |
| 2 | Capability dashboard | Partial | Dashboard status chips exist. Expand to a full "can / cannot do" list. |
| 3 | Live task timeline | Partial | Steps are in the Tasks screen. Make it live and visible on the Activity tab. |
| 4 | Pause / resume | New | Pause keeps the step log and context. Resume continues from the log. It is not a process freeze, so the screen may have changed. |
| 5 | Undo last action | Partial | Only reversible actions can be undone (drafted text, toggles, memory adds). A sent message cannot be unsent. |
| 6 | Action preview | New | Show the exact text and target before a Medium or High action runs. |
| 9 | Global emergency stop | Exists | Stop button, long-press, notification action. Extend to cancel speech and pending requests in one place. |
| 10 | Smart error recovery | Partial | Prompt guidance exists. Add structured recovery steps (re-read, scroll, go back, retry once). |
| 29 | Self-verification | Partial | Each action reports whether the screen changed. Make the result explicit per step. |
| 52 | Private sessions | New | Conversations with no history or memory writes. |
| 62 | Token usage dashboard | Partial | Overlay shows totals. Add a dashboard with per-request and per-task numbers. |
| 63 | API cost estimation | New | Editable price per 1M input, cached and output tokens. Estimates from the usage counts. |
| 69 | Connection diagnostics | Partial | Test connection exists. Split errors into endpoint, key, deployment, quota and network. |
| 70 | Privacy monitor | New | Lists what was sent to Azure for each request: text, screenshots, counts. |
| 67 | Context optimization | Partial | Caching, compaction and screenshot limits are in. Add a visible summary per request. |
| 68 | Response speed settings | Partial | Reasoning effort exists in settings. Add a plain "Fast / Balanced / Thorough" choice. |

## Phase 2: Core experience (new information architecture and the orb)

| # | Feature | Status | Notes |
|---|---|---|---|
| 11 | Living orb animation | Partial | States exist. Add idle breathing and state-specific motion. |
| 12 | Emotional states | New | Idle, happy, confused, thinking, error faces. Cosmetic; uses existing states. |
| 13 | Edge docking | Partial | Snap to the nearest edge exists. Make it a setting with a clear dock state. |
| 14 | Compact mode | New | Shrink the orb to a small dot; tap to expand. |
| 15 | Quick action ring | New | Long-press reveals 4–6 pinned actions in a ring. |
| 16 | Dynamic orb size and transparency | New | Two sliders in settings. |
| 17 | Visual task feedback | Exists | Focus ring, taps, typing indicator, scan sweep, status pill. |
| 18 | Adaptive positioning | Partial | Moves above the keyboard. Avoiding other on-screen controls is not built. |
| 8 | Conversation continuity | Done | Older messages are summarized once and included in later requests. Private sessions are skipped. |
| 33 | Proactive suggestions | Partial | One tip per app per session when the app has suggestions. Nothing runs by itself. |
| 73 | Contextual overlay | New | Shows suggested actions for the current screen; never executes them automatically. |

## Phase 3: Voice (after Phase 2)

| # | Feature | Status | Notes |
|---|---|---|---|
| 19 | Natural voice conversation | Partial | Tap-to-talk and continuous listening. Voice barge-in is not built. |
| 20 | Voice interruption (barge-in) | Partial | Tap the orb to interrupt speech. Interrupting by voice is blocked (echo on phone speakers). |
| 21 | Continuous listening | Exists | Setting exists. |
| 22 | Wake word ("Hey NYRO") | Blocked | Needs your decision: Porcupine (licensed) or Vosk (offline, free). |
| 23 | Voice personalities | Done | Voice style presets: Calm, Friendly, Brisk. Voice picker and speed and pitch sliders remain. |
| 24 | Contextual follow-ups ("do that again", "cancel it") | Partial | "Do that again" and "cancel it" use the last action. Picking "the second one" from a list is not built. |
| 25 | Voice and text switching | Done | Voice and text share one conversation. |
| 26 | Multilingual conversation | Partial | Replies follow the user's language. Recognition for Hindi and Telugu depends on the phone's speech models. |
| 27 | Adjustable response length | Done | Short, natural or detailed replies. |

## Phase 4: Agent intelligence and personalization

| # | Feature | Status | Notes |
|---|---|---|---|
| 28 | Multi-step planning | Partial | The model plans step by step. Add a visible plan in the timeline. |
| 30 | Adaptive automation | Partial | Re-reads the screen when a step fails. |
| 31 | App-specific skills | New | Start with WhatsApp (send message), Settings (toggles), YouTube (search), Chrome (open URL), Gmail (compose). Each skill is code with verification, not model guesses. |
| 32 | Task memory (unfinished tasks) | New | Saves paused and failed tasks with their step log. |
| 34 | Multi-tool reasoning | Exists | The agent combines device, screen and app tools. |
| 36 | Personal commands | New | Saved phrases that expand into instructions. |
| 49 | Custom personality | New | Editable style text in the system instructions. |
| 51 | Custom instructions | New | Persistent instructions added to the static prefix. Changing them invalidates the cache once. |
| 50 | Favorite applications | New | Counts app launches locally; shown on Home. |
| 71 | Contextual intelligence | Partial | Foreground app and recent conversation are included. |
| 72 | Cross-app workflows | Partial | Possible through the tools; skills (31) make it reliable. |
| 7 | Context awareness | Exists | Foreground app and screen are included. |
| 74 | Personal knowledge graph | New | Links people, projects, preferences and tasks. Large; starts after the memory search work. |
| 75 | Personal device agent | Blocked | Learning routines needs weeks of history and clear consent. Later. |

## Phase 5: Screen understanding

| # | Feature | Status | Notes |
|---|---|---|---|
| 35 | Visual UI understanding | Partial | Screenshots go to the vision deployment. Add the text layout described in the design notes. |
| 38 | Ask about screen | Partial | Possible through take_screenshot. Add a button on Home. |
| 39 | Screen translation | New | Model translates visible text on request. |
| 40 | Screenshot memory | New | Saves selected screenshots locally with a note. |
| 41 | Visual element selection | New | Drag a rectangle over the screen and ask about it. |
| 42 | Form assistance | New | Reads fields and fills them with confirmation for each sensitive field. |
| 43 | Error detection | New | Model reads visible error text and suggests a fix. |
| 44 | Screen comparison | New | Compares two saved screenshots. |
| 45 | Notification understanding | Partial | Reading and replying work. Add summaries and reply suggestions. |

## Phase 6: Device intelligence

| # | Feature | Status | Notes |
|---|---|---|---|
| 54 | Live device monitoring | Partial | Device screen updates every 3 s. Add thermal and history. |
| 55 | Battery intelligence | New | Rule-based suggestions from battery stats. |
| 56 | App usage insights | Blocked | Needs the Usage Access special permission from the user. |
| 57 | Connectivity assistant | Partial | Opens settings. Add status checks and step-by-step guidance. |
| 58 | Sensor intelligence | Exists | Device screen reads sensors. |
| 59 | Storage assistant | New | Finds large files. Deleting is High risk and always asks. |
| 60 | Performance assistant | New | Rule-based checks for RAM and thermal state. |
| 61 | Device troubleshooter | New | Checks permissions, network and app state, then explains results. |
| 37 | Task scheduling | Partial | WorkManager schedules approved tasks; exact times need the exact-alarm permission. |

## Phase 7: Personal memory

| # | Feature | Status | Notes |
|---|---|---|---|
| 46 | Personal memory | Exists | Local memories. |
| 47 | Memory suggestions | New | Suggested memories are saved only after you approve. |
| 48 | Memory search | Partial | Keyword search exists. Add search across conversations. |
| 53 | Memory import and export | New | JSON file, chosen by the user. |

## Phase 8: Advanced screen features

| # | Feature | Status | Notes |
|---|---|---|---|
| 64 | Model switching | Blocked | Conflicts with the single-model decision. **Decision needed.** |
| 65 | Vision model selection | Exists | Vision deployment setting. |
| 66 | Automatic model fallback | Removed | Not part of NYRO. |

## Decisions needed before Phase 1 ships

1. Confirm the phase order above.
2. Wake word (22): choose Porcupine (licensed) or Vosk (offline, free), or defer.
3. Model switching (64): drop it, or keep it for choosing the vision deployment only?
4. Offline command mode: when Azure is unreachable, NYRO still answers device questions and opens apps. Keep it? It is local behavior, not a model fallback.

## Open items after Phase 3

These items were started but are not complete. Each one is listed by roadmap number.

| # | Feature | Where it stands |
|---|---|---|
| 1 | Guided permissions | First missing step is shown. No step-by-step animation or per-step explanation. |
| 3 | Live task timeline | Shown on the Activity tab, not as a separate screen. |
| 5 | Undo | Only typed text and the flashlight. Taps and sent messages can't be undone. |
| 6 | Action preview | Only sent text shows the full text. Taps show the label only. |
| 10 | Error recovery | Re-reads the screen only when an element has moved. |
| 18 | Adaptive positioning | Keyboard only. Avoiding other on-screen controls is not built. |
| 20 | Barge-in | Tap to interrupt. Voice interruption is blocked by echo on phone speakers. |
| 22 | Wake word | Blocked on a decision: Porcupine or Vosk. |
| 24 | Follow-ups | Choosing from a list ("the second one") is not built. |
| 26 | Multilingual | Recognition for Hindi and Telugu depends on the phone's speech models. |
| 33 | Proactive suggestions | One tip per app. No suggestions while a task runs. |

Later phases (4 to 8) are not started. The Phase 4 items include app skills (#31), personal commands (#36), and task memory (#32).
