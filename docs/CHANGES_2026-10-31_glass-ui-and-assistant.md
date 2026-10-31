# Changes of 2026-10-31: main screen, charts, AIMI Assistant, sport guard

Branch: `feature/smb-quality-threshold`. Base: `upstream/dev_OAPSAIMI`.
Plain notes for the next developer. Nothing here was run on a device by the author of the notes.
Build and runtime checks are done by the owner of the phone (`vse.bat`, signed release build).

## 1. Main screen (glass dashboard)

Files: `plugins/main/.../dashboard/glass/*`, `.../viewmodel/OverviewViewModel.kt`, `plugins/main/src/main/res/values/strings.xml`.

- **Running mode icon.** `StatusCardState.loopMode` and `GlassUiState.loopMode` (type `RM.Mode?`). The icon is shown above the glucose value, 18 dp, 50% alpha, colour from `RM.Mode.loopColor`. Tap opens the loop dashboard, long press opens the mode list. The old "Loop" text block is removed (`GlassPillId.LOOP_STATUS` stays in the enum for stored settings, but is no longer drawn).
- **Side blocks.** Enabled blocks (reservoir age, cannula, battery, sensor, activity, tools) are put in one list and split half and half between the left and right column. Blocks show an icon and a value only, no label. Width 84 dp, min height 38 dp.
- **Glucose digits.** 76 sp. A value with four characters (for example `10.2`) uses 60 sp so it is not cut.
- **New optional blocks** (bottom row, off by default, set in Tools > Personalize): `RESERVOIR_LEVEL` (insulin left in the pump, opens pump screen) and `AIMI_INSIGHT` (label "AIMI Assistant", opens `AimiAssistantActivity` with an explicit class name, so `plugins:main` has no dependency on `plugins:aps`).
- **Label.** "Basal T" is now "TBR" (English string only).
- `GlassPersonalizationTest` lists the two new bottom-row ids.

## 2. Charts

Files: `GlassChartComponents.kt`, `GlassCombinedChartCard.kt`, `GlassOverviewComposeEmbedded.kt`, `GlassDeviationStrip.kt`, `GlassChartModels.kt`.

- **Window in minutes.** State `rangeMinutes` (15 min to 24 h). `rangeHours` and `timeRangeHours` are `Float` now. The drop-down chip ("6h") offers 3, 6, 12, 18, 24 h. Two-finger pinch steps through 15, 30, 60, 120, 180, 360, 720, 1080, 1440 min. While two fingers are down the marker does not move.
- **Moving back in time.** A slim bar with a rounded handle sits under the glucose chart (`TimeBar`). The handle is as wide as the window. History limit is 24 h: `window + shift <= 24 h`. State `panMs`. While shifted, predictions are hidden and `predictionHorizonHours = 0`, so history uses the whole width. A "now" chip in the header resets the shift. The finger on the chart only moves the marker line (`scrubProgress`). The marker is not cleared when new data arrives.
- **Gesture fix.** `pointerInput(Unit)` with `rememberUpdatedState`; keys that change with data restarted the gesture.
- **Layers menu.** The IOB and Bal buttons are replaced by a small arrow with a drop-down: IOB, COB, Insulin balance. COB reuses `IobChartCard` (`title`, `accent`, `valueFormat` parameters) with `graphViewModel.cobGraphFlow`.
- **Plot.** Fills the card from edge to edge, no edge fade. BG chart height 215 dp. Headroom above the highest value is 30% of before. Axis numbers (high and low line) are bold, white in dark theme. The finger readout is at the top right inside the green zone.
- **Doses.** Stick from the curve up; the dot is at the top of the stick (r = 9), the number above the dot. Stick length depends on the dose and grows until the number is free. Stick alpha 0.55, dot and text 0.7.
- **Header.** Stats, layers arrow, "now" chip (only when shifted) and the window chip. All grey, no bright blue.

## 3. Pump button long press

`ui/.../main/GlassNavigationBar.kt`. The detector runs on `PointerEventPass.Initial` (`awaitFirstDown` + `withTimeoutOrNull(longPressTimeoutMillis)` + `waitForUpOrCancellation`) because the tab's own click handling hid the press. A flag stops the release from also opening the pump screen. Not confirmed on a device.

## 4. AIMI Assistant (plugins/aps, `advisor/assistant`)

- `AssistantTabs.kt`: two tabs ("Настройки", "Insight") at the top of both screens. They look like one section.
- `AssistantLive.kt`: `LiveCycle.sportSafety` is read from the narrative text "Safety sport".
- `AssistantInsight.kt`: new `StuckRise` and `findStuck`. A stretch of 8 or more minutes where glucose rose 8 mg/dL or more in 5 minutes, the engine wanted 0.3 U or more, and a step in `smb_binding_trace` set the SMB to 0. Gaps up to 3 minutes are bridged. `buildStuckBundle` proposes post-hypo release margin and delta when the guard was `POST_HYPO_CAP` (back to 20 and 5, or 12 and 3 when they are already at the standard).
- `AimiInsightActivity.kt`: section "Подъёмы без SMB" with one card per stretch.
- A dashboard with tiles and hourly charts was tried and removed on request.

## 5. SMB: steps-only sport guard

`DetermineBasalAIMI2.kt` (`applySafetyPrecautions`, new `isSportSafetyOnlyFromSteps`), string `reason_safety_sport_rise_softened`.

Finding from the decision log of 2026-10-31 (18:14 to 18:54): the guard "Safety sport -> SMB=0" was on for 40 cycles while glucose rose from 5.5 to 9.2 mmol/L. The activity mode was "Resting"; the guard came from the step sums only.
Change: when the guard is on only because of steps (no manual sport, no AIMI activity context), SMB is above 0, glucose is at least target + 25 mg/dL and the short average delta is at least 3 mg/dL per 5 min, then half of the SMB is given instead of zero. All other sport cases are unchanged (zero).

Other findings, no code change: in 8 days the biggest cut was `POST_HYPO_CAP` (rebound guard). Release needs both glucose >= target + margin and a rise above the delta (standard 20 and 5 mg/dL). The owner's values are 20 and 5.

## 6. Open items

- Panning by finger on the chart itself is replaced by the time bar; check how it feels.
- More layers (basal, activity) are not added yet.
- Check on a device: mode icon position, long press on Pump, block order with fewer blocks, `10.2` digits, COB layer, time bar at 24 h.
