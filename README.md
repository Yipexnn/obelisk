# Obelisk Reroller

Android app that rerolls relics automatically until your chosen **Divine** relic counts are reached.

Loop: `Reroll` (if panel closed) → `💎200` → `💎200` (confirm) → `Open All` → read Divine counts from the relic list (OCR) → repeat.
Stops when targets hit, max rerolls reached, gems drop below your limit, or gems stop decreasing.

## Install
1. Download `ObeliskReroller.apk` from the **latest-apk** release (built by GitHub Actions).
2. Install it (allow installing from your browser/files app).
3. Settings → Apps → Obelisk Reroller → ⋮ → **Allow restricted settings**.
4. Settings → Accessibility → Installed apps → **Obelisk Reroller** → On.

## Use
1. Open the game, open the Reroll panel.
2. Floating panel → **Calib** → tap: Reroll, 💎200, Open All, your gem counter.
   Keep the Divine slots visible on screen (as after a reroll).
3. In the app set targets, limits → **Save**.
4. Floating panel → **▶ Start**. **Read** shows what the OCR currently sees (for testing).

Drag the panel by its header so it doesn't cover game buttons or the Divine slots; tap the header to collapse it, ✕ to close it (bring it back with "Show floating panel" in the app).
