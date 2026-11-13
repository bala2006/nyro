# NYRO roadmap: 75 features in phases

Status key: **Exists** (working in the app now), **Partial** (some of it is built), **New** (not built), **Blocked** (needs a decision or an Android capability we don't have yet).

Model fallback (#66) is removed. NYRO uses one Azure endpoint and one deployment for reasoning, plus an optional vision deployment that you configure.

Each phase ships as its own PR, and CI must pass before the next phase starts.

## Phase 1: Trust, control and cost (first PR after this roadmap)

Safety, visibility and reliability come first. Every later feature depends on them.

| # | Feature | Status | Notes |
|---|---|---|---|
| 1 | Smart permission setup | Partial | First missing permission shown as the next step. No walkthrough per permission. |
| 2 | Capability dashboard | Done | Capabilities screen lists what works and what doesn't, each linked to its fix. |
| 3 | Live task timeline | Partial | Live steps on the Activity tab, not a separate timeline screen. |
| 4 | Pause / resume | Done | Pause between steps; resume continues from the same point. |
| 5 | Undo last action | Partial | Typed text and flashlight only. Taps and sent messages can't be undone. |
| 6 | Action preview | Partial | Full text shown for sent messages. Taps show the label only. |
| 9 | Global emergency stop | Done | Stop button, long-press, notification action, and the Stop chip. |
| 10 | Smart error recovery | Partial | Re-reads the screen when an element has moved. No other recovery. |
| 29 | Self-verification | Partial | Unchanged screens are flagged on the step. No deeper check of the result. |
| 52 | Private sessions | Done | Private session: nothing written to history, tasks or memory. |
| 62 | Token usage dashboard | Done | Token dashboard on the Activity tab: task, all time, input, output, cache, speed. |
| 63 | API cost estimation | Done | Estimated cost with editable prices on the Activity tab. |
| 69 | Connection diagnostics | Done | Test connection names the failing part: key, deployment, endpoint, quota, network. |
| 70 | Privacy monitor | Partial | Last 10 requests with time, message count, screenshot count, tokens. Not per field. |
| 67 | Context optimization | Partial | Caching, compaction and smaller screenshots. No per-request controls. |
| 68 | Response speed settings | Done | Fast, Balanced, Thorough and Deep reasoning levels. |

## Phase 2: Core experience (new information architecture and the orb)

| # | Feature | Status | Notes |
|---|---|---|---|
| 11 | Living orb animation | Done | Slow idle breathing; expressions for happy, confused and error. |
| 12 | Emotional states | Done | Happy after a clean task, confused after a failed step, frown on error. |
| 13 | Edge docking | Done | Docks to the nearest edge; can be turned off. |
| 14 | Compact mode | Done | Compact dot in Settings → Orb. |
| 15 | Quick action ring | Done | Long-press opens quick actions: talk, screen question, undo, stop, suggestions. |
| 16 | Dynamic orb size and transparency | Done | Size and opacity sliders in Settings → Orb. |
| 17 | Visual task feedback | Exists | Focus ring, taps, typing indicator, scan sweep, status pill. |
| 18 | Adaptive positioning | Partial | Moves above the keyboard. Avoiding other on-screen controls is not built. |
| 8 | Conversation continuity | Done | Older messages summarized once; included in later requests. |
| 33 | Proactive suggestions | Partial | One tip per app per session. No suggestions while a task runs. |
| 73 | Contextual overlay | Done | Suggestions in the quick row; run only when tapped. |

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
| 31 | App-specific skills | Partial | WhatsApp: send a message by contact name, with checks. Other apps not built yet. |
| 32 | Task memory (unfinished tasks) | Not started | Task memory for unfinished tasks not built. |
| 34 | Multi-tool reasoning | Exists | The agent combines device, screen and app tools. |
| 36 | Personal commands | Done | Typed shortcuts in Settings expand to their instruction. Voice shortcuts not built. |
| 49 | Custom personality | Partial | Standing instructions set the style. Separate personality presets not built. |
| 51 | Custom instructions | Done | Standing instructions are added to every request. |
| 50 | Favorite applications | Not started | Favorite apps not built. |
| 71 | Contextual intelligence | Partial | Foreground app and recent conversation are included. |
| 72 | Cross-app workflows | Partial | Possible through the tools; skills (31) make it reliable. |
| 7 | Context awareness | Exists | Foreground app and screen are included. |
| 74 | Personal knowledge graph | New | Links people, projects, preferences and tasks. Large; starts after the memory search work. |
| 75 | Personal device agent | Blocked | Learning routines needs weeks of history and clear consent. Later. |

## Phase 5: Screen understanding

| # | Feature | Status | Notes |
|---|---|---|---|
| 35 | Visual UI understanding | Partial | Numbered boxes on tappable elements and the text layout. Understanding of charts and icons depends on the model. |
| 38 | Ask about screen | Done | Quick action 'What's on screen?'. |
| 39 | Screen translation | Partial | Quick action 'Translate screen' into English. Choosing another language is not built. |
| 40 | Screenshot memory | Not started | Saving selected screenshots with a note is not built. |
| 41 | Visual element selection | Not started | Drawing a rectangle over the screen to ask about it is not built. |
| 42 | Form assistance | Not started | Form assistance (fields and confirmations) is not built. Typing into fields works. |
| 43 | Error detection | Partial | Quick action 'Explain this error'. No dedicated error database. |
| 44 | Screen comparison | Not started | Screen comparison is not built. |
| 45 | Notification understanding | Partial | Quick action 'Summarize notifications' when access is on. Reply suggestions not built. |

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

## Consolidated open items (all phases)

Everything not marked Done is listed here. Statuses come from the phase tables above.

| # | Feature | Status | Where it stands |
|---|---|---|---|
| 1 | Smart permission setup | Partial | First missing permission shown as the next step. No walkthrough per permission. |
| 3 | Live task timeline | Partial | Live steps on the Activity tab, not a separate timeline screen. |
| 5 | Undo last action | Partial | Typed text and flashlight only. Taps and sent messages can't be undone. |
| 6 | Action preview | Partial | Full text shown for sent messages. Taps show the label only. |
| 10 | Smart error recovery | Partial | Re-reads the screen when an element has moved. No other recovery. |
| 18 | Adaptive positioning | Partial | Moves above the keyboard. Avoiding other on-screen controls is not built. |
| 19 | Natural voice conversation | Partial | Tap-to-talk and continuous listening. Voice barge-in is not built. |
| 20 | Voice interruption (barge-in) | Partial | Tap the orb to interrupt speech. Interrupting by voice is blocked (echo on phone speakers). |
| 22 | Wake word ("Hey NYRO") | Blocked | Needs your decision: Porcupine (licensed) or Vosk (offline, free). |
| 24 | Contextual follow-ups ("do that again", "cancel it") | Partial | "Do that again" and "cancel it" use the last action. Picking "the second one" from a list is not built. |
| 26 | Multilingual conversation | Partial | Replies follow the user's language. Recognition for Hindi and Telugu depends on the phone's speech models. |
| 28 | Multi-step planning | Partial | The model plans step by step. Add a visible plan in the timeline. |
| 29 | Self-verification | Partial | Unchanged screens are flagged on the step. No deeper check of the result. |
| 30 | Adaptive automation | Partial | Re-reads the screen when a step fails. |
| 31 | App-specific skills | Partial | WhatsApp: send a message by contact name, with checks. Other apps not built yet. |
| 32 | Task memory (unfinished tasks) | Not started | Task memory for unfinished tasks not built. |
| 33 | Proactive suggestions | Partial | One tip per app per session. No suggestions while a task runs. |
| 35 | Visual UI understanding | Partial | Numbered boxes on tappable elements and the text layout. Understanding of charts and icons depends on the model. |
| 37 | Task scheduling | Partial | WorkManager schedules approved tasks; exact times need the exact-alarm permission. |
| 39 | Screen translation | Partial | Quick action 'Translate screen' into English. Choosing another language is not built. |
| 40 | Screenshot memory | Not started | Saving selected screenshots with a note is not built. |
| 41 | Visual element selection | Not started | Drawing a rectangle over the screen to ask about it is not built. |
| 42 | Form assistance | Not started | Form assistance (fields and confirmations) is not built. Typing into fields works. |
| 43 | Error detection | Partial | Quick action 'Explain this error'. No dedicated error database. |
| 44 | Screen comparison | Not started | Screen comparison is not built. |
| 45 | Notification understanding | Partial | Quick action 'Summarize notifications' when access is on. Reply suggestions not built. |
| 47 | Memory suggestions | New | Suggested memories are saved only after you approve. |
| 48 | Memory search | Partial | Keyword search exists. Add search across conversations. |
| 49 | Custom personality | Partial | Standing instructions set the style. Separate personality presets not built. |
| 50 | Favorite applications | Not started | Favorite apps not built. |
| 53 | Memory import and export | New | JSON file, chosen by the user. |
| 54 | Live device monitoring | Partial | Device screen updates every 3 s. Add thermal and history. |
| 55 | Battery intelligence | New | Rule-based suggestions from battery stats. |
| 56 | App usage insights | Blocked | Needs the Usage Access special permission from the user. |
| 57 | Connectivity assistant | Partial | Opens settings. Add status checks and step-by-step guidance. |
| 59 | Storage assistant | New | Finds large files. Deleting is High risk and always asks. |
| 60 | Performance assistant | New | Rule-based checks for RAM and thermal state. |
| 61 | Device troubleshooter | New | Checks permissions, network and app state, then explains results. |
| 64 | Model switching | Blocked | Conflicts with the single-model decision. **Decision needed.** |
| 67 | Context optimization | Partial | Caching, compaction and smaller screenshots. No per-request controls. |
| 70 | Privacy monitor | Partial | Last 10 requests with time, message count, screenshot count, tokens. Not per field. |
| 71 | Contextual intelligence | Partial | Foreground app and recent conversation are included. |
| 72 | Cross-app workflows | Partial | Possible through the tools; skills (31) make it reliable. |
| 74 | Personal knowledge graph | New | Links people, projects, preferences and tasks. Large; starts after the memory search work. |
| 75 | Personal device agent | Blocked | Learning routines needs weeks of history and clear consent. Later. |

Blocked on a decision: #22 wake word (Porcupine or Vosk), #64 model switching (single model), #56 usage insights (needs the Usage Access permission from the user), #75 device agent (needs a design for history and consent).

Removed: #66 automatic model fallback.
