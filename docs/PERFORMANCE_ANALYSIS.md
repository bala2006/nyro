# NYRO latency and repeated-action analysis (pre-implementation)

Scope: Azure remains the only LLM. No local model, no new inference provider. Kotlin does deterministic work.
Branch analysed: `main` at `0fbdc34`. Everything marked **[source]** was confirmed by reading the code.
Anything marked **[inferred]** follows from the code plus your logs but needs on-device measurement.

## 1. Root causes

### A. One Azure call per tool call **[source]**
`Agent.run()` is a strict loop: `chat()` → execute tool calls → append results → `chat()`. Nothing in the loop
can finish a task without the model reading the previous result. Three things make this worse:

1. There is no deterministic path for predictable requests. `LocalCommands` exists but is only used when Azure is
   *not configured or unreachable*. "Open NextStep" always goes to Azure.
2. Every UI tool returns its own screen render, so each step needs a model turn to read it, even when the step
   was predictable ("tap the Search icon, type, press enter").
3. The model may already emit several tool calls per turn, but the system prompt never tells it to, and there is no
   plan construct with expectations, so it cannot safely chain steps across a screen change.

Test B (NextStep, ~7 calls) and Test C are direct consequences: `list_apps` → model → `list_apps` → model →
`open_app` → model → screenshot → model. `open_app` already resolves fuzzy names locally.

### B. Redundant screenshots **[source]**
`lastBatchWasScreen` is true after *any* tool with `touchesScreen` (including `read_screen`, `find_elements`,
`take_screenshot`, `open_app`). On the next iteration `screenshotForStep()` captures, annotates, JPEG-encodes and
uploads a screenshot unconditionally (when screenshots are allowed). Consequences:

- Every screen action costs a capture (+120 ms overlay hide), an annotate pass over a full-resolution
  ARGB copy, a scale, a JPEG encode and an image upload, even though the tool result already carries the full
  accessibility render.
- `take_screenshot` is itself `touchesScreen`, so after it the loop attaches the tool's image **and** a second
  automatic screenshot. Two captures within ~1 s also hit Android's screenshot rate limit, and `screenshot()` then
  sleeps a fixed 1100 ms before retrying.
- The automatic screenshot never shows the "Screen shared with Azure" notice, although Settings promises
  "A notice appears every time". This is a privacy-contract bug as well as a latency bug.
- Your Test A numbers fit: calls carrying an image took 4.4 s and 2.2 s; text-only calls took 1.3 to 2.4 s.

### C. Growing context **[source]**
- Every tool result is kept verbatim (up to 9000 chars) until the history reaches ~108k tokens
  (`COMPACT_AT_TOKENS`). Stale screen renders are never dropped.
- `render()` prints coordinates, resource ids and flags for up to 90 to 220 elements per action.
- `list_apps` returns up to 150 lines every call.
- 60+ tool schemas are sent on every request (stable, so cacheable, but they count toward the 1,024-token prefix
  that caching needs and are re-billed at the discounted rate).
- `stateBlock()` performs a full accessibility tree walk just to get the foreground app label.

The 2,754 → 6,867 token growth in Test A is this accumulation: roughly 400 to 600 tokens per step.
Caching (below) makes the stable prefix cheap, but it does not shorten generation, and growth is unbounded.

### D. Fixed waits and polling **[source]**
| Where | Delay |
|---|---|
| `waitForIdle` (every UI action) | `delay(120)` + wait for 350 ms of silence, up to 3000 ms |
| `read_screen` | same, 250 ms quiet, up to 1500 ms |
| `verifyForeground` (open_app) | `delay(250)` poll, each poll is a **full tree walk**, then `waitForIdle(300, 2000)`, then two more full reads |
| `type_text` | `delay(300)`, `delay(150)`, `delay(400)` |
| `screenshot()` rate limit | fixed 1100 ms |
| `Skills.settle` | 250 ms polling with full tree walks |
| `wait` tool | model-chosen fixed sleep (the 2,061 ms in Test A) |

`waitForIdle` measures silence since the last accessibility event of any kind. Two problems:
animated apps (Duolingo) emit events continuously, so the wait runs to its 3 s cap; and the service counts
NYRO's own overlay events (status pill text changes) as UI activity. **[inferred]**: the 3.6 s `open_app`
in Test A is poll granularity + three tree walks + idle wait on top of the actual cold start.

### E. Why loop prevention did not stop the Duolingo cycle **[source]**
`noChangeStreak` increments only when a result contains the literal text `Screen changed: NO`, and resets
on any other successful result. Against the Test A trace it cannot fire:

1. `signature` hashes the text, description, role and `bounds.top/40` of *every* element. Duolingo's animated
   and ticking content changes it constantly, so the check reports "changed" almost every time.
2. The pattern is tap → back → tap. `press_back` changes the screen, which resets the streak, so three
   consecutive no-change results never occur.
3. A successful `take_screenshot` (no such text in the result) also resets it.
4. When it does reach 3, it only appends a user message. The model can ignore it, and the next tool call
   still executes.

There is no record of *which* action was done on *which* screen, so "tap SECTION 2, UNIT 1 again" is invisible.

### F. Slow tool operations **[source for the structure, inferred for the 16.6 s]**
A single `tap_element` on a large tree does:
1. `ScreenReader.resolve(e0, ScreenSession.read(svc))`. The full snapshot is an *eager argument*, evaluated
   even when `node.refresh()` would have succeeded. One wasted tree walk per tap.
2. `clickNode` (cheap), then `observe`: `waitForIdle` (up to 3 s) and a second full snapshot.

`snapshot()` walks up to 1500 nodes with one `getChild` IPC per node and no time budget. On a heavy tree
(WebView or long text screens, which `flagIncludeNotImportantViews` and
`flagRequestEnhancedWebAccessibility` make larger) two walks plus a 3 s idle wait explain a multi-second tap.
I could not reproduce 16.6 s without the device, so the fix is a time-boxed walk plus per-phase timing so the
real bottleneck shows up in the next run.

### G. Additional defects found while reading **[source]**
- **Stale element execution.** `ScreenReader.resolve` accepts a node if `refresh()` succeeds and it is visible.
  RecyclerView recycles nodes, so the same node can now show a different row. The tap lands on the wrong
  element. The text/description of the refreshed node is never compared with what the model was shown.
- **Stale sequences.** If the model emits `[tap_element 5, tap_element 7]` in one turn, the second call runs
  against ids from the screen before the first tap.
- **Duplicate observation of identical screens.** Nothing detects "same screen, same render".
- **Cancellation of streaming.** After headers arrive, `readUtf8Line()` blocks and is not tied to the coroutine
  job, so Stop waits for the next streamed line.
- **No connection pre-warm.** The first request after idle pays DNS, TCP and TLS.

## 2. Research findings

| Topic | Finding | Source |
|---|---|---|
| Prompt caching | Automatic for GPT-4o and newer when the first 1,024 tokens are identical; hits in 128-token steps (GPT-5.5 and earlier). Keep context append-only; dynamic content last. `prompt_cache_key` only on GPT-5.6+ (already gated). `prompt_cache_options` / breakpoints return 400 on older models, so they must stay gated. Caching lowers cost and prefill time, not output generation. | [Microsoft Learn: prompt caching](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/prompt-caching) |
| Consequence for design | Editing an old message invalidates the cache from that point. So: reduce tokens at the source (compact renders, unchanged-screen collapse) instead of rewriting history each turn. | same |
| Window events | `TYPE_WINDOW_STATE_CHANGED` marks a visually distinct section; `TYPE_WINDOW_CONTENT_CHANGED` carries `getContentChangeTypes()` (subtree, text, enabled, pane…); `TYPE_WINDOWS_CHANGED` is system-only. Events can be noisy and are throttled by `notificationTimeout`, so waits must debounce and then verify state. | [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent) |
| Node prefetch (API 33+) | `getRootInActiveWindow(int)`, `AccessibilityWindowInfo.getRoot(int)` and `getChild(int,int)` take a prefetch strategy (`FLAG_PREFETCH_DESCENDANTS_HYBRID`, `…_SIBLINGS`, `…_UNINTERRUPTIBLE`). Prefetching returns batches (up to `MAX_NUMBER_OF_PREFETCHED_NODES`) and cuts IPC round trips. `minSdk` is 33, so it is available. | [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) |
| UI Automator waiting | `UiDevice.waitForIdle()` is documented as a last resort that makes tests non-deterministic. Preferred: `wait(Condition, timeout)` and condition polling. UI Automator 2.4 adds `waitForStableInActiveWindow(stableTimeoutMs, stableIntervalMs, stablePollIntervalMs, …)` and `onElement(timeout, poll) { }`. I adopt the concepts (wait for an explicit condition; stability = unchanged for an interval; bounded polling) inside the existing service, with no new dependency. | [UiDevice](https://developer.android.com/reference/androidx/test/uiautomator/UiDevice) |
| Accessibility screenshots | `takeScreenshot` can fail with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` when called too soon after the previous one; secure windows are refused. The exact minimum interval is not stated in the public reference, so I do not hard-code it and instead avoid back-to-back captures. | [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) |
| Targeted lookup | `findAccessibilityNodeInfosByText` / `…ByViewId` run the search app-side, so a text or id selector does not need a full tree walk. | Android reference |
| Batching | Parallel tool calls are already supported by the chat and responses wire formats and by the loop. The unsafe part is executing UI calls that depend on a screen the first call just changed. Fix is a local rule, not a protocol change. | code review |
| Streaming | Already used. What is missing is measurement of time to first event and HTTP header time, and tying the body read to job cancellation. | code review |

Not done on purpose: `prompt_cache_options`/breakpoints (unsafe for unknown deployments), `max_tokens`
(reasoning deployments reject it on Chat Completions), `detail:"low"` images (unmeasured quality loss).

## 3. Proposed architecture

```
 user text ──► FastRouter (pure Kotlin, closed vocabulary, validated slots)
                 │ Local intent ──► same tool pipeline (validate → permission → risk → confirm → run) ──► templated reply   [0 Azure calls]
                 └ Escalate ──► Agent loop (Azure)
                                   │  model emits tool calls or one `run_plan`
                                   ▼
                          ┌─ Safety pipeline (unchanged, per step) ─┐
                          │ ProgressTracker: block repeats/cycles   │
                          │ Batch guard: one UI mutation per turn   │
                          └─────────────────────────────────────────┘
                                   │
          PlanValidator ─► PlanExecutor ─► ActionPort (tools)  /  ScreenPort (EventGate + snapshot cache)
              preconditions → resolve semantic target on a FRESH snapshot → act → settle (events) → verify postconditions
              → continue | recover (bounded) | return to Azure with a compact observation
```

Components (new package `exec`, `perf`, `agent/router`):

- **FastRouter**: tokenises, strips filler, matches intent heads, then requires that *every remaining word belongs to that
  intent's closed vocabulary*. Unknown words escalate to Azure. This avoids hundreds of regexes and avoids
  misrouting ("battery saver", "how do I improve battery life").
- **AppIndex**: indexed app lookup with confidence tiers (exact, compact-exact so "next step" matches NextStep, unique prefix,
  unique word). Ambiguous or fuzzy matches escalate. Rebuilt on package add/remove broadcasts, warmed at startup.
- **EventGate**: accessibility-event-driven waits. Version counters, debounce, first-event timeout, quiet window,
  hard cap, fallback polling. Ignores NYRO's own package. Clock-injected so it is testable with virtual time.
- **Snapshot freshness**: each snapshot records `eventVersion` and capture time. `ScreenSession.fresh()` reuses it only if no
  relevant event arrived. Element resolution verifies the refreshed node still shows what the model saw.
- **ScreenSignature**: 64-bit layout signature (set of normalised interactive/static labels, digits masked, no bounds) for
  navigation identity, and a content signature for "anything changed".
- **ProgressTracker**: per-task action log keyed by (action, target label, layout signature). Detects no-effect repeats,
  tap → back → tap bounces, duplicate list_apps, reopen of the foreground app. Blocks (not advises).
- **Plan / PlanValidator / PlanExecutor**: the model submits steps with semantic selectors (never ids or coordinates),
  preconditions and expectations. Local code validates, resolves on a fresh snapshot, runs each step through the
  normal safety pipeline, verifies, and returns to Azure only on surprise.
- **Workflow infrastructure**: named states (conditions over the screen) and transitions (plan steps) with a runner that classifies the
  screen, picks a transition, verifies the target state and escalates on unknown states. Duolingo ships as an
  unverified template.
- **Perf**: monotonic timings, per-task trace saved to task history, p50/p95/p99 ring buffers, Diagnostics card and a
  local benchmark runner.

## 4. Expected improvements (estimates, to be confirmed on the device)

| Scenario | Before | After |
|---|---|---|
| "Open YouTube" / "Open NextStep" | 3 to 7 Azure calls, ~4 to 20 s | 0 Azure calls; router < 10 ms; time = Android launch + one foreground event |
| Battery, storage, notifications | 1 to 2 Azure calls (~3 to 5 s) | 0 calls; < 100 ms |
| Each UI step in a free-form task | model call + auto screenshot (+2 to 3 s) + 2 tree walks + up to 3 s idle wait | model call; 1 time-boxed walk; event-driven settle (typ. 150 to 300 ms) |
| Multi-step with known targets | N model calls | 1 `run_plan` call + checkpoints |
| Tokens per observation | ~120 chars/element with coordinates | measured by a unit test on a fixed fixture (see results) |

I do not claim Android launch time, network time or model generation time will shrink.

## 5. Risks and trade-offs

- **Router false positives.** Mitigated by closed vocabulary, unique-match requirement, and the fact that local intents are
  low-risk reads/opens. A Settings switch ("Fast local commands") turns the router off.
- **Plan misfires.** Targets are resolved on a fresh snapshot, risk is assessed on the resolved element, MEDIUM/HIGH
  steps end the plan, expectations are mandatory (defaulted to "screen changed"), retries are bounded.
- **Loop blocker false positives** (legitimately repeating a tap on an identical layout). Bounce rule only fires after an
  intervening back; the no-effect rule only fires when the earlier attempt changed nothing. Three blocks end the task with an
  explanation.
- **Dropping automatic screenshots** can hurt visually-driven screens. Mitigation: automatic attach remains when the
  accessibility tree is empty or non-interactive; `take_screenshot` is unchanged; the prompt says when to use it.
- **Event reliability.** Some apps emit few events. Every event wait has a first-event timeout and polling fallback, and the result is verified against
  a real snapshot.
- **Cache invalidation.** Not rewriting history keeps the cache; compaction (now at ~40k tokens) will break it once per compaction.
- **Duolingo workflow labels are unverified.** The template escalates on unknown states and never uses coordinates.
- **Test coverage limits.** JVM tests cover the logic with fakes. Real accessibility behaviour on the TECNO POVA 5 Pro
  must be validated on the device; I could not do that here.

---

# Implementation results

Branch `feature/latency-deterministic-execution`. Nothing is pushed or merged.

## What was verified, and how

| Check | Result |
|---|---|
| `./gradlew testDebugUnitTest` | 96 tests, 0 failures (JVM, virtual time for the wait logic) |
| `./gradlew assembleDebug assembleRelease` | OK (release is R8-minified) |
| `./gradlew lintDebug` | 0 errors, 52 warnings (dependency-version and similar; none in the files changed here) |
| On the TECNO POVA 5 Pro | **Not done.** The sandbox has no device. Everything below marked "measured" is from JVM tests or fixtures, not the phone. |

## Measured on the JVM (fixtures, not the phone)

| Item | Result |
|---|---|
| Router decision time | p99 0.10 ms over 500 phrases (400-app index). Phone will be slower; target is < 10 ms. |
| App lookup | 0.019 ms average (`AppIndex`, 400 apps) |
| Screen observation size | 989 → 466 estimated tokens (-53%) on a 90-element fixture; an unchanged screen is 26 tokens |
| Context compaction | 45,601 → 9,141 estimated tokens on a 30-step synthetic history |
| Tool schemas (stable prefix) | 51 tools, 18.9k chars; `run_plan` + `run_workflow` add 3.2k chars |
| Wait with no UI reaction | ≤ 450 ms (first-event timeout) vs ≥ 470 ms always before |
| Wait on an animated screen | capped at 1.5 s and reported "still animating" vs 3 s before |

## Azure request count

| Scenario | Before (your logs) | After |
|---|---|---|
| Find and open NextStep (Test B) | ~7 | **0** by construction: `FastRouterTest.findAndOpenIsOpen` routes "find and open NextStep" and "open next step" to `open_app` locally |
| Read battery / notifications / storage | 1 to 2 | 0 |
| Duolingo path (Test A) | 10 calls in the visible part | Not measured. The tap → back → tap cycle is blocked in code (`ProgressTrackerTest`, `PlanExecutorTest.theDuolingoNavigationLoopIsBlocked`). Call count depends on the model and the live UI. |

## Duolingo navigation loop

Fixed at the logic level and covered by tests that replay the trace (tap SECTION 2, UNIT 1 → back → tap again is refused with an explanation;
the third refusal ends the task). Whether the real app now completes a lesson depends on the unverified workflow labels and on the model.
**Needs a device run.**

## Dependencies

Added (test scope only): `junit:junit:4.13.2`, `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0` (same version as coroutines).
No runtime dependency added or removed. No local model of any kind.

## Open items for the real device

1. Time each stage with the new `perf:` block and Diagnostics to find where the 16.6 s tap went. The code now bounds the tree walk (1.2 s for observations, 2.5 s for explicit reads) and removes one redundant walk per tap, but I could not reproduce the original.
2. Confirm that `getRoot(FLAG_PREFETCH_DESCENDANTS_HYBRID | UNINTERRUPTIBLE)` reduces snapshot time on HiOS (it is API 33+ and documented, but unmeasured here).
3. Overlay hiding before a screenshot is unchanged (120 ms). Confirm the orb never appears in captured images and that no screenshot is sent that the notice did not announce.
4. Verify the Duolingo state conditions (`Workflows.duolingoStartLesson`) against the real UI.
5. Check `foregroundPackage` tracking when NYRO's own screen is in front (new rule keyed on `MainActivity`).
6. Run the local benchmark (Activity → Performance diagnostics) and the two manual Azure scenarios in `docs/BENCHMARKS.md`.
7. The confirmation dialog path (`Agent.confirm`) is Android-bound and untested on the JVM; plan/local/model calls all reach it through `Agent.invokeTool`.
