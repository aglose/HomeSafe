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

In this order: a tag someone made by hand; the card being one person's own; on a shared card, the
one person the bank names as the cardholder (banks rarely say); a remembered shop; the family, when
the card is the family's; otherwise nobody yet.

Tap a name under a purchase to tag it. Tap the purchase itself for the sheet, where **Always for
…** remembers the shop, so every purchase from it, past and future, goes the same way. Remembered
shops are listed in the settings and can be forgotten there. A tag stays with a pending purchase
when it settles.

The people are the budget sheet's (the names beside "Flow In"), and follow it: add or rename
someone there and the budget has them the next time the app reads the sheet. A limit and a card
role are kept by name, so a renamed person starts without either.

### Limits and the savings line

- **Limits** are typed in the settings: the whole month, each person, the family card. An empty
  one is no limit.
- **The savings line** is the sheet's monthly take-home minus the sheet's expense lines that
  leave the bank directly. Tick the lines that are paid by card under **Paid by card**; the rest
  are the bills. Card spending past the line is coming out of savings. The relay can't read the
  sheet's blocks itself (only the app parses the sheet), so the app reports the two numbers to the
  relay whenever they change.
- **The projection** carries the month's daily rate so far to the last day. Early in the month it
  is steadied by last month's rate. It is a straight line: one large charge still bends it.

## Alerts

Under **Limits and cards → Alerts** are three switches, all off to begin with:

| Switch | A notification when |
|---|---|
| **The month's limit** | Spending reaches four fifths of it, and again when it is passed |
| **Into savings** | The cards pass what take-home leaves after the bills |
| **Each person and the family card** | One passes its own limit |

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
- **iPhones don't get them yet**: the relay has no way to push to one until APNs is set up.
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
4. Under **Limits and cards**, set the limits and tick the sheet lines that are paid by card.
5. Anyone who should see the budget must be a Frigate admin or listed in `FINANCE_USERS`
   (`finance.env` on the box).

## How it's built

- `relay/relay.py`, "budget": `plaid_transactions_pull` / `plaid_transactions_store` (the hourly
  read), `budget_bucket` (whose a purchase is), `budget_month` (the page's answer),
  `budget_check` (the alerts), and the `/finance/budget…` routes. Tests: `BudgetSyncTest`,
  `BudgetMonthTest`, `BudgetAlertsTest`, `BudgetRoutesTest`, `DeviceOwnerTest` in
  `relay/test_relay.py`.
- Alerts on Android: `HomeSafeMessagingService` hands a `budget=1` push to
  `BudgetNotificationPoster` (`BudgetAlert` reads the push's data); the tap is
  `homesafe://finance?tab=budget` (`FinanceDeepLink`), which `ShellNavigation` opens.
- App: `BudgetRelayApi` → `BudgetRepository` → `BudgetViewModel` → `BudgetScreen` and
  `BudgetSettingsScreen` (`finance/`), with `BudgetPace` for the projection and `BudgetNarrator`
  for the sentence. The tank and the meters are the shaders `BUDGET_TANK_SHADER` and
  `BUDGET_HEAT_METER_SHADER` in `FinanceShaders.kt`. Previews in `BudgetPreviews.kt`
  (`./gradlew :shared:renderPreviews -Ppreview=Budget`).
