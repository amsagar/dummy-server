---
name: pods-order-validation
description: Validate a PODS order for service area, calendar availability, and leg-sequence correctness using imported PODS tools and decision table evaluation.
---

# PODS Order Validation

You validate a PODS order against 3 scheduling categories.

## Deterministic behavior (must follow)

- Execute checks in strict sequence: Order -> Serviceability -> ContainerAvailability -> Decision table.
- Always map fields from `Get_OrderID` output before calling downstream tools.
- Never call downstream tools with `{}` or missing required fields.
- If required mapping data is missing, stop that category and report the missing fields clearly.
- Do not claim "decision table not found" unless the tool explicitly returns a not-found error.
- Avoid duplicate tool calls in the same turn: do not call the same tool again with the same input payload.

## Tools to use

- `Get_OrderID` (or your order lookup tool name)
- `Serviceability`
- `ContainerAvailability`
- `decisionTableEvaluate` with `tableName: "Leg Sequences"`

## Canonical mapping from Get_OrderID

Use this normalization before any tool call:

- `orderId` <- `orderId` | `ORD_ID` | `orderNumber`
- `journeyType` <- `journeyType` | `journey` | `JourneyType`
- `originZip` <- `originZip` | `originPostalCode` | `origin.zip`
- `destinationZip` <- `destinationZip` | `destinationPostalCode` | `destination.zip`
- `originCountryCode` <- `originCountryCode` | `originRegionCode` | default `"US"`
- `destinationCountryCode` <- `destinationCountryCode` | `destinationRegionCode` | default `"US"`
- `legs` <- `legs[]` | `orderLegs[]` | `segments[]`

For each leg `i`:

- `leg.code` <- `code` | `serviceType` | `legCode`
- `leg.requestedDate` <- `requestedDate` | `serviceDate` | `date`
- `leg.siteIdentity` <- `siteIdentity` | `serviceCenter` | `sc`
- `leg.countryCode` <- `countryCode` | `regionCode` | use origin/destination country based on leg direction
- `leg.postalCode` <- `postalCode` | `zip` | use origin/destination zip based on leg direction

## Required flow (in this order)

1. Fetch order details using order ID.
2. Run service-area validation.
3. Run calendar availability validation.
4. Run leg-sequence validation.
5. Return a structured report with PASS/FAIL and remediation.

---

## Step 1: Fetch order

Call order lookup tool with `orderId` (or API-required key like `ORD_ID`).

Extract:

- `orderId`
- `originZip`
- `destinationZip`
- `originCountryCode` / `destinationCountryCode` (default `US` if absent)
- `journeyType`
- `serviceCenter`
- `legs[]` with each leg:
  - `code`
  - `name`
  - `requestedDate`
  - `serviceType`
  - `siteIdentity`

Normalize `legs[]` into `validationLegs[]` before Categories 2 and 3:

- Source from order `Lines[]` when `legs[]` is not explicitly present.
- Keep only service legs (`ProductFamily == "Service"` OR non-empty `ServiceCode`).
- `validationLeg.code` <- `ServiceCode` first, fallback `ItemCode`.
- `validationLeg.requestedDate` <- `ScheduledDate` first, fallback `DeliveryDate`.
- `validationLeg.siteIdentity` <- `AssignedSiteId` or matching `Addresses[].SiteID`.
- `validationLeg.postalCode` / `countryCode` <- from matched service-side address (`Addresses[]`).
- Sort by `Sequence` ascending.
- De-duplicate by (`code`, `requestedDate`, `siteIdentity`, `postalCode`), keep first.

If required fields are missing, report them explicitly.

---

## Step 2: Service Area Validation (Category 1)

Call `Serviceability` and pass mapped values from order:

```json
{
  "originPostalCode": "<originZip>",
  "destinationPostalCode": "<destinationZip>",
  "originRegionCode": "<originCountryCode>",
  "destinationRegionCode": "<destinationCountryCode>",
  "channel": "WEB",
  "custTrackingId": "<orderId>"
}
```

Never call `Serviceability` with `{}`.
Required before call: `originZip`, `destinationZip`.

### PASS
If serviceable.

### FAIL (ERROR-1)
Use exactly:

`Address [ADDRESS] in zip [ZIP] is not serviceable by [SC]. Suggested SC: [CORRECT_SC]. Action needed: Reroute order or confirm extended service approval.`

---

## Step 3: Calendar Availability (Category 2)

For each relevant leg in `validationLegs[]`, call `ContainerAvailability` with mapped leg fields:

```json
{
  "siteIdentity": "<siteIdentity>",
  "serviceDate": "<requestedDate>",
  "serviceType": "<serviceType>",
  "postalCode": "<originZip or destinationZip>",
  "regionCode": "<countryCode>",
  "channel": "WEB",
  "custTrackingId": "<orderId>"
}
```

Never call `ContainerAvailability` with `{}`.
Required before each call: `postalCode`, `regionCode`, `serviceType`, `serviceDate`.
If any required field is missing for a leg, do not call API for that leg; record validation error for that leg.
Do not run this for non-service recurring charges (for example rental/protection monthly lines).

### PASS
If requested slots are available.

### FAIL (ERROR-2)
Use exactly:

`[DATE] is not available for [SERVICE_TYPE] at [SC]. Next available: [ALT_DATE1], [ALT_DATE2]. Calendar conflict: [REASON].`

---

## Step 4: Service Leg Sequence (Category 3)

For each leg position `i` in `validationLegs[]` (1..N), call:

`decisionTableEvaluate`
with:
- `tableName: "Leg Sequences"`
- `inputs: { "journeyType": "<journeyType>", "position": i }`

Call shape must be exactly:

```json
{
  "tableName": "Leg Sequences",
  "inputs": {
    "journeyType": "<journeyType>",
    "position": <i>
  }
}
```

Never call `decisionTableEvaluate` without `inputs.journeyType` and `inputs.position`.
Do not evaluate positions that do not exist in `validationLegs[]`.

Compare actual leg code at that position with decision-table output:

- expected from table: `expectedCode`, `expectedName`, `optional`
- actual from order leg: `code`

Also verify requested dates are monotonic (no backward dates).

### PASS
All non-optional legs match and date order is valid.

### FAIL (ERROR-3)
Use exactly:

`Service leg sequence invalid for journey type [JOURNEY]. At position [N], expected [EXPECTED_CODE] ([EXPECTED_NAME]) but got [ACTUAL_CODE]. Please correct sequence or dates.`

If decision table is missing, include:
`ERROR-3: Decision table [Leg Sequences] not found.`
If the tool returns `matched:false`, report sequence mismatch/data gap (not table-not-found).

---

## Final response format

Return plain text in this simple table-style format:

```
Order: <orderId>

| Category | Status | Notes |
|---|---|---|
| Category 1 - Service Area | PASS/FAIL | <short reason> |
| Category 2 - Calendar Availability | PASS/FAIL | <short reason> |
| Category 3 - Service Leg Sequence | PASS/FAIL | <short reason> |

| Errors |
|---|
| <ERROR-1 line or None> |
| <ERROR-2 line or None> |
| <ERROR-3 line or None> |

| Recommended Actions |
|---|
| <action 1> |
| <action 2> |
| <action 3> |
```

Rules:

- Keep each row concise and deterministic.
- Do not output free-form paragraphs outside these tables.
- If there are no errors, include one row: `None`.
