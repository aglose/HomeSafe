# Bank sync: balances from the banks themselves

The budget sheet's balances used to be typed in by hand. With bank sync, each bank, brokerage and
lender is linked once through [Plaid](https://plaid.com), and from then on the relay reads every
account's balance each morning and writes it to Google Sheets, where the budget sheet looks it up.
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
- **"Plaid said: …"** when linking. Plaid's own explanation is shown as written. `INVALID_API_KEYS`
  means the secret doesn't match `PLAID_ENV`.
- **The Trial plan's 10 institutions are used up.** Plaid refuses the next link and says so. Its
  dashboard shows the count and how to apply for more. Every link ever made counts, unlinked
  ones too.
- **You signed in at the bank and it isn't in the list.** Open Linked accounts again: the server
  collects a finished sign-in when the page is asked for. It has half an hour from the sign-in.

## What stays manual

Home value, the RSU and ESPP vesting schedule, and the income and expense lines are not things a
bank reports, so they stay as typed. The sheet's history table is still filled in by hand.

## How it's built

- `relay/relay.py`, "bank sync": `plaid_link_start` / `plaid_link_finish` (Hosted Link),
  `plaid_sync_item` (one institution into `plaid_items`, `plaid_accounts`, `plaid_holdings`),
  `feed_tables` / `feed_write` (the two tabs), `bank_forever` (the daily read), and the
  `/finance/bank…` routes. Tests: `BankSyncTest` and `BankRoutesTest` in `relay/test_relay.py`.
- App: `BankSyncRelayApi` → `BankSyncRepository` → `BankSyncViewModel` → `BankSyncScreen`
  (`finance/`), with previews in `BankSyncPreviews.kt`
  (`./gradlew :shared:renderPreviews -Ppreview=BankSync`).
