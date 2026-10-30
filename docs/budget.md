# Budget: what the month is actually costing

The budget sheet says what a month is meant to cost. The **Budget** tab in Finance says what it is
costing: every hour the relay asks Plaid what was bought on each linked credit card, and the tab
sets the month so far against the limits you gave it and against the point where spending starts
to come out of savings.

In the app: **Finance → Budget**. Its settings are behind **Limits and cards**.

## What the page shows

- **The tank.** How much of the month's limit is spent, as liquid in a glass tank. It warms from
  green through amber to orange as the limit nears, and the rim glows once it is passed. The
  dashed line is where an even month would be today.
- **One sentence.** "$2,100 left for 21 days, about $100 a day", or what the month is heading for
  when it is running ahead, or how far into savings it is.
- **Whose are these?** Purchases on a shared card that the bank didn't put to a person, each with
  a name to tap.
- **Pace.** Spending day by day, against an even month (dashed) and where this pace ends it, with
  the limit and the savings line drawn across.
- **Budget lines.** Each of the sheet's lines that is paid by card (Groceries, $600) against what
  the sheet plans for it: where this pace takes it when that is past the plan, and what it usually
  comes to when that is far enough from the plan that the plan wants changing. Under them, what
  the purchases in none of the lines come to. Tap one to list its purchases.
- **Who spent what.** Each person and the family card against their own limits.
- **Where take-home goes.** Take-home, minus the bills that aren't paid by card, minus the cards
  so far: what is left, or how much is coming out of savings.
- **What it went on, where it went, month by month**, and every purchase. Tap a month's bar to
  look back at it.

## How it works

- **Cards are linked like any other institution** (see [bank-sync.md](bank-sync.md)): Finance →
  Wallet → the sync line → Linked accounts → *A bank or credit card*. That kind is the one that
  asks the institution for its transactions. A card linked as a loan has none to read; the page
  says so.
- **Every hour** the relay calls Plaid's `/transactions/sync` for each institution linked that
  way. It hands over only what changed since the last call. Banks pass purchases to Plaid a few
  times a day, so most hours find nothing new: the page says when the cards were last asked and
  when that last found anything. **Read the cards now** asks at once, at most once a minute.
- **Only credit cards' transactions are kept**, in `relay.db` on the Frigate box, and never in
  the relay's log. Unlinking a card deletes what was bought on it.
- **What counts.** Purchases, pending ones included (flagged, since they can still change). A
  refund comes off the month it arrives in. Paying the card off is not spending and is left out.
  The month is the household's calendar month, by the day the card was used.

### Each card has a role

| Role | What it means |
|---|---|
| **Two of us carry it** | Each purchase is put to one person (see below). Until it is, it counts toward the month but toward nobody's own limit. |
| **The family's** | Everything on it is family spending. |
| **One person's own** | Everything on it is theirs. For a card that shows up as its own account. |
| **Not in the budget** | Its purchases are ignored. |

A card with no role yet counts for nothing; the page asks what it is. Roles are kept by the card's
name in the feed (institution, name, last four), so unlinking a card and linking it again keeps its
role.

### Whose a purchase is

In this order: a tag someone made by hand; the card being one person's own; on a shared card,
whoever the bank's mark for the cardholder belongs to; a remembered shop; the family, when the card
is the family's; otherwise nobody yet.

**Whose card is which.** Banks mark each purchase with who made it, each in its own way. American
Express writes the cardholder's name ("SAM RIVERA 1006"), which is matched to the budget's people by
itself. Capital One writes only the last four digits of the card that was used ("1203"), which
nobody can match to a person without being told: the page asks once ("Whose card is which?"), and
from then on those purchases sort themselves. The same choices are under **Limits and cards → Cards**.

Tap a name under a purchase to tag it. Tap the purchase itself for the sheet, where **Always for
…** remembers the shop, so every purchase from it, past and future, goes the same way. Remembered
shops are listed in the settings and can be forgotten there. A tag stays with a pending purchase
when it settles.

### Which budget line a purchase is in

Each line of the budget sheet ticked under **Paid by card** is a budget line, planned at the
sheet's monthly amount for it. Every purchase is put in one of them, or in none, on its own:

1. **By hand.** Tap the purchase, then a line under **Budget line**, or **None** to keep it out of
   them all (a TV from Costco isn't groceries). **Let it sort itself** takes the choice back.
2. **A remembered shop.** With **Always for …** switched on as you pick, every purchase from that
   shop, past and future, goes the same way. These are listed under **Remembered shops** with the
   person ones, and can be forgotten there.
3. **On its own.** A line named after a shop has that shop's purchases ("Amazon" has "AMAZON
   MKTPL*2K1"). Otherwise a line whose name says what kind of spending it is takes the purchases
   that are that kind: Groceries takes what Plaid calls groceries (Safeway, Trader Joe's) and the
   big grocers and warehouse clubs by name (Costco, which Plaid calls a superstore); Dining out takes
   restaurants and fast food; Gas, Streaming, Meal kits, Pets and a few more likewise
   (`BUDGET_LINE_GUESSES` in `relay/relay.py`).

Whose a purchase is and which line it is in are separate: putting a Costco run in Groceries leaves
it Sam's. A line no longer ticked as paid by card stops being a line, and the purchases put in it
by hand or by rule fall where they would on their own.

**What the line usually comes to** is the average of the months before this one in which the cards
spent anything, once there are two of them. When it differs from the plan by more than a tenth
(and at least $25), the line says so: "Usually $710 a month, more than the $600 planned".

The people are the budget sheet's (the names beside "Flow In"), and follow it: add or rename
someone there and the budget has them the next time the app reads the sheet. A limit and a card
role are kept by name, so a renamed person starts without either.

### Limits and the savings line

- **Limits** are typed in the settings: the whole month, each person, the family card. An empty
  one is no limit.
- **The savings line** is the sheet's monthly take-home minus the sheet's expense lines that
  leave the bank directly. Tick the lines that are paid by card under **Paid by card**; the rest
  are the bills. Card spending past the line is coming out of savings. The relay can't read the
  sheet's blocks itself (only the app parses the sheet), so the app reports the two numbers, and
  what each card-paid line plans for, to the relay whenever they change.
- **The projection** carries the month's daily rate so far to the last day. Early in the month it
  is steadied by last month's rate. It is a straight line: one large charge still bends it.

## Alerts

Under **Limits and cards → Alerts** (and in Settings → Notifications) are four switches, all off to begin with:

| Switch | A notification when |
|---|---|
| **The month's limit** | Spending reaches four fifths of it, and again when it is passed |
| **Into savings** | The cards pass what take-home leaves after the bills |
| **Each person and the family card** | One passes its own limit |
| **Each budget line** | One of the sheet's card-paid lines goes over what the sheet plans for it ("Groceries is over what the budget plans") |

- The switches are the household's, kept by the relay. Each phone hears about each line **once a
  month**. A month already over a limit when a switch goes on says so once, not once for each
  line it passed on the way.
- Only phones last signed in to by someone who may see the finances are told. The relay learns
  whose phone it is when the app registers, which it does every time it opens.
- A phone in its quiet hours hears at the first hourly check after them. "Only when everyone's
  away" doesn't hold them back: that choice is about the cameras.
- On Android they arrive on a **Budget** notification channel of their own, at an ordinary
  importance, worded on the phone from the amounts in the push. Turning that channel off in the
  phone's settings silences them on that phone. A tap opens Finance on the Budget tab.
- An iPhone gets them through APNs (see [notifications.md](notifications.md#iphone)), as the relay's
  English banner.
- Spending shows up when the bank passes it on, which can be hours after the purchase, so the
  "over" notification can come after the fact.
- Every Android phone in the household should have a build with the Budget channel before a
  switch goes on: an older build shows any push it doesn't know as a camera alert.

## Setup

1. Bank sync set up as in [bank-sync.md](bank-sync.md): Plaid keys in `plaid.env` on the box.
2. Link each card as *A bank or credit card*. Capital One, American Express and most large issuers
   sign in on their own page, which Plaid's dashboard has to have enabled for your account
   (Developers → API → OAuth institutions).
3. On the Budget tab, say what each card is.
4. Under **Limits and cards**, set the limits and tick the sheet lines that are paid by card: each
   becomes a budget line.
5. Anyone who should see the budget must be a Frigate admin or listed in `FINANCE_USERS`
   (`finance.env` on the box).

## Amazon orders

A card shows an Amazon purchase as "AMZN Mktp US*2K1AB3CD4" and an amount, so groceries look just
like a new game. Amazon has no API for a household's own orders, but it emails every order
("Ordered: …") and shipment ("Shipped: …"). Once a mailbox is linked, the relay reads those emails
every hour, after the cards (Gmail, read-only), and puts each Amazon charge beside the order it
paid for:

- **Matching.** Amazon charges the card once per shipment. A charge is matched to a shipment
  whose email gave that exact total within a few days; otherwise to an order of exactly that
  total within a month of it; otherwise two to four charges that month adding up to an order's
  total are taken as its shipments. Where more than one fits, the charge nearest the email wins.
  A charge with no match says so (`amazon: null`). AWS, Prime, Prime Video, Kindle Unlimited and
  Audible are not orders and are left alone.
- **Sorting.** Each item is *essential* (groceries, household and cleaning supplies, toiletries,
  medicine, the baby's and the pets' things) or *discretionary*. Word lists sort the obvious ones
  (`_AMAZON_NEED_WORDS`). The rest go to the box's own model through Ollama (`VLM_MODEL`), twenty
  at a time, so no titles leave the house. Without Ollama they stay unsorted. Anyone can say
  otherwise for an item (`PUT /finance/budget/amazon/{order}/items/{pos}`), and a later email
  about the same order keeps what they said.
- **The split.** A charge is shared out between essential, discretionary and unsorted by the
  order's item prices. For a shipment that is the whole order's mix, since a shipment's email
  rarely says which items it carried.
- **What the app gets.** Each Amazon purchase in `/finance/budget` carries `from_amazon` and
  `amazon`: the order, how it was matched, its items with their sorting, and the charge's
  `essential`, `discretionary` and `unsorted` shares. The month carries `amazon` too: those three
  summed, `unmatched`, when the mailboxes were last read and which failed.
- **In the app.** Under the budget lines, an **Amazon** card has the month's Amazon charges as one
  bar ("$27 of $199 was essential"), split into essential, discretionary, not sorted yet and no
  order found. Tapping it lists the Amazon purchases, each row naming what was in the order. A
  purchase's sheet says which order it was and how it was matched, and gives each item
  **Essential** and **Discretionary**, plus **Let it sort itself** once someone has chosen.

Everything is kept in `relay.db` (`amazon_emails`, `amazon_orders`, `amazon_items`,
`amazon_shipments`). Email bodies aren't kept, and the relay never sends or changes mail.

### Linking a mailbox

The mailbox is whichever one the Amazon account emails. Each person with their own Amazon account
links their own.

Done once already (2026-10-29), in Google Cloud project `homesafe-percysafe`: the **Gmail API** is
enabled; the Google Auth Platform app "HomeSafe" is External and **In production**, with the home
page and privacy policy published from the `gh-pages` branch (`https://aglose.github.io/HomeSafe/`,
authorized domain `aglose.github.io`); and the Desktop OAuth client "HomeSafe relay Amazon orders"
was made. The app is unverified, so sign-in shows Google's "hasn't verified this app" screen
(**Advanced → Go to HomeSafe**), and at most 100 accounts can ever link it. An app left in
*Testing* would have its refresh tokens die after seven days.

1. Have the client's JSON as `relay/client_secret.json` (gitignored). Google shows a client's
   secret only once, when it's made: a lost one means **Add secret** on the client's page.
2. Nothing more to do on the consent screen. The relay asks only for `gmail.readonly`.
3. On the Mac: `python3 relay/gmail_link.py --client relay/client_secret.json --mailbox andrew --out relay/amazon.env`,
   and sign in as that mailbox's owner. Run it again with another `--mailbox` for each mailbox.
   The tokens go into the file and are never printed.
4. Copy `relay/amazon.env` next to the relay's `docker-compose.yml` on the box and recreate the
   container. The first read brings the last `AMAZON_LOOKBACK_DAYS` (120) days of emails.

## How it's built

- `relay/relay.py`, "budget": `plaid_transactions_pull` / `plaid_transactions_store` (the hourly
  read), `budget_bucket` (whose a purchase is), `budget_line` / `budget_line_guess` (which line
  it is in), `budget_month` (the page's answer), `budget_check` (the alerts), and the
  `/finance/budget…` routes. Tests: `BudgetSyncTest`, `BudgetMonthTest`, `BudgetLinesTest`,
  `BudgetAlertsTest`, `BudgetRoutesTest`, `DeviceOwnerTest` in `relay/test_relay.py`.
- `relay/relay.py`, "Amazon orders": `parse_amazon_email` (one email), `amazon_store`,
  `amazon_sort_items` (word lists, then Ollama), `amazon_match` (charges to orders),
  `amazon_split`, and `amazon_attach` / `amazon_month` (the budget's answer).
  `relay/gmail_link.py` links a mailbox. Tests: `AmazonEmailTest`, `AmazonMatchTest`,
  `AmazonBudgetTest`, on made-up emails. In the app, `BudgetAmazon.kt` (`AmazonBlock`, the
  sheet's `AmazonOrderSection`) draws what `BudgetRelayApi` reads into `Purchase.amazon` and
  `Budget.amazon`; `BudgetViewModel.sayNeed` sorts an item.
- Alerts on Android: `HomeSafeMessagingService` hands a `budget=1` push to
  `BudgetNotificationPoster` (`BudgetAlert` reads the push's data); the tap is
  `homesafe://finance?tab=budget` (`FinanceDeepLink`), which `ShellNavigation` opens.
- App: `BudgetRelayApi` → `BudgetRepository` → `BudgetViewModel` → `BudgetScreen` and
  `BudgetSettingsScreen` (`finance/`), with `BudgetPace` for the projection and `BudgetNarrator`
  for the sentence. The tank and the meters are the shaders `BUDGET_TANK_SHADER` and
  `BUDGET_HEAT_METER_SHADER` in `FinanceShaders.kt`. Previews in `BudgetPreviews.kt`
  (`scripts/render-ui-previews.sh` (Layoutlib; see docs/ui-previews.md)).
