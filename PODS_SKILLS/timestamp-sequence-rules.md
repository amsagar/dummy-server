---
name: Timestamp Sequence and Integrity Rules
description: Chronological ordering requirements for service leg timestamps, blocking logic for missing timestamps, and out-of-sequence detection. Use this when validating the temporal integrity of an order's move history.
---

# Overview

Every service leg on a PODS order must be timestamped in the correct chronological order. Timestamps are recorded by drivers in the POET system when a leg is physically completed. Missing or out-of-sequence timestamps cause cascading failures across billing, scheduling, and operations.

---

# Core Timestamp Rules

## Rule 1 — Chronological Order Required

All leg timestamps must be in strict chronological order matching the required service pattern sequence.

| Leg Pair | Rule |
|----------|------|
| IDEL → FPU | FPU timestamp MUST be after IDEL timestamp |
| IDEL → WRT | WRT timestamp MUST be after IDEL timestamp |
| WRT → RDL | RDL timestamp MUST be after WRT timestamp |
| WRT → FPU | FPU timestamp MUST be after WRT timestamp |
| IDEL → MOV | MOV timestamp MUST be after IDEL timestamp |
| MOV → FPU | FPU timestamp MUST be after MOV timestamp |
| IDEL → WTW | WTW timestamp MUST be after IDEL timestamp |

Any timestamp that is earlier than its required predecessor = **CRITICAL: out-of-sequence timestamp**.

## Rule 2 — No Missing Timestamps on Required Legs

- Every leg that has been physically completed MUST have a timestamp.
- A leg recorded as `completed` in RBMS without a corresponding POET timestamp = **CRITICAL: timestamp failure**.
- Downstream operations (invoicing, billing generation, next-leg scheduling) are blocked until all prior legs have valid timestamps.

## Rule 3 — Timestamp Must Precede Billing Events

- The IDEL timestamp starts the billing clock. No rent charges may be generated before the IDEL timestamp.
- The FPU timestamp stops the billing clock. Rent charges after the FPU timestamp = overbilling.
- If FPU timestamp exists in POET but has not reached Salesforce or D365 before the next Rent Anniversary Date → the system will incorrectly charge another month.

## Rule 4 — Integration Propagation Window

- FPU timestamp recorded in POET must reach Salesforce and D365 **before the next anniversary date**.
- If integration delay is detected (POET = Done, D365 Invoicing = Active) → **CRITICAL: billing-blocking sync delay**.
- Generate a Discrepancy Queue entry for human review.

---

# Timestamp Failure Detection

## Out-of-Sequence Detection
Compare each leg's timestamp against all preceding legs in the order's timeline. Flag any leg where:
```
leg[n].timestamp < leg[n-1].timestamp
```

## Missing Timestamp Detection
For each leg with status `completed` or `done` in RBMS/POET:
- Verify a non-null timestamp exists in POET.
- Verify the timestamp has propagated to Salesforce CPQ.
- Verify the timestamp has propagated to D365 (for billing-relevant legs: IDEL and FPU).

## Gate Progression Without Prerequisites
Block order stage advancement if:
- FPU is attempted without a recorded IDEL timestamp.
- RDL is attempted without a recorded WRT timestamp.
- MOV is attempted without a recorded IDEL timestamp.
- Any leg is attempted while a prior required leg has no timestamp.

---

# Cross-System Timestamp Reconciliation

Timestamps must be consistent across all four systems:

| System | Role |
|--------|------|
| POET | Source of truth — driver records leg completion here |
| Salesforce CPQ | Receives POET timestamp via integration |
| D365 F&O | Receives POET timestamp via integration (critical for billing) |
| RBMS | Scheduling system — must reflect completed legs |

### Reconciliation Check Steps
1. Get POET timestamps for all legs via `GetPOETTimestamps`.
2. Get Salesforce order timeline via `GetOrderTimeline`.
3. Get D365 sync status via `CheckD365OrderStatus`.
4. Compare timestamps across all three. Any mismatch = integration delay or data loss.

---

# Retroactive Timestamp Corrections

When a delayed signal arrives (driver recorded completion days late):

1. Rewind the order state to the actual event timestamp (not the recorded timestamp).
2. Recalculate billing based on the corrected timeline.
3. If the corrected timeline moves the FPU before a Rent Anniversary Date that already billed → flag for credit review.
4. Log the back-dated event in the Discrepancy Queue with original and corrected timestamps for human review.

---

# Severity Classification

| Issue | Severity |
|-------|----------|
| FPU timestamp before IDEL timestamp | CRITICAL |
| POET timestamp missing for completed leg | CRITICAL |
| FPU timestamp not propagated to D365 before anniversary | CRITICAL |
| RDL timestamp before WRT timestamp | CRITICAL |
| Any leg timestamp out of chronological order | CRITICAL |
| Timestamp in POET but not yet in Salesforce (integration lag) | HIGH |
| Back-dated event received after billing period closed | HIGH |
