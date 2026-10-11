# Bank sync: balances from the banks themselves

The budget sheet's balances used to be typed in by hand. With bank sync, each bank, brokerage and
lender is linked once through [Plaid](https://plaid.com), and from then on the relay reads every
account's balance each morning and writes it to Google Sheets, where the budget sheet looks it up
or has it typed in for it (see "Filling the budget sheet's own cells").
The app keeps reading the budget sheet exactly as before (see [finance.md](finance.md)).

In the app: **Finance → Wallet → the sync line → Linked accounts**.

## How it works

- **Linking** happens on Plaid's own page, at the institution's own sign-in where it has one
  (Chase, Schwab, Fidelity and most large institutions). The app asks the relay for a link, opens
  it in the browser, and the relay asks Plaid how it ended. HomeSafe never sees a bank password.
  The relay also looks for itself, every five minutes and whenever Linked accounts is opened:
  Android can restart the app while you are away at the bank's sign-in, and a sign-in nobody
  came back to collect is lost half an hour later.
- **What is kept** is one access token per institution, in `relay.db` on the Frigate box. The
  relay asks Plaid for balances, investment holdings and loan terms, and for what was bought on
  the credit cards (kept in `relay.db` too; see [budget.md](budget.md)). No product that can move
  money (Auth, Transfer, Payment Initiation) is ever requested. The phone never holds a token.
- **Every day at 6:00** on the household's clock (`BANK_SYNC_HOUR`), a thread in the relay reads
  every institution and rewrites the feed. There is no cron job; it is part of the relay container.
  A relay that was down at 6:00 catches up within five minutes of starting. **Sync now** in the app
  does the same at once, at most once a minute. The cards' purchases are read every hour as well,
  for the Budget tab.
- **Nothing new is public.** The relay polls Plaid instead of receiving webhooks, so no route is
  added to Tailscale Funnel.
- Only accounts that may see the finances (Frigate admins, or `FINANCE_USERS`) can use any of it.

Plaid refreshes an institution about once a day, and holdings after the markets close, so the
morning read has the previous day's closing numbers. Syncing more often mostly rereads the same
figures.

## The feed

The relay writes two tabs, and nothing else, in the sheet named by `FINANCE_FEED_SHEET_ID`:

| Tab | One row per | Columns |
|---|---|---|
| **Bank feed** | account | Key, Balance, Available, Limit, Institution, Account, Mask, Type, Subtype, APR %, Minimum payment, Payment due, Currency, Updated |
| **Holdings feed** | position in an investment account | Account key, Ticker, Name, Quantity, Price, Value, Cost basis, Kind, Institution, Currency, Price as of |

The **key** is the institution, the account's name and its last four digits, for example
`Chase Total Checking 0123`. A second account with the same three gets ` (2)`. A key is given once
and kept: closing one of two same-named accounts doesn't hand its key to the other, and a bank
renaming an account doesn't change its key. An institution unlinked and linked again comes back
under the keys it had. A card's or a loan's balance is what is owed, as a positive number.

In the budget sheet, replace a typed balance with a lookup of its key. There are two ways to set
the feed up, and the formula differs:

- **A feed sheet of its own (recommended).** The relay can then only *read* the budget sheet, as
  today, and can write nothing but the feed.

  ```
  =VLOOKUP("Chase Total Checking 0123", IMPORTRANGE("https://docs.google.com/spreadsheets/d/<feed id>", "Bank feed!A:B"), 2, FALSE)
  ```

  The first `IMPORTRANGE` in a sheet shows `#REF!` until you click it and choose **Allow access**.
- **Tabs in the budget sheet itself.** Set `FINANCE_FEED_SHEET_ID` to the budget sheet's own id and
  share that sheet with the service account as an Editor. The formula is shorter, but the relay's
  key could then edit the whole budget sheet.

  ```
  =VLOOKUP("Chase Total Checking 0123", 'Bank feed'!A:B, 2, FALSE)
  ```

Several accounts in one cell add up: `=SUMIF('Bank feed'!H:H, "depository", 'Bank feed'!B:B)` is
all the cash. An account that is unlinked or closed leaves the feed, and a cell that looks it up
shows `#N/A` until it is changed.

When Plaid can't read an institution, its rows keep their last balances and the **Updated** column
shows how old they are.

## Filling the budget sheet's own cells

The feed leaves the numbers typed into the budget sheet alone: a cell follows it only once its
number has been swapped for a lookup. The **sheet fill** does the typing instead. You list the
cells once, and after every sync the relay writes each one's balance into it.

The list is `sheet-fill.json`, beside `relay.db` on the Frigate box (`~/surveillance/relay/data/`).
It names accounts, so it is never committed. Each line is one cell:

```json
{
  "tab": "Home",
  "lines": [
    {"label": "Roth IRA", "right": 2, "accounts": ["Chase Roth IRA 7788"]},
    {"label": "Checking and savings", "right": 1, "accounts": ["Chase Total Checking 0123", "Chase Premier Savings 4410"]},
    {"label": "Credit Cards", "right": 1, "accounts": ["Chase Sapphire 9911"], "negative": true}
  ]
}
```

| Field | |
|---|---|
| `label` | The words in the row's label cell, as the sheet has them. Capitals and extra spaces don't matter. |
| `right` | How many columns to the right of the label the number sits (1 if left out). |
| `accounts` | The feed keys the cell adds up: column A of the **Bank feed** tab. |
| `negative` | `true` for a sheet that keeps what's owed below zero. A card's or a loan's balance is positive in the feed. |
| `tab` | The tab the label is in, for a line that isn't in the file's own `tab`. |

The file is read at every sync, so a change to it needs no restart: save it and tap **Sync now**.
The budget sheet has to be shared with the service account as an **Editor** (it already is when the
feed's tabs live in the budget sheet).

What keeps a write off the wrong cell:

- **The cell is found by its label every time**, never by a remembered address. Rows added above
  it move the write with it. A label that is gone, or in the tab twice, is not guessed at.
- **Only a typed number or an empty cell is replaced.** A formula or words in the cell mean the
  sheet was changed, and the line is left alone.
- **A line is written whole or not at all.** If one of its accounts is unlinked or has no balance,
  the sum of the rest is not written.
- **A cell that already holds the number isn't touched**, so a day with no movement writes nothing.
- **Two lines that come to the same cell are both left alone**, since which was meant can't be told.

A line that was left alone says why on the Bank sync page, under **Filled in your sheet**.

### A stock plan's vests

An employer's stock plan (RSUs) links like any brokerage: **Link an institution → Investments or
retirement**. Plaid reports the grants not yet vested and the account's past activity, but no
vesting dates. Vests come at a steady pace, though, so the past ones give the ones to come: the
next vest is one step on from the last (three months, around the same day of the month) and of as
many shares, and so on until the unvested shares run out. The Bank sync page shows this under
**Stock plan**. It is an estimate: a grant ending or a new one starting changes the number, and
the next vest that is read corrects it. Values are at the last closing price, before tax.

A line of `sheet-fill.json` can be one of those vests instead of an account's balance:

```json
{"label": "Nov 15th", "right": 2, "vest": {"account": "Acme Stock Plan 0042", "on": "2026-11-15"}}
```

| Field | |
|---|---|
| `vest.account` | The stock plan account's feed key. |
| `vest.on` | The day the vest falls, `YYYY-MM-DD`. A vest within 20 days of it is the one meant, since vests move around weekends. |
| `as` | `"shares"` to write the number of shares; left out, what they are worth at the last closing price. |

Like every line, it replaces only a typed number or an empty cell, so a cell that works the amount
out with a formula has to be emptied first. To keep a live price in the sheet, have the line write
the share count into a cell of its own and let the formula multiply it. Once the vest has happened
the line says so and leaves the cell with its last estimate; a line whose day has no vest near it
says that instead, and so does one that wants a vest's worth when the plan gave no price.

Every vest is worked out, to the last unvested share; the page shows the next twelve. Where two
vests are within reach of a line's day (a plan that vests every month), the nearer is the one
meant.

Each grant is a row of its own in the **Holdings feed** tab, so one share held several times over
adds up there with `SUMIF`.

### What was changed, and undoing it

Every write is kept on the relay with what each cell held before and after. The Bank sync page
lists them, newest first, under **What was changed**: the label, the old number, the new one and
the difference.

**Undo** on a write puts each of its cells back to the number it held before. The cell is found by
its label again, never by where it was, so a cell whose label has since been renamed or removed is
left as it is, and so is one that no longer holds what was written (someone has typed in it since).
The page says which. The lines of an undone write are then **paused**, because the next morning's sync would
otherwise write the same numbers again. **Resume** on a line writes it at once and from then on;
**Pause** stops one line without undoing anything.

The relay keeps the last 200 writes and shows the newest 20.

## Setup (once)

1. **Plaid account.** Sign up at <https://dashboard.plaid.com/signup> and choose **Personal use**.
   That is Plaid's free Trial plan: real institutions, 10 links in all, with Transactions,
   Investments and Liabilities included and the big institutions that sign in on their own page
   (Capital One, American Express, Chase and the like) available at once. **The 10 are for good:**
   unlinking an institution does not give its place back, so link each one once and leave it
   linked. Note the **client id** and the **production secret** (Developers → Keys).
2. **Feed sheet.** Make a new, empty Google Sheet and share it with the relay's service account as
   an **Editor**. The address is the one the budget sheet is already shared with; the app shows it
   on the bank sync page if the share is missing. Note the sheet's id (the long part of its URL).
3. **Settings.** On the Frigate box, create `~/surveillance/relay/plaid.env` (gitignored; the repo
   is public):

   ```
   PLAID_CLIENT_ID=...
   PLAID_SECRET=...
   FINANCE_FEED_SHEET_ID=<feed sheet id>
   ```

   To try it with Plaid's made-up banks first, use the sandbox secret and add `PLAID_ENV=sandbox`
   (any institution, user `user_good`, password `pass_good`). Institutions linked in the sandbox
   are not carried over to production: unlink them before switching.
4. **Deploy** the relay:

   ```bash
   docker compose up -d --build homesafe-relay
   ```
5. **Link** each institution in the app: **Link an institution**, pick what it is, and sign in on
   Plaid's page. One sign-in to a bank that also holds a mortgage or a brokerage account brings all
   of them. Two people's separate logins at the same institution are linked one after the other.
6. **Point the budget sheet at the feed**, one cell at a time, with the formulas above.

## Settings

| Variable | Default | |
|---|---|---|
| `PLAID_CLIENT_ID`, `PLAID_SECRET` | none | Bank sync is off without both |
| `PLAID_ENV` | `production` | `sandbox` for Plaid's practice institutions |
| `FINANCE_FEED_SHEET_ID` | none | Without it balances show in the app but aren't written |
| `FINANCE_FILL_FILE` | `/data/sheet-fill.json` | The cells of the budget sheet to fill in; nothing is filled without the file |
| `BANK_SYNC_HOUR` | `6` | Hour of the daily read, on the household's clock |
| `PLAID_COUNTRIES` | `US` | Comma-separated country codes Link lists institutions from |
| `PLAID_REDIRECT_URI` | none | Only for a bank whose sign-in hands over to its own phone app; must be registered in Plaid's dashboard |

## When something needs attention

- **"… wants you to sign in again."** The institution ended Plaid's access (a changed password, a
  lapsed consent). Tap **Sign in again**; the same link is refreshed and the feed keys stay.
- **An institution isn't in Plaid's list**, or links without its investment accounts. Start again
  and pick a different kind: *Investments or retirement* lists institutions by their investment
  accounts, *A bank or credit card* by their cash accounts. Small 401(k) record keepers are the
  ones most often missing; those balances stay typed.
- **"The server can't write to the feed sheet."** Share the feed sheet with the address shown, as
  an Editor.
- **"The server can't write to your budget sheet."** The same, for the budget sheet: the sheet fill
  writes there.
- **"The server's list of cells can't be used."** `sheet-fill.json` isn't valid JSON, or one of its
  lines is missing something; the page says which. Nothing is written until the whole file reads.
- **A line says "Not written".** The reason follows it: an account that isn't linked, a label that
  was renamed or removed or is in the tab twice, a cell that now holds a formula, two lines that
  come to the same cell, or a vest's line with no vest expected near its day. Fix the sheet or the
  file and sync.
- **"Plaid said: …"** when linking. Plaid's own explanation is shown as written. `INVALID_API_KEYS`
  means the secret doesn't match `PLAID_ENV`.
- **The Trial plan's 10 institutions are used up.** Plaid refuses the next link and says so. Its
  dashboard shows the count and how to apply for more. Every link ever made counts, unlinked
  ones too.
- **You signed in at the bank and it isn't in the list.** Open Linked accounts again: the server
  collects a finished sign-in when the page is asked for. It has half an hour from the sign-in.

## What stays manual

Home value, the RSU and ESPP vesting schedule, and the income and expense lines are not things a
bank reports, so they stay as typed, as does any balance at an institution that isn't linked. The
sheet's history table is still filled in by hand.

## How it's built

- `relay/relay.py`, "bank sync": `plaid_link_start` / `plaid_link_finish` (Hosted Link),
  `plaid_sync_item` (one institution into `plaid_items`, `plaid_accounts`, `plaid_holdings`),
  `feed_tables` / `feed_write` (the two tabs), `bank_forever` (the daily read), and the
  `/finance/bank…` routes. Tests: `BankSyncTest` and `BankRoutesTest` in `relay/test_relay.py`.
- `relay/relay.py`, "sheet fill": `fill_lines` (the file), `fill_plan` (what each line would do),
  `fill_sheet` (the write, kept in `sheet_writes`), `fill_undo`, and the `/finance/bank/fill/…`
  routes. Tests: `SheetFillTest`.
- `relay/relay.py`, "stock plan": `stock_events_sync` (the plan's activity, kept in
  `plaid_stock_events`), `stock_plan_outlook` (the vests to come from those read) and `fill_vest`
  (the vest a line means). Tests: `StockPlanTest`.
- App: `BankSyncRelayApi` → `BankSyncRepository` → `BankSyncViewModel` → `BankSyncScreen`
  (`finance/`), with previews in `BankSyncPreviews.kt`
  (`./gradlew :shared:renderPreviews -Ppreview=BankSync`).
