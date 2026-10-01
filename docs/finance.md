# Finance

PercySafe has a second app inside it: **Finance**, opened from the drawer behind the top bar's
menu button. It puts the household's own money next to how the country's economy is doing, in
Robinhood's dark look: true black, one green for up and one orange-red for down.

| Tab | What it shows | Where the data comes from |
|---|---|---|
| **Wallet** | Total assets over time, net worth, accounts by owner, allocation, monthly cash flow, debts, home equity, a mortgage planner, RSU/ESPP vesting, income and taxes by year, the last house sale, and the sheet's watchlist with live prices | The household budget sheet (Google Sheets), through the relay. Quotes from Yahoo Finance |
| **Markets** | S&P 500, Dow, Nasdaq, Russell 2000 and VIX, with live charts from 1 day to 5 years; 10-year yield, gold, oil, bitcoin and the dollar; the watchlist | Yahoo Finance's public chart endpoints, polled every 15 s while markets are open |
| **Economy** | Inflation (CPI, core CPI, core PCE against the Fed's 2%), the Treasury yield curve now and in the past, 2/10/30-year yields, the real interest rate, key rates | FRED (the St. Louis Fed), keyless CSV downloads |
| **Risk** | Twenty warning lights for a downturn or crisis, each against its own watch and danger line, blended into one 0–100 stress gauge | FRED |

Tapping a quote or an indicator opens its own page, with its chart, an explanation and its stats.

## Plain English

The app assumes you've never read a financial page.

- **ⓘ on everything.** Every section, chart, stat and indicator has an ⓘ that opens an explainer
  sheet (`ExplainSheet`, content in `Explainers.kt`). It covers what the thing is in one sentence,
  **Right now** (the live reading and which way it's been heading), **What it means for you** (in
  the household's own dollars when the sheet has the numbers), why it matters, an everyday
  comparison, what's normal, how it works, and chips for related ideas. Each chip turns the sheet
  to that idea.
- **Plain names.** Indicators lead with a plain name ("Long vs short-term borrowing costs"), with
  the technical name small beneath. Each one gets a one-sentence verdict built from the current
  reading (`Narrator.verdict`).
- **Today's economic weather.** This card tops the Economy tab. Prices, jobs, borrowing, markets,
  recession signs and government debt each get a sunny, cloudy or stormy sky and a sentence
  (`Narrator.briefing`).
- **How it all connects.** An interactive cause-and-effect map of ten parts of the economy with
  live readings (`ConnectionsScreen`). Tap a part to light up what it pushes on and what pushes on
  it. *Walk me through it* steps round the central loop: prices → the Fed → mortgages → home prices,
  and the Fed → jobs → shoppers → prices.
- **Stress scale.** Calm, elevated, high and severe are spelled out under the Risk gauge, with
  what today's band means.
- **Money checkup.** On Wallet: emergency fund, savings rate, costly debt, debt against what's
  owned, and saving for later. Each gets a pass or a flag against a common rule of thumb, with a tip.
- **Charts explain themselves.** Each one has a *How to read it* note. Past US recessions are
  shaded on the economic charts (`Recessions.us`), so you can see what a warning sign did just
  before each one. The Fed's 2% target always shows on the inflation chart.
- **Jargon buster.** The ? in the top bar opens every term, grouped by topic and searchable.

Every sentence comes from live numbers and rules of thumb, not forecasts, and none of it is advice.

## The budget sheet

The sheet stays private. The phone holds no Google credential and the sheet is never published.
The relay reads it with a Google **service account**, which the sheet is shared with read-only, and
passes it to a signed-in app like any other relay route (`GET /finance/sheet`, relay.py, "finance").
The relay sends the sheet as it is: every tab's raw values and its merged ranges. The app then reads
it with `PersonalFinanceParser`, which finds each block by its title ("Brokerage Accounts", "Flow
Out", "Debt", "Future Holdings", the history table's "Date" row, and so on) rather than by cell
address. Rows can be added, moved or removed. A block the parser can't find is left off the screen.

Only the server's **Frigate admin** accounts can read it. A viewer account, say a sitter's, gets
"Not for this account". To let a household member whose Frigate login is a viewer in too, add
their username to `FINANCE_USERS` (comma-separated) in `finance.env`. Admins keep access either
way. Every answer is marked `Cache-Control: private, no-store`.

A few conventions the parser relies on:

- **Joint accounts** have one amount merged across both people's columns. Two separate amounts
  mean one account each.
- **Account categories** come from names: "529" is education; "Roth", "401", "403", "457",
  "IRA", "pension", "HSA", "retire" or a big plan record keeper (ADP, Empower, Voya and so on)
  is retirement; "checking", "savings" or "cash" is cash; anything else is investing. Rename an
  account in the sheet to move it, e.g. "… Pension".
- **Net worth** is total assets less every debt except the mortgage. The sheet counts the house as
  the equity built in it, not its full value, so the mortgage against that full value isn't
  subtracted again. The sheet's own history does the same.
- The big chart plots **total assets**, not net worth. Before the old house was sold, the history's
  "Debt" column included that house's mortgage, but "Total Assets" didn't include the house, so a
  net-worth line would jump by a house's worth at the sale.

### One-time setup

1. In the Google Cloud project of the relay's service-account key (by default the push key,
   `fcm-service-account.json`, project `homesafe-percysafe`), enable the **Google Sheets API**:
   <https://console.cloud.google.com/apis/library/sheets.googleapis.com>.
2. Share the budget sheet with the key's `client_email` as a **Viewer**. Until you do, the app
   shows that address on the Wallet tab.
3. On the Frigate box, put the sheet's id (the long part of its URL) in `relay/finance.env`:

   ```
   FINANCE_SHEET_ID=<sheet id>
   ```

   The repo is public, so the id isn't in `docker-compose.yml`. `finance.env` is gitignored.
4. Deploy the relay as usual (`docker compose up -d --build` in `relay/`).

Each missing step has its own message on the Wallet tab (`SheetSetupCard`): the API is off, the
sheet isn't shared, no id is configured, or the relay is older than this feature.

## How it's built

- `finance/domain`: the models (`Series` is primitive arrays, since daily FRED series run to
  thousands of points), `IndicatorCatalog` (every reading with its thresholds, explanation and
  weight), `MarketCatalog`, and `FinanceRepository`.
- `finance/data`: `YahooFinanceApi` and `FredApi` share their own `HttpClient`, so Frigate's
  session cookies never reach a third party. Also `FinanceRelayApi`, `SheetGrid` +
  `PersonalFinanceParser`, and `FinanceRepositoryImpl`, which caches everything in memory and
  shares in-flight loads between tabs.
- `FinanceViewModel` is activity-scoped and shared with the drawer's teaser card. It polls quotes
  only while the drawer or the app is on screen. Under the iOS 26 host, only the visible tab's
  composition counts as on screen.
- `finance/ui`: the screens, and components built for them:
  - `LineChart`: Robinhood scrubbing with haptic ticks, a morph between any two datasets, a
    draw-on reveal, danger zones and reference lines.
  - `RollingNumber`: odometer digits.
  - `DonutChart` and `BarChart`.
  - Two runtime shaders, AGSL on Android and the same source as Skia effects elsewhere: the drifting
    aurora behind each headline (`AURORA_SHADER`) and the stress gauge's plasma ring
    (`STRESS_RING_SHADER`). Each has a plain fallback where shaders can't compile.
- While the finance app covers the screen, the shell stops composing the tabs underneath, so the
  camera streams stop. Their saveable state comes back when Finance closes.
- Previews of every tab, from made-up fixtures (`FinancePreviews.kt`), render with
  `./gradlew :shared:renderPreviews -Ppreview=Finance`.

The web build can't reach Yahoo or FRED (no CORS headers), so its finance tabs show their error
states. Nothing in the app is investment advice. The thresholds are rules of thumb taken from past
recessions, and each one's page explains it.
