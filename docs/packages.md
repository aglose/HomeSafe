# Packages

An app in the drawer: everything on its way to the house for everyone in it, and what the front
door camera saw when it came. Nothing is typed in. The relay reads the shipping emails from the
Gmail mailboxes already linked for Amazon's orders ([budget.md](budget.md), "Amazon orders"), so
there is nothing further to link.

```
Gmail (read-only) ──▶ relay "packages" ──▶ /packages ──▶ app
Frigate person events ─▲   │   ▲
Box's vision model ────┘   │   └── door sensor, Open-Meteo, optional tracking service
                           └──▶ pushes
```

## What it does

| Feature | What it means |
| --- | --- |
| **Both inboxes, automatically** | Amazon, carriers and shops. Each email moves its package along; emails with the same tracking or order number are one package. An order's separate shipments stay separate. |
| **Door camera proof** | For each delivery, the visit that fits its time, and the landing before and after with a handle to drag between them. See "What the camera can honestly say". |
| **Nobody at the door** | An email says delivered and the camera saw nobody: said plainly, and in a push of its own that replaces the delivery's, five minutes after the email, once it is fair to say. |
| **Porch watch** | A parcel delivered is out there until the front door opens or someone says "I've got it". A reminder after two hours, and one look at the landing right now. |
| **May be gone** | Someone came, took it, did not open the door and is not someone Frigate named: a normal push, worded as a question, with the two pictures. |
| **Surprises** | Mark a package a surprise and everyone else sees foil wrapping and a date. Their pushes, its pictures and what the assistant is told all keep it. "Gift season" starts everything one person orders as a surprise. |
| **Rain guard** | Rain within three hours (50% or more, from Open-Meteo) with a parcel still out. |
| **Morning list** | At 8 AM, how many come today, and that nobody is home if nobody is. |
| **Needs you** | A delivery attempted, a problem, a signature needed, or two days past a promised day go to the top, with a push. |
| **Claim kit** | Everything a shop or carrier will ask for, written out with the numbers and what the camera saw, to send or copy. |
| **Returns** | Days left on the return window (from the shop's usual policy, or set by hand), reminded three days before, and each return through to its refund, which Amazon's refund email marks by itself. |
| **When it will really come** | Past a few deliveries, when this carrier usually turns up at this house. |
| **Insights** | Deliveries by month, the hours of the week they come, each carrier's on-time record and usual hour, who gets the most, the top shops, what the camera saw. |
| **By hand** | Add by tracking number, with the carrier worked out from it. Nicknames, notes, pins, whose it is, archive, search and filter. |
| **Carrier scans** | Optional, with a key: see "A tracking service". |
| **The assistant** | Can answer "what's coming today?" from the same list, surprises kept. |

## What the camera can honestly say

Measured on this household's own deliveries on 2026-10-31, on the Front Door camera:

- Amazon's "Delivered" email came 72-130 s after a person event on the camera, in all four tried,
  and each had really left a parcel. The clock is the strong evidence.
- Asked to compare the landing before and after a visit, the box's model found 2 of the 4 parcels
  (flat mailers behind the railing get past it) and called 5 of 18 ordinary visits a parcel:
  sunlight, a dog, an open door.
- Asked about one picture at a time, it found 1 of the 4, and called none of 12 ordinary visits one.

A rehearsal of the whole engine against both real mailboxes the same day (a copy of the database,
pushes off) read 600 emails into 187 packages. All six deliveries from the past week that the
camera could still be asked about found a visit 31-74 s before their email, and the model called
all six, including two flat mailers it was right about when checked by eye. Three of about 120
Amazon parcels had no "Delivered" email at all, which is why a late one is asked about rather than
announced, and why a return or a refund counts as news that it came.

These are small samples, so treat them as a reason for caution rather than as rates. They are why
the model never decides alone:

| Verdict | Needs | The app says |
| --- | --- | --- |
| `confirmed` | An email, a visit at the right time, and the compare above saying a parcel appeared | "The camera saw a parcel land" |
| `seen` | An email and a visit at the right time | "The camera saw someone at the door", and the pictures |
| `not_seen` | An email, and no visit in the window | "The camera saw nobody at the door" |
| `camera_only` | No email, both questions agree, the door stayed shut and no face was named | "A parcel may have arrived" |

A visit that opened the door was someone in the house, so it can't be the delivery. Someone tapping
"Yes, it's there" or "No" under the pictures outranks all of it. A parcel left in a mailbox, a
locker or with a neighbour isn't looked for at all, so it raises no alarm.

"Taken in" is the door sensor's to say more than the model's: the door opening after a parcel came
marks it inside, which can be wrong (someone popped out and left it). "It's still out there" undoes
that. "May be gone" needs the compare to say it went, a second look to agree, no door and no name;
how often that is right hasn't been measured, since nothing has gone missing yet.

## Who sees what

One Frigate login may serve a family, so a surprise goes by phone, not login. The first time the
app opens it asks whose phone this is, and tells the relay (`package_phones`). A package's owner is
the mailbox its email came to. A phone that hasn't said sees every surprise masked. Only a parcel's
owner can make it a surprise (`PATCH` answers 403 to anyone else), since anyone else would be hiding
it from themselves with no way back, and a package nobody owns can't be one. A surprise that shares
a box with something else makes the whole box a surprise, because Amazon's title names both.
`device` is what the phone says it is: this keeps a surprise from family, not from someone who sets
out to read it.

The relay does the masking, in `package_json`: a masked package leaves with no title, shop,
tracking number, order, notes, price, pictures or timeline. `package_send` words each phone's push
for who holds it. **Not kept:** a surprise's charge still shows in the Budget tab of Finance, and
the emails are still in the mailbox; this keeps a surprise from a glance, not from a search.

## Setup

Nothing beyond deploying the relay, on this box's defaults. On the relay's environment, all optional:

| Variable | Default | |
| --- | --- | --- |
| `PACKAGE_CAMERA` | `amcrest_1` | The Frigate camera that sees where parcels are left |
| `PACKAGE_SPOT` | `0.36,0.34,0.30,0.30` | Where they are left in its frame: x, y, width, height, as fractions |
| `PACKAGE_DOOR` | `front` | Which door sensor is that door, by a word in its name |
| `PACKAGE_LOOKBACK_DAYS` | `100` | How far back the first read goes |
| `PACKAGE_17TRACK_KEY` | | See below |

The first read after deploying goes through up to 300 emails per mailbox an hour, oldest last, and
tells nobody about what it finds there: a push needs an email under three hours old. Deliveries
from the last seven days get a verdict and pictures, as far as the recordings still reach.

### A tracking service

Emails say when a shop chooses to write. For the carrier's own scans, set `PACKAGE_17TRACK_KEY` to
a [17TRACK](https://api.17track.net/en/doc) API key. Each new number uses one registration from its
allowance (a one-time 200 on accounts made since January 2026), so only open parcels with a number
are registered, and Amazon's own numbers, which it can't follow, never are. This code has only run
against a stand-in for the service, never the real one.

Without a key, some parcels never hear "delivered": USPS's "expected" emails have nothing to follow
them, and plenty of shops write "shipped" and stop. So only Amazon's (and what a tracking service
follows) can be late, two days past their promised day. A parcel that has gone quiet comes off the
list by itself: three days past a promised day from someone who won't write again, a week past it
from Amazon, three weeks after any other last word. A pin keeps it, as does bringing it back by
hand, and news of it puts it back on the list (`archived` is 2 when the relay did it).

Money back for something that never left "ordered" is a cancelled order, so nothing is coming. A
return or a refund for something that had shipped says it arrived, but not when, so the camera isn't
asked about that moment and it never goes on the porch watch.

Amazon also puts separate orders in one box, and can email "Delivered" under an order number its
"Shipped" email didn't give. What is in the box says which package it was, and an order whose
categories the email covers goes into that package (`package_merge_same_box`). A second order of
something else with the same category, on its way separately, would be merged too by mistake.

## How it runs

`relay/relay.py`, "packages". `packages_forever` goes round every 30 s: the last two days' mail
every two minutes and the whole lookback hourly, the door camera's new visits, the reminders. Pushes
each go out once, by a `told:<what>` line in `package_events`. Pictures of a delivery are copied to
`data/package_frames/` when it is judged, since recordings don't last, and go after 180 days.

To change how emails are read, change `parse_package_email` and bump `PACKAGE_PARSER`: every email
in the lookback is read again. An email goes back to the package it was filed under, deleted or not,
and one that still says the same only fills in blanks and can put another order into its box, so
what someone said about a package stays.
Its tests are written from the shapes real emails took, with made-up names and numbers.

The app (`features/packages`) holds nothing on the device, so unlike Finance and Atlas it has no
lock and nothing to show before sign-in: a parcel is news only while it is right. It asks for the
whole list when opened and every minute while it is up, and every change answers with the whole
list again.

The box on the page is a shader rather than a picture (`PARCEL_BOX_SHADER`): a solid, marched
through, lit by a lamp that goes from blue to amber as things get nearer. The route, the backdrop,
the foil and the scan over the camera's pictures are shaders too, and all stop while off screen.
