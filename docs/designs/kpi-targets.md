# KPI targets — plan (2026-11-08)

Status: DRAFT, awaiting confirmation. Nothing built.

## Goal
Third KPI type **Target** (the default for new KPIs): you set a target value + deadline, the card shows how far you are from it and whether you're on pace.
Higher-is-better / Lower-is-better stay as-is for "background maintenance" KPIs.

## Model
- `KPI.goalType` enum: `TARGET | HIGHER | LOWER`. New KPIs default `TARGET`.
  Existing KPIs: `goalType == null` → derived from `higherIsBetter` (no migration write needed; `higherIsBetter` kept for old data).
- `KPI.target` (only for TARGET): `{ value, deadline (LocalDate), baseline, setAt }`.
  - `baseline` = latest reading when the target is set; no reading yet → first reading after.
- No target history: a new target replaces the old one (history deliberately out of scope).

## Math (server-side, one place: new `KPITargetStatus.of(kpi, latestReading, today)` → in `KPIDTO`)
- direction = sign(target − baseline). Inferred, never stored or asked.
- gapPct = |current − target| / |baseline − target|. 0% = at target, 100% = where you started, >100% = worse than start.
- reached = current has crossed the target in the inferred direction.
- pace: expected = elapsed / (deadline − setAt); progress = 1 − gapPct (clamped 0..1)
  - **Ahead** progress ≥ expected + 10pp · **On track** within ±10pp · **Behind** progress < expected − 10pp
  - deadline passed and not reached → **Missed**; reached → **Reached** (both prompt "Set next target").
  - The ±10pp band stops one noisy day from flipping the badge. To change: `KPITargetStatus.PACE_BAND`.

## UI
- kpi-create.html: 3-way choice (Target ● default / Higher better / Lower better); Target shows value + deadline inputs.
- kpi-list card (TARGET): `current → target`, gap %, pace badge, deadline countdown; card tint green→red by gapPct (tokens `--success` → `--danger`).
- Reached/Missed card: "Set next target" → small inline form → `PUT /kpi/{name}/target {value, deadline}` (resets baseline/setAt).
- kpi-dashboard chart: dashed horizontal line at the target value.

## Stages (one commit each)
1. Model + `KPITargetStatus` + unit tests (math only).
2. API: create with goalType/target, `PUT …/target`, status in `KPIDTO`.
3. Create page 3-way choice.
4. List card + set-next-target form.
5. Dashboard target line; KPI FLOWS.md.
