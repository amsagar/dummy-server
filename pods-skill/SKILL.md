---
name: pods-order-validation
description: Validate a PODS order for service area, calendar availability, and leg-sequence correctness using imported PODS tools and decision table evaluation.
---

# PODS Order Validation

You validate a PODS order against 3 scheduling categories.

## Tools to use

- `Get_OrderID` (or your order lookup tool name)
- `Serviceability`
- `ContainerAvailability`
- `decisionTableEvaluate` with `tableName: "pods-leg-sequence"`

## Required flow (in this order)

1. Fetch order details using order ID.
2. Run service-area validation.
3. Run calendar availability validation.
4. Run leg-sequence validation.
5. Return a structured report with PASS/FAIL and remediation.

---

## Step 1: Fetch order

Call order lookup tool with `orderId`.

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

If required fields are missing, report them explicitly.

---

## Step 2: Service Area Validation (Category 1)

Call `Serviceability` with best available mapping from order data, including:

- origin/destination zip
- country codes
- `channel: "WEB"`
- `custTrackingId: orderId`

### PASS
If serviceable.

### FAIL (ERROR-1)
Use exactly:

`Address [ADDRESS] in zip [ZIP] is not serviceable by [SC]. Suggested SC: [CORRECT_SC]. Action needed: Reroute order or confirm extended service approval.`

---

## Step 3: Calendar Availability (Category 2)

For each relevant leg, call `ContainerAvailability` with leg date + service type + site identity (and defaults if required by API).

### PASS
If requested slots are available.

### FAIL (ERROR-2)
Use exactly:

`[DATE] is not available for [SERVICE_TYPE] at [SC]. Next available: [ALT_DATE1], [ALT_DATE2]. Calendar conflict: [REASON].`

---

## Step 4: Service Leg Sequence (Category 3)

For each leg position `i` (1..N), call:

`decisionTableEvaluate`
with:
- `tableName: "pods-leg-sequence"`
- `inputs: { "journeyType": "<journeyType>", "position": i }`

Compare actual leg code at that position with decision-table output:

- expected from table: `expectedCode`, `expectedName`, `optional`
- actual from order leg: `code`

Also verify requested dates are monotonic (no backward dates).

### PASS
All non-optional legs match and date order is valid.

### FAIL (ERROR-3)
Use exactly:

`Service leg sequence invalid for journey type [JOURNEY]. At position [N], expected [EXPECTED_CODE] ([EXPECTED_NAME]) but got [ACTUAL_CODE]. Please correct sequence or dates.`

---

## Final response format

Return plain text with this exact section structure:

1. `Order: <orderId>`
2. `Category 1 - Service Area: PASS|FAIL`
3. `Category 2 - Calendar Availability: PASS|FAIL`
4. `Category 3 - Service Leg Sequence: PASS|FAIL`
5. `Errors:` (list ERROR-1/2/3 lines, or `None`)
6. `Recommended Actions:` (bullet points)

Keep response concise, deterministic, and audit-friendly.