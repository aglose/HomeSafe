# Finance

PercySafe has a second app inside it: **Finance**, opened from the drawer behind the top bar's
menu button. It puts the household's own money next to how the country's economy is doing, in
Robinhood's dark look: true black, one green for up and one orange-red for down.

The drawer's Finance card shows the S&P 500 and net worth until **Settings → Finance in the menu**
says otherwise: up to four of the Markets tab's indices and macro markets (each with today's price,
change and sparkline) and the household's net worth, total assets, investments, cash and debt
excluding the mortgage. The choice is kept on the device (`FinancePreferencesEntity.drawerItems`).

| Tab | What it shows | Where the data comes from |
|---|---|---|
| **Wallet** | Total assets over time, net worth, the sheet's own charts, accounts by owner, allocation, monthly cash flow, debts, home equity, a mortgage planner, RSU/ESPP vesting, income and taxes by year, the last house sale, and the sheet's watchlist with live prices | The household budget sheet (Google Sheets), through the relay. Quotes from Yahoo Finance |
| **Budget** | The month's credit card spending against the household's limits: how fast the month is being spent, who spent what, where take-home goes, and every purchase | The linked cards, read hourly through Plaid by the relay. See [budget.md](budget.md) |
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

### The sheet's own charts

The charts in the sheet show up on the Wallet tab under **Sheet charts**, in the order they sit in
the sheet, each drawn the finance app's way: a headline with the latest value and its change, lines
and areas that scrub, bars that select by tap or slide, pies that highlight a slice. "Sheet ↗" opens
the chart's tab in Google Sheets.

Nothing is mapped by hand. The relay passes on what Google says about each chart: its kind, whether
it stacks, the ranges it plots, and the number format of those cells (one extra call, for the
formats). `SheetChartReader` then reads those ranges from the values it already has, the way Sheets
does:

- A range runs down a column, or along a row when it's one row high. Blank rows are dropped, so a
  range left long for rows to come (`S61:S203`) is fine.
- The header count is the chart's own setting. When the sheet leaves it to Google, a range that
  starts with words has a header and one that starts with a number doesn't. With no header, the
  label just above the range names the series.
- The x axis is dates when the cells are formatted as dates. Without a format it's dates when every
  value is a date serial between 1970 and 2099. Years like 2024 stay labels.
- Values are money when the cells' format is currency or has a `$`, percentages when it's a percent.
  Anything else is a plain number.
- Lines, areas, stepped areas, scatter and combo charts are drawn as lines. Column and bar charts are
  drawn as upright bars, green above zero and red below when there's one series. Pies become donuts
  and scorecards a big number. Any other kind (a waterfall, a treemap) gets a card that links to it.

So a chart added, edited or removed in the sheet changes the app on the next refresh, with no change
to the app or the relay.

### Keeping in sync

The app reads the sheet whenever Finance (or the drawer) opens, every two minutes while it's on
screen, and on a pull to refresh. The relay keeps a read for a minute and the app for two, so an
edit shows within a few minutes, or at once with a pull.

Reorganising the sheet is expected. Each part is found by its title, so moving a part around its
tab, inserting or deleting rows and columns, a few blank rows inside a block or the history, and the
people's columns sitting a few columns away from a block's title are all fine. Renaming a part's
title is what loses it, and so is moving a Home part to another tab: the Home parts are read from
the tab that has "Brokerage Accounts" (or "Monthly Cash Flow"), Income & taxes from the tab with the
"Year" / "Take Home" table, and so on. Renaming a tab is fine.

Every read also builds a report (`SheetHealth`): each part read, empty (its title is there, nothing
under it could be read) or missing (no title), each chart drawn or why not (a kind the app doesn't
draw, empty cells, a tab that's gone), and rows the parser had to leave out (a history row with no
date). The Wallet's first line shows it:

- green, "Synced 3 min ago · everything read";
- amber, "Synced · 2 things need a look", when a part or a chart couldn't be read;
- red, "Couldn't sync · showing the sheet from 2 h ago", when the last read failed. The page keeps
  the last read that worked rather than going blank.

Tapping it opens **Sheet sync**, which lists every part with what it found ("22 lines", "25
snapshots") or the title it looks for, every chart, and anything left out, with Sync now and Open the
sheet.

### Balances from the banks

The balances in the sheet don't have to be typed. **Linked accounts**, at the top of Sheet sync,
links each bank, brokerage and lender through Plaid; the relay then reads every account each
morning and writes it to a feed the sheet's cells look up, or types it into the cells you list. Each
write to a cell is shown there with what it changed, and can be undone. See [bank-sync.md](bank-sync.md).

### Balances typed by hand

Some balances no linked bank reports: a small 401(k) record keeper, a pension, an HSA at a payroll
provider, what the house is worth. They stay typed in the sheet, and someone logs in now and then
and copies the number over. **Finance → Wallet → Typed balances** (or **Update balances**) lists
every one of them with how long ago it was last updated:

- green within a month, amber after a month, red after two months, and amber with "No update
  recorded" for one never updated from the app;
- the Wallet's card says how many want updating and how old the oldest is, and each account and
  debt row on the Accounts and Debt pages says its age too.

Tapping a balance opens it with the sheet's number filled in. Type the new one and **Save**: the
relay writes it into that cell of the budget sheet and the app reads the sheet again. Saved
unchanged, the button says **Still right**, which marks it as checked without writing anything.

What is listed: every account (in "Brokerage Accounts" and "Checking/Savings", and the lone "Cash"
line), every debt and the home's value, wherever the cell holds a number typed in. A cell with a
formula (a total, or a lookup in the bank feed) is left out, since it follows other cells; so is
the home's equity, which the sheet works out. Balances the bank sync writes into the sheet each
morning (the sheet fill's lines that aren't paused) are listed apart, under **Filled in by the
bank**, dated by when Plaid last read their accounts. An account at zero stays on the list, so
it can be typed into when it holds money again.

How it's kept safe (`PUT /finance/sheet/value`, relay.py, "typed balances"):

- The cell is found by its row's label and how many columns right of it the number sits, the
  way the sheet fill finds it, never by an address. A label in its tab twice isn't guessed
  between: rename one of the two to type into it.
- The cell must still hold the number the app showed. Edited in the sheet since, the write is
  refused and the app reads the sheet again, so the editor shows the number there now.
- Only a typed number or an empty cell is written over, never a formula or words, and never a
  cell the bank sync fills in (unless its line is paused).
- Each write is listed with the sheet fill's under **What was changed** on the Bank sync page,
  marked as typed in the app, and **Undo** there puts the old number back. That page and its undo
  work without Plaid set up, for a household that only types balances in.

Google keeps no time per cell, so the relay keeps the ages itself (`sheet_values` in `relay.db`):
when a balance was typed or confirmed in the app, and when the sheet fill wrote it or found it
already right. An edit made straight in the sheet isn't seen, so a balance typed there still
shows its last age from the app. The budget sheet must be shared with the service account as an
**Editor** for the write (as for the sheet fill, [bank-sync.md](bank-sync.md)); with only Viewer
access the app says so.

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
- **Saving each month** counts money that never reaches take-home. The Forecasts tab's
  "Investments" table (the people's names under the title, then a block per year: the year, a row
  per account, a "Total") says what went in each year. Its workplace-plan rows ("401", "403",
  "457", "TSP", "pension", "HSA" in the name) come out of the paycheck first, so the latest year's
  are spread over twelve months and added both to what's saved and to pay: (left over + set aside)
  ÷ (take-home + set aside). The table's other rows (an IRA, a 529, a brokerage account) are paid
  from money already taken home and aren't added again. Without the table the figure is left over
  ÷ take-home.
- **Net worth** is total assets less every debt except the mortgage. The sheet counts the house as
  the equity built in it, not its full value, so the mortgage against that full value isn't
  subtracted again. The sheet's own history does the same.
- The history's **"Debt" column** leaves mortgages out too, so the Debt chart and today's debt
  figure count the same things. Its snapshots from before the old house was sold used to include
  that house's mortgage; in October 2026 they were rewritten in the sheet without it, the loan's
  balance on each date worked out from its last statement.
- The big chart plots **total assets**, not net worth.

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

## What is kept

The months are meant to be set side by side for years, so the relay keeps its own record in
`relay.db`, for good. All of it stays on the Frigate box; none of it reaches a log or the feed.

| What | Where | How often |
|---|---|---|
| Every card purchase, with Plaid's category and whatever was tagged | `plaid_transactions` | As read, hourly |
| Each account's balances | `plaid_balance_days` | One row per account per day |
| Each investment's quantity, price and value | `plaid_holding_days` | One row per holding per day |
| Stock plan vests | `plaid_stock_events` | Past Plaid's two years they stay as read |
| The limits, each card's role and the sheet's plan | `budget_plans` | One row per month, as the month left it |
| The budget sheet's tabs | `finance_sheet_days` | A copy on each day it differs from the last (zlib-compressed JSON) |

- **Comparisons are worked out when asked for**, not saved as totals. So sorting a purchase
  differently later changes the months it was in, and a new way of comparing reaches back over
  everything already kept. Only the plan is frozen: what a month was held to is what it had then.
- **Unlinking keeps the past.** Plaid is told to forget the institution and the relay deletes its
  token, but what was bought on a card stays, under the card's key, and goes on counting in its
  month; so do the balances and holdings. Purchases still pending go, since they may never post,
  and so do its stock plan vests, which the page reads by account. There is no button to erase the
  history; that takes a query on the box.
- **Linking again doesn't count anything twice.** Each posted purchase read under the new link
  takes the place of the kept one of the same card, day and amount, and takes on its tags. A
  purchase whose day or amount changed in between would show up twice.
- **How far back it goes.** A new card link asks Plaid for all 730 days it offers (`PLAID_TXN_DAYS`).
  Plaid fixes that when the link is made: a link from before it stays at the 90 days it asked for,
  and signing in again doesn't change it. Only unlinking and linking again brings more, using up
  another of the plan's links. Banks may hold less than two years.
- **Only credit cards.** A bank account's transactions are read and dropped (see
  [budget.md](budget.md)), so rent or a mortgage paid from checking is known only from the sheet.

### The nightly copy

Each night's first check copies `relay.db` to `RELAY_BACKUP_DIR` (`/mnt/surveillance/relay-backups`
on the box, the recordings drive, a different disk from `relay.db`'s) and checks the copy. It keeps
the last 14 nights and each month's first for good. A copy holds everything `relay.db` does, Plaid's
tokens included, so the folder and files are for root alone. It guards against a lost system disk
or a bad migration, not against losing the box: nothing leaves it.

To restore, stop the relay, put a copy in place of `data/relay.db`, and start it again.

## Without the server

Finance opens whether or not the camera server can be reached: from the drawer as usual, and from
the sign-in screen, which opens the same drawer (see "Before sign-in" in the README).

- **What it shows.** Everything it fetches is kept on the device as it arrives, one row of JSON
  per thing in `FinanceSavedEntity` (Room, schema 21): the sheet as the relay sent it, each month
  of the budget, the linked banks, each quote, each chart, each FRED series. With nothing to fetch
  from, the pages are drawn from those rows. The sheet, the budget and the banks say how old they
  are in the places they always did, and the top bar says "Offline · saved on this device". A quote
  or a chart that couldn't be refreshed keeps its error beside the kept copy, so a stale price is
  never passed off as live. Changes (tagging a purchase, a limit, "Sync now", linking a bank) need
  the server and fail with "Not connected to the server"; the page stays as it was.
- **Who may see it.** Online, the server decides: every read of the household's money rides on
  its session. Offline there is nobody to ask, so the app opens on a lock and asks for a
  fingerprint or a face (`FinanceAccess`, `IdentityCheck`). That holds even when the saved login
  was just opened with a fingerprint: Finance asks for another. It locks again when the app is
  closed, when the phone goes to the background, and when the server comes back (so the next
  outage asks again). A prompt answered after any of those has happened under it unlocks nothing.
  A server that dies mid-session is caught by the first request it doesn't answer: that request
  has the connection look again (`ConnectionRepository.checkServer`), and if the server is gone
  the lock is on before the kept copy is reached for. From then on it is asked every 15 seconds
  until it answers, and no request is sent to it meanwhile. While it is locked the repositories refuse the kept figures and the view
  model drops the sheet, so the drawer's card shows its markets and "Locked while offline" rather
  than the household's figures. A device with no fingerprint or face enrolled (and the desktop and web builds)
  can't unlock: there Finance waits for the server.
- **Whose it is.** The kept sheet, budget and banks belong to the server they came from (its
  Tailscale address). A session on any other server is shown none of them, and the first answer
  kept from another server deletes what the last one left. Nobody signed in at all is shown what
  the last server left. Accounts on the same server are not told apart on the device: the server
  does that, by refusing the ones it doesn't show the money to, which deletes the kept copy.
- **What is not protected by it.** The lock is the app declining to read and draw the rows; the
  rows themselves are plain JSON in the app's private database, like the rest of what the app
  keeps. If the server refuses the account (signed out, or not an admin), the kept sheet, budget
  and banks are deleted.

## How it's built

- `finance/domain`: the models (`Series` is primitive arrays, since daily FRED series run to
  thousands of points), `IndicatorCatalog` (every reading with its thresholds, explanation and
  weight), `MarketCatalog`, and `FinanceRepository`.
- `finance/data`: `YahooFinanceApi` and `FredApi` share their own `HttpClient`, so Frigate's
  session cookies never reach a third party. Also `FinanceRelayApi`, `SheetGrid` +
  `PersonalFinanceParser`, `SheetChartReader`, and `FinanceRepositoryImpl`, which caches
  everything in memory and shares in-flight loads between tabs. `FinanceSaved` keeps the last
  answer to each call on the device, and `FinanceAccessImpl` says when those may be shown (see
  "Without the server").
- `FinanceViewModel` is activity-scoped and shared with the drawer's teaser card. It polls quotes
  only while the drawer or the app is on screen. Under the iOS 26 host, only the visible tab's
  composition counts as on screen.
- `finance/ui`: the screens, and components built for them:
  - `LineChart`: Robinhood scrubbing with haptic ticks, a morph between any two datasets, a
    draw-on reveal, danger zones and reference lines.
  - `TypedBalancesScreen`: the balances typed by hand, their ages and the editor that types a new
    one in (`typedBalances` in `domain/TypedBalances.kt` picks them out of the sheet).
  - `RollingNumber`: odometer digits.
  - `DonutChart`, `BarChart`, and `GroupedBarChart` (several series side by side or stacked, for the sheet's charts).
  - Two runtime shaders, AGSL on Android and the same source as Skia effects elsewhere: the drifting
    aurora behind each headline (`AURORA_SHADER`) and the stress gauge's plasma ring
    (`STRESS_RING_SHADER`). Each has a plain fallback where shaders can't compile.
- While the finance app covers the screen, the shell stops composing the tabs underneath, so the
  camera streams stop. Their saveable state comes back when Finance closes.
- Previews of every tab, from made-up fixtures (`FinancePreviews.kt`), render with
  `scripts/render-ui-previews.sh` (Layoutlib; see docs/ui-previews.md).

The web build can't reach Yahoo or FRED (no CORS headers), so its finance tabs show their error
states. Nothing in the app is investment advice. The thresholds are rules of thumb taken from past
recessions, and each one's page explains it.
