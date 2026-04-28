---
name: PODS Service Leg Patterns
description: Valid service leg sequences and approved edge cases for PODS container rental orders. Use this to validate whether an order's leg sequence is structurally correct.
---

# Leg Code Reference

| Code | Full Name |
|------|-----------|
| IDEL / NEW | Initial Delivery — first leg of any order; starts the billing clock |
| FPU | Final Pickup — last leg of any order; stops the billing clock |
| WRT | Warehouse Return — container picked up and taken to a PODS warehouse (temporary) |
| RDL | Redelivery — container taken from warehouse back to customer site |
| MOV | Move — container relocated from one customer address to another |
| WTW | Warehouse to Warehouse — container transported between two PODS warehouses |
| SID | Self Initial Delivery — customer collects from a PODS location |
| SFP | Self Final Pickup — customer returns container to a PODS location |
| CSCDEL / CSRED / CSFPU | City Service variants for dense urban operations |

`[optional]` = leg may or may not be present depending on the specific order.

---

# Pattern 1 — Onsite Storage

Container stays at the customer's property for the entire rental.

```
IDEL → FPU
```

---

# Pattern 2 — Warehouse Storage

Container is delivered, then stored at a PODS warehouse, then optionally redelivered before final pickup.

```
IDEL → WRT → [RDL] → FPU
```

---

# Pattern 3 — Local Move

Container is delivered, then moved to a different address within the same franchise territory.

```
IDEL → MOV → FPU
```

---

# Pattern 4 — Inter-Franchise (IF) Move

Order crosses franchise territory boundaries. Requires coordination between two franchise operations.

```
IDEL → [WTW] → MOV → FPU       (IF Move)
IDEL → [WTW] → FPU              (IF Onsite)
IDEL → [WTW] → WRT → [RDL] → FPU  (IF Warehouse)
```

---

# Approved Edge Cases (do NOT flag these as invalid)

| Type | Description |
|------|-------------|
| Self Initial Delivery (SID) | Customer collects container from PODS location |
| Self Final Pickup (SFP) | Customer returns container to PODS location |
| City Service (CSCDEL, CSRED, CSFPU) | Dense urban area operations with modified logistics |
| Cross Border | Order crosses an international border |
| Hawaii | Island logistics with special handling rules |

---

# Validation Rules

- Every order MUST begin with IDEL (or SID as an approved edge case).
- Every order MUST end with FPU (or SFP as an approved edge case).
- RDL can only occur after WRT.
- MOV can only occur after IDEL.
- WTW can only occur after IDEL and before MOV, WRT, or FPU.
- Any order whose legs do not match one of the 4 main patterns or 5 approved edge cases MUST be flagged for manual review.
- Do not block approved edge cases — isolate only truly unrecognized patterns.
