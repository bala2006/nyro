# Benchmarks

Two kinds: JVM tests that run anywhere, and on-device runs that need the TECNO POVA 5 Pro (Dimensity 6080, 8 GB, Android 13 / HiOS).
Emulator numbers are not accepted for accessibility behaviour.

## 1. JVM measurements (`./gradlew :app:testDebugUnitTest`)

Printed by the tests (`app/build/test-results/testDebugUnitTest/*.xml`, `system-out`):

| What | Where | Meaning |
|---|---|---|
| `router p99` | `FastRouterTest.routingIsFast` | Local decision time for 500 routed phrases, 400-app index, warmed JIT. JVM on a workstation; the phone will be slower, so treat it as a lower bound. |
| `app lookup avg` | `FastRouterTest.appLookupIsFastAndTiered` | `AppIndex.resolve("next step")` over 400 apps. |
| `observation size` | `ScreenModelTest.compactObservationsAreMuchSmallerThanTheOldFormat` | Characters of the old action result vs the compact result vs the "unchanged" one-liner on a fixed 90-element fixture. |
| `compaction` | `ScreenModelTest.contextCompactionReplacesTheMiddleAndKeepsToolPairs` | Estimated tokens before and after context compaction. |
| `tool schemas` | `PlanTest.planToolSchemaIsStableAndWellFormed` | Size of the stable tool-definition prefix, and what `run_plan`/`run_workflow` add. |

## 2. On-device, no Azure (repeatable)

Activity tab → Performance diagnostics → **Run local benchmark**. Runs each scenario 5 times through the real request path
(router, safety pipeline, Android calls) and records `bench:<name>` p50/p95/p99:

1. Open YouTube
2. Open Duolingo
3. Find and open NextStep
4. Read battery percentage
5. Read notifications (needs notification access)

It opens real apps and returns home between runs. Nothing is sent to Azure. Run it with the phone idle, screen on, Wi-Fi as normal, battery saver off.
Record: build SHA, HiOS build number, whether apps were already running (cold vs warm start), and the four numbers per scenario.

## 3. On-device, with Azure (manual, repeat 5 times each)

Cannot be scripted because they depend on live app state and the model.

| Scenario | Request | Reset between runs |
|---|---|---|
| Navigate to a Japanese lesson | "Open Duolingo and start a Japanese lesson" | Press Home; leave the lesson |
| Find a pending office in NeuroPath | "Find a pending office in NeuroPath" | Press Home |

After each run open Activity → Tasks → the task. Its log ends with a `perf:` block:

```
perf: total 12.4s · azure calls 3 · tokens in 9120 (cached 6400, new 2720) out 210 · screenshots 0
  ttft: 3× total 2.1s avg 700ms max 900ms
  http_headers: 3× ...
  a11y_snapshot: 6× total 0.8s avg 133ms max 240ms
  ui_wait: 5× ...
  gesture: 2× ...
actions: tap_element(section 2, unit 1)>; press_back>; ...
```

Copy `azure calls`, `tokens in/cached/new`, `screenshots`, and the `ttft`, `a11y_snapshot`, `ui_wait` and `tool` lines into the table in the pull request.
The `actions:` line shows the sequence the loop detector saw (`>` new screen, `~` content changed, `=` nothing changed, `✗` failed).

## 4. What to compare against

Baseline numbers from the original report (main at `0fbdc34`): Test A about 27.5 s for 8 steps (19 s model, 8.5 s tools);
Test B about 7 model calls; Test C cancelled after repeated app listing; one earlier tap took 16.6 s.
The same scenarios on this branch should show: Test B at 0 Azure calls, no automatic screenshots (`screenshots 0` unless the tree was empty),
no repeated `tap_element(section 2, unit 1)`, and a per-phase breakdown that shows where any remaining slow tap goes.
