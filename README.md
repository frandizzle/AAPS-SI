# AAPS — SmartInsulin APS Plugin

> 💬 **SmartInsulin Discord:** [Join the server here](https://discord.gg/Jezqptxp) — if there's any questions, come in and ask away!

> 📖 **Full AAPS Wiki:** https://wiki.aaps.app
>
> 📋 **3-day survey** (required after looping for 3 days): https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform

---

> **⚠️ Medical Disclaimer**
> SmartInsulin is not a medical device and has not been approved by any regulatory authority. It is intended for experienced closed-loop users only. Use entirely at your own risk. Always have fast-acting glucose available. The authors accept no liability for any outcomes resulting from use of this software.

---

## What is SmartInsulin?

SmartInsulin is a custom APS (Automated Pancreas System) plugin for AndroidAPS. It replaces the standard OpenAPS algorithm with a system that **learns your personal insulin response over time** and adjusts its behaviour accordingly — without requiring you to manually tune every parameter.

Think of it as a loop that watches how your body responds and gradually figures out:

- How sensitive you are to insulin at different times of day
- What your basal rate should look like overnight vs during the day
- Whether it's been too aggressive (causing lows) or too conservative (leaving you high)
- When you've eaten something even if you haven't announced a meal


  <img src="https://github.com/user-attachments/assets/b877932e-043d-4cf7-9906-c8f3ef1113b0" width="250">
  <br>
  <img src="https://github.com/user-attachments/assets/6a13ea32-3d61-4630-8ff4-0e9104d1b26c" width="250">


---

## Requirements

- AndroidAPS 3.4.1.0-dev or later
- Dexcom G6/G7, Libre, or other CGM supported by AAPS
- Compatible pump (tested with Omnipod DASH)
- Android 9+

---

## How it Works

> 💡 **A Note on Carbs:** SmartInsulin does **not** use or utilise carb entries for its dosing calculations. The algorithm is driven entirely by your Insulin Sensitivity Factor (ISF), blood glucose momentum, and learned insulin profiles.

### Every 5 minutes, SmartInsulin:

1. Reads your current BG, how fast it's moving, and how much insulin is already on board
2. Predicts where your BG will be in the next 30–60 minutes using your learned insulin curve
3. Works out how much insulin is needed to close the gap to your target
4. Applies safety checks before delivering anything
5. Delivers an SMB and/or adjusts your basal rate

### The Learning System

SmartInsulin runs several learners in the background during fasting:

| Learner | What it learns |
|---------|---------------|
| **Aggressiveness** | Whether the loop has been too aggressive or too conservative based on your Time in Range over the last 24h |
| **Circadian** | How your insulin sensitivity and basal needs vary by hour of the day *and* day of the week — each hour/day slot converges independently |
| **Basal** | Whether your fasting BG consistently trends up or down, and adjusts the basal rate to compensate |
| **Aggression Nudge** | A fast-acting trim that nudges ISF and basal when a sustained pattern of over- or under-delivery is detected at a specific hour and day |
| **Profile (DIA/Peak)** | The shape of your insulin curve — how quickly it peaks and how long it lasts — learned separately for each meal type |

Learning is **paused** during meal modes, high temp targets, activity, CGM warmup, and for a configurable window after meals end. The Learning card always shows the current state and reason.

#### How the Aggression Nudge Works

Think of it like a car's fuel trim system — the goal is to get your profile dialled in so the loop is hovering near **aggression = 1.0**, meaning it doesn't need to constantly add or remove insulin to stay on target. Just like a car at lambda 1.0 (stoichiometric): if it's consistently running 15% rich (to much insulin), trim the fuel(insulin) out until it settles at 1.0. If it's running 15% lean (not enough insulin), add fuel(insulin) back in.

- **Short-term trim (Aggression Nudge)** — fires every fasting cycle when the circadian ceiling for a specific hour and day deviates from 1.0 beyond a threshold. Nudges ISF and basal proportionally to the deficit or surplus. A 5% deviation produces a tiny nudge; a 20% deviation produces a stronger one.
- **Long-term trim (Circadian physics learners)** — slow EWMA signals (ISF deviation, basal drift, negative IOB) that learn the true underlying correction over weeks and absorb the nudge's adjustments permanently.

The **circadian ceiling** per hour/day is a slow-moving average — it won't shift from a single bad cycle. It reflects a genuine recurring pattern. Once it deviates past the threshold in either direction, nudging begins. As the profile corrects and the ceiling recovers toward 1.0, the nudge automatically backs off.

**Too much insulin** (ceiling consistently below threshold — loop pulling insulin out to stay on target):
- ISF multiplier nudged **up** → effective ISF value rises → less aggressive dosing per unit
- Basal multiplier nudged **down** → less background insulin delivered

**Not enough insulin** (ceiling consistently above 1.05 — loop consistently adding extra insulin to stay on target):
- ISF multiplier nudged **down** → effective ISF value falls → more aggressive dosing per unit
- Basal multiplier nudged **up** → more background insulin delivered

The Learning card shows real before/after values in your units (e.g. `ISF now 2.03 mmol/U from 2.01 mmol/U`, `Basal now 1.1253 U/h from 1.1280 U/h`) so the direction and magnitude of each change is always visible.

The nudge only fires when in a clean fasting state. When anything blocks it, the Learning card shows **⏸ Paused — [specific reason]**.

#### Day-of-Week Circadian Learning

The circadian learner stores a separate ISF multiplier, basal multiplier, and ceiling for each hour **and** each day of the week (Mon–Sun):

- Tuesday 9am can have a different learned profile than Wednesday 9am
- Each day/hour slot builds confidence independently
- The Circadian 24h table in the SmartInsulin tab has a day selector (Mon–Sun) to inspect each day's learned values
- On first install, all 7 days start from the same global values and diverge as real data accumulates

---

## Safety Gates

Before any insulin is delivered, the following checks happen in order:

| Gate | What it does |
|------|-------------|
| **LGS threshold** | Hard suspend — zero basal, no SMBs |
| **Low guard** (`pred_min < lowGuard`) | Predictive suspend — zero basal, no SMBs |
| **Warn guard** | Caution zone — basal tapered down proportionally |
| **Rebound window** | Configurable post-low recovery — SMBs restricted, TBR tapered from 30% back to 100% |
| **High temp target** | SMBs blocked — elevated TBR only |
| **CGM warmup** | SMB fraction reduced proportionally in first 24h after sensor insertion |

### Low Guard vs LGS — What's the Difference?

**LGS threshold** — reacts to your *current* BG right now. If BG drops below this value, the loop immediately suspends — zero basal, no SMBs, full stop. This is a hard reactive floor.

**Low guard** — reacts to your *predicted* BG over the next 30–60 minutes. If the prediction shows BG will drop below this value, the loop suspends *before it happens*. This is a forward-looking protective floor.

**Recommendation: set both to the same value** (e.g. 4.5 mmol / 80 mg/dL).

The **warn guard** sits above both (e.g. 4.9 mmol / 88 mg/dL) and acts as an earlier warning — the loop starts tapering basal down proportionally as BG approaches the low guard.

SmartInsulin includes pattern-recognition safety gates inside its learning system:

* **Rollercoaster Detection:** If BG crosses your target line 2+ times within a rolling 90-minute window, aggressiveness ceiling is capped by 15% for that hour — forcing gentler corrections until you stabilise.
* **Soft Low Approach:** If BG is dropping toward the low guard with active IOB, aggressiveness ceiling is cut by 10% to soften the landing.

### Low Recovery Window

After a low BG event, SmartInsulin enters a recovery window (configurable 20–90 min):

- TBR starts at 30% of normal and ramps back to 100% over the configured duration
- SMBs are blocked for the first 75% of the window
- Progress is shown in the SI tab: e.g. "20min of 45min"

**Soft Landing Bypass:** If the low was borderline and IOB was low at the time, UAM detection is allowed to continue during recovery so if you eat immediately, the system can still respond.

**Manual Override:** Activating any meal mode via **Smart Meal** instantly clears the rebound window.

---

## Meal Modes

Meal modes are activated via the **Smart Meal** button and apply a tighter ISF for faster correction during and after eating.

### Manual Meal Modes
| Mode | Description |
|------|-------------|
| Breakfast | Morning meal |
| Lunch | Midday meal |
| Dinner | Evening meal |
| Low Carb | Reduced-carb meal — gentler ISF |
| Extended | Long-duration meals (e.g. grazing, restaurant) |

### Pre-Bolus 1 & 2

- **Pre-bolus 1** — delivered immediately when you confirm the meal
- **Pre-bolus 2** — scheduled automatically at a configurable delay, with safety gates (BG above target, IOB below 75% max, delta not falling)

### Cancelling Modes & Pre-Boluses

From the **Smart Meal** button:
- **Cancel a Meal Mode** — clears the active mode and returns to Fasting
- **Cancel Pre-Bolus 2** — discards pending PB2 without cancelling the meal mode

### Post-Meal Lockout

After any meal mode ends, a configurable dirty window (default 90 min) raises UAM detection thresholds (~1.5×) to prevent fat/protein tail rises being mistaken for a new meal.

---

## CGM Smoothing (UKF)

### Dexcom G6 Users
Enable **Unscented Kalman Filter** in Config Builder and enable *First Day CGM Smoothing* in SmartInsulin settings. Smoothing applies for 24 hours after insertion then expires automatically.

### Dexcom G7 Users
Enable **Unscented Kalman Filter** in Config Builder. Leave *First Day CGM Smoothing* **OFF** — UKF runs permanently for the full session.

---

## UAM Auto-Detection

SmartInsulin watches CGM rise patterns and automatically activates the appropriate meal mode when a genuine unannounced meal is detected.

### Detection Logic

To trigger UAM, all of the following must be met for N consecutive readings (default 3):

| Condition | Default | What it means |
|-----------|---------|---------------|
| BG above trigger threshold | 5.5 mmol / 99 mg/dL | Prevents triggering near target |
| Delta ≥ riseMinDelta | 0.15 mmol / 2.7 mg/dL | Each reading must be rising |
| ShortAvgDelta ≥ threshold | 0.11 mmol / 2.0 mg/dL | Trend must confirm the rise |
| UnexpectedDelta ≥ threshold | 0.11 mmol / 2.0 mg/dL | Rise must exceed what insulin activity alone explains |

**Clean vs Dirty window:**
- **Clean** — fasting, normal thresholds
- **Dirty** — post-meal lockout active, thresholds raised ~1.5×

**Wobble tolerance (on/off):** When on, a single weak reading mid-streak only needs to reach 50% of the threshold if the 15-min average still confirms the trend. Prevents sensor noise from killing a genuine rise streak. Turn off for stricter, more predictable behaviour.

**Burst trigger:** If total BG rise from the local minimum exceeds the burst threshold (default 1.0 mmol / 18 mg/dL), UAM fires immediately without waiting for the full consecutive count. The burst tracker measures rise from the lowest recent BG — even across interrupted streaks — so a sequence like +3 (fail) +8 +9 = 20 mg/dL total will trigger correctly. The SI tab shows burst progress: `Burst: +0.72/+1.00mmol`.

### UAM Windows

| Window | Notes |
|--------|-------|
| UAM Breakfast | Configurable hours |
| UAM Lunch | Configurable hours |
| UAM Dinner | Configurable hours |
| UAM Snack | Late evening |
| UAM Afternoon | Fills the gap between lunch and dinner |

**Window priority:** Breakfast → Lunch → Afternoon → Dinner → Snack.

### Protein/Fat (P/F) Mode

Activates when BG is elevated and flat (not spiking) after a meal. Triggers after N consecutive readings where BG ≥ P/F threshold (default 6.5 mmol / 117 mg/dL) and ShortAvgDelta is flat (−0.15mmol/-2.7mgdl to +0.25mmol/4.5mgdl range).

P/F has its own ISF, duration, and separate day/night ISF windows. It applies half the normal post-meal lockout (minimum 30 min) to protect learning without over-blocking. Does not trigger the dirty window.

### UAM Entry SMB Fraction

For the first N SMBs after UAM fires (default: 3 SMBs at 80%), delivery is reduced. Softens the front-end of the response while IOB registers before full aggression kicks in.

---

## STFT (Soft Target Fine-Tune)

When fasting BG sits above a configurable threshold for 3+ consecutive readings, STFT progressively lowers the effective dosing target — encouraging more correction without triggering a full meal mode. Resets immediately when BG falls. Blocked during meal modes, UAM, high temp targets, and post-low rebound window.

---

## Activity Integration

SmartInsulin reads heart rate and step count to determine activity level. Higher activity raises the effective BG target and suppresses learning.

| Level | Colour in SI tab | Loop behaviour |
|-------|-----------------|----------------|
| Sedentary | Grey | No adjustment |
| Light | Green | Minor target raise, learning suppressed |
| Moderate | Amber | Moderate target raise, learning suppressed |
| Heavy | Orange-red | Significant target raise, learning suppressed |

---

## P/F Day/Night ISF

- **Day ISF** — applies during configured day hours
- **Night ISF** — applies during configured night hours
- **Fallback ISF** — applies outside both windows (0 = use profile ISF)

**Hour boundary rule:** End hours are inclusive. For clean handover: set day end=16 and night start=17.

---

## SmartInsulin Tab

| Card | What it shows |
|------|--------------|
| **Overview** | Day/time, active mode, aggressiveness (with % impact), ISF and basal calculations with multipliers, last basal learning signal |
| **Time in Range** | Colour bars for fasting and meal TIR. Est. HbA1c (GMI formula, min 24 readings) and average BG with data window |
| **Learning** | Learning state, activity level, aggression nudge status with plain-English explanation and before/after values |
| **Meal Auto-Detection** | UAM status, detection streak, burst progress (`Burst: +0.72/+1.00mmol`), thresholds, P/F status |
| **Soft Target** | STFT active/inactive with reason |
| **Circadian 24h** | 24-row table: ISF×, Bas×, Ceiling, Confidence per hour. **Day selector (Mon–Sun)** to view each day's learned values. Current hour highlighted. Resets to today on tab resume. |
| **Insulin Profiles** | Learned peak and DIA per meal type |
| **Raw Status Log** | Full technical detail for debugging |
| **Reset Learners** | Individual reset buttons for each learner |

### Learning Card — Aggression Nudge Status

| State | Colour | Meaning |
|-------|--------|---------|
| ⚡ Too much insulin — adjusting | Amber | Ceiling below threshold. Shows deviation %, hour, day, and actual ISF/basal before and after |
| ⚡ Not enough insulin — adjusting | Green | Ceiling above surplus threshold. ISF nudged down, basal nudged up |
| ⏸ Paused — [reason] | Blue | Blocked by meal mode, post-meal lockout, activity, temp target, or CGM warmup |
| Insulin levels look right for this hour | Grey | Ceiling between 0.95–1.05 — no nudge needed |

<p align="center">
  <img src="https://github.com/user-attachments/assets/2f3e04b9-dc41-4371-b083-cfd760e35b2d" width="200">
  <img src="https://github.com/user-attachments/assets/e018530b-134d-4bbe-8313-eb4320076ed4" width="200">
  <img src="https://github.com/user-attachments/assets/c89e37ab-f520-47e2-8263-73b6f0d8f3fb" width="200">
  <img src="https://github.com/user-attachments/assets/a5bc2936-4785-4a37-8a9e-54ce6a3bd2a4" width="200">
  <img src="https://github.com/user-attachments/assets/04ac513b-c25c-4acd-ba09-88afd63ea980" width="200">
</p>

---

## Loop Output Format

```
SI mode=Fasting | BG=6.3 | d=0.17 | IOB=0.81/14 | pred_min=6.0 | lo=5.0 warn=5.0
| target=5.5 | ISF=2.0 | basal=0.905(x1.00) | Peak=55m DIA=540m | aggr=0.96
| tir=Fasting:100%in/0%hi/0%lo Meal:94%in/5%hi/0%lo | NORMAL | targetBG=5.5
| microBolus=true | trigger=predMinGap(6.0->5.5) | smb=0.150 | tbr=1.130
| circ(ISF×1.02 bas×1.00 ceil=0.96) | hr=82 steps=37/5m | 59min left
```

Values are displayed in your configured units (mmol/L or mg/dL).

---

## Settings Reference

### Max Basal Rate vs SmartInsulin Max TBR

**Max Basal Rate** (General & Safety) — hard outer ceiling enforced by AAPS. No TBR can exceed this.

**SmartInsulin Max TBR** (General & Safety) — SmartInsulin's own inner cap. Should be equal to or lower than Max Basal Rate.

**Recommendation: set both to the same value.**

### Settings Screens

| Category | Key Settings |
|----------|-------------|
| **General & Safety** | SMB toggles, max IOB, max basal rate, SmartInsulin max TBR, max SMB, aggression cap, low/warn guard, LGS threshold, low recovery window (20–90 min) |
| **Learning** | Enable learning, learning rate, basal learning, post-meal lockout duration |
| **Dawn Phenomenon** | Window start/end hours, SMB reduction fraction |
| **Activity** | Enable activity targets, resting HR, target offset per activity level |
| **Meal Modes** | ISF per manual meal mode, mode window, pre-bolus 1 & 2 defaults |
| **STFT** | CGM warmup block |
| **First Day CGM** | First-day UKF smoothing, CGM warmup SMB guard, UAM disable during warmup |
| **UAM Auto-Detection** | Enable, wobble tolerance on/off, rise delta, burst threshold (0 = off), entry SMB fraction/count, day/night window hours |
| **UAM Windows** | Per-window enable, hours, duration, ISF for Breakfast/Lunch/Dinner/Snack/Afternoon |
| **UAM Protein/Fat** | Enable, stuck readings, duration, fallback ISF, day ISF + hours, night ISF + hours |

---

## Omnipod Basal Drift Fix

This build includes a fix for a known Omnipod basal drift issue at the 0.05 U/h step boundary.

**To enable:**
1. Navigate to your AAPS `extra` folder
2. Create an empty file named exactly: `omnipod_basal_drift` (no extension)
3. Restart AAPS

Opt-in via file flag — no effect on other pumps if the file is absent.

---

## Reporting a Bug

Please include:
- Loop output reason string from the AAPS Loop screen
- SmartInsulin tab screenshot
- AAPS version (build hash shown in top-right of the Loop screen)

---

## Acknowledgements

Built on top of [AndroidAPS](https://github.com/nightscout/AndroidAPS) and the OpenAPS algorithm. Inspired by the broader open-source diabetes community — iAPS, Loop, OpenAPS, and everyone who has contributed to making closed-loop insulin delivery accessible.

---

**Feedback & Discussion**

I built this primarily for my own use, but I'm always open to feedback or algorithmic discussions from other tinkerers.
* For **bug reports**, please open a GitHub Issue with your loop output string and a screenshot of the SmartInsulin tab.
* For **general discussion**, join the Discord linked at the top of this page.

---

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" width="60">

`3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2`
