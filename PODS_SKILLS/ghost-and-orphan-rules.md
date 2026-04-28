---
name: Ghost Assignment and Orphaned Record Rules
description: Referential integrity rules for detecting containers still linked to previous customers (ghost assignments), legs or order lines with no parent record (orphaned records), and scheduling anomalies. Use this when validating container-to-customer associations and record linkage integrity.
---

# Overview

Two categories of referential integrity failure are common in PODS order data:

1. **Ghost Assignments** — a container is still linked to a previous customer in the system after the rental ended.
2. **Orphaned Records** — a leg or order line has lost its association to its parent record.

Both cause downstream errors in billing, scheduling, and inventory management.

---

# Ghost Assignments

## Definition
A container that remains linked to a previous customer's order in one or more systems after the FPU has been completed and the rental has ended.

## How It Happens
- FPU is recorded in POET but the container record in PodHunter or Salesforce is not updated.
- Integration delay between POET → Salesforce → PodHunter leaves the old customer association in place.
- A new order for the same container is created before the previous order's records are fully closed.

## Detection Check
Cross-reference the container ID across all systems:

| System | What to Check |
|--------|---------------|
| Salesforce CPQ | Is there an active order for this container? What is the Customer ID (CID)? |
| PodHunter | What CID is currently assigned to this container? Is the container marked `On Rent`? |
| D365 F&O | Is there an active invoice line for this container? |
| RBMS | Is there a scheduled move for this container tied to an old customer? |

**Flag if:** PodHunter CID ≠ Salesforce active order CID, or container shows `On Rent` in PodHunter but FPU is recorded in POET.

## Severity
- Container linked to wrong customer with active billing → **CRITICAL**
- Container linked to wrong customer, no active billing → **HIGH**
- Container status stale in one system only (likely integration lag) → **MEDIUM**

---

# Orphaned Records

## Definition
A leg or order line that exists in the system but has no valid association to a parent order or parent leg.

## How It Happens
- An order is deleted or cancelled but its child legs/lines are not cleaned up.
- A data migration or integration error creates legs without a parent leg ID.
- An order type conversion (e.g., Local → Long Distance) leaves old legs behind without relinking them.

## Detection Check

### Orphaned Legs
- Every leg must have a valid `parentLegId` or `orderId` that exists and is active in Salesforce CPQ.
- Query: fetch all legs for a container and verify each has a resolvable parent order reference.
- Flag any leg where the parent order ID is null, deleted, or does not exist.

### Orphaned Order Lines
- Every order line must be associated with an active order header.
- A line with status `Booked` or `Invoiced` whose parent order is cancelled = orphaned.
- These lines may continue generating charges even though the order is closed.

## Severity
- Orphaned line still generating rent charges → **CRITICAL**
- Orphaned leg blocking downstream operations → **CRITICAL**
- Orphaned record with no active financial impact → **MEDIUM**

---

# Container Rental Overlap (Double Billing)

## Definition
A single physical container has more than one active rental line simultaneously, causing the customer to be billed multiple times for the same container.

## Detection Check
- Aggregate all active rental lines for a given container ID.
- Sum the `Quantity` field across all active lines.
- If total Quantity > 1 → **CRITICAL: double billing**.

## Common Causes
- Overlapping order lines from a data migration.
- A new order created before the previous order's FPU line was closed.
- A billing correction that accidentally created a duplicate line.

---

# Scheduling and Calendar Integrity (RBMS)

## Duplicate Jobs
- Check RBMS calendar for duplicate job entries for the same container on the same date.
- A container cannot have two drivers scheduled for the same move on the same day.

## Ghost Calendar Entries
- Jobs that remain on the driver calendar after the associated order was cancelled.
- Detection: cross-reference every RBMS calendar entry against its order status in Salesforce. If order is cancelled but job is still active → ghost entry.

## Incorrect Job Types
- The job type in RBMS must match the leg type in Salesforce.
- Example: a WRT leg in Salesforce must correspond to a Warehouse Return job in RBMS, not a Final Pickup job.

## Unremoved Cancellations
- When a scheduled leg is cancelled, the RBMS job must be removed.
- Jobs left on the calendar after cancellation cause scheduling conflicts and driver confusion.

## Offbooks Moves
- A move manually added to a driver's calendar outside the normal automated routing system.
- These are valid but must be cross-checked: every offbooks move must have a corresponding leg in Salesforce.
- An offbooks move with no Salesforce leg = untracked operation → flag for review.

---

# Full Referential Integrity Check Sequence

When validating a container, run these checks in order:

1. **Container exists** — verify container ID is in PodHunter and D365 master data.
2. **Customer assignment** — verify CID in PodHunter matches the active order CID in Salesforce.
3. **Rental status** — if FPU recorded, verify container is no longer `On Rent` in PodHunter.
4. **No orphaned legs** — all legs have valid parent order references.
5. **No orphaned lines** — all order lines belong to active orders.
6. **No rental overlap** — total active rental line quantity ≤ 1 for this container.
7. **Calendar integrity** — no duplicate, ghost, or mismatched RBMS jobs.
8. **Offbooks coverage** — every offbooks move has a Salesforce leg record.
