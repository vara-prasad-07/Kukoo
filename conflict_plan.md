# Kukoo — Robust Conflict & Day-Planning Plan (v2: start-time model)

**Goal:** a scheduling core that never shows wrong information, on any day, at any time of day, with any mix
of tasks — 100 % on-device and offline.
**Status:** Phases 0-4 are implemented and tested (see §12). Not built yet: settings screen for awake hours,
end-of-task prompts / time-change receivers (Phase 5), calendar import and TIGHT / REMINDER_CLASH (Phase 6).
**Supersedes v1**, which assumed the time on a task was a *deadline*. It is not — see §1.

---

## 0. TL;DR

A task has a **start time** and a **duration**. So a scheduled task is simply the fixed interval
`[start, start + duration)`. That makes the feature far simpler and far more exact than a deadline scheduler:

- A **conflict is an overlap** between two intervals. No "late", no "no room", no priority-driven guessing
  about who missed a deadline. Overlap is a fact you can compute exactly.
- **Planning the day** means: show the timeline, find **free slots**, and *propose* a start time for tasks
  that have none. Kukoo never silently moves a task the user scheduled.
- Conflicts are caught **while the time is being chosen** (form and voice), not only when a plan is opened.
- Every fix Kukoo offers is **simulated first**, so a suggestion can never create a new conflict.

All of it is plain Kotlin + SQLite + `java.time`. No model, no network.

---

## 1. The model (what the code field means)

`Task.deadline` (column `deadline_at`) **stays in code and in the database** — renaming the field would be a
risky migration for no user benefit. In the product it is the **start time**.

| Concept | Definition |
|---|---|
| Start | `Task.deadline` (epoch ms). May be `null` = **unscheduled** |
| Duration | `Task.durationMin` (5 min – 8 h, default 30) |
| Interval | `[start, start + duration)` — half-open, so **back-to-back tasks do not conflict** (9:00–9:30 then 9:30–10:00 is fine) |
| Relevant | open tasks only; done tasks never conflict |
| Reminder call | rings `start − reminderMin` (already the meaning today; wording now says "before it starts") |

Task state at time `now`, computed for every open task (each task is in **exactly one**):

| State | Rule |
|---|---|
| `UNSCHEDULED` | no start time |
| `UPCOMING` | `start ≥ now` |
| `IN_PROGRESS` | `start < now < end` — *not* a problem; shown as "Now" |
| `PAST` | `end ≤ now` and still open — "was 7:00–7:30 and isn't marked done" |

### Vocabulary in the app (Phase 0 — done)

| Was | Now |
|---|---|
| Deadline (field, "Add deadline", "Remove deadline") | Start time |
| "When is X due?" | "When should X start?" |
| "due tomorrow at 5 PM" | "starting tomorrow at 5 PM" / "starts …" |
| "Overdue" (Home section, stat) | "Past start time" / "Past start" |
| "Due today" (stat) | "Today" |
| "No deadline" | "No start time" |
| "What's due today?" chip | "What's on today?" |
| "Change the deadline to 6 PM" chip | "Change the time to 6 PM" |
| "Reminder … before the deadline" | "… before it starts" |

Spoken input still accepts the old words ("deadline", "due"), plus "start time" / "starting time" /
"what's on today". Model prompts are unchanged on purpose, so on-device model behaviour did not shift.

> **Stopgap:** the replan conflict text now reads "runs N minutes past its scheduled time". That whole
> planner is replaced in Phase 3, so this wording is temporary.

---

## 2. What a conflict is

| Kind | Rule | Severity | Example (spoken by the engine) |
|---|---|---|---|
| `OVERLAP` | two relevant intervals intersect by > 0 ms **and the overlap ends after `now`** | high | "Standup and Dentist overlap for 15 minutes, from 9:15 to 9:30 AM." |
| `PAST` | open task whose interval is over | medium | "Gym was 7:00–7:30 this morning and isn't marked done." |
| `UNUSUAL_HOUR` | interval touches the user's sleep/quiet hours | warning | "Call mom is at 3 AM. Did you mean 3 PM?" |
| `TIGHT` *(optional)* | gap between consecutive tasks < user's buffer | info | "Only 5 minutes between Standup and Gym." |
| `REMINDER_CLASH` *(optional)* | a reminder call would ring during another task | info | "The call about Gym at 8:50 would ring during Standup." |
| `UNPLACEABLE` | unscheduled task with no free slot in the next 7 days | info | "I couldn't find 2 hours for Taxes this week." |

`IN_PROGRESS` and `UPCOMING` are states, not conflicts. `UNUSUAL_HOUR` exists because speech recognition
mishears AM/PM constantly — it is the cheapest way to catch a wrong time before it becomes a wrong day.

**Overlap is symmetric.** Both tasks are named; priority is *never* used to decide whether a conflict
exists — only to suggest which one to move (§4).

**Invariants** (these become property tests, §6):

1. **Exact:** `OVERLAP` exists iff two relevant intervals intersect; minutes are exact (rounded up only for
   speech, never in logic).
2. **No false alarms:** touching intervals, done tasks, unscheduled tasks, and overlaps that are entirely in
   the past never produce a conflict.
3. **Every open task lands in exactly one state** (§1); none is dropped from any view.
4. **Symmetric and stable:** result does not depend on task order in the store; recomputing without changes
   gives byte-identical output.
5. **Verified fixes:** applying any offered resolution leaves zero new conflicts and stays inside awake hours.
6. **Same words on screen and in voice:** both come from the engine (`EngineResult.spoken`); the LLM never
   writes a conflict sentence.
7. **Fully offline.**

---

## 3. Detection

`ConflictDetector.detect(tasks, now, horizon, acknowledged)`

1. **Expand recurrence.** A repeating task is one row holding its *next* start. Generate one virtual
   occurrence per repeat inside the horizon (default 14 days) using `DeadlineResolver.nextOccurrence` (local
   wall-clock time, so a 7 AM task stays 7 AM across DST). Occurrence id = `(taskId, index)`.
2. **Keep relevant intervals:** open, scheduled, `end > now`.
3. **Sweep line:** sort by start, keep an active set, emit a pair whenever intervals intersect. O(n log n).
4. **Cluster:** connected overlapping tasks form a **group** (A overlaps B, B overlaps C → one group of
   three); pair detail is kept so messages stay exact.
5. **Drop acknowledged pairs** (§4.3).
6. **Sanity rules:** `UNUSUAL_HOUR`, `PAST`, optional `TIGHT` / `REMINDER_CLASH`.

Output is a `ConflictReport` (`groups`, `pastTasks`, `warnings`, `unplaceable`) used by every screen and by
voice, so all callers agree.

---

## 4. Planning & resolution

### 4.1 Free-slot finder (the "planning" part)

`SlotFinder.find(duration, notBefore, notAfter?, busy, availability)` returns the earliest free interval that:
fits `duration` contiguously, lies inside **awake hours** (default 07:00–23:00, configurable) and outside
breaks, keeps the optional buffer from neighbours, and starts no earlier than `max(now rounded up to 5 min,
notBefore)`. A gap exactly equal to the duration fits. Tasks are placed contiguously — a start time is one
time, so there is no splitting.

**"Plan my day"** (Home icon / voice) becomes:
1. Show today's **timeline**: tasks in order, free gaps, overlaps drawn side by side.
2. For **unscheduled** tasks, *propose* start times: order = priority ↓, duration ↓ (fit big blocks first),
   then creation order; first-fit into the free gaps. Nothing is saved until the user accepts (all, or one).
3. For **overlaps**, offer the resolutions below.

### 4.2 Resolutions — simulated, never guessed

For an overlap group the engine builds candidates and re-runs detection on each; only candidates that leave
zero new conflicts are offered:

1. **Move the lower-priority task** (tie: the later-created, then the shorter one) to its **next free slot**
   after the conflict.
2. **Move it to the same time tomorrow** (or the next day that is free).
3. **Shorten** the earlier task to end when the other starts (only if the result is ≥ the 5-minute minimum).
4. **Keep both** — acknowledge the overlap (§4.3).

Each option is a list of existing `TaskCommand.UpdateTask`s, applied as **one undoable step** (`undoAction`
becomes a batch closure). Voice: *"Standup and Dentist overlap from 9:15 to 9:30. I can move Dentist to
9:30, move it to tomorrow at 9:15, or keep both. Which?"* → "the first one".

### 4.3 Acknowledged overlaps ("keep both")

Sometimes overlap is intentional (laundry while cooking). "Keep both" stores an acknowledgement:
`conflict_ack(taskA, taskB, startA, endA, startB, endB)` (new table, additive migration). It is **invalidated
automatically** when either task's start or duration changes, and deleted with either task. This removes the
biggest source of *nagging* wrong info.

---

## 5. When conflicts are surfaced

| Moment | Behaviour |
|---|---|
| **Choosing a time in the form** | Live, as the picker changes: "Overlaps Standup (9:00–9:30) by 15 min" + **Move to 9:30** / **Save anyway**. Cost is microseconds, so no debounce needed. |
| **Adding by voice** | Asked before saving, once start **and** duration are known: "That overlaps Standup 9 to 9:30. Move it to 9:30, pick another time, or keep both?" `keep both` saves and acknowledges. New draft step alongside title/start/duration/priority. |
| **Editing / moving / snoozing / reopening** | Engine plans before and after; reports only the **difference**: "That now overlaps Dentist by 15 minutes." / "That clears the overlap." |
| **Undo** | Recomputed from scratch; no stale conflict. |
| **Plan my day / timeline** | Full picture (§4.1) with resolution buttons. |
| **Ask** ("any conflicts?", "am I free at 3?", "when's my next free hour?", "can I fit a 2-hour task today?") | New read-only commands; nothing is saved. |
| **Home** | Banner "2 overlaps" + a red chip on each affected row ("Overlaps Standup"). |
| **Time passing** | On app foreground, `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`, and after each task's end: tasks become `PAST`; a local notification "Still on Gym? Mark done or add 15 minutes" (adding time re-runs detection, so a knock-on overlap is reported at once). Uses existing `CallScheduler` alarm plumbing. |

### Voice / LLM contract (unchanged principle)

- The LLM/rule parser only emits commands. New: `CheckConflicts`, `FreeSlots(duration?, day?)`,
  `ResolveConflict(choice)`, plus `Replan` reinterpreted as "plan my day".
- `LlamaGrammar` / `LlamaIntentParser` get those actions. `Grounding.kt` must drop `ResolveConflict` when
  nothing is pending (same anti-hallucination rule as the add-task draft).
- Every sentence comes from `TaskEngine`, so tests can assert exact wording.

### Offline guarantee

- `KukooApp` uses `LocalReplanner` only; **`OfficeKitReplanner` (HTTP to a laptop) is removed** from the
  default wiring. This also removes the ~4 s stall it causes on a real phone today.
- Optional Phase 6: read the device calendar through `CalendarContract` (read-only, on-device provider,
  `READ_CALENDAR`) and treat events as busy intervals. Still no network access by Kukoo.

---

## 6. Edge cases (each becomes a named test)

| # | Scenario | Required behaviour |
|---|---|---|
| 1 | 9:00–9:30 then 9:30–10:00 | No conflict (half-open) |
| 2 | 9:00–9:30 and 9:29–9:59 | Overlap of exactly "1 minute" |
| 3 | Long task contains a short one | Overlap = the short one's length; wording says "inside" |
| 4 | Identical start & duration | Overlap = full duration, both named |
| 5 | A∩B and B∩C but not A∩C | One group of three, pair detail kept; resolution moves the fewest tasks |
| 6 | 23:30 + 90 min crossing midnight | Interval intact; conflicts found on the next day; timeline splits at midnight |
| 7 | Overlap wholly in the past | Ignored |
| 8 | One task ended, other still running | Ignored (the overlap has elapsed); a *future* part still counts |
| 9 | Done task overlapping | Ignored; **reopen** re-checks and reports |
| 10 | Task with no start time | Never a conflict; proposed a slot in "plan my day" |
| 11 | Daily 7:00 gym + one-off tomorrow 7:15 | Overlap on tomorrow's occurrence, message names the date |
| 12 | Weekly / monthly (31st) / weekdays recurrence | Correct occurrences; no crash on short months |
| 13 | DST spring-forward (02:30 doesn't exist) / fall-back (01:30 twice) | `java.time` resolution, deterministic, no exception |
| 14 | Timezone change | Repeating tasks stay at local wall-clock time; one-offs keep their instant; all recomputed |
| 15 | Snooze +15 min | New overlaps reported as a diff |
| 16 | Lengthen a task into the next one | "That now overlaps…"; shorten again → "That clears the overlap." |
| 17 | Undo any change | Conflict state recomputed and correct |
| 18 | "Keep both" then edit one task | Acknowledgement dropped, overlap reappears |
| 19 | Delete one of two acknowledged tasks | Acknowledgement removed |
| 20 | "3 in the morning" (mis-heard) | `UNUSUAL_HOUR` with "did you mean 3 PM?" |
| 21 | Slot finder: gap exactly equals duration | Fits |
| 22 | Slot finder: nothing today | Tries following days up to 7, else `UNPLACEABLE` |
| 23 | Buffer setting on | Slots and `TIGHT` respect it |
| 24 | Two tasks with identical titles | Wording identifies them by time |
| 25 | 200 tasks | Detect + slot search < 20 ms on the iQOO 15 |
| 26 | Start exactly `now` | `UPCOMING`/`IN_PROGRESS` boundary is consistent (start ≤ now < end is IN_PROGRESS) |
| 27 | Open task whose end passed | `PAST`, offered done / reschedule / delete, never an overlap with the future |
| 28 | Adding a task that starts in the past | Still refused as today ("has already passed") |
| 29 | Form default duration (30 min) causing a surprise overlap | Message shows the duration used, so the user can change it |

---

## 7. Architecture & file map

New package `domain/schedule/` — all pure Kotlin:

```
Interval.kt          half-open interval maths (overlap, contains, subtract)
Occurrences.kt       recurrence expansion over a horizon (DST-safe)
ConflictDetector.kt  sweep line, groups, PAST / UNUSUAL_HOUR / optional rules
SlotFinder.kt        first-fit free slots inside availability
Resolver.kt          candidate fixes, simulation, filtering
ScheduleSettings.kt  awake hours, breaks, buffer, ask-before-saving (prefs)
```

Changes to existing code:
- `Planner.kt` / `Replanner.kt` / `OfficeKitReplanner.kt`: the deadline scheduler is replaced by the above;
  `Replan` now means "plan my day". `Plan.kt` keeps `ScheduledBlock`-style items but with `Conflict` = overlap.
- `TaskEngine.kt`: new `conflicts()`, `impactOf(change)`, draft step for overlap choice, `resolve()`, batch undo;
  every mutating command appends its diff sentence.
- `TaskCommand.kt`, `RuleBasedIntentParser.kt`, `LlamaIntentParser.kt`, `LlamaGrammar.kt`, `Grounding.kt`: new commands.
- `SqliteTaskStore.kt`: `DB_VERSION` 3 → 4, adds `conflict_ack` (existing task rows untouched;
  `SchemaMigrationTest` extended).
- `TaskEditorSheet.kt`: live overlap warning with **Move** / **Save anyway**.
- `PlanScreen.kt` → day **timeline** (overlaps side by side, free gaps, proposals with Accept).
- `HomeScreen.kt` / `TaskGrouping.kt`: banner, row chips, `IN_PROGRESS` shown as "Now".
- New `ui/settings/MyDayScreen.kt` for awake hours, breaks, buffer.
- `KukooApp.kt`: drop `OfficeKitReplanner`. `call/CallScheduler.kt` + receivers: end-of-task prompts,
  time/timezone changes.

---

## 8. Test strategy — how "perfect" is enforced

1. **Golden tests** for every row in §6 with a fake `Clock` (`TestSupport.kt` exists).
2. **Property tests** (seeded random, thousands of cases) for invariants 1–5: random tasks, durations,
   recurrences, `now`, and zones including `America/New_York`, `Europe/London`, `Australia/Lord_Howe`.
3. **Oracle test:** for ≤ 8 tasks compare the sweep-line result to a brute-force O(n²) pairwise check.
4. **Metamorphic tests:** moving a task by its own duration past another removes exactly that overlap; every
   offered resolution, when applied, yields zero new conflicts and stays in awake hours; reordering the input
   never changes the output.
5. **Wording tests:** exact `spoken` string per kind, so voice and screen cannot drift.
6. **Migration test:** a v3 database opens as v4 with every task intact.
7. **Parser tests:** every new voice command, plus the existing "start time" wording tests (added in Phase 0).
8. **On-device check** on the iQOO 15 in airplane mode: scenarios 1, 2, 11, 16, 20 by voice and by form.
9. `./gradlew testDebugUnitTest` must be green before each phase closes.

Existing tests that encode the *old* deadline-scheduler behaviour (`PlannerTest`,
`TaskEngineTest.replanAfternoon_reportsExactShortfall`) are replaced in Phase 3 with the tests above, each
replacement noted in the commit message.

---

## 9. Phases

| Phase | Deliverable | Exit criteria |
|---|---|---|
| **0 – Wording** | ✅ Done: "deadline/due/overdue" → start-time vocabulary across UI and spoken text; parser accepts "start time" and "what's on today" | Tests green (225 + 2 new) |
| **1 – Detection core** (2 d) | `Interval`, `Occurrences`, `ConflictDetector`, states | Rows 1–14, 26, 27 pass; invariants 1–4 + oracle pass |
| **2 – Slots & settings** (2 d) | `SlotFinder`, `ScheduleSettings`, awake-hours + `UNUSUAL_HOUR` | Rows 20–23 pass |
| **3 – Engine & voice** (3 d) | `conflicts()`, diff on every change, overlap question in the add draft, `Resolver`, acknowledgements + migration, new commands, remove `OfficeKitReplanner` | Rows 15–19, 24, 28, 29; wording + migration tests; every offered fix is conflict-free |
| **4 – UI** (3 d) | Live warning in the editor, Home banner/chips, day timeline with proposals | Manual + screenshot tests; numbers match voice |
| **5 – Time-driven** (1–2 d) | `PAST` prompts, time/timezone receivers, end-of-task notification | Fires offline on device; DST tests |
| **6 – Optional** | Read-only calendar import; `TIGHT` and `REMINDER_CLASH` | Behind a setting |

≈ 11–12 working days; phases 1–3 alone make conflicts exact and remove all the wrong-info cases.

---

## 10. Decisions I made (veto any)

1. **Ask before saving an overlapping voice task**, with "keep both" as an option (rather than save then
   warn). Overlap is the core promise of the feature, and undo already exists either way.
2. **"Keep both" is remembered** and dropped when either task changes.
3. **Awake hours default to 07:00–23:00**, configurable; used for slots and `UNUSUAL_HOUR`.
4. **Kukoo never moves a scheduled task on its own**; it only proposes.
5. **Plan my day proposes start times for unscheduled tasks**; nothing is saved until accepted.
6. **Office Kit / laptop replanning is removed** to keep everything on-device.
7. **Adding a task that already started stays refused** for now (row 28).
8. **Out of scope:** a separate real deadline field (a "must finish by"), partial progress, travel time,
   and learning the user's true durations. A future `dueBy` would layer on top without changing the model.

## 11. Risks

- **Default 30-minute duration** on forms can create overlaps the user did not intend → message always shows
  the duration used (row 29).
- **Recurrence expansion** is the trickiest date maths (DST, month ends) → covered by dedicated property tests.
- **Voice friction** from the extra overlap question → mitigated by "keep both" and by asking only when an
  overlap really exists.

---

## 12. What is implemented (as built)

**Detection and planning** (`domain/Overlaps.kt`, `Replanner.kt`, `Plan.kt`): overlap detection over
`[start, start+duration)` with recurrence expansion (14 days), acknowledged overlaps ("keep both", keyed on
exact times so they lapse when either task changes), a free-slot finder inside awake hours (07:00-23:00,
constants in `PlannerConfig`), and "plan my day" as a timeline with suggested start times. The deadline
scheduler, `Planner` and the laptop `OfficeKitReplanner` are removed; nothing uses the network.

**What the call assistant asks** (`TaskEngine`, `ConflictReplies`, parsers):

| Situation | Assistant |
|---|---|
| New task overlaps (asked before saving, once start and length are known) | "Call mom today at 4 PM would overlap Standup, from 4 PM to 4:30 PM. I can fit it today at 4:30 PM instead. Should I use that, pick another time, or keep both?" |
| Start in the middle of the night (00:00-04:59) | "Gym would start tomorrow at 3 AM, in the middle of the night. Did you mean tomorrow at 3 PM?" |
| Edit creates an overlap | applies it, then "Heads up: it overlaps Standup ... Want me to move Dentist to today at 4:30 PM, or keep both?" |
| "any conflicts?" | lists overlaps, offers to move the less important, movable task |
| "when am I free?" / "can I fit an hour?" | next free slot |
| "plan my day" | timeline, conflicts, then offers a fix or a start time for the first unscheduled task, one at a time |
| Daily call / reminder call | opens by raising today's overlap and asking what to do |

Answers understood: yes (many phrasings), keep both / it's fine, no, cancel / never mind, a new time (also a
bare hour or just a day, which keeps the time of day), a new length ("make it 15 minutes"). Yes / no /
keep both are decided by rules before the model runs, so a small model cannot turn "no" into a cancelled task.
`LlamaIntentParser` also learned `check_conflicts` and `find_time` (prompt and grammar) and is told what was
just asked.

**Screens**: the task editor warns live ("Overlaps Standup (4 PM to 4:30 PM)", a one-tap "Move to ...",
"Save anyway" which is remembered); Home marks overlapping tasks and shows a banner; the plan screen shows
overlaps and suggested times.

**Tests**: 60+ new (detector oracle and random-suggestion property tests, DST, whole spoken conversations
through the real parse-then-execute loop, LLM-path grounding, v3 to v4 migration). Full suite green.

**Known limits**: completing a repeating task creates a new row, so "keep both" for that pair is asked
again next time; only the row's own next start can be moved by a spoken fix; no on-device visual check of
the new screens yet.
