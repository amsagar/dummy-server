---
name: pods-order-validation
description: >
  Validates a PODS order across three checks — leg sequence, serviceability,
  and container availability — by invoking three registered tools and one
  decision table. Trigger when the user asks to validate, check, or run
  validations on a PODS order.
---

# PODS Order Validation

You validate a PODS order by calling registered resources in order. **Every
required tool call must complete before you write your final answer.** Output
that summarizes incomplete work is a bug — keep calling tools until the
checklist in Step 6 is satisfied, then summarize.

## Required resources

| Resource | Type | When to call |
|---|---|---|
| `Get_OrderID` | tool | Once, at start, to fetch the order |
| `Leg Sequences` (via `decisionTableEvaluate`) | decision table | Once, after extracting leg lines |
| `Serviceability` | tool | **Once per non-skipped leg line — no exceptions** |
| `ContainerAvailability` | tool | Once per qualifying IDEL line (may be zero) |

## Hard rules — read before every step

1. **No fabrication.** If a tool was not called, you cannot report its result.
2. **No early summary.** Do not write "partial", "remaining lines not
   processed", "due to length", or any equivalent. If the checklist in Step 6
   is not green, the next thing you do is **call the next tool**, not write
   prose. There is no token or time budget that justifies stopping early.
3. **No batching trick.** Calling `Serviceability` once and assuming the rest
   are similar is forbidden. Each leg line gets its own call.
4. **Field discipline.** Filter on `ItemCode` (not `ServiceCode`). Map to
   service-code form only when sending to the `Leg Sequences` decision table
   (Step 3) — see the mapping table below.

---

## Step 1 — Fetch the order

Call `Get_OrderID` with `ORD_ID = <order id from user>`. Bind the response to
`order`.

## Step 2 — Extract leg lines

Filter `order.Lines` by `ItemCode`. **`VALID_ITEM_CODES` below uses ItemCode
values, not ServiceCode values.**

```js
// ItemCode values that count as service legs.
const VALID_ITEM_CODES = [
  "IDEL", "RETSC", "LDT", "REDEL", "FPU",
  "MOV", "SID", "SFP", "SCDEL", "CSRED", "CSFPU"
];

// CRITICAL: read ItemCode, not ServiceCode.
const legLines = order.Lines.filter(
  l => l.ItemCode && VALID_ITEM_CODES.includes(l.ItemCode)
);
```

Sanity check: if `legLines.length === 0`, recheck — you almost certainly read
the wrong field. A typical Long Distance order produces 4–5 leg lines.

## Step 3 — Leg Sequence (call decision table `Leg Sequences`)

The decision table is keyed on **service-code form**, not ItemCode. Map each
leg line's ItemCode to its service-code equivalent before sending.

### ItemCode → ServiceCode mapping

| ItemCode | ServiceCode (send this to the table) |
|---|---|
| `IDEL` | `NEW` |
| `RETSC` | `WRT` |
| `LDT` | `WTW` |
| `REDEL` | `RDL` |
| `FPU` | `FPU` |
| `MOV` | `MOV` |
| `SID` | `SID` |
| `SFP` | `SFP` |
| `SCDEL` | `SCDEL` |
| `CSRED` | `CSRED` |
| `CSFPU` | `CSFPU` |

If a leg line's `ServiceCode` field is non-empty, prefer it over the mapping
(it's authoritative). Use the table only as a fallback.

### Prepare inputs

```js
const ITEM_TO_SERVICE = {
  IDEL: "NEW", RETSC: "WRT", LDT: "WTW", REDEL: "RDL", FPU: "FPU",
  MOV: "MOV", SID: "SID", SFP: "SFP", SCDEL: "SCDEL", CSRED: "CSRED", CSFPU: "CSFPU"
};

const sorted = [...legLines].sort((a, b) => a.Sequence - b.Sequence);
const actualSequence = sorted.map(l =>
  (l.ServiceCode && l.ServiceCode.trim()) || ITEM_TO_SERVICE[l.ItemCode]
);
const journeyType = order.OrderType;  // e.g. "Long Distance"
```

### Invoke

Call `decisionTableEvaluate` with:
- `tableName: "Leg Sequences"`
- `journeyType: <string>`
- `actualSequence: <array of service-code strings>`

If the response is `{matched: false}`, the sequence is invalid — capture that
verbatim. Do not retry with a different sequence shape unless you have
genuinely identified a mapping bug. Report `valid: false`,
`message: "No matching row in Leg Sequences decision table for this journey
and sequence"`.

## Step 4 — Serviceability — **loop, do not stop early**

Build the work queue:

```js
const toService = legLines.map(line => {
  const origin = line.Addresses?.find(a => a.AddressType === "Origination");
  const dest   = line.Addresses?.find(a => a.AddressType === "Destination");
  return { line, origin, dest };
});
```

Then iterate every entry in `toService`. **There is no acceptable reason to
process fewer than `toService.length` entries.** If you feel the urge to stop
("this is taking long", "the pattern is clear", "summarize the rest"), that
is the model laziness the hard rules forbid — call the next tool instead.

For each entry:

- If `origin` or `dest` is missing → record
  `{lineId, itemCode, status: "skipped", reason: "missing addresses"}` and
  continue. (This is the **only** reason to skip a leg.)
- Otherwise, call `Serviceability` with:

| Input | Source |
|---|---|
| `originZip` | `origin.PostalCode` |
| `destinationZip` | `dest.PostalCode` |
| `originCountryCode` | `origin.CountryCode` |
| `destinationCountryCode` | `dest.CountryCode` |
| `custTrackingId` | `String(order.OrderIdentity)` |

You may issue these calls in parallel. After all responses arrive, interpret
each:
- `PostalCodeException.ExceptionType` is null/empty → `isServiceable: true`
- `PostalCodeException.ExceptionType` is non-empty → `isServiceable: false`,
  `exceptionType: <that value>`

**Do not write the final answer until every entry in `toService` has either
a Serviceability tool result or a "skipped: missing addresses" record.**

## Step 5 — Container Availability (conditional)

Scan **all** `order.Lines` (not just leg lines):

```js
const toCheck = order.Lines
  .filter(l => l.ItemCode === "IDEL")
  .filter(l => {
    if (l.DeliveryDate) return false;            // already delivered → skip
    return !l.ContainerId || !l.ScheduledDate;   // missing container or schedule
  });
```

If `toCheck` is empty, record `containerAvailability: []` and proceed.

For each line in `toCheck`, call `ContainerAvailability` with:

| Input | Source |
|---|---|
| `zip` | `origin.PostalCode` (Origination address) |
| `countryCode` | `origin.CountryCode` |
| `siteIdentity` | `line.AssignedSiteId` |
| `containerSize` | `line.ContainerSize` |
| `referenceDate` | `line.ScheduledDate` if present, else today's ISO date |
| `custTrackingId` | `String(order.OrderIdentity)` |

Collect entries from `GeneralAvailabilityDates` where `IsAvailableCS === true`
and `ReasonCode` is empty. On error, mark the line `status: "unavailable"`
with the error detail.

---

## Step 6 — Completion checklist (must be green before output)

Before writing your final answer, verify the tool-call log for this run:

- [ ] `Get_OrderID` called exactly once.
- [ ] `decisionTableEvaluate` called exactly once with `tableName: "Leg Sequences"`.
- [ ] `Serviceability` calls + skipped-for-missing-addresses records together
      cover **every** entry in `toService`. Count must equal `legLines.length`.
- [ ] `ContainerAvailability` called once per line in `toCheck`, or zero
      times if `toCheck` was empty.

If any box is unchecked: **do not summarize**. Make the next required tool
call instead. Only when all four boxes are checked may you produce the
output object below.

## Step 7 — Output

```json
{
  "orderId": 600030447,
  "legSequence": {
    "journeyType": "<from order>",
    "sequence": ["<service-code list>"],
    "valid": true,
    "message": "<from Leg Sequences decision table>"
  },
  "serviceability": [
    {
      "lineId": "...",
      "itemCode": "...",
      "originZip": "...",
      "destinationZip": "...",
      "isServiceable": true,
      "exceptionType": null
    }
  ],
  "containerAvailability": [
    {
      "lineId": "...",
      "itemCode": "IDEL",
      "checked": true,
      "skipReason": null,
      "availableDates": ["2026-12-16T00:00:00"]
    }
  ]
}
```

A single line failure must not block the rest. Collect all errors and return
them together. The `serviceability` array MUST contain one entry per leg
line — including skipped ones with `status: "skipped"`. An array shorter than
`legLines.length` is a violation of the hard rules.
