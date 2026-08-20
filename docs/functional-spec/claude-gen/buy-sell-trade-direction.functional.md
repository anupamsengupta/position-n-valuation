# Functional Specification -- Buy/Sell Trade Direction

**Feature:** Buy/Sell trade direction support across trade capture, position ledger, settlement valuation, and dashboard
**Version:** 1.0
**Status:** DRAFT
**Date:** 2026-08-20
**Spec references:** FR-034, D-1, D-2, D-11, S1, S5a, S5b, S7
**Reference deal:** T-7788, tenant TN_0042, EPEX DE_LU wind PPA

---

## 1. Context

### 1.1 Problem Statement (Domain Language)

The platform currently captures trades without an explicit Buy/Sell direction indicator. The `PositionLedgerEntry.quantity` field is documented as "signed: +long, -short" (FR-034), and the design context document (CONTEXT section 2.4) states that "BUY/SELL conflates trade action with position sign. Ledger stores signed quantity (long +, short -); LONG/SHORT is a display projection."

However, the current trade capture command (`TradeCapture`) does not carry a direction field, meaning:

1. The caller must manually sign the quantity (positive for buy, negative for sell), which is error-prone and opaque to upstream systems that naturally express trades as "Buy 10 MW" or "Sell 10 MW".
2. The PnL calculation in `SettlementMaterializationJob.buildResult()` and `SettlementRevaluationService.buildSettlementCell()` computes `pnl = marketAmount - tradeAmount`, which equals `(marketPrice - tradePrice) * energy`. This is correct for a Buy (long) position where profit occurs when market price exceeds trade price. For a Sell (short) position, the correct PnL is `(tradePrice - marketPrice) * energy`, i.e., `pnl = tradeAmount - marketAmount`. Equivalently: `pnl = -1 * (marketAmount - tradeAmount)` for sells.
3. The dashboard UI (`PositionLedger.tsx`, L3 Position Contributions) has no column indicating whether a position is a Buy or Sell, making it impossible for traders to visually distinguish long from short positions.
4. The forward mark calculation (`ForwardMarkJob.buildResult()`) computes `markValue = price * energy` without direction awareness, producing incorrect unrealized MtM for sell positions.

The feature must introduce an explicit `TradeDirection` (BUY / SELL) at capture time, flow it through the position ledger and settlement pipeline, apply the correct sign convention to PnL and MtM calculations, and display the direction in the dashboard.

### 1.2 Design Principle Alignment

Per CONTEXT section 2.4, the ledger stores signed quantity; direction is a display projection. This specification preserves that principle:

- `TradeDirection` is captured on the `TradeCapture` command as input metadata.
- The `DefaultTradeCaptureHandler` uses direction to determine the sign of `quantity` on `PositionLedgerEntry` (BUY = positive, SELL = negative), removing this burden from the caller.
- `TradeDirection` is persisted on `PositionLedgerEntry` as a denormalized attribute for display and audit purposes. It is NOT used in calculation -- the signed quantity drives all arithmetic.
- PnL and MtM formulas are corrected to be sign-aware via the already-signed quantity, so `pnl = marketAmount - tradeAmount` naturally produces the correct result when `tradeAmount = price * signedEnergy` (negative energy for sells).

**Critical clarification:** The current PnL bug is not merely about adding a `-1` multiplier for sells. The root cause is that `energy` (MWh) in `VolumeRecord` is always positive regardless of direction. The fix requires that either (a) energy is signed by direction before use in amount calculations, or (b) a direction sign multiplier is applied to PnL. Option (a) is cleaner because it makes `tradeAmount` and `marketAmount` inherently signed, and `pnl = marketAmount - tradeAmount` then works for both directions without branching. See Open Question OQ-1.

---

## 2. Actors

| Actor | Role in this feature |
|---|---|
| **Trader** | Captures trades with explicit Buy or Sell direction via the API (or upstream trade capture system). Views positions and PnL on the dashboard. |
| **Operations (Ops)** | Validates that trade direction is consistent with counterparty confirmation. Monitors settlement cells for correctness. |
| **Middle Office** | Reviews position ledger for correct signed quantities. Validates PnL sign against expected portfolio exposure. |
| **Risk** | Aggregates net position across portfolio; relies on signed quantities for correct long/short netting. Validates unrealized MtM for forward positions. |
| **Compliance** | REMIT/EMIR/MiFID II transaction reporting requires Buy/Sell indicator. See section 4. |
| **Back Office** | Settlement and invoicing consume settlement cell amounts; correct sign is essential for invoice direction (payable vs. receivable). |

---

## 3. Business Events

| # | Business Event | Trigger | Impact of Direction |
|---|---|---|---|
| BE-1 | Trade captured | `POST /api/trades/capture` with `direction` field | Direction determines quantity sign on `PositionLedgerEntry`. Quantity is stored as `abs(quantity)` if BUY, `-abs(quantity)` if SELL. Direction is persisted for display. |
| BE-2 | Position ledger entry created | `DefaultTradeCaptureHandler.handle()` | New field `direction` written to each monthly-block entry. |
| BE-3 | Settlement cells materialized | `SettlementMaterializationJob.buildResult()` triggered by `PositionEntryCaptured` | `tradeAmount` and `marketAmount` are computed using signed energy (= energy * directionSign). PnL = `marketAmount - tradeAmount` is naturally correct for both Buy and Sell. |
| BE-4 | Settlement revaluation | `SettlementRevaluationService.revalue()` triggered by `MarketDataUpdated` or `VolumeSuperseded` | Same signed-energy logic as BE-3. |
| BE-5 | Forward marks computed | `ForwardMarkJob.buildResult()` | `markValue = price * signedEnergy` produces correctly signed unrealized MtM. |
| BE-6 | Trade-leg rollup materialized | `TradeLegRollupMaterialization` triggered by `SettlementComputed` | Rollup sums over signed amounts and PnL. Direction is denormalized onto `TradeLegRollupCell` for display. |
| BE-7 | Dashboard queries | `DashboardQueryService` methods (L1-L4) | `PositionContribution` carries direction. UI displays Buy/Sell badge. PnL and MtM values are already correctly signed from S5a/S5b. |
| BE-8 | Trade amended | `POST /api/trades/amend` | Direction may be changed on amendment (edge case -- see section 8). New version's quantity re-signed. |
| BE-9 | Trade cancelled | `POST /api/trades/cancel` | Direction irrelevant on cancellation (entries superseded). |

---

## 4. Regulatory Mapping

### 4.1 REMIT (Regulation 1227/2011)

**Applicable.** REMIT transaction reporting (ACER TRUM Table 1, Field 16) requires a **Buy/Sell indicator** for each reportable wholesale energy transaction. The current absence of an explicit direction field is a gap for any downstream REMIT reporting system that consumes position data from this platform.

- The `TradeDirection` field on `PositionLedgerEntry` satisfies the REMIT requirement for a reportable Buy/Sell indicator.
- REMIT timestamps must be in UTC. This feature does not change timestamp handling.

### 4.2 EMIR (Regulation 648/2012)

**Applicable.** EMIR trade reporting (ESMA Technical Standards, RTS Field 29) requires a **Direction** field for OTC derivative trades. For physical power forwards classified as commodity derivatives under MiFID II, this field must be populated.

- Same `TradeDirection` field serves EMIR reporting.

### 4.3 MiFID II (RTS 22 Transaction Reporting)

**Applicable.** RTS 22, Field 28 (Buy/sell indicator) is mandatory for all reportable transactions. Value domain: `BUYI` / `SELL`.

- The `TradeDirection` enum (`BUY`, `SELL`) maps directly to RTS 22 values.

### 4.4 Summary

All three regulatory frameworks require an explicit Buy/Sell indicator. The current system does not carry one as a first-class attribute. Introducing `TradeDirection` closes a regulatory gap even though this platform is upstream of the actual report submission.

---

## 5. Acceptance Criteria

### AC-1: Trade Capture with Direction

**Given** a trade capture request with `direction = "BUY"` and `quantity = 10`
**When** the trade is captured via `POST /api/trades/capture`
**Then** the resulting `PositionLedgerEntry` records have:
- `quantity = +10` (positive, per BUY convention)
- `direction = "BUY"`

**Given** a trade capture request with `direction = "SELL"` and `quantity = 10`
**When** the trade is captured via `POST /api/trades/capture`
**Then** the resulting `PositionLedgerEntry` records have:
- `quantity = -10` (negative, per SELL convention)
- `direction = "SELL"`

**Given** a trade capture request with `direction = "SELL"` and `quantity = -10` (caller pre-signed)
**When** the trade is captured
**Then** the resulting `PositionLedgerEntry` records have:
- `quantity = -10` (absolute value 10, negated for SELL)
- `direction = "SELL"`
- The handler takes `abs(quantity)` and applies the direction sign, preventing double-negation.

### AC-2: Trade Capture Validation

**Given** a trade capture request with `direction = null` or missing
**When** the trade is captured
**Then** the system rejects the request with a validation error (HTTP 400).
Direction is mandatory; there is no default.

**Given** a trade capture request with `direction = "HEDGE"` (invalid value)
**When** the trade is captured
**Then** the system rejects the request with a validation error (HTTP 400).

### AC-3: Settlement Cell PnL -- Buy Position

**Given** a BUY position with tradePrice = 50.00 EUR/MWh and energy = 2.5 MWh for a 15-min interval
**And** the market price for that interval is 55.00 EUR/MWh
**When** settlement cells are materialized
**Then** the settlement cell has:
- `price = 50.00`
- `volumeMwh = 2.5` (positive)
- `amount = 125.00` (= 50.00 * 2.5, trade cost)
- `marketPrice = 55.00`
- `marketAmount = 137.50` (= 55.00 * 2.5)
- `pnl = +12.50` (= 137.50 - 125.00, profit because market > trade for buy)

### AC-4: Settlement Cell PnL -- Sell Position

**Given** a SELL position with tradePrice = 50.00 EUR/MWh and energy = 2.5 MWh for a 15-min interval
**And** the market price for that interval is 55.00 EUR/MWh
**When** settlement cells are materialized
**Then** the settlement cell has:
- `price = 50.00`
- `volumeMwh = -2.5` (negative, reflecting short position)
- `amount = -125.00` (= 50.00 * -2.5, trade revenue for seller)
- `marketPrice = 55.00`
- `marketAmount = -137.50` (= 55.00 * -2.5)
- `pnl = -12.50` (= -137.50 - (-125.00) = -12.50, loss because market > trade for sell)

*Note: If the market price were 45.00, pnl = (-112.50) - (-125.00) = +12.50 (profit for seller when market < trade).*

### AC-5: Forward Mark -- Direction Awareness

**Given** a SELL position with forward curve price = 60.00 EUR/MWh and resolved energy = 5.0 MWh
**When** forward marks are computed
**Then** the mark value is `60.00 * (-5.0) = -300.00`
And the unrealized MtM correctly reflects the short exposure.

### AC-6: Dashboard L3 -- Direction Display

**Given** the dashboard Position Ledger (L3) is rendered for a portfolio containing both BUY and SELL positions
**When** the user views the position contributions
**Then** each row displays a `Direction` column showing "Buy" or "Sell"
**And** the direction is visually differentiated (e.g., color-coded badge: green for Buy, red for Sell)
**And** the `quantity` column shows the absolute value with the direction implied by the Direction column
**Or** the `quantity` column shows the signed value (consistent with current behavior -- see OQ-2).

### AC-7: Dashboard L1 -- Portfolio Summary Aggregation

**Given** a portfolio with:
- BUY position: settled PnL = +100.00
- SELL position: settled PnL = -50.00
**When** the portfolio summary (L1) is computed
**Then** `realizedPnl = +50.00` (net sum of correctly signed PnL values)
**And** `settledNetMw` and `settledNetMwh` reflect net long/short position (sum of signed values).

### AC-8: Rollup Aggregation

**Given** trade-leg rollup cells are materialized for BUY and SELL positions
**When** the rollup grid (L2) is queried
**Then** the rollup correctly sums signed `settledValue`, `marketValue`, and `realizedPnl` across directions
**And** net MW (TWA) and net MWh (sum) are computed from signed quantities.

### AC-9: Trade Amendment with Direction Change

**Given** an existing BUY position (quantity = +10 MW)
**When** the trade is amended to SELL (same quantity magnitude) via `POST /api/trades/amend`
**Then** the old position entries are superseded (biTemporally closed)
**And** new position entries are created with `quantity = -10 MW` and `direction = "SELL"`
**And** settlement cells are recomputed with correct sell-side PnL.

### AC-10: Backward Compatibility -- Existing Data

**Given** existing `PositionLedgerEntry` records created before this feature (no `direction` field)
**When** the system reads these entries
**Then** direction is inferred from the sign of `quantity`: positive = BUY, negative = SELL
**And** a migration or default-population strategy is applied (see OQ-3).

---

## 6. DST Handling

**DST: not directly impacted by this feature, but transitively relevant.**

The introduction of trade direction does not alter the interval generation, gate closure, or time-series mechanics. However, the corrected PnL formula (`pnl = marketAmount - tradeAmount` with signed energy) must produce correct results on DST transition days:

- **Spring-forward (23-hour day, 92 quarter-hour intervals):** The missing hour 02:00-03:00 CET has no intervals. PnL is computed only for the 92 intervals that exist. No direction-specific logic needed; the sign propagates through the existing interval-by-interval calculation.

- **Fall-back (25-hour day, 100 quarter-hour intervals):** The duplicate hour 02:00-03:00 (CEST then CET) produces two sets of 4 intervals. PnL for each is independently computed using signed energy. No direction-specific ambiguity; the UTC-keyed interval start/end distinguishes the two occurrences.

- **Daily and monthly aggregation:** Sums of signed PnL, amount, and energy across DST-affected days produce correct net totals because signing is per-interval, not per-day.

No new DST-specific rules are introduced by this feature.

---

## 7. Data Model Impact

### 7.1 New Domain Type

**`TradeDirection` enum** (in `pv-domain`)

```
BUY   -- Long position: quantity positive, profit when market > trade
SELL  -- Short position: quantity negative, profit when market < trade
```

This is a domain enum, not a string. Stored as a VARCHAR/TEXT in persistence but type-safe in domain code.

### 7.2 Modified Domain Models

| Model | Change |
|---|---|
| `TradeCapture` (command) | Add field: `TradeDirection direction` (mandatory, non-null) |
| `PositionLedgerEntry` | Add field: `TradeDirection direction` (mandatory). Builder updated. |
| `PositionContribution` (L3 value object) | Add field: `String direction` ("BUY" or "SELL") |
| `TradeLegRollupCell` (S7) | Add field: `String direction` (denormalized from S1) |

### 7.3 Modified DTOs (pv-app, simulator-scope)

| DTO | Change |
|---|---|
| `TradeCaptureRequest` | Add field: `String direction` (mandatory). `toCommand()` maps to `TradeDirection.valueOf()`. |
| `PositionLedgerEntryDto` | Add field: `String direction`. Mapped from `PositionLedgerEntry.direction().name()`. |
| `PositionContributionDto` | Add field: `String direction`. Mapped from `PositionContribution.direction()`. |

### 7.4 Modified Services

| Service | Change |
|---|---|
| `DefaultTradeCaptureHandler.handle()` | Apply direction sign to quantity: `absQuantity * (direction == SELL ? -1 : 1)`. Persist direction on entry. |
| `SettlementMaterializationJob.buildResult()` | No formula change needed IF energy from `VolumeRecord` is already signed. See OQ-1. If energy is unsigned, apply direction sign: `signedEnergy = energy * sign(position.quantity())`. |
| `SettlementRevaluationService.buildSettlementCell()` | Same as above. |
| `ForwardMarkJob.buildResult()` | Same: `markValue = price * signedEnergy`. |
| `DefaultDashboardQueryService.positionContributions()` | Propagate `direction` from `PositionLedgerEntry` or `TradeLegRollupCell` to `PositionContribution`. |

### 7.5 Modified UI Schemas

| Schema | Change |
|---|---|
| `positionContributionSchema` (api.ts) | Add field: `direction: z.enum(["BUY", "SELL"])` |

### 7.6 Modified UI Components

| Component | Change |
|---|---|
| `PositionLedger.tsx` | Add `Direction` column after `Leg` column. Render as colored badge ("Buy" in green, "Sell" in red). |

### 7.7 Persistence Entity

| Entity | Change |
|---|---|
| `PositionLedgerEntryEntity` (pv-persistence) | Add column: `direction VARCHAR(4) NOT NULL`. |
| `TradeLegRollupEntity` (pv-persistence, if exists) | Add column: `direction VARCHAR(4)`. |
| Settlement cell entity | No change. Direction is implicit in the sign of `volumeMwh` and `amount`. |

### 7.8 Kafka Events

No new events or topics. The `PositionEntryCaptured` event carries `positionId`; the consumer loads the full `PositionLedgerEntry` (which now includes direction) from the repository. No schema change to the event payload is required.

### 7.9 Reference Data Dependencies

None. `TradeDirection` is a closed enum with no reference data table.

---

## 8. Edge Cases

### EC-1: Cross-timezone delivery

Direction does not interact with timezone. The signed quantity and energy are timezone-agnostic (computed per UTC interval). No special handling required.

### EC-2: Gate closure windows

Direction does not affect gate closure mechanics. No interaction.

### EC-3: Corrections and backdated trades

A backdated correction (`amendmentReason = BACKDATED_CORRECTION`) may change direction. The bitemporal supersession mechanism handles this: old entries are closed, new entries with the corrected direction and re-signed quantity are created. Settlement cells are recomputed.

### EC-4: Cancellations

On cancellation, entries are superseded. Direction on cancelled entries is preserved for audit trail but has no downstream effect.

### EC-5: Multi-currency

Direction sign is independent of currency. A SELL trade in GBP produces negative GBP amounts. No interaction.

### EC-6: Multi-tenant isolation

`TradeDirection` is a per-trade attribute. It does not affect tenant isolation. Standard RLS policies apply.

### EC-7: Zero quantity

A trade captured with `quantity = 0` and any direction produces zero-signed amounts. This is a degenerate case. The system should allow it (some exchanges permit zero-volume capacity trades) but the dashboard may hide zero rows.

### EC-8: Spread / structured trades with multiple legs

A spread trade may have one BUY leg and one SELL leg. Each leg is captured independently with its own direction. The position ledger handles each leg as a separate `PositionLedgerEntry`. Portfolio-level netting (L1, L2) correctly aggregates across opposing legs.

### EC-9: PPA with volume forecast

For PPAs (Power Purchase Agreements), direction is typically BUY (offtaker buys from generator). The volume is forecast-driven (S3 FORECAST series). Direction sign applies to the resolved volume uniformly across all forecast intervals.

### EC-10: Existing positions without direction (data migration)

Positions created before this feature have no `direction` field. Options:
1. **Default based on quantity sign:** `quantity >= 0` implies BUY; `quantity < 0` implies SELL.
2. **Migration script:** Backfill `direction` column based on quantity sign.
3. **Nullable with inference:** Allow `direction = null` on legacy entries and infer at read time.

See OQ-3 for the recommended approach.

### EC-11: Idempotent re-capture

If a trade is re-captured (same tradeId, tradeLegId, tradeVersion), the idempotency check in `DefaultTradeCaptureHandler` returns existing entries. The direction on existing entries must match the re-capture request. If it differs, this is a data integrity issue (the source system is inconsistent). See OQ-4.

---

## 9. Open Questions

| # | Question | Impact | Proposed Resolution |
|---|---|---|---|
| OQ-1 | **How is energy signed?** The `VolumeRecord.energy()` returned by `VolumeResolver` is currently always positive. Should direction-signing happen: (a) inside `VolumeResolver` (it receives the position which has signed quantity), (b) at the call site in `SettlementMaterializationJob`/`SettlementRevaluationService` where `signedEnergy = energy * signum(position.quantity())`, or (c) by making `VolumeRecord` carry a sign? | Critical: determines where the PnL fix is implemented. Option (b) is safest because it changes the least code and VolumeResolver remains direction-agnostic (it resolves physical volume, which is inherently unsigned). | Recommend option (b): sign energy at the settlement cell build site using `signum(position.quantity())`. This keeps VolumeResolver (S3) unchanged and localizes the direction logic to S5a/S5b. |
| OQ-2 | **Dashboard quantity display: signed or absolute?** The current `PositionLedger.tsx` displays `quantity` as-is (signed). With direction now shown as a separate column, should `quantity` show the absolute value (cleaner UX, direction is in its own column) or remain signed (consistent with ledger semantics)? | UI only. No backend impact. | Recommend: show absolute value in the `Qty` column and direction in the `Direction` column. The signed value is available via tooltip or detail view. |
| OQ-3 | **Data migration for existing positions.** What is the strategy for positions created before this feature? Options: (a) migration script backfills based on quantity sign, (b) nullable field with read-time inference, (c) treat as a breaking change requiring full re-capture. | Determines schema migration complexity. Option (a) is preferred for data consistency. | Recommend option (a): a one-time migration sets `direction = CASE WHEN quantity >= 0 THEN 'BUY' ELSE 'SELL' END`. After migration, the column is NOT NULL. |
| OQ-4 | **Idempotent re-capture with direction mismatch.** If a trade is re-captured with a different direction but the same (tradeId, tradeLegId, tradeVersion), should the system: (a) return existing entries (current behavior, ignoring the mismatch), (b) reject with an error, or (c) treat it as an amendment? | Edge case. Should be rare but needs a defined behavior. | Recommend option (b): reject with HTTP 409 Conflict if direction differs on idempotent re-capture. The caller should amend instead. |
| OQ-5 | **TradeAmendRequest: does it carry direction?** The existing `TradeAmendRequest` DTO needs to include direction if amendments can change direction. If direction is immutable after capture, amendments that need to flip direction require cancel + re-capture. | Determines amendment workflow complexity. | Recommend: amendments CAN change direction. The `TradeAmendRequest` must include `direction` as a mandatory field. |
| OQ-6 | **SettlementCell: should `volumeMwh` be signed?** Currently `volumeMwh` on `SettlementCell` is always positive. If we sign it per direction, downstream consumers (back office, invoicing) must handle signed energy. If we keep it positive and only sign `amount`/`marketAmount`/`pnl`, the energy remains a physical quantity and direction is encoded only in monetary values. | Affects all downstream consumers of `SettlementCell`. | Recommend: sign `volumeMwh` (and `volumeMw`) to be consistent with position sign convention. This makes `amount = price * volumeMwh` naturally correct without branching. Downstream consumers already handle signed PnL; signed volume is the natural extension. Requires validation that no downstream consumer assumes positive volume. |
| OQ-7 | **FR-034 alignment.** FR-034 states "signed quantity (+long, -short)" which already implies direction is encoded in sign. Does the functional spec need a formal FR amendment to add the `direction` field, or is it additive and non-breaking to FR-034? | Documentation completeness. | Recommend: add FR-034a stating that `direction` (BUY/SELL) is captured at trade entry and persisted as a denormalized attribute alongside the signed quantity. The signed quantity remains the authoritative computational input. |
