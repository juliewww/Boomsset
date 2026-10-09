# Domain Model

> Status: **Product decisions finalized (2026-07-28); the schema can be implemented as described here.**

## Design premise

**Snapshot-based, not ledger-based.** Users don't enter transactions — they periodically answer
"what is this asset worth right now." This shapes the entire data model: the core is
(asset × time → valuation), not a transaction ledger.

## One key split: Quote ≠ Snapshot

These two things must be stored separately; combining them into one table causes two bugs:

1. Quote refresh is high-frequency (every time the app opens). If refreshing also wrote a
   snapshot, ten app opens a day × N assets would blow up the snapshot table, and the
   historical curve would get polluted with high-frequency noise.
2. Share counts change (buying/selling more). If a snapshot only stored market value, then
   after the user adds to a position today, **historical net worth would get recalculated
   using the new share count** — the past curve would change out from under them.

So:

- **`Quote`** stores **public market data**, independent of the user; refreshes only write here.
- **`Snapshot`** stores **the user's holdings state**, written only when the user actively
  makes a change.

## Core entities

### Asset — one asset or liability

- `id`
- `name` — a name the user picks, e.g. "CMB Savings Card," "Company Stock Options"
- `assetClass` — **one of the five top-level classes**, see "Asset classification system."
  **Liabilities must also have one**, used for attribution/offsetting
- `subtypeId` — subtype (WeChat Wallet / money market fund / A-share stock ...), predefined
  plus user-defined
- `includeInAllocation` — defaults to `true`. Whether this asset counts toward allocation
  percentages. This toggle exists so that if a primary residence later turns out to dominate
  the pie chart and needs excluding, it's a boolean flip, not a schema change
- `currency` — the currency this asset is denominated in (CNY / USD / HKD ...)
- `isLiability` — liability flag. Mortgages, auto loans, credit card debt subtract from net worth
- `defaultValuationMode` — **`QUOTED` or `MANUAL`**, see next section.
  ⚠️ This is only "**which mode a new snapshot defaults to**," **not** the criterion used at
  valuation time — valuation always follows the snapshot's own `mode`
- `defaultQuoteSymbol` — only set when the default is `QUOTED`. Likewise, only a default for
  new snapshots
- `archivedAt` — optional. Sold or settled assets get archived rather than deleted

### Valuation modes: QUOTED vs MANUAL

| | `QUOTED` | `MANUAL` |
|---|---|---|
| Applies to | stocks, funds, crypto, anything with a market ticker | real estate, deposits, vehicles, custom assets |
| Market value | **read-only**, = shares × market unit price | entered directly by the user |
| What the user can edit | **shares + cost basis** | **market value + cost basis** |
| Participates in quote refresh | yes | no |
| Shows unrealized gain/loss | yes (once cost basis is filled in) | yes (once cost basis is filled in) |

Market value being read-only for `QUOTED` doesn't mean "not allowed to change" — it means
**it's a computed value**. To change it, either change the share count, or wait for the
quote to update.

**Cost basis is editable in both modes**, so both modes can show unrealized gain/loss and
return rate. Cost basis is a field independent of valuation mode — don't infer from
"market value is read-only" that "cost basis is read-only too."

**Fallback for failed price fetches** (implemented): use the last successfully fetched unit
price, marked as stale — the UI shows "unit price X · quoted N days ago," with a staleness
warning past **3 days**. Don't treat an asset as worth 0 just because a price fetch failed.

The threshold is 3 days rather than 1: weekends and holidays naturally have no quotes, so
1 day would false-flag every Monday. **We deliberately don't build a trading calendar** —
maintaining holiday tables per market costs far more than it's worth, and getting one wrong
just adds noise.

**Users can manually override the unit price.** This is the only remedy when the quote
source fails. A manual entry is written into the same `quote` table, so **a single
successful automatic refresh that same day will overwrite it** — this is intentional: a
manual entry is a fallback for "couldn't auto-fetch," and an actual market price is always
more accurate. "Pinning" a price would require adding an `is_manual` flag to `quote`,
which is a separate change.

**Can be converted to MANUAL after delisting.** See "Mode conversion" below — this is also
why the mode has to live on the snapshot.

### Snapshot — a snapshot of the user's holdings state

**Immutable, append-only.** Correcting history means inserting a new record, not editing in
place. This way history is always reproducible. **Each snapshot is the complete holdings
state at that point in time** (not a delta); the UI pre-fills a new one from the previous
snapshot.

- `id`
- `assetId`
- `asOf` — the point in time this state took effect
- **`mode`** — `QUOTED` or `MANUAL`. **Valuation always follows this field, never `Asset`**
- **`valueMinor`** — only when `mode = MANUAL`: market value in the asset's own currency,
  as an integer in minor units (cents)
- **`quantityScaled`** — only when `mode = QUOTED`: shares held, a fixed-point integer
  (see "Representing amounts and shares")
- **`quoteSymbol`** — only when `mode = QUOTED`: which ticker this snapshot should use to
  fetch its price
- **`costBasisMinor`** — optional, cost basis in the asset's own currency. See "Cost basis
  and unrealized gain/loss"
- `recordedAt` — when it was entered, kept separate from `asOf` (so past months' data can
  be backfilled)

**Why `mode` and `quoteSymbol` live on the snapshot rather than the asset:** once a stock
is delisted and converted to MANUAL, its historical share-based snapshots still need to be
valuable. If the code branched on `Asset`'s current mode, it would try to read the empty
`valueMinor` on old snapshots; if the asset's `quoteSymbol` were cleared, old snapshots
wouldn't know which ticker to look up. Recording both on the snapshot makes the conversion
**purely additive** — the historical curve doesn't move at all, and as a bonus it also
handles ticker changes.

### Derived: update history (the section at the bottom of the asset list page)

**Not a new table.** The `snapshot` chain — immutable and append-only — already is the
event log. `UpdateHistory.build(data, zone)` simply flattens it in reverse chronological
order and pairs each entry with "the record immediately preceding it in the chain."
Keeping a separate event table would just create a second source of truth that could drift
out of sync with the snapshots.

There are three event types, all derived from the snapshot itself:

| Criterion | Type |
|---|---|
| `asset.archivedAt == snapshot.asOf` | Archived |
| First record in that asset's chain | Added |
| Everything else | Updated |

The archive criterion works because `archiveAsset` writes the same `now` value to both
fields. Un-archiving clears `archivedAt` (the zeroed-out snapshot stays), and that record
automatically reverts to displaying as a plain "Updated" — which is correct, since the
archive really was undone.

**QUOTED records show only shares and cost basis, never market value.** Market value =
shares × the quote at that time, and quotes still don't do historical backfill (see the
known issue at the top of AGENTS.md) — most historical points can't be priced, so computing
it would either produce "cannot value" or use today's price to explain a record from three
months ago. What the snapshot actually stores — shares and cost basis — is shown as-is.
Likewise, **amounts are shown in the asset's own currency, not converted to the base
currency**.

#### Retention policy: nothing ever gets deleted

This is a **product decision**, not an optimization we haven't gotten to yet:

- Snapshots are the **sole** data source for the net worth curve, and the carry-forward
  rule takes "the most recent record at or before that point in time." Deleting records
  "older than six months" would mean an asset that hasn't been updated in six months
  couldn't get a snapshot for **today either**, and it would vanish entirely from net worth,
  allocation, and the asset list — not a loss of precision, an asset disappearing into thin
  air, silently.
- There's no real storage benefit: one snapshot row is about 100 bytes; 20 assets updated
  monthly for ten years is still under 250 KB.
- This is an extension of the same principle as "snapshots are immutable" and "archiving is
  not deleting."

**Pagination is UI-side only**: 20 records by default plus "load more." By record count, not
by time window — someone who updates quarterly would only have two records in "the last six
months" (expanding it would look broken), while someone who updates daily would have hundreds.

The scale ceiling follows the same one as `PortfolioData`: loading tens of thousands of
snapshots at once will noticeably slow down, but it won't silently produce wrong results.

### Quote — market data

- `symbol` / `asOf` / `priceMinor` / `currency`
- **Only one record per `symbol` per day** (upserted by day)

When the app opens, refresh only writes here. **It never produces a Snapshot.**

### FxRate — exchange rates

- `base` / `quote` / `asOf` / `rate`

Converting historical net worth must use **the exchange rate at that time**, not today's.
Otherwise exchange-rate fluctuations would pollute the historical curve, showing the user
swings they never actually experienced.

**"Use the rate at that time" is a hard requirement on the refresh layer, not just a query-layer
rule.** Valuation takes "the most recent exchange rate at or before that point in time," so
if only today's rate is stored, historical points can't get one at all → those assets are
judged "cannot value" → net worth at those points computes to 0. The visible symptom is
**the curve starting from 0 when you look back** — a trajectory the user never actually
lived through, and no error is raised.

So the refresh layer must **backfill by range**: for every non-base currency, cover
`[the earliest snapshot date in that currency, the end point]`, where the end point is
today if there are still unarchived assets, or the date of the last snapshot if everything
has been archived. The start point is never earlier than the first snapshot — before that,
those assets didn't exist yet, so they're not part of net worth. The logic lives in
`RateRefresher.requiredRanges`.

Gaps within the range are allowed (they can only come from a previous partial failure): the
carry-forward rule takes "the most recent rate at or before that point," so a gap means
using a slightly stale rate, **not** failing to value at all — a different, milder severity
than "no rate at all."

⚠️ **The same requirement applies to Quote too, but so far only FX rates do this.** See the
known issue in AGENTS.md.

### AssetSubtype — subtype

- `id` / `name` / `assetClass` / `defaultValuationMode` / `isBuiltIn` / `hidden`

Built-in subtypes **cannot be deleted** (historical assets would end up pointing at nothing);
they can only be hidden via `hidden`.

### TargetAllocation / TargetAllocationItem — target allocation

See "Asset allocation" section.

### Derived: NetWorth time series

Not a table — a query result. Base currency is passed in as a query parameter (not hardcoded).

```
Net worth at time T =
  Σ  assets: assetValue(asset, T) × fxRate(asset.currency → base, T)
  -  Σ liabilities: assetValue(asset, T) × fxRate(asset.currency → base, T)

assetValue(asset, T) =
  let s = snapshot(asset, ≤T)              // take the most recent snapshot at or before T
  s.mode == MANUAL  → s.valueMinor
  s.mode == QUOTED  → s.quantityScaled × quote(s.quoteSymbol, ≤T).priceMinor
```

Note the branch is on `s.mode`, **not** `asset.defaultValuationMode`.

**Carry-forward rule**: if there's no snapshot/quote exactly at the target time, take the
**most recent one before it** (net worth is a stock concept — the last valuation remains
valid), rather than treating it as nonexistent.

### Derived: unrealized gain/loss

```
Gain/loss(asset, T)      = assetValue(asset, T) - snapshot(asset, ≤T).costBasisMinor
Gain/loss rate(asset, T) = gain/loss / costBasisMinor
```

Assets where `costBasisMinor` is null don't participate in gain/loss statistics (liabilities,
and assets where the user never filled in a cost basis). Portfolio-level gain/loss only
sums the portion that has a cost basis, **and the UI must state the coverage** — otherwise
a user could take a gain/loss figure that only covers 30% of their assets and read it as
representing their whole net worth.

## Finalized product decisions

1. **Update frequency**: users can update anytime; **no reminders are sent.**
2. **Quote refresh**: **refreshes every time the app opens.** Only writes `Quote`, never
   `Snapshot` (see the split above). A failed refresh falls back to the stale price and
   doesn't block the UI.
3. **Can market value be edited**: see "Valuation modes" — `QUOTED` market value is
   read-only, `MANUAL` is editable. An asset either auto-fetches a price and can't have its
   market value edited, or it's editable but then has no market price to refresh. The two
   are mutually exclusive, so there's no conflict like "manually edited an auto-priced asset
   and then it got overwritten."
4. **Base currency**: **defaults to CNY, switchable for viewing.** Stored in DataStore
   Preferences, passed as a parameter into the net worth query — it never lands on `Asset`
   or `Snapshot`.
5. **Archive behavior**: **zeroed at the data layer, truncated at the display layer.** See
   "Archiving."
6. **Mode conversion**: `QUOTED` **is allowed to convert to `MANUAL`** after delisting. See
   "Mode conversion."
7. **Cost basis and unrealized gain/loss**: **in scope.** See the section below.
8. **Classification system**: two layers — five top-level classes (the four SAA classes plus
   Protection) + customizable subtypes. See "Asset classification system."
9. **Allocation view**: in scope. Denominator = **total net worth** (including a primary
   residence), liabilities are attributed and offset against their class. Target allocations
   **can coexist as multiple sets for comparison.** See "Asset allocation."
10. **Update timing**: already handled — `asOf` (which point in time the valuation is for)
    and `recordedAt` (when it was entered) are already separate, supporting backfilling
    historical data. Monthly change statistics are naturally possible without any extra field.

## Cost basis and unrealized gain/loss

**This is an explicit exception to the "no transaction ledger" boundary stated in AGENTS.md.
It's a product decision — don't treat it as scope creep and delete it.**

Cost basis is recorded on **`Snapshot.costBasisMinor`** (in the asset's own currency, as an
integer in minor units), not on `Asset`. Reasons:

1. Cost basis is itself part of "holdings state," at the same level as share count.
2. Adding to a position already requires writing a new snapshot; updating cost basis in the
   same action requires no extra workflow.
3. As a result, **gain/loss automatically becomes a time series**, so a gain/loss curve can
   be charted. Recording it on `Asset` would only ever give you a single current number.

**Both valuation modes support entering a cost basis, and both display a return rate.**
`costBasisMinor` is an independent field, unrelated to `mode` — a `QUOTED` stock and a
`MANUAL` house can both have a cost basis.

### Input form: store total cost, average price is derived

In `QUOTED` scenarios, users tend to think in terms of "average cost of ¥10.5/share" rather
than "total invested ¥105,000." So:

- **Storage only keeps total cost** `costBasisMinor` — this is the single source of truth
- **Average price is a derived display value**: `average cost = costBasisMinor / quantityScaled`
- **Both fields are enterable**; entering an average price immediately converts it to a
  total cost for storage

**Why store total cost rather than average price**: if average price were stored, a user
adding to a position and changing shares from 100 to 200 without updating the average price
would have total cost silently become `average price × 200` — a price they never actually
paid, **and no error would be raised**. Storing total cost instead means that after adding
to a position, the cost is merely stale, and the derived average price will visibly drop —
a signal the user can actually see.

**Precision in the conversion**: `average price × shares` is a fixed-point multiplication,
and the result must be rounded to the nearest minor unit. A side effect is that a user
entering 10.5 and reading it back might see 10.499999 — the displayed average price should
be rounded to a reasonable number of digits.

**We don't do lot-by-lot average-cost derivation.** We don't support deriving something like
"bought 100 shares @10 + bought 50 shares @12 → average 10.67" — that would require
recording every individual transaction, exactly what this app is built not to do. What the
user enters is their current total investment or current average price, not something we
compute from a history of trades.

### A tradeoff worth knowing: FX gain/loss is not broken out separately

Cost basis is stored in the **asset's own currency**, consistent with `valueMinor`. So when
converting to the base currency, cost basis and market value use **the same exchange rate**,
and the effect of the exchange rate cancels out, yielding **pure market gain/loss**.

Concretely, holding a US stock: cost basis $100 (USD/CNY was 7.0 at the time, actually paid
¥700), current value $110 (rate is now 7.2, ¥792).

- The gain/loss we display: `(110 - 100) × 7.2 = ¥72` (pure market return)
- The user's actual RMB-denominated return: `792 - 700 = ¥92` (including ¥20 of FX gain)

That ¥20 difference is FX gain, and the current model **does not capture it**. Capturing it
would require recording the exchange rate at the time each cost was incurred, but cost basis
accumulates from multiple purchases with no single point in time, which isn't cheap to
implement.

For v1 we accept this, and **the UI labels the gain/loss as "market gain/loss"** so users
aren't misled into thinking it's their true RMB-denominated return. If this needs to be
addressed later, the approach would be to additionally store a cost-basis snapshot
denominated in the base currency.

## Mode conversion (QUOTED → MANUAL)

When a stock is delisted, a fund is liquidated, or the user simply doesn't want
auto-fetched pricing anymore:

1. Compute the current market value using the last valid quote.
2. **Append** a new snapshot with `mode = MANUAL`, `valueMinor` = the value computed in
   step 1, and `costBasisMinor` carried forward from the previous snapshot.
3. Change `Asset.defaultValuationMode` to `MANUAL` and clear `defaultQuoteSymbol`.

**Not a single historical snapshot is touched.** They keep `mode = QUOTED` and their own
`quoteSymbol`, and are still valued against historical quotes in the `Quote` table — so the
historical curve is completely unchanged. This is the payoff of recording mode on the
snapshot.

The reverse conversion (MANUAL → QUOTED) works the same way, but the user needs to fill in
the correct `quoteSymbol` and share count first.

## Archiving

**Approach: zeroed at the data layer, truncated at the display layer.**

- **Data layer**: archiving appends a terminal snapshot (`valueMinor = 0` for `MANUAL`;
  `quantityScaled = 0` for `QUOTED`). This way the "take the most recent snapshot at or
  before T" carry-forward rule naturally returns 0, without needing to special-case
  `archivedAt` in every query — otherwise carry-forward would make a sold asset continue
  forever.
- **Display layer**: the asset's detail chart **ends at the last real snapshot** and is
  labeled "archived," **without drawing the line diving down to 0** (which would look like
  the asset crashed, not that it was sold).

**Something more important than this mechanism**: after archiving a stock, if the cash
received from selling it isn't recorded into another asset, **total net worth will drop by
that amount out of nowhere** — the user sees a loss they never experienced. This is an
inherent characteristic of the snapshot-based model, not a bug, but it must be handled in
UX: **the archive flow needs to ask "where did this money go?"** so the user can conveniently
add the proceeds into cash or another asset.

## Asset classification system (two layers)

Split into two layers because they solve different problems: **top-level class** serves
allocation percentages (needs to be few and stable), **subtype** serves bookkeeping
categorization (can be numerous and extensible).

The original flat single layer ("cash & deposits, stocks, funds, bonds, real estate...")
conflated the two, making allocation percentages impossible and extensibility awkward.

### Layer one: five top-level classes (framework = the four SAA classes + Protection)

Adopts the four Strategic Asset Allocation (SAA) classes, plus a "Protection" class to fit
the domestic habit of including annuities/whole life insurance in one's allocation.

| Class | Includes |
|---|---|
| `LIQUID` Liquid funds | WeChat Wallet, Alipay, bank checking accounts, money market funds, cash |
| `FIXED_INCOME` Fixed income | bank time deposits, government bonds, bond funds, bank wealth management products, corporate bonds |
| `EQUITY` Equity | A-shares, Hong Kong stocks, US stocks, equity funds, index funds, options |
| `ALTERNATIVE` Alternatives / physical assets | real estate, gold, crypto, vehicles, collectibles |
| `PROTECTION` Protection | annuities, whole life insurance (valued at cash value) |

> On "authority": there is no single top authority in the asset allocation space. The four
> SAA classes are the most commonly used top-level split in institutional practice, grounded
> in Markowitz's Modern Portfolio Theory. The "Standard & Poor's Family Asset Quadrant"
> (the "4-3-2-1 rule") circulated in Chinese personal-finance circles **is not an official
> S&P publication** — it's a phrase that emerged through marketing/word of mouth. It's a
> useful popular framework, but shouldn't be cited as an authoritative source. If this is
> ever referenced in the UI, don't write it as "S&P research shows..."

### Layer two: subtype

A set of common subtypes is predefined (see the right-hand column of the table above),
**and users can also add their own.** The subtype table needs an `isBuiltIn` flag — built-in
subtypes cannot be deleted, only hidden, otherwise historical assets would end up pointing
at nothing.

Subtype determines the default value of `defaultValuationMode` (A-shares default to
`QUOTED`, real estate defaults to `MANUAL`).

## Asset allocation: target percentages and deviation

This is the app's second core view (the first is the net worth curve): **how far is my
allocation from what I intended.**

### Denominator: total net worth

**Decided: the denominator is total net worth** (including a primary residence, minus
liabilities), not just investable assets.

This choice creates an arithmetic problem that has to be solved. If the denominator is net
worth and the numerator is each class's raw asset value, **the percentages sum to more than
100%**: a ¥3M house + ¥1M in stocks, with a ¥2M mortgage → net worth ¥2M, real estate =
300/200 = 150%, stocks = 50%, total 200%. That can't be drawn as a pie chart.

**Solution: attribute liabilities to their corresponding class and offset them there**,
so each class displays its **net exposure**.

```
Class net exposure(class) = Σ assets in that class - Σ liabilities attributed to that class
Allocation percent(class) = class net exposure(class) / net worth
```

Same example: real estate net exposure ¥1M (50%) + stocks ¥1M (50%) = 100%. ✅

Attribution rules: mortgage → `ALTERNATIVE`, auto loan → `ALTERNATIVE`, credit card /
consumer loan → `LIQUID` (unsecured debt is attributed to whichever class you'd use to pay
it off). **Every liability must have an `assetClass`** — otherwise the classes won't sum to
net worth and the percentages won't close.

**Edge case: a class's net exposure is negative** (a ¥100K car against a ¥150K auto loan, or
credit card debt exceeding liquid funds). A pie chart can't render a negative slice. Rule:
in the pie chart, treat it as 0 and explicitly label that class as negative; show the true
negative value in the detail view. **Don't silently clamp it to 0** — that would hide
something the user genuinely needs to know.

**An honest expectation to manage**: because a primary residence counts toward the
denominator, a homeowner will likely see `ALTERNATIVE` at 50%+, while preset target
allocations only put it at 5–10%. This isn't a bug — it's the direct consequence of using
total net worth. If this makes target allocations lose their usefulness as guidance, the
cheapest fix is to turn off `includeInAllocation` for the primary residence.

### TargetAllocation — target allocation, multiple sets coexist

- `id` / `name` ("Conservative" / "My 2026 Plan") / `isBuiltIn` / `isActive`
- Linked to multiple `TargetAllocationItem` records: `assetClass` + `targetPercent`
- **Multiple sets can exist simultaneously and be compared side by side** ("current vs.
  Conservative vs. Aggressive"), with one marked as active
- Validation: a set's `targetPercent` values must sum to 100

Three presets are built in (Conservative / Balanced / Aggressive). **The preset values are
common industry starting points, not authoritative prescriptions**, and users must be able
to edit them.

**The semantics of built-in vs. custom**: `isBuiltIn` only means "shipped with the app,
cannot be deleted" — **it does not mean "cannot be edited."** domain.md requires from the
outset that presets be editable. But if a user messes one up they need a way back, so
built-ins offer "restore defaults" (re-writing the values from `BUILT_IN_PRESETS`). Custom
sets can be deleted.

⚠️ **Worth noting from a product/compliance standpoint**: an app telling a user "you should
allocate 30% to equities" could, in some jurisdictions, be construed as investment advice.
It's recommended that the UI present presets as **generic templates** rather than
recommendations tailored to that user, and avoid phrasing like "we recommend that you...".
This isn't a technical problem, but it's costlier than one.

### Deviation

```
Deviation(class) = allocation percent(class) - target percent(class)
```

Positive means overweight, negative means underweight. This is a derived value, not stored.

## Growth rate: two measures that must be kept separate

**Net worth growth ≠ investment return.** If ¥10,000 of salary gets deposited this month,
net worth rises by ¥10,000, but that isn't "earned." Conflating the two would let a user
misjudge their own investing skill.

```
Net worth growth rate  = (ending net worth - starting net worth) / starting net worth      ← includes new contributions, measures "change in wealth"
Investment return rate = (gain/loss(end) - gain/loss(start)) / starting cost basis         ← excludes new contributions, measures "investing skill"
```

The second depends on `costBasisMinor` (see "Cost basis and unrealized gain/loss") — this is
exactly what cost basis is for.

Both must be shown in the UI, and **the labels must make the distinction clear.**

Monthly / quarterly / yearly grouping is done at the query layer (grouped by `LocalDate` in
the user's local time zone), requiring no additional table.

**The growth rate must always be shown alongside its baseline.** The same "+2%" starts from
a completely different point depending on whether you're looking at month/quarter/year — the
percentage alone doesn't say what it's relative to. And **the percentage needs an amount next
to it**: "+2%" isn't memorable, but "+¥12,345" is.

**The baseline is the previous sample point, not the oldest point in the window.** In the
monthly view the overview card answers "how did I do this month", so it compares against last
month and moves forward as new periods are recorded. Anchoring it to the oldest point made it
a cumulative figure that stayed stuck on the first month forever (reported from real usage:
recorded in August, September and October, and the card still read "compared to August"),
*and* it contradicted the bar chart directly below, whose growth labels have always been
period-over-period — two different percentages for the same latest bar, with nothing on screen
saying they used different bases. It also made the headline number depend on the chart's
`trimBeforeFirstSnapshot` display toggle, which must not change what a number means.
See `NetWorthSeries.endpoints`.

## Liability ratio: the denominator is total assets

```
Liability ratio = total liabilities / total assets
```

**The denominator is not net worth.** For the question "what fraction of my wealth is debt,"
using net worth as the denominator would produce figures over 100% under high leverage
(¥1M net worth carrying a ¥2M loan → 200%), which doesn't mean anything readable.

**When total assets ≤ 0, the result is null, not 0%.** 0% would be read as "no debt," which
is a completely different state from "zero assets recorded, just one credit card entry."
This follows the same principle as "can't-value is never shown as 0": **an
undefined/meaningless quantity must never be displayed as 0.**

## Data freshness: how recent is the latest record

A direct consequence of "snapshots, not a ledger": **net worth doesn't update itself** — a
record from three months ago and a record from today look identical in the UI. So the top
of the screen must show the date of the most recent snapshot and how many days ago that was
— this is the user's only clue for judging "does this net worth figure still mean anything."

**Only unarchived assets' snapshots count.** Archiving appends a zero-value snapshot, which
is the action of "ending upkeep" for that asset; treating it as "the latest record" would let
a single archive action make the whole portfolio look freshly updated.

We don't presume to tell the user "it's time to update": update cadence varies by person —
someone who tracks monthly and someone who tracks quarterly have wildly different tolerances
for what counts as "stale."

## Representing amounts and shares: never use Double

Amounts are stored entirely as `Long` in **minor currency units** (cents). Net worth is a
large sum of additions, and floating-point error accumulates and gets amplified — and the
entire value of this app rests on that final total being trustworthy.

Shares need fractional values (fund units, crypto), represented as a **fixed-point integer**
`quantityScaled` with a fixed scale (recommend scale=8, enough to cover BTC down to the
satoshi level). **Never use Double.**

`shares × unit price` is a multiplication of two fixed-point numbers — multiply before
dividing, normalize by the scale, and never convert to floating point along the way.

## Time handling

- Storage uses `kotlin.time.Instant` (UTC)
- Display and "group by day/month" use `LocalDate` in the user's local time zone
- A snapshot's `asOf` means "state as of the end of that day" — must stay consistent across
  time zones

## Easy mistakes to make when implementing this

1. **Valuation must branch on `Snapshot.mode`, not `Asset.defaultValuationMode`.** This is
   the crux of mode conversion and historical correctness — getting it backwards won't raise
   any error until a conversion actually happens, and will then silently compute wrong values
   from then on.
2. **The archive's zero-value snapshot must participate in carry-forward, but must not be
   drawn on the detail chart.** These two pieces of logic belong to the data layer and the
   display layer respectively — don't conflate them.
3. **The denominator for gain/loss can be 0 or null.** Assets with no cost basis entered, and
   liabilities, don't participate in gain/loss.
4. **`shares × unit price` is a multiplication of two fixed-point numbers** — multiply before
   dividing, normalize by scale, never convert to floating point along the way.
5. **Each snapshot is a complete state, not a delta.** When a user only changes market value,
   `costBasisMinor` must be pre-filled forward from the previous snapshot — leaving it blank
   is equivalent to erasing the cost basis.
5b. **The flow for changing share count should also prompt for updating cost basis.** Adding
   to a position means more money was invested; if shares go up but cost basis doesn't, the
   return rate will look inflated. Since changing shares already requires writing a new
   snapshot, collect cost basis in the same form — make "forgot to update cost basis"
   structurally hard to happen, rather than relying on the user remembering.
6. **The numerator for allocation percentage is "net exposure," not raw asset total.**
   Forgetting to subtract attributed liabilities makes the classes sum to more than 100%.
7. **Every liability must have an `assetClass`.** Missing one makes the percentages fail to
   close, silently, with no error raised.
8. **Net worth growth rate and investment return rate are two different numbers** — don't use
   one to stand in for both in the UI.
