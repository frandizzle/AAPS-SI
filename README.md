# AAPS — SmartInsulin APS Plugin

> 💬 **SmartInsulin Discord:** [Join the server here](https://discord.gg/Jezqptxp) — if there's any questions, come in and ask away!
[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)

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

### Every 5 minutes, SmartInsulin:

1. Reads your current BG, how fast it's moving, and how much insulin is already on board
2. Predicts where your BG will be in the next 30–60 minutes using your learned insulin curve
3. Works out how much insulin is needed to close the gap to your target
4. Applies safety checks before delivering anything
5. Delivers an SMB and/or adjusts your basal rate

### The Learning System

SmartInsulin runs three learners in the background during fasting:

| Learner | What it learns |
|---------|---------------|
| **Aggressiveness** | Whether the loop has been too aggressive or too conservative based on your Time in Range over the last 24h |
| **Circadian** | How your insulin sensitivity and basal needs vary by hour of the day — each hour has its own multiplier that converges independently |
| **Basal** | Whether your fasting BG consistently trends up or down, and adjusts the basal rate to compensate |
| **Profile (DIA/Peak)** | The shape of your insulin curve — how quickly it peaks and how long it lasts — learned separately for each meal type |

Learning is **paused** during meal modes, high temp targets, activity, CGM warmup, and for a configurable window after meals end.

---

## Safety Gates

Before any insulin is delivered, the following checks happen in order:

| Gate | What it does |
|------|-------------|
| **LGS threshold** | Hard suspend — zero basal, no SMBs |
| **Low guard** (`pred_min < lowGuard`) | Predictive suspend — zero basal, no SMBs |
| **Warn guard** | Caution zone — basal tapered down proportionally |
| **Rebound window** | 60-minute post-low recovery — SMBs restricted, TBR tapered from 30% back to 100% |
| **High temp target** | SMBs blocked — elevated TBR only |
| **CGM warmup** | SMB fraction reduced proportionally in first 24h after sensor insertion |

### Low Guard vs LGS — What's the Difference?

These two settings both protect against lows but they work at different points in the decision:

**LGS threshold** — reacts to your *current* BG right now. If BG drops below this value, the loop immediately suspends — zero basal, no SMBs, full stop. This is a hard reactive floor.

**Low guard** — reacts to your *predicted* BG over the next 30–60 minutes. If the prediction shows BG will drop below this value, the loop suspends *before it happens*. This is a forward-looking protective floor.

**Recommendation: set both to the same value** (e.g. 4.5 mmol / 80 mg/dL). If LGS is lower than low guard, there's a gap where BG could actually reach a dangerous level before the hard suspend kicks in. If LGS is higher than low guard, you'll get hard suspends before the predictive system even has a chance to act.

Setting them equal means: "suspend immediately if BG is at X *or* if BG is predicted to reach X."

The **warn guard** sits above both (e.g. 4.9 mmol / 88 mg/dL) and acts as an earlier warning — the loop starts tapering basal down proportionally as BG approaches the low guard, reducing the chance of hitting it in the first place.

### Low Recovery Window

After a low BG event, SmartInsulin enters a 60-minute recovery window:

- TBR starts at 30% of normal and ramps back to 100% over 60 minutes
- SMBs are blocked for the first 45 minutes
- After 45 minutes, SMBs are restored but the TBR taper continues

**Soft Landing Bypass:** If the low was borderline (not a crash) and IOB was low at the time, UAM meal detection is allowed to continue during recovery — so if you eat immediately after a near-low, the system can still respond. If BG drops low a second time after a bypass was active, full lockout applies for the rest of that session.

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

Each mode has its own configurable ISF, window duration, and pre-bolus settings.

### Pre-Bolus 1 & 2

When activating a meal mode via Smart Meal, you can schedule:

- **Pre-bolus 1** — delivered immediately when you confirm the meal
- **Pre-bolus 2** — scheduled automatically at a configurable delay after the first bolus, with safety gates that prevent delivery if BG is below target, IOB is too high, or BG is falling

**Pre-bolus 2 safety gates:**
- BG must be above your profile target
- IOB must be below 75% of your max IOB
- Instant delta must not be falling faster than −0.11 mmol / −2.0 mg/dL / 5min
- 15-minute average delta must not be falling faster than −0.17 mmol / −3.0 mg/dL / 5min

### Post-Meal Lockout

After any meal mode (manual or UAM) ends, a configurable dirty window (default 90 min) raises UAM detection thresholds to prevent fat/protein tail rises being mistaken for a new meal.

---

## CGM Smoothing (UKF)

Fresh CGM sensors often produce jumpy, noisy data during their first 24 hours as the filament settles. To counteract "phantom spikes" that could trigger false meal detections or unwarranted STFT adjustments, SmartInsulin integrates specifically with AAPS's **Unscented Kalman Filter (UKF)** smoothing plugin.

How you configure this depends entirely on which CGM you use:

### Dexcom G6 Users
The G6 transmitter applies its own heavy smoothing natively, but often struggles on day one.
* **How to enable:** Go to the AAPS Config Builder and select **Unscented Kalman Filter** under the Smoothing section. Then, go to SmartInsulin Settings -> First Day CGM and **enable** *First Day CGM Smoothing*.
* **What it does:** SmartInsulin will apply UKF smoothing for exactly 24 hours after a sensor insertion. Once 24 hours have passed, it automatically expires and passes the raw G6 data through (behaving like the "No Smoothing" plugin). This gives you a clean first day without double-smoothing the rest of the session.

### Dexcom G7 Users
The G7 provides much noisier, "rawer" data continuously throughout the entire session.
* **How to enable:** Go to the AAPS Config Builder and select **Unscented Kalman Filter** under the Smoothing section. In SmartInsulin Settings, **leave the *First Day CGM Smoothing* toggle OFF**.
* **What it does:** UKF smoothing will run permanently. This is highly recommended for G7 users to prevent the loop from aggressively chasing micro-fluctuations and sensor wobble.

---

## UAM Auto-Detection

SmartInsulin watches CGM rise patterns in the background and automatically activates the appropriate meal mode when a genuine unannounced meal is detected — without you having to do anything.

### Detection Logic

To trigger UAM, all of the following must be met for N consecutive readings (default 3):

| Condition | Default | What it means |
|-----------|---------|---------------|
| BG above trigger threshold | 5.5 mmol / 99 mg/dL | Prevents triggering near target |
| Delta ≥ riseMinDelta | 0.15 mmol / 2.7 mg/dL | Each reading must be rising |
| ShortAvgDelta ≥ threshold | 0.11 mmol / 2.0 mg/dL | Trend must confirm the rise |
| UnexpectedDelta ≥ threshold | 0.11 mmol / 2.0 mg/dL | Rise must exceed what insulin activity alone explains |

**Clean vs Dirty window:**
- **Clean** — fasting mode, normal thresholds apply
- **Dirty** — post-meal lockout is active, thresholds are raised ~1.5× to avoid detecting fat/protein tail rises as a new meal

**Wobble tolerance:** If the 15-minute average confirms the trend, a single noisy CGM reading only needs to reach 50% of the delta threshold. This prevents a brief sensor compression artifact from resetting a genuine rise streak.

**Burst trigger:** If total BG rise from streak start exceeds a configurable threshold (default 1.0 mmol / 18 mg/dL), UAM fires immediately without waiting for the full consecutive count. Catches sudden meal spikes.

### UAM Windows

| Window | Notes |
|--------|-------|
| UAM Breakfast | Configurable hours |
| UAM Lunch | Configurable hours |
| UAM Dinner | Configurable hours |
| UAM Snack | Late evening |
| UAM Afternoon | Fills the gap between lunch and dinner |

**Window priority:** Breakfast → Lunch → Afternoon → Dinner → Snack. If windows overlap, the earlier window in this list wins. In practice the default hours don't overlap, but if you customise windows be aware that Afternoon takes priority over Dinner at any shared hour.

### Protein/Fat (P/F) Mode

A passive watchdog for slow fat/protein-driven rises. Activates when BG is elevated and flat — not spiking like a carb meal, just stuck high. Triggers after N consecutive readings where:

- BG ≥ P/F threshold (default 6.5 mmol / 117 mg/dL)
- ShortAvgDelta is flat (−0.15 to +0.25 mmol / −2.7 to +4.5 mg/dL range)

P/F has its own ISF, duration, and **separate day/night ISF windows** — fat/protein hits differently at midnight vs mid-afternoon. P/F does not trigger the post-meal dirty window.

### UAM Entry SMB Fraction

For the first N SMBs after a UAM mode fires (default: 3 SMBs at 80%), the delivery fraction is reduced. This softens the front-end of the response, giving the initial IOB time to register before full aggression kicks in.

---

## STFT (Soft Target Fine-Tune)

When fasting BG sits above a configurable threshold (default 6.0 mmol / 108 mg/dL) for 3+ consecutive readings, STFT progressively lowers the effective dosing target — encouraging more correction without triggering a full meal mode.

- Activates after N consecutive readings above threshold
- Lowers target by a small configurable step per reading
- Resets immediately when BG falls or delta goes negative
- Blocked during meal modes, UAM modes, high temp targets, and the post-low rebound window

---

## Activity Integration

SmartInsulin reads heart rate and step count to determine your activity level. Higher activity raises the effective BG target (reducing hypo risk during exercise) and suppresses learning.

| Level | Colour in SI tab | Loop behaviour |
|-------|-----------------|----------------|
| Sedentary | Grey | No adjustment |
| Light | Green | Minor target raise |
| Moderate | Amber | Loop adjusting target and learning |
| Heavy | Orange-red | Significant target raise, learning suppressed |

---

## P/F Day/Night ISF

Protein and fat digestion behaves differently at different times of day. The P/F mode supports separate ISF values for a configurable day window and night window:

- **Day ISF** — applies during your configured day hours (e.g. 10:00–16:00)
- **Night ISF** — applies during your configured night hours (e.g. 22:00–06:00)
- **Fallback ISF** — applies if the current hour falls outside both windows (0 = use profile ISF)

**Hour boundary rule:** End hours are inclusive — setting end=16 covers 16:00–16:59. For a clean handover with no gap, set day end=16 and night start=17.

---

## SmartInsulin Tab

The SmartInsulin tab in AAPS provides a full status view organised into cards:

| Card | What it shows |
|------|--------------|
| **Overview** | Day/time, active mode, aggressiveness (with % impact), ISF calculation, basal calculation, low recovery status, pre-bolus status |
| **Time in Range** | Colour bars for fasting and meal TIR — green = in range, amber = high, red = low |
| **Learning** | Learning state and reason, post-meal pause countdown, activity level |
| **Meal Auto-Detection** | UAM status, detection streak, thresholds, P/F status — clean/dirty window explained |
| **Soft Target** | STFT active/inactive with reason (meal mode, UAM, P/F, or responding normally) |
| **Circadian 24h** | 24-row table: ISF×, Bas×, Ceiling, Confidence per hour — current hour highlighted, confidence colour-coded |
| **Insulin Profiles** | Learned peak and DIA per meal type — green = learned (5+ samples), amber = still learning, grey = using profile values |
| **Raw Status Log** | Full technical detail for debugging |
| **Reset Learners** | Individual reset buttons for aggressiveness, basal, circadian, and profiles |

**Aggressiveness note:** During meal modes, aggressiveness is locked at 1.0 — the fasting value shown is for reference only and is not being applied.
<p align="center">
  <img src="https://github.com/user-attachments/assets/2f3e04b9-dc41-4371-b083-cfd760e35b2d" width="200">
  <img src="https://github.com/user-attachments/assets/e018530b-134d-4bbe-8313-eb4320076ed4" width="200">
  <img src="https://github.com/user-attachments/assets/c89e37ab-f520-47e2-8263-73b6f0d8f3fb" width="200">
  <img src="https://github.com/user-attachments/assets/a5bc2936-4785-4a37-8a9e-54ce6a3bd2a4" width="200">
</p>



---

## Loop Output Format

The loop reason string uses pipe-separated format:

SI mode=Fasting | BG=6.3 | d=0.17 | IOB=0.81/14 | pred_min=6.0 | lo=5.0 warn=5.0
| target=5.5 | ISF=2.0 | basal=0.905(x1.00) | Peak=55m DIA=540m | aggr=0.96
| tir=Fasting:100%in/0%hi/0%lo Meal:94%in/5%hi/0%lo | NORMAL | targetBG=5.5
| microBolus=true | trigger=predMinGap(6.0->5.5) | smb=0.150 | tbr=1.130
| circ(ISF×1.02 bas×1.00 ceil=0.96) | hr=82 steps=37/5m | 59min left


Values are displayed in your configured units (mmol/L or mg/dL).

---

## Settings Reference

### Max Basal Rate vs SmartInsulin Max TBR — What's the Difference?

There are two separate TBR limits and it's worth understanding both:

**Max Basal Rate** (General & Safety) — the hard outer ceiling enforced by AAPS constraints. No TBR can ever exceed this regardless of what SmartInsulin requests. Set this to a safe absolute maximum for your body (e.g. 3× your highest profile basal rate is a common starting point).

**SmartInsulin Max TBR** (General & Safety) — SmartInsulin's own inner cap, applied before the AAPS constraint. This is what SmartInsulin will actually aim for during aggressive correction. Should be equal to or lower than Max Basal Rate.

**Recommendation: set both to the same value.** Having them different just creates a confusing gap where AAPS might allow a rate that SmartInsulin would never request anyway. If you're unsure, start conservative and raise it as you gain confidence.

### Settings Screens

| Category | Key Settings |
|----------|-------------|
| **General & Safety** | SMB toggles, max IOB, max basal rate, SmartInsulin max TBR, max SMB, aggression cap, low/warn guard, LGS threshold |
| **Learning** | Enable learning, learning rate, basal learning, post-meal lockout duration |
| **Dawn Phenomenon** | Window start/end hours, SMB reduction fraction |
| **Activity** | Enable activity targets, resting HR, target offset per activity level |
| **Meal Modes** | ISF per manual meal mode, mode window, pre-bolus 1 & 2 defaults |
| **STFT** | CGM warmup block |
| **First Day CGM** | First-day UKF smoothing, CGM warmup SMB guard (skip every 3rd SMB), UAM disable during warmup |
| **UAM Auto-Detection** | Enable, rise delta, burst threshold, entry SMB fraction/count, day/night window hours |
| **UAM Windows** | Per-window enable, hours, duration, ISF for Breakfast/Lunch/Dinner/Snack/Afternoon |
| **UAM Protein/Fat** | Enable, stuck readings, duration, fallback ISF, day ISF + hours, night ISF + hours |

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
* For **general discussion**, you can usually find me in the Nightscout/AndroidAPS Discord server (ping `@yourusername`). Please avoid sending direct messages for general tech support!

---

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" width="60">

`3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2`
