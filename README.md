# AAPS
* Check the wiki: https://wiki.aaps.app
*  Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

# SmartInsulin APS — AAPS Plugin

SmartInsulin is a custom Automated Pancreas System (APS) plugin for [AndroidAPS](https://github.com/nightscout/AndroidAPS) that builds on top of the standard OpenAPS SMB algorithm with adaptive learning, meal-aware dosing, and UAM (Unannounced Meal) auto-detection.

> **⚠️ Medical Disclaimer**
> This software is intended for use by experienced closed-loop users only. It is not a medical device and has not been approved by any regulatory authority. Use entirely at your own risk. Always have fast-acting glucose available. Never make changes to your closed-loop configuration without understanding the implications. The authors accept no liability for any outcomes resulting from use of this software.

---

## Overview

SmartInsulin replaces the standard AAPS APS algorithm with a system that:

- **Learns your insulin response** over time using circadian-aware ISF, basal, and aggressiveness multipliers
- **Detects unannounced meals** from CGM rise patterns and automatically applies tighter ISF for faster correction
- **Applies soft target correction** (STFT) when BG drifts above target without a meal — gradually reducing the effective target to encourage correction without a full meal-mode response
- **Protects against lows** with a configurable low guard, rebound window, and soft landing bypass
- **Adapts to your activity level** via heart rate and step count integration

---

## Core Algorithm

### Prediction & Dosing

At each loop cycle (every 5 minutes), SmartInsulin:

1. Reads current BG, delta, IOB, and CGM state
2. Computes a **predicted minimum BG** over the next 30–60 minutes using the learned insulin curve (peak and DIA)
3. Calculates `insulinReq` from the gap between `pred_min` and target
4. Scales the SMB delivery by aggressiveness, circadian multipliers, dawn fraction, and CGM state
5. Delivers the SMB and sets a TBR for residual correction

### Safety Gates

Before any insulin is delivered, the following checks are applied in order:

| Gate | Behaviour |
|------|-----------|
| **LGS threshold** | Hard suspend — zero basal, no SMBs |
| **Low guard** (`pred_min < lowGuard`) | Predictive suspend — zero basal, no SMBs |
| **Warn guard** | Caution zone — basal tapered proportionally to predicted proximity to low guard |
| **Rebound window** | Post-low recovery period (60 min) — SMBs restricted, TBR tapered |
| **High temp target** | SMBs blocked — elevated TBR only |
| **CGM warmup** | SMB fraction reduced proportionally in first 12h after sensor insertion |

---

## Adaptive Learning

SmartInsulin learns from every loop cycle in fasting mode. Learning is paused during meal modes and for a configurable post-meal lockout window.

### Aggressiveness Learner
Tracks **Time In Range** over a rolling window. If TIR is high, aggressiveness nudges up slightly. If TIR is low (too many highs or lows), it backs off. This gives a global sense of whether the loop is working.

### Circadian Learner
Maintains a 24-hour table of **ISF multipliers**, **basal multipliers**, and **aggressiveness ceiling** values — one bucket per hour. Each hour's multiplier converges independently based on observed outcomes at that time of day. Low-confidence hours (few samples) blend toward 1.0.

### Basal Learner
Tracks whether fasting BG trends are consistently rising or falling and adjusts the effective basal multiplier accordingly.

### Profile Learner (DIA/Peak)
Learns insulin curve shape (peak and DIA) from meal and UAM events. Separate learned profiles are maintained for each meal mode. Requires a minimum number of samples before learned values are used.

---

## Meal Modes

Manual meal modes can be activated via the **Smart Meal** button. Each mode applies a tighter ISF to increase correction aggression during a meal.

| Mode | Description |
|------|-------------|
| Breakfast | Morning meal |
| Lunch | Midday meal |
| Dinner | Evening meal |
| Low Carb | Reduced-carb meal — gentler ISF |
| Extended | Long-duration meals (e.g. grazing, restaurant) |

Each mode has a configurable ISF, window duration, and pre-bolus settings. A second pre-bolus can be scheduled automatically at a configurable delay after the first.

### Post-Meal Lockout
After a meal mode ends, a configurable dirty window (default 90 min) applies stricter UAM detection thresholds to prevent false UAM triggers from meal tail rises.

---

## UAM Auto-Detection

SmartInsulin continuously watches CGM rise patterns in the background and automatically activates the appropriate meal mode when a genuine unannounced meal rise is detected.

### Detection Logic

A UAM trigger requires **all** of the following per reading:

| Condition | Default | Description |
|-----------|---------|-------------|
| BG above trigger threshold | 5.5 mmol | Prevents triggering near target |
| Delta ≥ riseMinDelta | 0.15 mmol | Minimum per-reading rise |
| ShortAvgDelta ≥ threshold | 0.15 mmol | Trend confirmation |
| UnexpectedDelta ≥ 0.15 mmol | — | Rise must exceed insulin activity (BGI gap check) |
| Consecutive readings | 3 | Filters CGM noise |

**Wobble tolerance:** If `shortAvgDelta` confirms the trend (≥ riseMinDelta), a single noisy instantaneous delta reading only needs to meet 50% of the threshold. This prevents a brief CGM compression artifact from resetting a genuine streak.

**Burst trigger:** If the total BG rise from streak start exceeds a configurable threshold (default 1.0 mmol), UAM fires immediately without waiting for the full consecutive reading count. Catches sudden meal spikes.

**CGM timestamp dedup:** Streak counters only increment once per unique CGM reading timestamp. Manual loop refreshes cannot game the streak counter.

### UAM Windows

Each window has its own configurable start/end hours, mode duration, and ISF:

| Window | Default Hours | Notes |
|--------|--------------|-------|
| UAM Breakfast | Configurable | Morning |
| UAM Lunch | Configurable | Midday |
| UAM Afternoon | 14:00–17:00 | Fills the gap between lunch and dinner |
| UAM Dinner | Configurable | Evening |
| UAM Snack | 21:00–23:00 | Late evening |
| Protein/Fat | Any fasting hour | Special watchdog mode (see below) |

UAM detection is active between a configurable **day start** and **night cutoff** hour. Outside these hours the streak counter is suppressed.

### Protein/Fat (P/F) Mode

A passive watchdog that activates when BG is elevated and flat — indicating a slow fat/protein-driven rise rather than a carb spike. Triggers when:

- BG ≥ configurable P/F threshold (default 6.5 mmol) — independent of the UAM rise threshold
- ShortAvgDelta is flat (within -0.15 to +0.25 mmol range)
- Streak of N consecutive readings (configurable, default 4)

P/F has its own ISF and duration. It does **not** trigger the post-meal dirty window — UAM meal modes can fire normally after P/F expires.

P/F streak resets if BG returns to profile target.

### UAM Entry SMB Fraction

For the first N SMBs after a UAM meal mode fires (default: 3 SMBs at 80%), the SMB delivery fraction is reduced. This softens the front-end of the UAM response, giving the initial IOB time to register in predictions before full correction aggression kicks in. Configurable via `UAM entry SMB fraction` and `UAM entry SMB count`.

---

## STFT (Soft Target Following)

When BG is consistently above a configurable threshold (default 6.0 mmol) in fasting mode, STFT progressively lowers the effective dosing target — encouraging more correction without triggering a full UAM mode.

- Activates after N consecutive readings above threshold (default 3)
- Lowers target by a configurable step per reading (e.g. -0.1 mmol)
- Floors at a configurable minimum target
- Resets immediately when BG falls below threshold or delta goes negative
- Blocked during meal modes, high temp targets, and post-low rebound

---

## Soft Landing Bypass

After a borderline low (near the low guard threshold), the standard 60-minute rebound window would normally block UAM detection — which is a problem if you eat immediately after a near-low. The soft landing bypass lifts this restriction when all of the following are true:

| Condition | Value |
|-----------|-------|
| Minimum BG during low | ≥ lowGuard − 0.3 mmol (e.g. ≥ 4.7 if guard is 5.0) |
| ShortAvgDelta at crossing | > −0.15 mmol (not crashing) |
| IOB at time of low | < 1.0U |
| Second low occurred | No |
| Current time | Within UAM meal hours |

If BG goes low a **second time** after a bypass was active, the bypass is permanently revoked for that session and the full rebound window applies.

---

## Dawn Phenomenon

A configurable dawn window reduces SMB delivery fraction during early morning hours when insulin resistance is typically elevated, preventing over-delivery before breakfast.

---

## Activity Integration

SmartInsulin monitors heart rate and step count to detect activity levels (Sedentary / Light / Moderate / Heavy). During activity, the effective BG target is raised by a configurable offset per activity level, reducing the risk of exercise-induced hypoglycaemia.

---

## Settings Reference

Settings are organised into collapsible categories in the AAPS preferences screen:

| Category | Key Settings |
|----------|-------------|
| **General & Safety** | SMB toggles, max SMB/TBR, aggression cap, low/warn guard, CGM warmup |
| **Learning** | Enable learning, learning rate, basal learning, post-meal lockout duration |
| **Dawn Phenomenon** | Window start/end hours, SMB reduction fraction |
| **Activity** | Enable activity targets, offset per activity level |
| **Meal Modes** | ISF per manual meal mode, carb targets, mode window, pre-bolus settings |
| **STFT** | CGM warmup block |
| **UAM Auto-Detection** | Enable, thresholds, delta, burst threshold, day/night window, entry SMB fraction/count |
| **UAM Windows** | Per-window enable, hours, duration, ISF for each of Breakfast/Lunch/Afternoon/Dinner/Snack/Protein-Fat |

---

## Loop Output Format

The loop reason string uses a pipe-separated format for readability:

```
SI mode=Fasting | BG=6.3 | d=0.17 | IOB=0.81/14 | pred_min=6.0 | target=5.5 | ISF=2.0 | basal=0.905(x1.00) | Peak=55m DIA=540m | aggr=0.96 | tir=Fasting:100%in/0%hi/0%lo | NORMAL | targetBG=5.5 | microBolus=true | trigger=predMinGap(6.0->5.5) | smb=0.150 | tbr=1.130 | STFT: watching (1/3 >6.0) | UAM: watching (1/3 rising rise=0.45/1.0mmol) | uamEntry: SMB 1/3 @80% | circ(ISF×1.02 bas×1.00 ceil=0.96) | 59min left
```

The SmartInsulin tab in the AAPS overview provides a detailed status view including circadian multipliers per hour, STFT/UAM thresholds, post-meal lockout status, rebound/bypass detail, and full learning state.

---

## Requirements

- AndroidAPS 3.4.1.0-dev or later
- Dexcom G6/G7, Libre, or other CGM supported by AAPS
- Compatible pump (tested with Omnipod DASH)
- Android 9+

---

## Contributing

This plugin is in active development. Issues and pull requests welcome. When reporting a bug please include:
- Loop output reason string from the AAPS Loop screen
- SmartInsulin tab screenshot
- AAPS version (build hash)

---

## Acknowledgements

Built on top of the [AndroidAPS](https://github.com/nightscout/AndroidAPS) open source project and the OpenAPS algorithm. Inspired by the work of the broader open-source diabetes community including iAPS, Loop, and OpenAPS contributors.

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" srcset="https://cdn.iconscout.com/icon/free/png-512/bitcoin-384-920569.png 2x" alt="Bitcoin Icon" width="100">

3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2
