# EU Power — Instrument Calculation Requirements

**Clustered by instrument type for functional specification generation.**  
Each section is self-contained and can be fed independently to a spec generation agent.  
Version 1.0 — May 2026

---

## 1. Day-Ahead (DA) — Exchange Spot

**Exchange:** EPEX Spot day-ahead auction. **Granularity:** Hourly (all zones) + 15-min (DE, AT, NL, BE, FR). **Settlement:** D+2 cash settlement via exchange clearing (ECC). **Delivery:** Physical, next calendar day.

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| DA-VOL-01 | Auction volume resolution | Volume per hourly or 15-min contract as executed in the DA auction. Fixed at auction close (12:00 CET D-1). No shaping needed — volume is at native market granularity. |
| DA-VOL-02 | Block order decomposition | Block orders (baseload, peak, off-peak, custom blocks) must be decomposed into their constituent hourly/15-min intervals for position aggregation. Block MW applies uniformly to all intervals in the block definition. |
| DA-VOL-03 | Nomination/scheduling volume | Volume nominated to TSO (via balancing group schedule) for physical delivery. Should match traded volume. Any deviation = pre-delivery imbalance. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| DA-PRC-01 | Auction clearing price | Market clearing price per hourly/15-min contract as published by EPEX post-auction. This IS the deal price — no further price determination needed. |
| DA-PRC-02 | Negative price handling | DA prices can go negative (frequent in high-wind/solar hours). System must handle negative €/MWh in all calculations without sign errors. A BUY at negative price = seller pays buyer. |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| DA-SET-01 | Gross settlement | `price[t] × volume[t] × interval_hours` per interval. Sum across all intervals for daily total. |
| DA-SET-02 | Payment due date | D+2 (EPEX ECC clearing). Exchange handles netting — platform tracks for cashflow forecasting. |
| DA-SET-03 | Exchange fees | EPEX trading fee + ECC clearing fee per MWh. Configured per exchange, applied to gross settlement. |
| DA-SET-04 | Imbalance settlement | `(Nominated[t] − Actual_delivered[t]) × imbalance_price[t]`. Imbalance price published by TSO post-delivery. Settled monthly by TSO to BRP. Can be positive or negative. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| DA-VAL-01 | Realized P&L | For DA, everything is realized at delivery. `(DA_price - portfolio_average_cost) × volume` or simply the gross cashflow if DA is the reference price. |
| DA-VAL-02 | Intraday P&L vs DA price | If the position was first established in DA and later adjusted in ID: `(ID_price - DA_price) × adjustment_volume`. |

> **Spec agent context:** DA is the simplest instrument. No MTM needed (settles immediately). No shaping needed (native granularity). No optionality. The primary complexity is imbalance settlement and block order decomposition.

---

## 2. Intraday (ID) — Exchange Continuous + Auction

**Exchange:** EPEX Spot intraday continuous (opens 15:00 D-1, closes 5 min before delivery) + XBID cross-border. **Granularity:** 15-min (DE, AT, NL, BE, FR), hourly (all). **Settlement:** Via ECC clearing. **Delivery:** Physical, same day or next day.

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| ID-VOL-01 | Trade-by-trade volume | Each ID trade is a separate execution with its own price and volume. Multiple trades can cover the same delivery interval. Volume = sum of all trades per interval. |
| ID-VOL-02 | Net position per interval | ID trades net against DA position. Net = DA_volume + Σ(ID_buy_volumes) − Σ(ID_sell_volumes) per interval. This is the final nominated position. |
| ID-VOL-03 | XBID cross-border volume | SIDC-coupled trades involve cross-border capacity allocation. Volume limited by available interconnector capacity. Track original zone and counterpart zone. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| ID-PRC-01 | Trade execution price | Each trade has its own execution price (continuous matching). No single clearing price — VWAP or last price used for reporting. |
| ID-PRC-02 | VWAP calculation | Volume-Weighted Average Price per delivery interval across all ID trades: `Σ(price[i] × volume[i]) / Σ(volume[i])`. Used for index settlement and reporting. |
| ID-PRC-03 | ID auction price | Germany-specific 15:00 ID auction. Single clearing price per 15-min contract, similar to DA mechanism. |
| ID-PRC-04 | Negative price handling | Same as DA — ID prices frequently go negative, especially close to delivery in high-renewables hours. |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| ID-SET-01 | Per-trade settlement | `price × volume × interval_hours` per trade. Aggregated for clearing. |
| ID-SET-02 | Payment due date | D+1 or D+2 depending on exchange/clearing rules. Track per trade. |
| ID-SET-03 | Imbalance settlement | Same as DA-SET-04. The final nominated position (DA + ID net) vs. actual metered delivery drives imbalance. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| ID-VAL-01 | Realized P&L | Like DA — fully realized at delivery. Per-trade P&L: `(execution_price - reference_price) × volume`. Reference = DA price or portfolio cost basis. |
| ID-VAL-02 | Pre-delivery unrealized P&L | For ID trades executed for future delivery intervals (e.g., next-day contracts traded at 15:00): unrealized until delivery. `(current_best_bid/ask - execution_price) × volume`. Short-lived — becomes realized within hours. |

> **Spec agent context:** ID complexity comes from multiple trades per interval (VWAP needed), near-real-time execution, and the netting against DA positions. No forward MTM needed (too short-lived). XBID cross-border trades add zone-pair tracking.

---

## 3. OTC Bilateral (Fixed Price + Indexed)

**Counterparty:** Bilateral, governed by EFET or ISDA master agreement. **Products:** Baseload, peak, off-peak, shaped. **Tenors:** Week, month, quarter, season, calendar year. **Settlement:** Physical delivery + financial settlement monthly in arrears per EFET terms.

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| OTC-VOL-01 | Contractual volume resolution | `contracted_MW × hours_in_period`. For baseload: all hours. For peak: Mon-Fri 08:00–20:00 CET excluding public holidays. For off-peak: all non-peak hours. Requires holiday calendar per market area. |
| OTC-VOL-02 | Shape decomposition | Annual/quarterly/monthly contracts must be shaped to daily and hourly/15-min for position aggregation with spot trades. Apply shape profiles (seasonal, day-type, intraday). |
| OTC-VOL-03 | Tolerance band | Some bilateral contracts include ±X% annual volume tolerance. If actual take is outside the band, penalty pricing applies to the excess/shortfall. |
| OTC-VOL-04 | Take-or-pay minimum | Minimum annual offtake commitment. Shortfall below minimum triggers payment at contract price for un-taken volume. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| OTC-PRC-01 | Fixed price | Agreed €/MWh for the delivery period. May differ by product type (base vs peak) within the same contract. |
| OTC-PRC-02 | Indexed price | Settlement against a published index (e.g., EPEX DA base average for the delivery month). Final price known only after delivery period. Formula: `index_value ± premium/discount`. |
| OTC-PRC-03 | Formula pricing | Complex price formulas: e.g., `0.7 × EEX_Base_M+1 + 0.3 × EPEX_DA_Avg + €2.50`. Must support configurable multi-component formulas with weights and offsets. |
| OTC-PRC-04 | Price escalation | Annual escalation on the fixed component: `base_price × (1 + escalation_pct)^year_offset`. Compounds from deal effective date. |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| OTC-SET-01 | Monthly invoice generation | Standard EFET: monthly settlement in arrears. Invoice = `Σ(price × volume × hours)` per delivery day within the month. Separate line items for base, peak, off-peak if applicable. |
| OTC-SET-02 | Payment due date | Per EFET General Agreement: typically 20th business day of the month following delivery (M+20BD). Configurable per contract. |
| OTC-SET-03 | Bilateral netting | Multiple deals with the same counterparty under the same master agreement: net receivables vs. payables. Single net payment per counterparty per settlement period. |
| OTC-SET-04 | Late payment interest | EFET standard: EURIBOR + agreed margin (typically 200 bps) on overdue amounts, calculated daily. `overdue_amount × (EURIBOR + margin) / 365 × days_overdue`. |
| OTC-SET-05 | Tax / levy pass-through | Some contracts pass through electricity tax (Stromsteuer DE: €20.50/MWh) and/or network charges. Separate invoice line items. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| OTC-VAL-01 | Forward MTM | `(forward_curve_price[t] − deal_price[t]) × volume[t]` summed across all undelivered intervals. Uses the latest EEX forward curve shaped to matching granularity. |
| OTC-VAL-02 | Realized P&L | For delivered intervals: `(settlement_price − deal_price) × actual_volume`. For fixed-price OTC: settlement_price = deal_price, so realized P&L = 0 on the deal itself (value was captured at booking via MTM). |
| OTC-VAL-03 | Unrealized P&L | Current MTM of undelivered volume. Changes daily with forward curve movements. |
| OTC-VAL-04 | P&L attribution | Decompose daily P&L change into: price effect (curve moved), volume effect (if applicable), new deal effect (day-one P&L), time decay (for long-dated). |
| OTC-VAL-05 | NPV discount (long-dated only) | For OTC contracts > 2 years: discount future cashflows using EUR OIS/ESTR curve. `MTM_discounted = Σ(cashflow[t] × discount_factor[t])`. Immaterial for contracts < 1 year. |
| OTC-VAL-06 | Credit valuation adjustment | Counterparty credit risk on positive MTM exposure. `CVA = PD × EAD × LGD`. Requires counterparty credit rating or internal scoring. |
| OTC-VAL-07 | Counterparty exposure | Net MTM per counterparty after netting. Compare against credit limit. Alert if exposure > threshold. |

> **Spec agent context:** OTC is the most diverse instrument class. The key complexity drivers are: formula pricing (configurable multi-component), bilateral netting across deals, EFET payment terms, and forward MTM requiring shaped forward curves. Indexed OTC adds the dependency on post-delivery index publication. Two sub-variants: OTC-Fixed and OTC-Indexed — share the same volume/settlement logic but differ in price determination.

---

## 4. Power Purchase Agreement (PPA)

**Structure:** Pay-as-Produced (primary), Fixed Volume, Floor/Cap (Collar). **Tenor:** 5–25 years. **Settlement:** Monthly in arrears against metered generation. **Counterparty:** Bilateral, bespoke contract. **Linked to:** Specific generation asset (wind farm, solar park).

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| PPA-VOL-01 | Pay-as-produced volume | Contractual volume = actual metered generation of the linked asset at ISP granularity (15-min). Not known until delivery occurs. Forward-looking volume = P50 generation forecast. |
| PPA-VOL-02 | P10/P50/P90 forecast | Probabilistic generation forecast from weather model. P50 = median expected generation. P10/P90 = downside/upside bounds at 80% confidence. Used for forward position and risk. |
| PPA-VOL-03 | Shape decomposition (forward) | For the undelivered portion: annual expected GWh → monthly (seasonal wind/solar profile) → daily → 15-min. Asset-specific shape profiles, not generic market profiles. |
| PPA-VOL-04 | Degradation adjustment | Annual capacity degradation: `effective_capacity = nameplate × (1 − degradation_rate)^years_since_commissioning`. Wind: ~0.3%/year. Solar: ~0.5%/year. Applied multiplicatively to forecast. |
| PPA-VOL-05 | Curtailment volume | TSO-mandated curtailment (Einspeisemanagement in DE) reduces delivered volume below generation capacity. Compensation: some PPAs compensate at contract price for curtailed MWh, others at market price, others not at all. Contract-specific. |
| PPA-VOL-06 | Tolerance band / annual true-up | Some PPAs guarantee minimum annual delivery (e.g., ≥ 85% of P50). If actual < minimum: seller compensates buyer. If actual > cap (e.g., 115% of P50): excess at spot price instead of PPA price. Threshold percentages are contract terms. |
| PPA-VOL-07 | Availability deduction | Scheduled maintenance windows where the asset is unavailable. Not counted as curtailment. Contract may specify maximum allowed unavailability days/year. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| PPA-PRC-01 | Fixed PPA price with escalation | `price_year_n = base_price × (1 + annual_escalation)^n`. Base price and escalation rate are contract terms. Escalation may be CPI-linked (requires CPI index lookup) or fixed percentage. |
| PPA-PRC-02 | Floor/cap (collar) price | Settlement price = `max(floor, min(cap, market_price))`. Buyer pays the bounded price, not the raw market price. Floor and cap may escalate annually. |
| PPA-PRC-03 | Capture price | Generation-weighted average market price: `Σ(market_price[t] × generation[t]) / Σ(generation[t])`. NOT the arithmetic average of market prices. Always ≤ base average for wind/solar due to merit order effect. Capture rate = capture price / base average price. |
| PPA-PRC-04 | Negative price clauses | Many PPAs specify: if market price < 0 for N consecutive hours (typically 6h), buyer payment = €0 for those hours (not negative). Some PPAs: buyer pays the negative price. Contract-specific. |
| PPA-PRC-05 | Proxy generation (for compensation) | If asset is curtailed or in outage: "what would have generated?" based on reference wind speed / irradiance data and the asset's power curve. Used for curtailment compensation calculation. |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| PPA-SET-01 | Monthly energy settlement | `Σ(ppa_price[t] × metered_generation[t] × 0.25h)` across all 15-min intervals in the month. This is the core invoice amount. |
| PPA-SET-02 | Curtailment compensation invoice | If contract includes curtailment compensation: `Σ(proxy_generation[t] − actual_generation[t]) × compensation_price × 0.25h` for curtailed intervals. Separate invoice line item. |
| PPA-SET-03 | Tolerance band settlement | Annual calculation: if cumulative delivered MWh < minimum threshold: `(minimum_mwh − actual_mwh) × ppa_price` payable by seller. If > maximum: excess settled at spot. |
| PPA-SET-04 | GoO / certificate transfer | Guarantees of Origin (GoOs) bundled with energy delivery. Track GoO transfer per MWh delivered. If GoOs sold separately (unbundled PPA), separate financial settlement. GoO price per certificate as contract term or market price. |
| PPA-SET-05 | Payment due date | Monthly in arrears per contract terms. Typically M+15 to M+30 calendar days. Depends on meter data availability and invoice approval process. |
| PPA-SET-06 | Preliminary vs final settlement | Preliminary settlement based on preliminary meter data (D+1). Final settlement after annual reconciliation (~M+14). Difference = settlement correction invoice. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| PPA-VAL-01 | PPA MTM (forward) | `Σ((forward_capture_price[t] − ppa_price[t]) × expected_volume[t])` across all undelivered intervals. **Critical:** must use forward capture price (not forward base price) because capture rate < 100%. |
| PPA-VAL-02 | Cannibalisation / shape cost | The P&L drag from adverse price-volume correlation. `shape_cost = (base_average_price − capture_price) × expected_volume`. This is a material valuation component — can be 10-20% of gross value for wind. |
| PPA-VAL-03 | NPV discounting | Mandatory for PPAs > 2 years. `MTM_npv = Σ(cashflow[t] × discount_factor[t])` using EUR ESTR/OIS curve. For an 8-year PPA, discounting can reduce MTM by 5-15% vs. undiscounted. |
| PPA-VAL-04 | Realized P&L | For delivered intervals: `(metered_volume × ppa_price) − (metered_volume × capture_price_equivalent)`. Or decomposed: energy margin + shape cost + curtailment compensation. |
| PPA-VAL-05 | Volume P&L | P&L change from forecast revision: `(new_forecast − old_forecast) × (forward_price − ppa_price)`. Material when weather outlook changes significantly. |
| PPA-VAL-06 | Price P&L | P&L change from forward curve movement: `expected_volume × (new_forward_capture − old_forward_capture)`. |
| PPA-VAL-07 | Capture rate forecast | Forward-looking capture rate estimation. Historical capture rate × technology-specific trend factor (cannibalisation increasing as renewable penetration grows). Used in PPA-VAL-01. |
| PPA-VAL-08 | Cash Flow at Risk (CFaR) | Probabilistic distribution of future PPA cashflows. Inputs: P10/P50/P90 volume scenarios × forward price scenarios (Monte Carlo or historical simulation). Output: worst-case annual cash requirement at 95% confidence. |
| PPA-VAL-09 | Counterparty exposure | Net MTM exposure to PPA counterparty. Long-term PPAs create large exposure. Must track against credit limit and collateral/guarantees posted. |

> **Spec agent context:** PPA is the most complex instrument. Unique calculations: capture price (PPA-PRC-03), cannibalisation cost (PPA-VAL-02), curtailment compensation (PPA-SET-02), degradation adjustment (PPA-VOL-04), tolerance bands (PPA-VOL-06). The forward MTM MUST use capture price, not base price — this is the most common PPA valuation error. Pay-as-produced volume uncertainty (P10/P50/P90) drives CFaR. Settlement has preliminary/final correction cycles aligned with the quality-driven retention model.

---

## 5. Financial Swap

**Structure:** Fixed-for-floating exchange of cashflows. No physical delivery. **Fixed leg:** Agreed €/MWh. **Floating leg:** Published index (EPEX DA base, EEX settlement). **Clearing:** ECC (exchange-cleared) or bilateral (OTC). **Tenors:** Month, quarter, season, calendar year.

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| SWP-VOL-01 | Notional volume | `contracted_MW × hours_in_period`. Baseload or peak, same hour classification as OTC. This is the notional, not physical delivery — no nomination needed. |
| SWP-VOL-02 | Shape decomposition | Same as OTC-VOL-02. Quarterly/annual notional shaped to monthly/daily for position aggregation and MTM. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| SWP-PRC-01 | Fixed leg price | Agreed at trade execution. Fixed for the swap's lifetime. |
| SWP-PRC-02 | Floating leg price | Determined by the reference index over the delivery period. Typically: arithmetic average of EPEX DA hourly prices for the delivery month (for base) or peak hours only (for peak). Not known until delivery month completes. |
| SWP-PRC-03 | Floating leg estimation (pre-delivery) | For undelivered periods: floating leg estimated from forward curve. `estimated_floating = forward_curve_price` for the delivery period at matching granularity. |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| SWP-SET-01 | Net settlement amount | `(fixed_price − floating_price) × notional_volume_MWh`. If positive: floating payer pays fixed payer. If negative: fixed payer pays. Single net cashflow per settlement period. |
| SWP-SET-02 | Payment due date (bilateral) | Per ISDA/EFET terms: typically M+10 to M+20 BD after delivery month end. Floating leg must be determinable first (requires index publication). |
| SWP-SET-03 | Variation margin (cleared) | For ECC-cleared swaps: daily variation margin = `today_MTM − yesterday_MTM`. Cash settled daily via margin account. |
| SWP-SET-04 | Initial margin (cleared) | Collateral posted to ECC. Calculated by exchange using SPAN/VaR. Not a P&L item but impacts cash/collateral management. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| SWP-VAL-01 | Swap MTM | `Σ((forward_floating[t] − fixed_price) × volume[t] × discount_factor[t])` across all undelivered periods. For short-dated swaps (< 1 year): discounting immaterial, can omit. |
| SWP-VAL-02 | Realized P&L | For delivered months: `(fixed − actual_floating) × volume`. Known with certainty once the floating index for the month is published. |
| SWP-VAL-03 | Unrealized P&L | Current MTM of undelivered periods. = SWP-VAL-01. |
| SWP-VAL-04 | P&L attribution | Price P&L (curve movement) + time decay (for discounted swaps) + new deal P&L (day-one). |

> **Spec agent context:** Swaps are financially settled — no physical delivery, no nomination, no imbalance. The primary complexity is: (1) floating leg requires the delivery-month index to be finalized before settlement, (2) cleared vs. bilateral determines margin treatment, (3) MTM requires discounting for long-dated swaps. Swaps are frequently used as hedging instruments against PPAs — cross-referencing swap P&L with PPA P&L is important for hedge effectiveness reporting.

---

## 6. Exchange Future

**Exchange:** EEX (Leipzig), ICE Endex, Nasdaq Commodities. **Products:** Baseload, peakload. **Tenors:** Week, month, quarter, season, calendar year (up to Cal+6). **Clearing:** ECC. **Settlement:** Financial (cash-settled) or physical (cascading into spot delivery). **Margining:** Daily variation margin + initial margin.

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| FUT-VOL-01 | Contract volume (lots) | `lots × lot_size_MW × hours_in_delivery_period = MWh`. EEX German power: 1 lot = 1 MW. Hours depend on base vs. peak and the specific delivery period. |
| FUT-VOL-02 | Peak hour count | Peak = Mon-Fri 08:00–20:00 CET, excluding public holidays for the delivery zone. Must use the correct holiday calendar to count peak hours accurately. Wrong count = wrong volume = wrong P&L. |
| FUT-VOL-03 | Shape decomposition | Cal → quarters → months → daily → hourly/15-min for position aggregation. Same cascade as OTC. |
| FUT-VOL-04 | Cascade expiry roll | When a Cal future expires: the position conceptually "cascades" into the quarterly contracts. When a quarterly expires: into monthly. This is a position management operation, not a market event — the CTRM must handle the roll and resulting P&L attribution. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| FUT-PRC-01 | Trade execution price | Exchange execution price at the time of trade. |
| FUT-PRC-02 | Daily settlement price | Published by exchange (EEX) at end of each trading day. Used for variation margin calculation and MTM. |
| FUT-PRC-03 | Final settlement price | For financially settled futures: average of the reference spot index over the delivery period (same as swap floating leg). For physically settled: cascades to spot. |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| FUT-SET-01 | Variation margin (daily) | `(today_settlement_price − yesterday_settlement_price) × volume_MWh`. Positive = margin received. Negative = margin paid. Settled daily via ECC margin account. This IS the realized P&L for futures (mark-to-market settlement). |
| FUT-SET-02 | Initial margin | Collateral requirement calculated by ECC using SPAN methodology. Not P&L — cash/collateral management. Changes with position size and market volatility. |
| FUT-SET-03 | Delivery settlement (physical) | If physically settled: at expiry, future cascades to spot delivery. Settlement against the spot reference price over the delivery period. `(execution_price − reference_spot_average) × volume`. |
| FUT-SET-04 | Exchange fees | EEX trading fee + ECC clearing fee per lot. Configurable per exchange and product. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| FUT-VAL-01 | Forward MTM | `(current_settlement_price − execution_price) × remaining_volume`. Because futures are daily-margined, the MTM already flows through variation margin. The "unrealized" portion shrinks daily as margin settles. |
| FUT-VAL-02 | Realized P&L | For futures: realized P&L = cumulative variation margin received/paid from trade date to valuation date. Unlike OTC where realized = delivered, futures realize through daily margining. |
| FUT-VAL-03 | Roll P&L | When rolling a position (closing front-month, opening next-month): `(close_price − open_price) × volume`. Must attribute this correctly as roll cost, not market P&L. |
| FUT-VAL-04 | Cascade P&L attribution | At expiry cascade (Cal → Q, Q → M): P&L attribution between the expired product and the cascade products. The sum must reconcile. |

> **Spec agent context:** Futures' distinguishing feature is daily margining — P&L is realized incrementally through variation margin, not at delivery. The cascade expiry roll (FUT-VOL-04) is critical and often mis-implemented. Peak hour count (FUT-VOL-02) is a frequent source of bugs — wrong holiday calendar = wrong MWh = wrong P&L. Futures are the primary hedging instrument against PPAs.

---

## 7. Exchange / OTC Option

**Types:** European call/put (exercise at expiry only), American call/put (exercise any time). **Underlying:** EEX power futures (baseload, peak). **Clearing:** ECC (exchange) or bilateral (OTC). **Premium:** Paid upfront on trade date. **Exercise:** Into the underlying future position.

### Volume Determination

| Req ID | Calculation | Detail |
|---|---|---|
| OPT-VOL-01 | Notional volume | Same as the underlying future: `lots × lot_size × hours`. The option gives the RIGHT but not the obligation to this volume. |
| OPT-VOL-02 | Exercise volume | Volume materializes only on exercise. European: at expiry. American: at any point before expiry. Un-exercised = zero volume. |
| OPT-VOL-03 | Delta-equivalent volume | `notional_volume × delta`. This is the effective volume exposure for position management. Delta ranges 0 to 1 (calls) or -1 to 0 (puts). Changes with underlying price and time to expiry. |

### Price Determination

| Req ID | Calculation | Detail |
|---|---|---|
| OPT-PRC-01 | Strike price | Agreed at trade execution. Fixed for the option's lifetime. |
| OPT-PRC-02 | Premium | Price paid by buyer to seller for the option right. Quoted in €/MWh. `total_premium = premium_per_MWh × notional_MWh`. |
| OPT-PRC-03 | Intrinsic value | Call: `max(0, underlying_price − strike)`. Put: `max(0, strike − underlying_price)`. This is the value if exercised immediately. |
| OPT-PRC-04 | Time value | `option_premium − intrinsic_value`. Positive when time remains before expiry. Decays to zero at expiry (theta decay). |

### Settlement / Cashflow

| Req ID | Calculation | Detail |
|---|---|---|
| OPT-SET-01 | Premium payment | Paid on trade date or T+1. One-time cashflow from buyer to seller. Regardless of exercise outcome. |
| OPT-SET-02 | Exercise settlement | On exercise: option converts to the underlying future at the strike price. Subsequent settlement follows future rules (variation margin etc.). |
| OPT-SET-03 | Cash settlement (if applicable) | Some options are cash-settled: `intrinsic_value × notional_volume` paid at expiry. No underlying future position created. |
| OPT-SET-04 | Initial margin | For short (sold) options: initial margin required. For long (bought) options: premium is the maximum loss, no margin beyond premium paid. |

### Valuation / P&L

| Req ID | Calculation | Detail |
|---|---|---|
| OPT-VAL-01 | Option MTM (Black-76) | Standard model for European commodity options. Inputs: forward price (F), strike (K), volatility (σ), time to expiry (T), risk-free rate (r). Output: call/put premium. `Call = e^(-rT) × [F × N(d1) − K × N(d2)]` where `d1 = [ln(F/K) + 0.5σ²T] / (σ√T)`, `d2 = d1 − σ√T`. |
| OPT-VAL-02 | Volatility surface | Implied volatility varies by strike (skew) and expiry (term structure). Must maintain a vol surface: σ(K, T). Sourced from exchange (EEX publishes settlement vols) or broker quotes. Interpolation between published strikes/expiries. |
| OPT-VAL-03 | Greeks — Delta | ∂(option_price)/∂(underlying_price). Used for delta hedging and delta-equivalent position. For calls: N(d1). For puts: N(d1) − 1. |
| OPT-VAL-04 | Greeks — Gamma | ∂²(option_price)/∂(underlying_price)². Rate of change of delta. Highest for ATM options near expiry. Drives hedging rebalancing frequency. |
| OPT-VAL-05 | Greeks — Vega | ∂(option_price)/∂(volatility). Sensitivity to vol changes. For power options: high vega = large P&L swings on vol surface updates. |
| OPT-VAL-06 | Greeks — Theta | ∂(option_price)/∂(time). Daily time decay. Always negative for long options. This is the "cost" of holding the option over time. |
| OPT-VAL-07 | Greeks — Rho | ∂(option_price)/∂(risk_free_rate). Sensitivity to interest rate changes. Usually immaterial for power options < 1 year. |
| OPT-VAL-08 | P&L decomposition | Daily P&L = delta P&L (underlying moved) + gamma P&L (convexity) + vega P&L (vol changed) + theta P&L (time decayed) + rho P&L (rates moved). Must reconcile to actual MTM change. |
| OPT-VAL-09 | Exercise decision | American options: optimal exercise when intrinsic value > holding value (time value ≈ 0). System should flag when exercise is optimal. European: automatic exercise if ITM at expiry (exchange rule). |

> **Spec agent context:** Options are the most mathematically complex instrument. The Black-76 model, volatility surface management, and Greeks are the core. For a CTRM targeting small-to-mid EU traders, options may be Phase 2 — most small shops don't actively trade options. If included in MVP, at minimum: MTM using Black-76, delta and vega Greeks, premium settlement, exercise handling. Full Greeks (gamma, theta, rho) and P&L decomposition can be deferred. The vol surface is the hardest data management problem — requires regular updates from exchange or broker sources.

---

## 8. Cross-Instrument Calculations (Portfolio Level)

These calculations span across all instruments within a portfolio and cannot be attributed to a single instrument type.

| Req ID | Calculation | Detail | Instruments |
|---|---|---|---|
| PORT-01 | Net position aggregation | Sum all deal-level positions (shaped to common granularity) to produce portfolio CONTRACTED / HEDGED / OPEN view. Requires consistent granularity alignment via shaping engine. | All |
| PORT-02 | Hedge ratio | `hedged_volume / contracted_volume` per time period. Drives hedging strategy. Track at monthly, quarterly, annual granularity. | PPA vs Futures/Swaps/OTC |
| PORT-03 | Hedge effectiveness | Correlation between PPA volume/price changes and hedge instrument changes. Required for hedge accounting under IFRS 9. Prospective and retrospective testing. | PPA + hedges |
| PORT-04 | Total P&L reconciliation | Realized + Unrealized across all instruments must reconcile. Cross-check: deal-level P&L sums to portfolio P&L. Daily change decomposes into price/volume/time/new deal/amendment components. | All |
| PORT-05 | VaR / PaR / CFaR | Portfolio-level risk metrics. VaR: maximum loss at confidence interval. PaR: P&L distribution. CFaR: cashflow distribution. Historical simulation (min 2 years history) or parametric. Monte Carlo for non-linear (options). | All |
| PORT-06 | Counterparty exposure netting | Net MTM across all deals per counterparty, respecting netting agreements. Gross exposure vs net exposure. Track against credit limits. | OTC, PPA, bilateral Swaps |
| PORT-07 | UoM normalization | All volumes normalized to MWh for aggregation. Deals entered in MW (capacity) must be converted using interval hours. Deals in GWh must be scaled. Consistent rounding rules (4 decimal places on MW, 2 on MWh). | All |
| PORT-08 | Currency normalization | All P&L and MTM in EUR. Non-EUR deals converted at ECB daily fixing rate or contract-specified rate. FX P&L attributed separately from commodity P&L. | Cross-currency deals |
| PORT-09 | Regulatory position reporting | REMIT standard + non-standard contract reporting. EMIR derivative reporting. MiFID II position limits. Each requires specific data fields — map instrument attributes to regulatory schemas. | All (reportable) |
| PORT-10 | Cashflow forecasting | Project future cash inflows/outflows by payment due date across all instruments. Include: settlement amounts, margin calls, premium payments. Used for treasury/liquidity management. | All |

---

## 9. MVP Prioritization Recommendation

| Phase | Instruments | Calculations Included |
|---|---|---|
| **Phase 1 (MVP)** | PPA, OTC Fixed, Futures | All VOL, PRC, SET calculations. Forward MTM. Realized + unrealized P&L. Basic P&L attribution (price/volume). Shape decomposition. Holiday calendar. UoM conversion. Cashflow generation with payment due dates. |
| **Phase 2** | + DA, ID, OTC Indexed, Swaps | + Index price resolution. VWAP. Imbalance settlement. Floating leg. Variation margin. Bilateral netting. Formula pricing. |
| **Phase 3** | + Options | + Black-76. Vol surface. Greeks. Premium settlement. Exercise handling. |
| **Phase 4** | Portfolio-level | + VaR/PaR/CFaR. Hedge effectiveness (IFRS 9). CVA. Full P&L attribution with Greeks decomposition. Regulatory reporting automation. |

> **Rationale:** Phase 1 covers the three instruments that 90% of small-to-mid EU renewable energy traders use daily: PPAs (origination), OTC fixed-price (bilateral hedging), and EEX futures (exchange hedging). The core valuation and settlement engine built for these three extends naturally to the remaining instruments in later phases.
