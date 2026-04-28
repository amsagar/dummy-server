---
name: PODS Billing Rules
description: Billing cliff, auto-renew flag behavior, anniversary date logic, FPU timing requirements, and common billing failure modes. Use this when validating billing state on any active or recently closed order.
---

# Billing Model

- PODS uses **non-prorated 30-day billing cycles**.
- A new 30-day billing period begins on the **Rent Anniversary Date** each month.
- If a container is not picked up before the billing anniversary date, the customer is charged for a full additional month at the current locked rate.
- This makes accurate, timely FPU recording critical to billing correctness.

---

# Key Billing Terms

| Term | Meaning |
|------|---------|
| Rent Anniversary Date | The date each month when a new 30-day billing period begins |
| Billing Cliff | The moment just before the Rent Anniversary Date — the last safe window to record an FPU and avoid another month's charge |
| Auto-Renew | A flag on a rental line that automatically generates a new billing charge each period |
| Rate Lock | Freezing the rental price for a 30-day period so mid-month modifications do not trigger incorrect rate changes |
| On Rent | Status of a container actively being rented; billing clock is running |

---

# Critical Billing Rules

## 1. Auto-Renew Flag

- Auto-Renew MUST be **disabled** when FPU is completed.
- If Auto-Renew is still active after FPU → **CRITICAL: ongoing overbilling**.
- Check: query active rental lines for this container and verify `autoRenew = false` once FPU timestamp exists.

## 2. Billing Cliff Window

- Monitor all active containers within **48 hours** of their Rent Anniversary Date.
- If FPU is scheduled but not yet completed within this window → escalate to **High-Stakes Observation**.
- Trigger status-check notification to driver/carrier app.
- If driver confirms pickup will not happen before anniversary → notify billing to prepare for another month of rent at current rate.

## 3. Rent Generation Gap (IDEL to FPU)

- Billing charges must be generated continuously from IDEL through FPU.
- If rent line generation stopped before FPU → **CRITICAL: unbilled revenue gap**.
- Check: verify rent lines exist for every 30-day period between IDEL date and current date (or FPU date).

## 4. Rate Lock During Active Period

- On each anniversary date, the rate for the next 30 days is locked via a Billing Snapshot.
- Any order modification during the month must NOT change the locked rate.
- Rate changes only take effect on the next anniversary date.
- Mid-month modifications that trigger an immediate price change = **Rate Drift** — flag as CRITICAL.

## 5. Container Rental Overlap (Double Billing)

- A single container must not have more than one active rental line at the same time.
- Check: aggregate active rental lines per container. If total Quantity > 1 → **CRITICAL: double billing**.

## 6. Custody Tracking and Rate Changes

- Customer Custody = container is at customer's site.
- Warehouse Custody = container is at a PODS facility.
- When a container moves mid-month (e.g., WRT), record the new physical location but defer any rate change to the next anniversary date.
- Do not allow a custody transfer to trigger an immediate rate change within a billing period.

---

# Post-FPU Billing Checks

After FPU is recorded, verify all of the following within the same billing day:

1. Auto-Renew flag is `false` on all rental lines for this container.
2. No new rent charges have been generated after the FPU timestamp.
3. The order line status has transitioned from `Booked` → `Invoiced`.
4. D365 has received and confirmed the FPU event.
5. Container status in PodHunter is no longer `On Rent`.

---

# Common Billing Failure Modes

| Failure | Cause | Severity |
|---------|-------|----------|
| Post-FPU Auto-Renew Leakage | Auto-Renew not disabled after FPU | CRITICAL |
| Missed Rent Generation | Rent line generation stopped early | CRITICAL |
| Double Billing | Overlapping active rental lines | CRITICAL |
| Rate Drift | Mid-month modification triggered rate change | CRITICAL |
| Billing Cliff Miss | FPU not recorded before anniversary | HIGH |
| Booked-to-Invoice Failure | Order line stuck in Booked status | HIGH |
| Quote Expiration | Rate or fuel surcharge offer expired before order finalized | MEDIUM |
