# Charging: what the Teslas cost

The Charging app in the drawer (each car on the Cars page links to its own charges) says what the household's
Teslas cost to charge: each month, each car, every charge, at home and away, and the electricity
rates it was priced at. The relay keeps it all (relay.py, "charging"); the phones only ask.

## Where the numbers come from

| What | From | Goes back to |
| --- | --- | --- |
| Charges at home and elsewhere | The cars' stream ([tesla.md](tesla.md), "Telemetry") | The day this was deployed. Nothing kept the stream before, and Tesla keeps no home history anyone can ask for. |
| Supercharger charges: price, energy, site | Tesla's charging history | As far as Tesla's history goes, once linked with the charging scope (below) |
| Supercharger spending by month | Every card's "TESLA SUPERCHARGER" charges (see [budget.md](budget.md)) | As far as the cards go |
| Paid to the electricity provider by month | The cards and banks, by `bill_match` in the rates file | As far as the cards go |
| Miles and energy used | Each car's odometer and lifetime energy, last reading each day | The day this was deployed |

**A charge** starts when the car says `Charging` and ends when it says anything else; two at the
same place less than 15 minutes apart are one. Its energy is filed under the hours it went in
(`charge_hours`), which is what a price by the hour needs.

**How much it took from the wall** is measured from the charger's volts and amps as they stream. A
charge whose measurement falls well short of what the battery gained (a stream that dropped, a car
not told to send them) is estimated instead: the battery's gain, plus 10% lost in charging, spread
evenly over the charge. The list marks those **Estimated**.

**A charge missed altogether** (the relay or the box was down) is found the next time the car says
its battery: a rise of 3% or more with no charge heard is written down from the rise alone, running
at 7 kW up to that moment. Its hours are a guess, so on a time-of-use plan so is its price. A reading
within 15 minutes of a charge's end is that charge's last, not a new one. A Tesla receipt for the
same stretch replaces the guess.

**A car in gear isn't charging**, whatever was last heard of its charger, and a lost link to the
broker forgets what was heard: only a stated charge state, measured power or a filling battery
keeps a charge going.

**Home charging starts with the relay listening.** Months before the first day a car was heard
(`recording_from`) are only what Tesla's receipts and the cards say of Superchargers. The app says so
on those months, fades their bars, and doesn't compare them with later ones.

**Costs aren't kept.** They are worked out from those rows when asked for, so changing the plan, or
correcting a rate in the file, re-prices everything, past charges included. The rows themselves are
in `relay.db`, in the nightly backup with everything else (`RELAY_BACKUP_DIR`).

## How a charge is priced

- **At home:** the plan's rate for each hour, as the bill charges it: `rate × (1 + tax) + surcharge`.
  On a tiered plan the car is priced at the top tier, because the house uses up the baseline with or
  without it: each kWh the car adds is one more above. The monthly customer charge is the house's and
  is left out. The month also shows what the same hours would come to on the other plan. That
  comparison is the car's kWh only: on a time-of-use plan the rest of the house moves to those rates
  too, so check the whole bill before switching.
- **At a Supercharger:** what Tesla billed, idle fees included. Until the receipts are linked, or
  for a charge they don't cover, what the cards paid counts in the month but under no car.
- **Anywhere else:** unknown until someone types in what was paid, on the charge's page.

**A price per kWh** divides what has a price by the energy that has one: a charge with no price yet,
and what the cards paid beyond the receipts, come into neither side. Idle fees count in what a charge
cost but not in what its kWh cost.

**A price per mile** by place compares home with fast charging fairly, since a home kWh is counted at
the wall and loses about 10% on its way to the battery. It is the place's price over the battery
energy it gave, at that car's own mileage from its odometer and lifetime energy. Idle fees are left
out here too.

**Each car** has a page of its own, from its row in the month: what it has come to so far, and each
month's energy, prices and miles.

The app's plan switch only changes how the app prices charges. Pick the plan printed on the bill.

## The rates file

The plans' rates are in `~/surveillance/relay/data/charging-rates.json` on the box, not in the
repository, which is public: a utility gives away the town. Read again whenever it changes, no
restart. `relay/charging-rates.example.json` shows the shape:

- `time_zone` is the clock the utility's hours and the months go by, so a phone travelling can't move
  them. Without it, the zone most phones say they are in.
- `tax_percent` is taxed on the rate, `surcharge_per_kwh` is added after it. Neither includes anything
  the utility says is currently zero.
- Each plan has `versions`, each with its `from` date. **Add a version whenever the utility's rates
  change**, and keep the old ones: a charge is priced by the version of its day.
- A plan with `peak` and `off_peak` is priced by the hour: `peak_start` to `peak_end` on weekdays,
  outside `holidays`. One with `tier1` and `tier2` is tiered.
- `gas` is the price and mileage the "Against gas" card compares with. It is an assumption, not a
  lookup; change it when it stops being true.

The page shows each plan's effective date, so a stale file can be noticed.

## Setup

Each step makes the page say more; it works without them.

1. **Deploy** the relay (`relay/deploy.sh`) and put the rates file on the box. Charges are written
   down from then on, estimated from the battery until step 2.
2. **Tell the cars to send their power**, from the Mac that holds the private key:
   ```bash
   scripts/tesla-telemetry.sh
   ```
   A car takes it up the next time it wakes. `scripts/tesla-telemetry.sh status` says `synced: true`.
   A night's charge sends about 2,000 more values, under $1 a month for both cars at Tesla's $1 per
   150,000 values.
3. **Link Tesla again for the receipts.** At [developer.tesla.com](https://developer.tesla.com), turn on
   *Vehicle Charging Management* for the application first: asking for a scope it lacks fails the
   sign-in. Then, for each account:
   ```bash
   docker exec homesafe-relay python relay.py tesla-link
   ```
   That scope also allows charging commands, but those need a signed command, and the relay has
   neither the signing code nor the private key. It only reads the history.

Then, to read the receipts now rather than within 6 hours:

```bash
docker exec homesafe-relay python relay.py charging-history
```

If a receipt's fields read wrongly, this is the first run against real answers: Tesla's reference
gives no example of them, so the parser (`charging_receipt`) was written from memory of its shape.

## Where it is on the box

```bash
docker logs homesafe-relay 2>&1 | grep "charging:"
```
```
charging: sarahs_car started charging (home), session 12
charging: sarahs_car finished charging (home): 26.3 kWh by power
```

Everything the app is told, as JSON:

```bash
docker exec homesafe-relay python relay.py charging-summary
```
