# Functional Specification: Day-Ahead (DA) Exchange Spot

**Feature slug:** `day-ahead-exchange-spot`
**Version:** 1.0
**Date:** 2026-08-21
**Status:** DRAFT
**Source requirements:** Instrument Calculation Requirements, Section 1
**Binding spec references:** FR-020 through FR-025, FR-030, FR-034, FR-036, FR-054a, FR-070/071, D-1, D-11

---

## Context

### Problem Statement in Domain Language

Day-Ahead (DA) exchange spot is the foundational physical power trading instrument on EPEX Spot. A tenant submits bids into the DA auction (gate closure 12:00 CET D-1), receives execution results as a batch feed, and physically delivers/takes power on the next calendar day. Settlement occurs at D+2 via ECC clearing. Because DA trades are fully realized within 48 hours of auction close, no mark-to-market or forward valuation is needed -- all P&L is realized.

The platform must support the complete DA lifecycle: batch ingestion of auction results (one deal per executed contract), block order decomposition into constituent intervals, nomination tracking against TSO schedules, energy settlement at interval granularity, exchange fee accounting, imbalance settlement against TSO-published balancing energy prices, and realized P&L computation including cross-instrument differential P&L (DA vs. intraday adjustments, DA vs. PPA contract price).

DA is the simplest instrument in the platform's instrument universe, but it introduces several capabilities not yet present in the codebase: batch trade ingestion from exchange feeds, block order decomposition requiring holiday calendars, a separate nomination volume tracking layer, exchange fee schedules with effective-dated configuration, a monthly imbalance settlement cycle distinct from the energy settlement cycle, and the concept of a reference/benchmark price against which other instruments compute P&L.

### What the Platform Already Supports

The existing codebase provides the structural foundation for DA:

- **Position Ledger (S1):** Bitemporal entries at trade-leg x delivery-month grain (D-1). The `originType` field already supports `EXCHANGE_FILL`. The `TradeCapture` command and `DefaultTradeCaptureHandler` handle single-trade capture with idempotency and bitemporal supersession.
- **Settlement Cells (S5a):** 15-min interval measures with `price`, `volumeMw`, `volumeMwh`, `amount`, `marketPrice`, `pnl`, `currency`. The existing `SettlementMaterializationJob` pipeline populates these.
- **Volume Series (S3):** Supports PROFILE series type at 15-min and HOURLY granularity with `VolumeReference x multiplier` (D-11). DA trades use fixed-profile references with multiplier = 1.0.
- **Price Expression (S2):** Supports `ConstantLeaf` for fixed DA clearing prices. The `priceExpressionId` on the ledger entry points to a degenerate expression.
- **Market Data (S4):** `MarketDataPort.lookupFixing()` can serve DA clearing prices per interval. Market data series and versioning are in place.
- **Event Pipeline:** `PositionEntryCaptured` -> `SettlementMaterializationJob` -> `SettlementComputed` -> rollup materialization. This pipeline handles DA identically to other instruments once trades are captured.
- **Rollups (S7):** Materialized aggregates from settlement cells. DA rolls up the same way as any other instrument.
- **MarketCalendar (FR-024/025):** Sole authority for interval structure, DST-correct interval counts, peak/off-peak classification.

### What Is New for DA

| Capability | Status | Requirement IDs |
|---|---|---|
| Batch auction result ingestion (FTP/API feed parse, validation, multi-deal creation) | NEW | DA-VOL-01 |
| Block order decomposition (baseload, peak, off-peak, custom blocks with holiday calendar) | NEW | DA-VOL-02 |
| Nomination volume tracking (separate from traded volume, per-interval, per balancing group) | NEW | DA-VOL-03 |
| Negative price sign handling in settlement | EXISTS (needs verification) | DA-PRC-02 |
| Exchange fee schedules (configurable, effective-dated, per-exchange, per-member-tier) | NEW | DA-SET-03 |
| Imbalance settlement cycle (TSO data ingestion, BG attribution, monthly aggregation) | NEW | DA-SET-04 |
| Payment date computation (TARGET2 calendar, D+2 business day roll) | NEW | DA-SET-02 |
| Reference price configuration for P&L (DA as benchmark) | NEW | DA-VAL-01 |
| Cross-instrument differential P&L (DA vs. ID adjustment) | NEW | DA-VAL-02 |
| Auction-result immutability enforcement (REMIT regulatory record) | NEW | DA-PRC-01 |

---

## Actors

| Actor | Role in DA lifecycle | Key interactions |
|---|---|---|
| **Trader (DA desk)** | Submits bids to EPEX (outside platform scope); reviews execution results; monitors position and P&L | Views DA position grid, settlement amounts, realized P&L, imbalance exposure |
| **Operations / Scheduling** | Imports auction results; manages nomination to TSO; attributes nominations to balancing groups; reviews traded-vs-nominated deviations | Triggers batch import; reviews nomination workbench; submits schedules to TSO (external) |
| **Middle Office** | Validates auction imports; reconciles exchange-reported totals against imported deals; monitors data quality alerts (extreme prices) | Reviews import reconciliation report; acknowledges or escalates data quality alerts |
| **Back Office / Settlement** | Manages energy settlement records; applies exchange fees; reconciles ECC invoices; processes imbalance settlement from TSO | Reviews settlement per delivery day; manages fee schedule configuration; processes monthly TSO imbalance invoices |
| **Risk** | Monitors intraday position changes resulting from DA fills; reviews imbalance exposure | Consumes position and P&L feeds; reviews imbalance cost attribution |
| **Compliance** | Ensures REMIT reporting of DA trades; ensures immutability of auction results | Reviews REMIT transaction report feed; audits that imported prices/volumes are unedited |

---

## Business Events

### Event Catalogue

| # | Business Event | Trigger | Upstream | Downstream |
|---|---|---|---|---|
| BE-01 | **DA auction results available** | EPEX publishes execution report (~12:42 CET D-1) | External: EPEX FTP/API feed | BE-02 |
| BE-02 | **Batch import initiated** | Ops triggers import or scheduled job polls for new file | BE-01 | BE-03, BE-04 |
| BE-03 | **Auction result validated** | Per-file: sum-of-interval-volumes == exchange-reported-total; per-contract: price within plausibility range | BE-02 | BE-05 (on success), BE-06 (on failure) |
| BE-04 | **Block order decomposed** | Block orders in the execution report are expanded into constituent intervals | BE-02 | BE-05 |
| BE-05 | **DA trades captured** | One `TradeCapture` command per executed contract (or decomposed interval); uses existing pipeline | BE-03/04 | Existing: `PositionEntryCaptured` -> settlement materialization |
| BE-06 | **Import validation failed** | Volume mismatch or structural parse error | BE-03 | Alert to Middle Office; import quarantined |
| BE-07 | **DA clearing prices ingested** | EPEX price publication imported into market data (S4) | External: EPEX price feed | Settlement materialization uses prices via `MarketDataPort.lookupFixing()` |
| BE-08 | **Nomination submitted** | Ops submits schedule to TSO by gate closure (14:30 CET D-1 for DE) | BE-05 (traded volumes as basis) | BE-09 |
| BE-09 | **Nomination recorded** | System stores nominated volume per interval per balancing group with timestamp | BE-08 | BE-10 |
| BE-10 | **Nomination deviation detected** | Nominated volume != traded volume for any interval | BE-09 | Warning to Ops and Risk |
| BE-11 | **Delivery complete** | Physical delivery day passes (00:00-00:00 CET next day) | Clock | BE-12 |
| BE-12 | **Energy settlement computed** | `price[t] x volume[t] x interval_hours` per interval; daily total | BE-11, BE-07 | BE-13 |
| BE-13 | **Exchange fees applied** | Trading fee + clearing fee per MWh on absolute (not netted) volume | BE-12 | BE-14 |
| BE-14 | **Cashflow record generated** | Settlement amount, currency (EUR), pay/receive direction, payment date (D+2 TARGET2), counterparty (ECC) | BE-12, BE-13 | Treasury/liquidity module (PORT-10) |
| BE-15 | **Imbalance data received** | TSO publishes reBAP and metered delivery (typically D+1) | External: TSO / ENTSO-E transparency platform | BE-16 |
| BE-16 | **Imbalance settlement computed** | `(Nominated[t] - Actual_delivered[t]) x imbalance_price[t]` per 15-min interval | BE-15 | BE-17 |
| BE-17 | **Monthly imbalance invoice generated** | Aggregation of all 15-min imbalance cashflows for the calendar month per BRP/balancing group | BE-16 (accumulated over month) | Back Office reconciliation against TSO invoice |
| BE-18 | **Realized P&L computed** | `(DA_price - reference_price) x volume` per interval; reference price configurable | BE-12 | Dashboard (S7 rollups) |
| BE-19 | **Data quality alert raised** | DA price outside plausibility range (-500 to +4,000 EUR/MWh) | BE-07 | Middle Office review; price is NOT rejected |

---

## Regulatory Mapping

### REMIT (Regulation 1227/2011)

**Applicable.** DA exchange trades on EPEX Spot are wholesale energy products reportable under REMIT.

| REMIT obligation | Applicability to DA | Platform impact |
|---|---|---|
| **Art. 8(1) -- Transaction reporting** | Each DA execution is a reportable transaction. Reported to ACER via RRM (Registered Reporting Mechanism) by T+1. | The platform must produce a REMIT transaction report record per executed DA contract. Fields: UTI (unique trade identifier), parties (LEI), product (contract specification), price, quantity, delivery period, execution venue (EPEX MIC: XEPC), execution timestamp. |
| **Art. 8(1) -- Fundamental data** | Not directly applicable to trading (applies to generation/consumption operators). | No DA-specific impact. |
| **Art. 8(5) -- Record keeping** | All transaction data must be retained for 5 years. Auction prices and volumes are regulatory records and must be immutable after import (DA-PRC-01). | Auction results must be stored as immutable records. No user-editable fields on imported DA prices/volumes. Corrections require a formal amendment process creating a new bitemporal version with reason `REGULATORY_CORRECTION`. |
| **Art. 8 -- Position reconstruction** | ACER may request reconstruction of positions at any historical knowledge date. | Existing S1 bitemporal ledger (FR-007) handles this. DA positions reconstruct identically to other instruments. |

### EMIR (Regulation 648/2012)

**Not applicable.** EPEX DA spot contracts with physical delivery are not OTC derivatives. They are physically settled commodity contracts traded on a regulated market (exchange) with delivery within T+2. The EMIR clearing obligation and trade reporting obligation do not apply to physically settled spot contracts.

Note: If a tenant trades DA-linked financial derivatives (e.g., DA futures, contracts for difference referencing DA price), those are separate instruments subject to EMIR, but the DA spot trade itself is exempt.

### MiFID II / MiFIR (Directive 2014/65/EU, Regulation 600/2014)

**Partially applicable.**

| MiFID II obligation | Applicability | Platform impact |
|---|---|---|
| **RTS 22 -- Transaction reporting** | EPEX DA contracts are traded on a regulated market. The exchange (EPEX) itself reports to the NCA. The trading firm may have separate reporting obligations if it is a MiFID investment firm. | Platform must retain sufficient trade data (execution timestamp, price, quantity, venue MIC, client ID) for the firm's own RTS 22 reporting. The platform does not submit the report -- it provides the data. |
| **Position limits (Art. 57)** | Commodity derivative position limits apply to exchange-traded power contracts. DA spot is typically classified as a spot contract, not a derivative, so position limits under Art. 57 may not apply. | The platform should support aggregated net position reporting per bidding zone per delivery period (already supported via S6/S7 rollups) in case the NCA classifies DA as subject to limits. |
| **Best execution (Art. 27)** | Firms executing on EPEX must document that the execution venue delivers best execution. | Outside platform scope -- policy obligation, not a data system requirement. |

### Summary

DA spot trades trigger **REMIT transaction reporting** as the primary regulatory obligation. Imported auction results (prices and volumes) must be treated as **immutable regulatory records**. EMIR does not apply to physically settled DA spot. MiFID II transaction reporting obligations fall on the exchange, though the platform must retain data for the firm's own compliance records.

---

## Acceptance Criteria

### DA-VOL-01: Auction Volume Resolution

**Scenario 1: Standard hourly DA import**

```
Given an EPEX DA auction execution report for delivery day 2026-09-15
  And the report contains 24 hourly contracts for bidding zone DE_LU
  And each contract specifies a volume (MW) and delivery hour
When Operations triggers the batch import
Then the system creates 24 trade-leg records (one per hourly contract)
  And each trade-leg has originType = "EXCHANGE_FILL"
  And each trade-leg has a PROFILE volume series at HOURLY granularity with multiplier = 1.0
  And each trade-leg's delivery period spans exactly one hour [HH:00, HH+1:00) CET
  And each position ledger entry references DeliveryRange for the month 2026-09
  And the sum of all imported interval volumes equals the exchange-reported total MWh for the session
  And a PositionEntryCaptured event is published per trade-leg
```

**Scenario 2: 15-minute granularity DA import (DE, AT, NL, BE, FR)**

```
Given an EPEX DA execution report for delivery day 2026-09-15 in bidding zone DE_LU
  And the report contains 96 quarter-hourly contracts
When Operations triggers the batch import
Then the system creates 96 trade-leg records
  And each trade-leg has a PROFILE volume series at MIN_15 granularity
  And the delivery period per trade-leg spans exactly 15 minutes
```

**Scenario 3: Multi-zone auction results**

```
Given a tenant participates in DA auctions for both DE_LU and FR
  And the EPEX execution report contains results for both zones
When Operations triggers the batch import
Then the system creates separate deals per zone
  And each deal's deliveryPointId references the correct bidding zone
  And zone-level volume totals are validated independently against exchange-reported totals
  And positions are not netted across zones (FR-023)
```

**Scenario 4: Volume validation failure**

```
Given an EPEX DA execution report for DE_LU
  And the sum of interval volumes is 1,240 MWh
  But the exchange-reported session total is 1,250 MWh (10 MWh discrepancy)
When Operations triggers the batch import
Then the import is quarantined with status VALIDATION_FAILED
  And a data quality alert is raised to Middle Office
  And no trade-leg records are created
  And no PositionEntryCaptured events are published
```

**Scenario 5: Idempotent re-import**

```
Given DA auction results for delivery day 2026-09-15 DE_LU have already been imported
  And 96 trade-legs exist with tradeVersion = 1
When Operations re-triggers the import for the same auction session
Then the existing trade-legs are returned without duplication
  And no new PositionEntryCaptured events are published
  And the operation is idempotent per the existing DefaultTradeCaptureHandler contract
```

### DA-VOL-02: Block Order Decomposition

**Scenario 1: Baseload block decomposition**

```
Given a DA baseload block order for delivery day 2026-09-15 (Tuesday)
  And the block volume is 20 MW
  And the delivery day has 96 quarter-hour intervals (standard day)
When the block order is decomposed
Then 96 intervals are created, each at 20 MW
  And each interval produces 5.0 MWh (20 MW x 0.25 h)
  And the total energy = 480 MWh (20 MW x 24 h)
```

**Scenario 2: Peak block decomposition with holiday calendar**

```
Given a DA peak block order for delivery day 2026-12-25 (Friday, Christmas Day)
  And the block volume is 30 MW
  And the bidding zone is DE_LU
  And December 25 is a public holiday in the DE holiday calendar
When the block order is decomposed
Then zero peak intervals are created (holidays are off-peak)
  And the block order is flagged as having no deliverable intervals
  And a warning is raised to Operations
```

**Scenario 3: Peak block on a normal weekday**

```
Given a DA peak block order for delivery day 2026-09-15 (Tuesday)
  And the block volume is 30 MW
  And peak hours are defined as 08:00-20:00 CET (Mon-Fri, excluding holidays)
When the block order is decomposed
Then 48 quarter-hour intervals are created (12 peak hours x 4 QH)
  And each interval is at 30 MW
  And the total peak energy = 360 MWh (30 MW x 12 h)
```

**Scenario 4: Partially executed block order**

```
Given a DA baseload block order for delivery day 2026-09-15
  And the block volume is 20 MW
  And the execution ratio is 50% (partially filled)
When the block order is decomposed
Then 96 intervals are created, each at 10 MW (20 MW x 50%)
  And each interval produces 2.5 MWh
```

**Scenario 5: Custom block decomposition**

```
Given a DA custom block defined as "Morning" = 06:00-10:00 CET
  And the block volume is 15 MW
  And the delivery day is 2026-09-15
When the block order is decomposed
Then 16 quarter-hour intervals are created (4 hours x 4 QH)
  And each interval is at 15 MW
  And the delivery period for each interval falls within [06:00, 10:00) CET
```

### DA-VOL-03: Nomination/Scheduling Volume

**Scenario 1: Standard nomination recording**

```
Given DA trades have been captured for delivery day 2026-09-15 in DE_LU
  And the tenant's balancing group is BG-DE-001
When Operations records the nomination of 50 MW for hour 10:00-11:00 CET
Then the nomination record stores:
  - nominated volume: 50 MW per interval
  - balancing group: BG-DE-001
  - nomination timestamp (UTC)
  - delivery intervals: 4 QH intervals within [10:00, 11:00) CET
  And the nomination is tracked separately from the traded volume
```

**Scenario 2: Nomination deviation warning**

```
Given DA traded volume for interval 10:00-10:15 CET is 50 MW (BUY)
  And the nominated volume for the same interval is 48 MW
When the system compares nominated vs traded volumes
Then a pre-delivery imbalance warning is raised
  And the deviation of -2 MW is flagged
  And the warning is visible to Operations and Risk
```

**Scenario 3: Multi-balancing-group nomination**

```
Given the tenant operates two balancing groups: BG-DE-001 and BG-DE-002
  And DA trades are captured with assets mapped to different BGs
When Operations records nominations
Then each nomination is attributed to the correct BG based on asset-to-BG mapping
  And imbalance calculations in DA-SET-04 are performed per BG
```

### DA-PRC-01: Auction Clearing Price Ingestion

**Scenario 1: Standard price ingestion**

```
Given EPEX publishes DA clearing prices for delivery day 2026-09-15 DE_LU
  And the publication contains 96 quarter-hour prices in EUR/MWh (2 decimal places)
When the price feed is ingested
Then each price is stored in the market data subsystem (S4) as a fixing
  And each price is matched to the corresponding traded contract by interval
  And the price is stored as a ConstantLeaf price expression with scale = PRICE (8 decimals)
  And the imported price record is immutable (no user edit permitted)
```

**Scenario 2: Price outside plausibility range**

```
Given a DA clearing price of 4,500 EUR/MWh for interval 18:00-18:15 CET
  And the plausibility range is [-500, +4,000] EUR/MWh
When the price is ingested
Then the price is accepted and stored (NOT rejected)
  And a data quality alert is raised to Middle Office
  And the alert contains: interval, price, bidding zone, delivery day
```

**Scenario 3: Immutability enforcement**

```
Given DA clearing prices for delivery day 2026-09-15 have been imported
When any user attempts to edit an imported DA auction price
Then the system rejects the edit with reason "Auction results are immutable regulatory records"
  And the original price remains unchanged
  And the rejection is audit-logged
```

### DA-PRC-02: Negative Price Handling

**Scenario 1: BUY at negative price**

```
Given a DA BUY trade for interval 14:00-14:15 CET on 2026-09-15
  And the volume is 50 MW (signed quantity = +50)
  And the DA clearing price is -25.50 EUR/MWh
When settlement is computed
Then the settlement amount = -25.50 x 50 x 0.25 = -318.75 EUR
  And the negative amount means the buyer RECEIVES money (credit)
  And the cashflow direction is "receive" for the buyer
```

**Scenario 2: SELL at negative price**

```
Given a DA SELL trade for interval 14:00-14:15 CET on 2026-09-15
  And the volume is 30 MW (signed quantity = -30)
  And the DA clearing price is -25.50 EUR/MWh
When settlement is computed
Then the settlement amount = -25.50 x (-30) x 0.25 = +191.25 EUR
  And the positive amount means the seller PAYS money (debit)
  And the cashflow direction is "pay" for the seller
```

**Scenario 3: Negative price display**

```
Given a DA settlement view showing interval 14:00-14:15 CET
  And the clearing price is -25.50 EUR/MWh
When the UI renders the settlement line
Then the price is displayed in a distinct color (configurable, default: red)
  And credits (negative amounts for buyer) are labeled "Credit" or "Receipt"
  And the system does NOT auto-correct or flag the negative price as an error
```

### DA-SET-01: Gross Settlement

**Scenario 1: Standard daily settlement**

```
Given DA trades for delivery day 2026-09-15 in DE_LU
  And 96 quarter-hour intervals have been traded
  And interval 10:00-10:15 has price = 72.50 EUR/MWh, volume = 50 MW (BUY)
When settlement is computed for the delivery day
Then each interval produces one settlement line item
  And interval 10:00-10:15 settlement = 72.50 x 50 x 0.25 = 906.25 EUR
  And all settlement amounts are in EUR
  And settlement amounts are rounded to 2 decimal places per ECC clearing rules
  And the daily total = sum of all 96 interval settlement amounts
```

**Scenario 2: Multiple trades same interval (gross per-trade settlement)**

```
Given for interval 10:00-10:15 CET:
  - Trade A: BUY 50 MW at 72.50 EUR/MWh
  - Trade B: SELL 20 MW at 72.50 EUR/MWh
When settlement records are generated
Then each trade produces its own settlement cell (per S5a, one cell per position per interval)
  And Trade A settlement = 72.50 x 50 x 0.25 = 906.25 EUR (pay)
  And Trade B settlement = 72.50 x (-20) x 0.25 = -362.50 EUR (receive)
  And netting across trades is performed by the exchange (ECC) on its daily invoice — outside this system
  And the platform's rollup views (S7) aggregate the gross settlements for portfolio-level net position reporting
```

Note: If the exchange feed delivers a pre-netted trade (e.g., net +30 MW for a session), the platform captures it as a single trade-leg. The system of record is always per-trade-leg; exchange-level netting is not replicated internally.

### DA-SET-02: Payment Due Date

**Scenario 1: Standard D+2 payment**

```
Given a DA delivery day of Wednesday 2026-09-16
  And the TARGET2 calendar shows Thursday 2026-09-17 and Friday 2026-09-18 as business days
When the payment date is computed
Then the payment date = Friday 2026-09-18 (D+2 TARGET2 business days)
```

**Scenario 2: D+2 falls on TARGET2 holiday**

```
Given a DA delivery day of Wednesday 2026-12-23
  And TARGET2 holidays include Thursday 2026-12-24 (half-day/close) and Friday 2026-12-25
When the payment date is computed
Then D+1 = 2026-12-24 (if TARGET2 closed, skip)
  And the payment rolls forward to the next TARGET2 business day
  And a cashflow record is generated with the correct rolled payment date
```

**Scenario 3: Cashflow record content**

```
Given settlement for delivery day 2026-09-16 totals 45,230.50 EUR (net payable)
When the cashflow record is generated
Then the record contains:
  - amount: 45,230.50
  - currency: EUR
  - direction: PAY (positive = payment to exchange for net buyer)
  - payment_date: 2026-09-18 (D+2 TARGET2)
  - counterparty: ECC (European Commodity Clearing AG)
  - reference: list of source trade IDs
  And the cashflow feeds into PORT-10 (treasury/liquidity forecasting)
```

### DA-SET-03: Exchange Fees

**Scenario 1: Fee application to gross volume**

```
Given for delivery day 2026-09-15:
  - BUY: 500 MWh across all intervals
  - SELL: 200 MWh across all intervals
  And the EPEX trading fee is 0.05 EUR/MWh
  And the ECC clearing fee is 0.02 EUR/MWh
When fees are computed
Then the fee base = 500 + 200 = 700 MWh (absolute volume, NOT netted)
  And the trading fee = 700 x 0.05 = 35.00 EUR
  And the clearing fee = 700 x 0.02 = 14.00 EUR
  And total fees = 49.00 EUR
  And fees are recorded as a separate settlement line item (not netted into energy price)
```

**Scenario 2: Fee schedule with effective dates**

```
Given the EPEX trading fee schedule:
  - Before 2026-07-01: 0.04 EUR/MWh
  - From 2026-07-01: 0.05 EUR/MWh
  And the delivery day is 2026-09-15
When fees are computed
Then the system uses the fee rate effective on the delivery date: 0.05 EUR/MWh
```

**Scenario 3: Volume-tiered fees**

```
Given a tenant has a member-tier fee discount:
  - Tier 1 (0-10,000 MWh/month): 0.06 EUR/MWh
  - Tier 2 (10,001+ MWh/month): 0.04 EUR/MWh
  And the tenant's cumulative monthly volume exceeds 10,000 MWh
When fees are computed for a delivery day
Then the tier-2 rate is applied
  And the fee schedule configuration supports per-tenant tier assignments
```

### DA-SET-04: Imbalance Settlement

**Scenario 1: Over-delivery (positive imbalance)**

```
Given for interval 10:00-10:15 CET on delivery day 2026-09-15:
  - Nominated volume: 50 MW
  - Actual metered delivery: 52 MW
  - Imbalance price (reBAP): 85.00 EUR/MWh
When imbalance settlement is computed
Then imbalance volume = 52 - 50 = +2 MW (over-delivery)
  And imbalance energy = 2 x 0.25 = 0.5 MWh
  And imbalance settlement = 0.5 x 85.00 = 42.50 EUR (receipt from TSO)
```

**Scenario 2: Under-delivery (negative imbalance)**

```
Given for interval 10:00-10:15 CET on delivery day 2026-09-15:
  - Nominated volume: 50 MW
  - Actual metered delivery: 47 MW
  - Imbalance price (reBAP): 120.00 EUR/MWh
When imbalance settlement is computed
Then imbalance volume = 47 - 50 = -3 MW (under-delivery)
  And imbalance energy = -3 x 0.25 = -0.75 MWh
  And imbalance settlement = -0.75 x 120.00 = -90.00 EUR (payment to TSO)
```

**Scenario 3: Monthly imbalance aggregation**

```
Given all 15-min imbalance settlements for September 2026 have been computed
  And the balancing group is BG-DE-001
When the monthly imbalance invoice is generated
Then the invoice aggregates all QH imbalance cashflows for the month
  And the invoice shows:
  - total imbalance energy (MWh, net over/under)
  - total imbalance cost (EUR, net pay/receive)
  - breakdown by delivery day
  And the invoice is attributable to the portfolio/book responsible for scheduling
```

**Scenario 4: Imbalance cost attribution**

```
Given the tenant has two portfolios: PORTFOLIO-DA and PORTFOLIO-WIND
  And the scheduling decision was made by the DA desk
When imbalance costs are attributed
Then the imbalance cost is charged to the portfolio responsible for the nomination deviation
  And the attribution is configurable (default: portfolio of the scheduling decision)
```

### DA-VAL-01: Realized P&L

**Scenario 1: DA as reference price (zero self-P&L)**

```
Given DA is configured as the reference price for the DA portfolio
  And a DA BUY trade at 72.50 EUR/MWh for interval 10:00-10:15
When realized P&L is computed
Then P&L = (72.50 - 72.50) x volume = 0 EUR
  And the DA trade has zero P&L against itself when DA is the reference
```

**Scenario 2: DA vs PPA contract price**

```
Given a PPA delivers 50 MWh at contract price 55.00 EUR/MWh for interval 10:00-10:15
  And the trader sells 50 MWh in DA at clearing price 68.00 EUR/MWh
When realized P&L is computed on the combined position
Then realized P&L = (68.00 - 55.00) x 50 x 0.25 = 162.50 EUR per QH interval
  And the P&L is attributed to the portfolio holding both the PPA and DA hedge
```

**Scenario 3: Configurable reference price**

```
Given a portfolio's P&L reference is configured as "portfolio weighted average cost"
  And the portfolio's weighted average cost for the interval is 60.00 EUR/MWh
  And the DA clearing price is 72.50 EUR/MWh
When realized P&L is computed for a DA BUY
Then P&L = (72.50 - 60.00) x volume x 0.25 per interval
  And the reference price source is stored on the settlement cell for auditability
```

### DA-VAL-02: Intraday P&L vs DA Price

**Scenario 1: ID adjustment against DA position**

```
Given a DA BUY of 30 MW at 65.00 EUR/MWh for interval 10:00-10:15
  And an ID SELL of 10 MW at 72.00 EUR/MWh for the same interval
When the DA-vs-ID differential P&L is computed
Then the adjustment volume = 10 MW (the ID portion)
  And the differential P&L = (72.00 - 65.00) x 10 x 0.25 = 17.50 EUR
  And this P&L is attributed to the intraday trading desk, not the DA desk
  And the ID trade is linked to the original DA position for this computation
```

---

## Data Model Impact

### Existing Structures -- Reuse

| Structure | DA usage | Changes needed |
|---|---|---|
| `PositionLedgerEntry` (S1) | One entry per DA contract per delivery month. `originType = "EXCHANGE_FILL"`. | None -- existing structure supports DA directly. |
| `SettlementCell` (S5a) | One cell per 15-min interval. `price` = DA clearing price, `amount` = price x volume x 0.25. `valuationType` = "REALIZED". | None -- existing structure works. Need to confirm `valuationType` enum includes "REALIZED". |
| `VolumeReference` + `VolumeSeries` (S3) | Fixed PROFILE series with `multiplier = 1.0`, no `assetId`, no `meteredSeriesKey`. | None -- this is the "degenerate" fixed-profile case already documented in VolumeReference. |
| `PriceExpression` (S2) | `ConstantLeaf` with the DA clearing price per interval. | None -- degenerate price expression already supported. |
| `TradeCapture` command | Used per DA contract. `originType = "EXCHANGE_FILL"`. | None structurally, but the batch import orchestrator (new) issues one `TradeCapture` per contract. |
| `MarketDataPort` (S4) | DA clearing prices stored as fixings, queryable via `lookupFixing()`. | None -- existing port interface is sufficient. |
| Rollups (S7) | DA settlement cells roll up identically to other instruments. | None. |

### New Structures Required

| Structure | Purpose | Key fields | Notes |
|---|---|---|---|
| **AuctionImportSession** | Tracks a batch import of DA auction results. Provides idempotency and reconciliation. | `sessionId`, `tenantId`, `exchange` (EPEX_SPOT), `deliveryDay`, `biddingZone`, `importTimestamp`, `status` (PENDING, VALIDATED, IMPORTED, FAILED), `exchangeReportedTotalMwh`, `importedTotalMwh`, `fileReference` | Not a position entity -- an operational tracking record. |
| **BlockDefinition** | Configurable block type per exchange. Reference data. | `blockId`, `exchange`, `blockType` (BASELOAD, PEAK, OFF_PEAK, CUSTOM), `intervalSpec` (start hour, end hour, day-of-week mask), `holidayCalendarRef`, `effectiveFrom`, `effectiveTo` | Reference data entity. Peak hours (08:00-20:00 CET Mon-Fri ex holidays) must be configurable per exchange and zone. |
| **HolidayCalendar** | Per-zone public holiday calendar for peak/off-peak determination. | `calendarId`, `zone`, `date`, `holidayName`, `year` | Reference data. Consumed by block decomposition (DA-VOL-02) and peak classification (FR-027). May already be partially modeled via MarketCalendar (FR-024). |
| **NominationRecord** | Per-interval nominated volume for TSO scheduling. | `nominationId`, `tenantId`, `balancingGroupId`, `deliveryDay`, `intervalStart` (UTC), `intervalEnd` (UTC), `nominatedVolumeMw`, `nominationTimestamp` (UTC), `nominationVersion` | New entity. Not a position ledger entity -- it is a separate volume tracking layer per DA-VOL-03. |
| **BalancingGroup** | Reference entity for BRP's balancing groups. | `bgId`, `tenantId`, `tsoArea`, `bgCode` (official TSO-assigned code), `activeFrom`, `activeTo` | Reference data. Maps assets/portfolios to BGs. |
| **AssetToBalancingGroupMapping** | Maps assets/delivery points to the responsible balancing group. | `mappingId`, `tenantId`, `assetId`/`deliveryPointId`, `balancingGroupId`, `effectiveFrom`, `effectiveTo` | Reference data. Used by DA-VOL-03 to route nominations. |
| **ImbalanceRecord** | Per-interval imbalance settlement. | `recordId`, `tenantId`, `balancingGroupId`, `intervalStart` (UTC), `intervalEnd` (UTC), `nominatedVolumeMw`, `actualDeliveredMw`, `imbalanceVolumeMw`, `imbalancePriceMwh`, `imbalanceAmount`, `currency`, `tsoDataSource`, `tsoPublicationTimestamp` | New entity. Separate settlement stream from energy settlement (DA-SET-04). |
| **ExchangeFeeSchedule** | Configurable fee rates per exchange with effective dates. | `scheduleId`, `tenantId`, `exchange`, `feeType` (TRADING, CLEARING), `ratePerMwh`, `memberTier`, `effectiveFrom`, `effectiveTo` | Reference data. Supports per-tenant tier assignments and periodic fee changes (DA-SET-03). |
| **CashflowRecord** | Settlement cashflow for treasury/liquidity forecasting. | `cashflowId`, `tenantId`, `amount`, `currency`, `direction` (PAY/RECEIVE), `paymentDate`, `counterpartyId` (ECC), `cashflowType` (ENERGY_SETTLEMENT, EXCHANGE_FEE, IMBALANCE_SETTLEMENT), `sourceTradeIds`, `deliveryDay` | New entity. Feeds PORT-10. May already be partially modeled outside the position/valuation module. |
| **TARGET2Calendar** | Banking calendar for payment date computation. | `date`, `isBusinessDay` | Reference data. Industry-standard calendar published by ECB annually. |

### Reference Data Dependencies

| Ref data | Source | Used by | Exists in platform? |
|---|---|---|---|
| EPEX bidding zone list (DE_LU, FR, AT, NL, BE, ...) | EPEX / ENTSO-E | DA-VOL-01 zone validation | Partially -- `DeliveryPoint` hierarchy (FR-022) |
| Holiday calendar per zone | National holiday lists | DA-VOL-02 block decomposition | No -- needs to be added |
| Block type definitions per exchange | EPEX contract specifications | DA-VOL-02 | No -- needs to be added |
| TARGET2 calendar | ECB | DA-SET-02 payment date | No -- needs to be added |
| EPEX/ECC fee schedule | Exchange membership agreement | DA-SET-03 | No -- needs to be added |
| TSO balancing area definitions | TSO (50Hertz, Amprion, TenneT, TransnetBW) | DA-SET-04 imbalance | No -- needs to be added |
| reBAP imbalance price series | TSO / ENTSO-E transparency platform | DA-SET-04 | Ingestible via MarketDataPort as a fixing series |
| DA clearing price series per zone | EPEX | DA-PRC-01 | Ingestible via MarketDataPort as a fixing series |

---

## DST Handling

DST handling is **directly applicable and critical** for DA. Every DA requirement touches time-series at interval granularity.

### Interval Counts on DST Transition Days

Per FR-024/FR-025, the MarketCalendar is the sole authority. DA must respect:

| Day type | Hourly contracts | 15-min contracts | Total hours |
|---|---|---|---|
| Normal | 24 | 96 | 24 |
| Spring-forward (last Sun Mar, CET->CEST) | 23 | 92 | 23 |
| Fall-back (last Sun Oct, CEST->CET) | 25 | 100 | 25 |

### DA-VOL-01 (Auction Result Import) on DST Days

**Spring-forward (e.g., 2027-03-28):**
- EPEX publishes 23 hourly / 92 quarter-hourly contracts for this delivery day.
- The batch import must expect 23/92 contracts, not 24/96.
- Validation: sum of 92 QH volumes must equal the exchange total.
- The hour 02:00-03:00 CET does not exist. If the exchange feed contains a contract referencing 02:00-03:00 CET, it must be rejected as invalid.

**Fall-back (e.g., 2026-10-25):**
- EPEX publishes 25 hourly / 100 quarter-hourly contracts.
- The batch import must expect 25/100 contracts.
- The hour 02:00-03:00 occurs twice. EPEX uses the convention "02:00 CEST" (first occurrence, summer time, UTC+2) and "02:00 CET" (second occurrence, winter time, UTC+1).
- The import parser must distinguish these two hours using the UTC offset or the exchange's ordinal labeling convention.
- Each occurrence is a separate tradeable contract with its own price and volume.

### DA-VOL-02 (Block Decomposition) on DST Days

**Spring-forward:**
- A baseload block for the spring-forward day covers 23 hours / 92 QH intervals, not 24/96.
- Total energy for a 20 MW baseload block = 20 x 23 = 460 MWh, not 480 MWh.
- A peak block that would normally include 02:00-03:00 as off-peak still produces the correct interval count (the missing hour is excluded naturally since it does not exist).

**Fall-back:**
- A baseload block for the fall-back day covers 25 hours / 100 QH intervals.
- Total energy for a 20 MW baseload block = 20 x 25 = 500 MWh.
- Peak classification: the duplicate hour 02:00-03:00 falls in off-peak regardless of which occurrence, so peak blocks are unaffected. But if custom blocks include 02:00-03:00, both occurrences must be included.

### DA-SET-01/04 (Settlement) on DST Days

- Settlement must compute over the correct number of intervals (92 or 100).
- `interval_hours` remains 0.25 for each 15-min interval and 1.0 for each hourly interval regardless of DST -- the interval duration is fixed, only the count changes.
- Daily settlement totals must reflect the 23 or 25-hour day.

### DA-SET-04 (Imbalance) on DST Days

- Imbalance prices (reBAP) are published per 15-min interval. On DST transition days, the TSO publishes 92 or 100 values.
- The system must match imbalance prices to nomination/delivery intervals using UTC interval boundaries, not local-time labels, to avoid ambiguity on the fall-back day.

### Storage and Reporting

- All timestamps (interval boundaries, nomination timestamps, import timestamps) are stored as UTC (`Instant` / `TIMESTAMP WITH TIME ZONE`) per the persistence layer convention.
- REMIT transaction reports require UTC timestamps -- confirmed compatible.
- Display layer shows CET/CEST alongside UTC for all time-critical views.
- On the fall-back day, the interval grid labels the duplicate hour as "02:00 CEST" / "02:00 CET" (or "02:00A" / "02:00B") per the presentation layer convention.
- On the spring-forward day, the interval grid skips 02:00-03:00 with a visual indicator.
- Daily aggregation boundaries: a "delivery day" is defined in CET/CEST. On spring-forward, the CET day spans 23:00 UTC (previous day) to 22:00 UTC. On fall-back, it spans 22:00 UTC to 23:00 UTC (next day). All queries for "delivery day" must compute the correct UTC boundaries.

---

## Edge Cases

### Cross-Timezone

1. **Multi-zone tenants.** A tenant trading in both DE_LU and NO1 (Nord Pool) faces different DA auction gate closures, different clearing price publication times, and different holiday calendars. The system must handle concurrent imports for different zones without interference. Each zone's import session is independent.

2. **CET vs UTC in EPEX feeds.** EPEX timestamps in execution reports use CET/CEST. The import parser must convert to UTC for storage. The parser must know the DST state on the delivery day to perform the conversion correctly.

### Gate Closure Windows

3. **Late import.** If the auction results are imported after gate closure for nomination (14:30 CET D-1), the system should flag that the nomination window has passed. The trades are still captured, but the scheduling warning is elevated.

4. **Gate closure on DST transition.** On the spring-forward and fall-back days, gate closure at 14:30 CET/CEST corresponds to a different UTC instant. The system must convert gate closure times using the correct offset for the day in question.

### Corrections and Cancellations

5. **Exchange trade correction.** EPEX may issue corrections to auction results (rare but possible). Corrections must create a new bitemporal version of the affected trade-legs (tradeVersion incremented), superseding the original via the existing `DefaultTradeCaptureHandler` supersession logic. The original version is closed (`knownTo` set) but not deleted -- it remains available for regulatory reconstruction.

6. **Partial cancellation of block order.** If a block order is cancelled after decomposition, all constituent interval trade-legs must be cancelled atomically. The cancellation propagates through the existing `DefaultTradeCancelHandler`.

### Backdated Trades

7. **Late delivery of exchange execution report.** If the EPEX feed arrives after D-1 (e.g., due to system outage), the import must still proceed. The `businessEffectiveDate` on the `TradeCapture` command is set to the original auction execution time, not the import time. The `knownFrom` reflects when the system learned of the trade.

8. **TSO imbalance data corrections.** The TSO may revise metered delivery data weeks or months after delivery. The imbalance settlement must support re-computation with corrected volumes. Each correction creates a new version of the `ImbalanceRecord` with a reason code.

### Multi-Currency

9. **Currency scope.** EPEX DA clears exclusively in EUR. No multi-currency handling is needed for DA energy settlement. However, if the tenant's reporting currency differs from EUR, the P&L reporting layer must apply FX conversion. Exchange fees are also in EUR.

### Multi-Tenant Isolation

10. **Auction import isolation.** Each tenant's auction import session is tenant-scoped. One tenant's import failure must not affect another tenant's import. The `AuctionImportSession` is tenant-partitioned.

11. **Fee schedule isolation.** Fee schedules are per-tenant (different membership tiers). One tenant's fee configuration must not be visible to or affect another tenant.

12. **Balancing group isolation.** Balancing groups and asset-to-BG mappings are tenant-scoped. A BG code (e.g., "11XBALGRP-001--A") may appear in multiple tenants (different companies operating similar BG structures) and must be treated as distinct.

### Negative Price Edge Cases

13. **Negative price and fees.** Exchange fees are computed on absolute volume regardless of price sign. A BUY at -50 EUR/MWh still incurs the standard per-MWh fee.

14. **Negative price and imbalance.** If the DA price is negative and the imbalance price is also negative, the sign arithmetic must be correct. The imbalance settlement formula `(Nominated - Actual) x imbalancePrice` works correctly with negative imbalance prices -- a negative imbalance price for over-delivery means the over-delivering party pays, not receives.

### Volume and Rounding

15. **Sub-MWh volumes.** DA contracts may have fractional MW volumes (e.g., 12.5 MW). The system must support decimal volumes at the precision defined by `NumericPrecision.VOLUME`.

16. **Settlement rounding.** Per ECC clearing rules, settlement amounts are rounded to 2 decimal places (EUR cents). Rounding is applied per interval line item, not to the daily total. This means the sum of rounded interval amounts may differ slightly from rounding the unrounded total -- the system uses the per-interval rounded approach to match ECC's calculation.

### Auction Re-Run

17. **EPEX auction re-run.** In exceptional cases (system failure, market coupling failure), EPEX may re-run the DA auction. The re-run produces a new set of clearing prices and volumes, superseding the original. The system must support importing re-run results as a correction (new tradeVersion), following the same supersession logic as edge case #5.

---

## DA-OPS-01: Operational Alerts Dashboard

### Problem Statement

Multiple DA lifecycle events raise alerts that require attention from different desks (Operations, Middle Office, Risk, Back Office). Without a consolidated view, alerts are scattered across subsystems and may be missed or delayed. The platform needs a categorized alerts dashboard that aggregates all DA-related alerts by category, severity, and status.

### Alert Categories

| Category | Alert type | Source event | Severity | Primary audience |
|---|---|---|---|---|
| **Auction Ingestion** | Import validation failed (volume mismatch) | BE-06 | CRITICAL | Middle Office |
| **Auction Ingestion** | Import parse error (malformed file) | BE-06 | CRITICAL | Operations |
| **Auction Ingestion** | Idempotent re-import detected (no-op) | BE-05 | INFO | Operations |
| **Auction Ingestion** | Late import (after nomination gate closure) | Edge #3 | WARNING | Operations, Risk |
| **Auction Ingestion** | Auction re-run supersession | Edge #17 | WARNING | Middle Office |
| **DA Clearing Prices** | Price outside plausibility range | BE-19 | WARNING | Middle Office |
| **DA Clearing Prices** | Negative clearing price | DA-PRC-02 | INFO | Trading, Risk |
| **DA Clearing Prices** | Price feed missing/delayed | (new) | CRITICAL | Operations |
| **Nomination / Scheduling** | Nomination deviation detected | BE-10 | WARNING | Operations, Risk |
| **Nomination / Scheduling** | Nomination gate closure approaching (< 1h) | (new) | INFO | Operations |
| **Nomination / Scheduling** | Nomination not yet submitted for delivery day | (new) | WARNING | Operations |
| **Imbalance Settlement** | TSO imbalance data received | BE-15 | INFO | Back Office |
| **Imbalance Settlement** | TSO data correction (revised metered delivery) | Edge #8 | WARNING | Back Office |
| **Imbalance Settlement** | High imbalance cost for interval (configurable threshold) | DA-SET-04 | WARNING | Risk |
| **Exchange Fees** | Fee schedule approaching expiry | (new) | INFO | Back Office |
| **Settlement** | Settlement amount exceeds daily threshold | (new) | WARNING | Risk |

### Acceptance Criteria

**Scenario 1: Alert dashboard displays categorized alerts**

```
Given the DA lifecycle has produced alerts across multiple categories
  And Operations user navigates to the DA Alerts dashboard
When the dashboard loads
Then alerts are grouped by category (Auction Ingestion, DA Clearing Prices, Nomination/Scheduling, Imbalance Settlement, Exchange Fees, Settlement)
  And each alert shows: timestamp (UTC + CET), severity (CRITICAL/WARNING/INFO), category, message, delivery day, bidding zone
  And alerts are sorted by severity (CRITICAL first) then by timestamp (newest first) within each category
  And unacknowledged alert counts are visible per category as badges
```

**Scenario 2: Alert filtering and acknowledgement**

```
Given the dashboard shows 15 alerts across 4 categories
When the user filters by severity = CRITICAL
Then only CRITICAL alerts are displayed
  And the category grouping is preserved
When the user acknowledges a CRITICAL alert
Then the alert status changes to ACKNOWLEDGED
  And the acknowledging user and timestamp are recorded
  And the unacknowledged count decreases by one
```

**Scenario 3: Alert retention and auto-resolution**

```
Given a WARNING alert for "Nomination not yet submitted" for delivery day 2026-09-16
When the nomination is submitted for that delivery day
Then the alert is auto-resolved with status RESOLVED
  And a resolution timestamp is recorded
  And resolved alerts are hidden by default but viewable via a "Show resolved" toggle
```

**Scenario 4: Multi-tenant alert isolation**

```
Given tenant A has 3 CRITICAL alerts for a failed import
  And tenant B has 0 alerts
When tenant B's user views the alerts dashboard
Then zero alerts are displayed
  And tenant A's alerts are never visible to tenant B (D-14)
```

### Data Model Impact

| Structure | Purpose | Key fields |
|---|---|---|
| **OperationalAlert** | Persisted alert record | `alertId` (UUID), `tenantId`, `category` (enum), `severity` (CRITICAL/WARNING/INFO), `alertType` (enum per category), `message` (text), `deliveryDay`, `biddingZone`, `sourceEventId` (correlation), `raisedAt` (UTC), `acknowledgedBy`, `acknowledgedAt`, `resolvedAt`, `status` (OPEN/ACKNOWLEDGED/RESOLVED) |

### Open Questions

| # | Question | Impact |
|---|---|---|
| OQ-DA-OPS-1 | Should alerts be persisted in the position/valuation module or published as events to a separate operational monitoring system? | Determines whether OperationalAlert is a pv-domain entity or an outbound event to an ops platform. |
| OQ-DA-OPS-2 | Should the dashboard support email/webhook notifications for CRITICAL alerts, or is a pull-based UI sufficient for v1? | Determines whether a notification subsystem is needed. |
| OQ-DA-OPS-3 | What are the configurable thresholds for "high imbalance cost" and "settlement exceeds daily threshold"? Are these per-tenant or system-wide? | Affects alert-raising logic and configuration model. |

---

## DA-UI: User Interface Requirements

### DA-UI-01: Auction Import Status Panel

**Purpose:** Operations triggers a DA batch import and needs to track progress, see validation results, and review any errors — all from a single panel.

**Scenario 1: Import in progress**

```
Given Operations has triggered a DA batch import for DE_LU, delivery day 2026-09-16
When the import status panel is displayed
Then the panel shows:
  - Exchange: EPEX SPOT
  - Bidding zone: DE_LU
  - Delivery day: 16 Sep 2026
  - Status: a progress indicator cycling through PENDING → VALIDATING → IMPORTING → IMPORTED
  - Progress bar or step indicator showing the current phase
  And the panel auto-refreshes (poll every 2 seconds) until a terminal state is reached
```

**Scenario 2: Import completed successfully**

```
Given a DA import for DE_LU, 2026-09-16 has completed with status IMPORTED
When the import status panel is displayed
Then the panel shows:
  - Status: IMPORTED (green badge)
  - Trade count: 96
  - Exchange reported total: 12,450.00 MWh
  - Imported total: 12,450.00 MWh (matching = green, mismatch = red)
  - Import duration: 3.2s
  - File reference: epex_da_20260916_delu.csv
  And a "View Trades" link navigates to the DA position grid filtered for this delivery day
```

**Scenario 3: Import validation failed**

```
Given a DA import for DE_LU, 2026-09-16 has failed validation
When the import status panel is displayed
Then the panel shows:
  - Status: VALIDATION_FAILED (red badge)
  - Error list displayed in a scrollable area:
    - "Volume mismatch: expected 12,450.00 MWh, got 12,440.00 MWh (delta: -10.00 MWh)"
    - "Contract C-042: delivery period 02:00-03:00 CET does not exist on spring-forward day"
  And a "Retry Import" button is available (triggers a new import with the same file)
  And no trade IDs are shown (no trades were created)
```

**Scenario 4: Import history list**

```
Given Operations navigates to the DA import history view
Then a table shows all import sessions for the current tenant, sorted newest first:
  | Delivery Day | Zone  | Status           | Trades | Total MWh   | Imported At         |
  | 16 Sep 2026  | DE_LU | IMPORTED         | 96     | 12,450.00   | 2026-09-15 12:45 UTC|
  | 15 Sep 2026  | DE_LU | IMPORTED         | 96     | 11,830.50   | 2026-09-14 12:43 UTC|
  | 15 Sep 2026  | FR    | VALIDATION_FAILED| 0      | —           | 2026-09-14 12:44 UTC|
  And each row is clickable to open the detail panel (Scenarios 2/3)
  And the list supports filtering by status, zone, and date range
```

### DA-UI-02: DA Settlement Grid (Interval-Level)

**Purpose:** Trading and Back Office view the settlement detail for a DA delivery day — every interval's price, volume, and settlement amount.

**Scenario 1: Standard delivery day view**

```
Given the user selects delivery day 2026-09-16, zone DE_LU
When the DA settlement grid is displayed
Then the grid shows one row per quarter-hour interval (96 rows on a normal day):
  | Interval (CET)   | Price (EUR/MWh) | Volume (MW) | Dir | Energy (MWh) | Amount (EUR) | Status  |
  | 00:00 - 00:15     | 45.20           | 30.0        | BUY | 7.50         | 339.00       | SETTLED |
  | 00:15 - 00:30     | 44.80           | 30.0        | BUY | 7.50         | 336.00       | SETTLED |
  | ...               | ...             | ...         | ... | ...          | ...          | ...     |
  | 23:45 - 00:00     | 52.10           | 25.0        | BUY | 6.25         | 325.63       | SETTLED |
  And a summary row at the bottom shows:
    - Total energy: 720.00 MWh
    - Total settlement: 45,230.50 EUR
    - VWAP (volume-weighted avg price): 62.82 EUR/MWh
  And the Direction column uses colored badges (green BUY, red SELL) matching the existing PositionLedger pattern
```

**Scenario 2: Negative price highlighting**

```
Given interval 14:00-14:15 has a clearing price of -25.50 EUR/MWh
When the DA settlement grid is displayed
Then the price cell for 14:00-14:15 shows "-25.50" in a distinct warning color (amber/orange text)
  And the amount cell shows the correct signed value:
    - BUY 50 MW: -318.75 EUR (credit — displayed in green with "CR" suffix)
    - SELL 30 MW: +191.25 EUR (debit — displayed in standard text)
  And a tooltip on the price cell reads "Negative clearing price — buyer receives credit"
```

**Scenario 3: DST spring-forward day**

```
Given delivery day 2027-03-28 (spring-forward, CET → CEST)
When the DA settlement grid is displayed
Then the grid shows 92 rows (23 hours × 4 QH)
  And the interval 01:45-02:00 CET is followed by 03:00-03:15 CEST
  And a visual separator row between 01:45 and 03:00 displays "⏭ DST spring-forward: 02:00-03:00 CET skipped"
  And the day summary shows total hours = 23
```

**Scenario 4: DST fall-back day**

```
Given delivery day 2026-10-25 (fall-back, CEST → CET)
When the DA settlement grid is displayed
Then the grid shows 100 rows (25 hours × 4 QH)
  And the duplicate hour 02:00-03:00 is displayed as:
    - 02:00 CEST, 02:15 CEST, 02:30 CEST, 02:45 CEST (first occurrence, summer time)
    - 02:00 CET,  02:15 CET,  02:30 CET,  02:45 CET  (second occurrence, winter time)
  And a visual separator row between the two occurrences displays "⏮ DST fall-back: 02:00-03:00 repeated"
  And the day summary shows total hours = 25
```

**Scenario 5: Multiple trades per interval**

```
Given interval 10:00-10:15 has two trades:
  - Trade A: BUY 50 MW at 72.50 EUR/MWh
  - Trade B: SELL 20 MW at 72.50 EUR/MWh
When the DA settlement grid is displayed
Then each trade is shown as a separate row (per gross per-trade-leg settlement)
  And the interval column groups both rows under "10:00 - 10:15"
  And Trade A shows amount +906.25 EUR (pay)
  And Trade B shows amount -362.50 EUR (receive)
  And the day summary shows the net across all trades
```

### DA-UI-03: Nomination Workbench

**Purpose:** Operations views nominated vs traded volumes side-by-side to identify deviations before and after delivery.

**Scenario 1: Nomination comparison view**

```
Given DA trades have been captured for delivery day 2026-09-16 DE_LU
  And nominations have been recorded for BG-DE-001
When the nomination workbench is displayed
Then the grid shows one row per quarter-hour interval:
  | Interval (CET)   | Traded (MW) | Nominated (MW) | Deviation (MW) | Status    |
  | 00:00 - 00:15     | 30.0        | 30.0           | 0.0            | OK        |
  | 00:15 - 00:30     | 30.0        | 28.0           | -2.0           | DEVIATION |
  | ...               | ...         | ...            | ...            | ...       |
  And deviation cells are color-coded:
    - 0.0: no highlight (OK)
    - ±0.1 to ±5.0: amber (minor deviation)
    - > ±5.0: red (significant deviation)
  And a summary bar shows:
    - Total traded: 720.00 MWh
    - Total nominated: 712.50 MWh
    - Net deviation: -7.50 MWh
    - Intervals with deviation: 3 of 96
```

**Scenario 2: Nomination not yet submitted**

```
Given DA trades exist for delivery day 2026-09-17
  And no nominations have been recorded for that day
When the nomination workbench is displayed for 2026-09-17
Then the "Nominated" column shows "—" for all intervals
  And a banner at the top reads "Nominations not yet submitted for 17 Sep 2026"
  And the banner severity is:
    - INFO if nomination gate closure is > 1 hour away
    - WARNING if nomination gate closure is < 1 hour away
    - CRITICAL if gate closure has passed
```

**Scenario 3: Balancing group filter**

```
Given the tenant has two balancing groups: BG-DE-001 and BG-DE-002
When the nomination workbench is displayed
Then a dropdown allows selecting the balancing group
  And the grid filters to show only nominations for the selected BG
  And "All BGs" shows an aggregated view with BG as a grouping column
```

### DA-UI-04: Imbalance Settlement View

**Purpose:** Back Office and Risk view imbalance settlement results per interval and at monthly aggregation level.

**Scenario 1: Daily imbalance detail**

```
Given imbalance settlement has been computed for delivery day 2026-09-16, BG-DE-001
When the daily imbalance view is displayed
Then the grid shows one row per quarter-hour interval:
  | Interval (CET)   | Nominated (MW) | Actual (MW) | Imbalance (MW) | reBAP (EUR/MWh) | Amount (EUR) |
  | 00:00 - 00:15     | 30.0           | 30.5        | +0.5           | 85.00            | +10.63       |
  | 00:15 - 00:30     | 28.0           | 27.0        | -1.0           | 92.00            | -23.00       |
  | ...               | ...            | ...         | ...            | ...              | ...          |
  And positive imbalance (over-delivery) amounts are green (receipt from TSO)
  And negative imbalance (under-delivery) amounts are red (payment to TSO)
  And a summary bar shows:
    - Net imbalance energy: -12.50 MWh
    - Net imbalance cost: -1,450.00 EUR (net payment to TSO)
    - Max interval imbalance: -3.0 MW at 18:15-18:30
```

**Scenario 2: Monthly aggregation view**

```
Given the user selects month view for September 2026, BG-DE-001
When the monthly imbalance view is displayed
Then the view shows one row per delivery day:
  | Delivery Day | Net Imbalance (MWh) | Net Cost (EUR) | Max Deviation (MW) | Intervals w/ Imbalance |
  | 01 Sep 2026  | -5.25               | -620.00        | -2.0               | 12 of 96               |
  | 02 Sep 2026  | +3.00               | +245.50        | +1.5               | 8 of 96                |
  | ...          | ...                 | ...            | ...                | ...                    |
  And a monthly summary row shows:
    - Total net imbalance: -42.75 MWh
    - Total net cost: -5,230.00 EUR
  And this view is used for reconciliation against the TSO monthly invoice
  And each day row is clickable to drill into the daily interval detail (Scenario 1)
```

**Scenario 3: TSO data correction indicator**

```
Given TSO has issued a correction for delivery day 2026-09-05
  And the imbalance records have recordVersion = 2
When the daily imbalance view is displayed for 2026-09-05
Then a banner reads "TSO data corrected on 2026-09-12 (version 2)"
  And the original values (version 1) are available via a "Show original" toggle
  And delta between original and corrected amounts is shown in a separate column when toggled
```

### DA-UI-05: Exchange Fee Summary

**Purpose:** Back Office views exchange fees per delivery day for reconciliation against ECC invoices.

**Scenario 1: Daily fee breakdown**

```
Given exchange fees have been computed for delivery day 2026-09-16
When the exchange fee view is displayed
Then the view shows:
  | Fee Type | Rate (EUR/MWh) | Gross Volume (MWh) | Fee Amount (EUR) |
  | Trading  | 0.05           | 700.00             | 35.00            |
  | Clearing | 0.02           | 700.00             | 14.00            |
  | **Total**| —              | —                  | **49.00**        |
  And a note reads "Fees computed on gross (absolute) volume: BUY 500 MWh + SELL 200 MWh = 700 MWh"
  And the fee schedule effective date and member tier are displayed
```

### DA-UI-06: Operational Alerts Dashboard

**Purpose:** All desks view a consolidated, categorized list of DA operational alerts. See DA-OPS-01 for the alert taxonomy and lifecycle.

**Scenario 1: Dashboard layout**

```
Given the user navigates to the DA Alerts dashboard
When the dashboard loads
Then a category strip shows badge counts per category:
  [Auction Ingestion (2)] [DA Clearing Prices (1)] [Nomination (3)] [Imbalance (0)] [Fees (0)] [Settlement (0)]
  And clicking a category badge filters the alert list to that category
  And the default view shows all open alerts sorted by severity (CRITICAL first), then by timestamp (newest first)
```

**Scenario 2: Alert row content**

```
Given an alert exists: CRITICAL / AUCTION_INGESTION / "Import validation failed: volume mismatch -10 MWh"
When the alert is displayed in the list
Then each alert row shows:
  | Severity | Category           | Message                                                | Delivery Day | Zone  | Time (UTC)          | Status |
  | 🔴 CRIT  | Auction Ingestion  | Import validation failed: volume mismatch -10 MWh     | 16 Sep 2026  | DE_LU | 2026-09-15 12:45:00 | OPEN   |
  And severity is indicated by color:
    - CRITICAL: red background/border
    - WARNING: amber background/border
    - INFO: blue or neutral background
  And clicking the row expands to show:
    - Source event ID (correlation)
    - Full error details
    - "Acknowledge" button (if OPEN)
    - "Resolve" button (if ACKNOWLEDGED)
```

**Scenario 3: Alert filtering**

```
Given the dashboard shows 15 alerts across 4 categories
When the user applies filters:
  - Severity: CRITICAL + WARNING (exclude INFO)
  - Date range: last 7 days
  - Status: OPEN only
Then only matching alerts are displayed
  And the category badge counts update to reflect filtered results
  And a "Clear filters" link restores the default view
```

**Scenario 4: Keyboard navigation**

```
Given the alerts list is focused
When the user presses:
  - Arrow Down / Arrow Up: move focus between alert rows
  - Enter: expand/collapse the focused alert detail
  - A: acknowledge the focused alert (if OPEN)
  - R: resolve the focused alert (if ACKNOWLEDGED)
  - Escape: collapse detail / clear selection
Then all actions are keyboard-accessible (WCAG 2.2 AA)
  And a screen reader announces alert severity, category, and message on focus
```

### DA-UI-07: DA Summary KPI Tiles

**Purpose:** A compact overview strip at the top of any DA view showing key daily aggregates.

**Scenario 1: KPI tile content**

```
Given the user is viewing DA data for delivery day 2026-09-16, zone DE_LU
When the KPI tile strip is displayed
Then it shows the following tiles (reusing the existing KpiTile component):
  | Tile Label          | Value        | Unit     | Color logic                          |
  | Net Volume          | +300.00      | MWh      | Green if net BUY, red if net SELL    |
  | VWAP                | 62.82        | EUR/MWh  | Neutral                              |
  | Settlement Total    | 45,230.50    | EUR      | Neutral                              |
  | Exchange Fees       | 49.00        | EUR      | Neutral                              |
  | Imbalance Cost      | -1,450.00    | EUR      | Green if receipt, red if payment     |
  | Open Alerts         | 2            | —        | Red if CRITICAL, amber if WARNING    |
  And each tile is clickable to navigate to its corresponding detail view
```

### DA-UI-08: Navigation and Integration

**Scenario 1: DA section in main navigation**

```
Given the user is on the main dashboard
When the user navigates to the DA section
Then a sidebar or tab group shows:
  - Import (DA-UI-01)
  - Settlement (DA-UI-02)
  - Nominations (DA-UI-03)
  - Imbalance (DA-UI-04)
  - Fees (DA-UI-05)
  - Alerts (DA-UI-06)
  And the currently active section is highlighted
  And the URL updates to reflect the active section (deep-linkable)
```

**Scenario 2: Cross-navigation from existing dashboard**

```
Given the user is on the existing portfolio rollup grid (S7)
  And a rollup cell shows DA trades contributing to the period
When the user clicks on a rollup cell and drills into the position ledger (L3)
  And selects a DA trade (originType = "EXCHANGE_FILL")
Then a "View DA Settlement" action is available
  And clicking it navigates to DA-UI-02 filtered for that trade's delivery day and zone
```

**Scenario 3: Shared components**

```
The DA UI reuses the following existing primitives:
  - NumericCell: for all monetary, MW, MWh, and price values
  - StatusBadge: for import status, delivery status, alert severity
  - KpiTile: for DA-UI-07 summary tiles
  - SkeletonTable: for loading states
  - EmptyState: for empty grids
  - HideZeroToggle: for filtering zero-value rows in settlement and imbalance grids
  - SelectAllCheckbox / RowCheckbox: for multi-select in settlement grid
  - GranularityToggle: for switching between QH and hourly aggregation in settlement grid

New primitives needed:
  - ProgressStepper: multi-step progress indicator for import status (PENDING → VALIDATING → IMPORTING → IMPORTED)
  - DeviationCell: numeric cell with threshold-based coloring (OK / minor / significant)
  - CategoryBadgeStrip: horizontal row of category badges with counts (for alerts dashboard)
  - DST separator row: visual row indicating skipped or repeated hours
```

### DA-UI-09: Accessibility Requirements

All DA screens must meet WCAG 2.2 AA, consistent with the existing dashboard:

1. **Grid navigation:** Arrow keys move between rows, Enter activates/drills, Space toggles selection, Escape closes detail panels. Tab order follows logical reading order.
2. **Screen reader:** All grids use `role="grid"` with `aria-rowcount`, `aria-colcount`, `aria-label`. Alert severity is announced on focus. Status transitions are announced via `aria-live="polite"` regions.
3. **Color independence:** Severity and direction indicators use text labels or icons in addition to color (e.g., "CRIT" text, "BUY"/"SELL" text, "CR" suffix for credits). Negative prices show text "(neg)" alongside color.
4. **Focus management:** When navigating between panels (e.g., import history → import detail), focus moves to the heading of the new panel. When a modal or detail panel closes, focus returns to the trigger element.
5. **Keyboard shortcuts:** Documented in a help tooltip accessible via `?` key. All shortcuts have visible affordances (e.g., underlined first letter on buttons).

---

## Open Questions

| # | Question | Impact | Suggested owner |
|---|---|---|---|
| OQ-1 | **Batch import interface.** What is the exact format of the EPEX execution report? Is it CSV, XML, or API (REST/SFTP)? Does the platform need to support multiple exchange feed formats (EPEX vs Nord Pool)? | Determines the import parser design. | Solutions Architect + Exchange connectivity team |
| OQ-2 | **One deal per interval vs one deal per auction session.** DA-VOL-01 says "one deal per executed contract." Should each 15-min contract be a separate trade (tradeId), or should the daily auction session be one trade with 96 trade-legs? The ledger supports both, but the choice affects deal count, regulatory reporting grain, and the batch import flow. 50-200 deals/day (per the requirement) suggests one deal per interval. | Affects Position Ledger entry count and REMIT reporting. The current `TradeCapture` command creates one entry per leg per delivery month -- a single-interval DA trade creates exactly one entry. | Functional Expert + Compliance |
| OQ-3 | **MarketCalendar: is it implemented?** FR-024/025 define the MarketCalendar as the sole authority for interval structure. Is there an existing implementation, or does this need to be built? Block decomposition (DA-VOL-02) and DST handling depend on it. | Blocks implementation of DA-VOL-02 and DST-correct import validation. | Solutions Architect |
| OQ-4 | **Nomination tracking scope.** Is nomination tracking (DA-VOL-03) in scope for the position/valuation module, or does it belong to a separate scheduling/nominations module? The functional spec (FR-021) explicitly states "Nomination/schedule/forecast/actual/imbalance are NOT position origins; they are volume-series layers." | Determines whether NominationRecord lives in pv-domain or in a separate module. | Functional Expert |
| OQ-5 | **Imbalance settlement scope.** DA-SET-04 describes a full imbalance settlement cycle (TSO data ingestion, monthly invoicing). Is this in scope for the position/valuation module, or is it a separate settlement/invoicing module that consumes position data? | Determines whether ImbalanceRecord and the monthly aggregation logic belong here. | Functional Expert + Product |
| OQ-6 | **Cashflow generation scope.** DA-SET-02 describes cashflow records feeding PORT-10 (treasury/liquidity forecasting). Does PORT-10 exist? Is the CashflowRecord defined here, or is it an interface to an external treasury system? | Determines whether CashflowRecord is a new entity in pv-domain or an outbound event. | Solutions Architect + Product |
| OQ-7 | **Reference price configuration.** DA-VAL-01 says "configurable reference price for P&L calculation." Where does this configuration live? Is it per-portfolio, per-instrument-type, or per-trade? How does this interact with the existing `marketPriceExpressionId` on the ledger entry? | Affects P&L computation and the SettlementCell.pnl calculation. The existing `marketPriceExpressionId` may already serve this purpose. | Functional Expert |
| OQ-8 | **Cross-instrument P&L linkage.** DA-VAL-02 requires linking an ID adjustment trade to the original DA position. How is this linkage established? By explicit user association, by automatic matching on delivery interval + portfolio, or by a parent-child trade relationship? | Affects data model -- may require a trade-linkage or position-linkage entity. The existing `cascadeParentId` on the ledger is for cascade expansion, not instrument-to-instrument linkage. | Functional Expert |
| OQ-9 | **EPEX member identification.** For REMIT reporting, the platform needs to know the tenant's EPEX member identity (LEI, EIC code). Where is this stored? Is it a tenant-level configuration or a per-exchange-membership entity? | Affects REMIT transaction report generation. | Solutions Architect + Compliance |
| OQ-10 | **DA price as market data series or as trade attribute.** DA-PRC-01 says prices are matched to traded contracts. Should the DA clearing price also be stored as a market data fixing series (for use as marketPrice in P&L, and as a benchmark for other instruments' valuation)? Or is the trade-level price sufficient? The spec says "DA price serves as the benchmark reference for most other instruments' P&L calculations" -- this implies it must exist as a market data series. | Determines whether DA prices are dual-stored (trade attribute + market data series) or single-stored with a cross-reference. | Solutions Architect |
| OQ-11 | **Peak hour definition source.** DA-VOL-02 references peak hours as "08:00-20:00 CET Mon-Fri, excluding holidays." Is this the EPEX peak definition, the EEX peak definition, or configurable per exchange? Different exchanges define peak slightly differently. | Affects BlockDefinition reference data. | Functional Expert + Market Ops |
| OQ-12 | **Imbalance price sign convention.** The German reBAP can be positive or negative. What is the sign convention? Is a positive reBAP always a cost to the short party (under-deliverer), or does it follow the ENTSO-E settlement sign convention where the direction depends on the system balance? The spec must clarify the sign arithmetic to avoid settlement errors. | Critical for correct imbalance settlement (DA-SET-04). | Functional Expert + Regulatory |
| OQ-13 | **Existing SettlementCell.valuationType values.** The existing `SettlementCell` has a `valuationType` field (String). What values are currently defined? DA needs "REALIZED" or similar. Does the existing materialization pipeline already populate this for delivered intervals? | May require no changes if "REALIZED" is already a supported value. | Implementation team |
| OQ-14 | **RESOLVED — Volume netting at exchange level.** The platform settles gross per trade-leg (each position produces its own settlement cells per S5a). Exchange-level netting is performed by ECC on its daily invoice and is outside this system's scope. If the exchange feed delivers a pre-netted trade, it is captured as a single trade-leg. Portfolio-level aggregation is handled by rollups (S7). | Resolved. No impact on platform design. | — |
