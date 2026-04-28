---
name: D365 Validation Rules
description: The 35 validation rules that govern order data entering D365 F&O (the ERP). 33 are Critical severity; 1 is Warning. Use this when checking whether an order is valid for D365 sync and invoicing.
---

# Overview

- 35 validation rules govern order data entering D365 F&O.
- **33 rules are Critical** — failure blocks D365 sync and downstream invoicing.
- **1 rule is Warning** — logged but does not block sync.
- 6 rules (IDs 4, 23–27) were not in the original specification — discovered in production.
- Multiple rules (IDs 8, 9, 11, 28, 29, 33) have production-specific error message variants.

---

# Order-Level Checks (IDs 1–4)

| ID | Check | Detail | Severity |
|----|-------|--------|----------|
| 1 | Order Identity | A unique Order Identity must exist | Critical |
| 2 | Account Number | Must exist in CustTable (D365 customer master) | Critical |
| 3 | Payment Fields | Payment method and processing fields must be valid | Critical |
| 4 | *(Production-discovered)* | Additional order-level check found in production | Critical |

---

# Order Line Core (IDs 5–27)

| ID | Check | Detail | Severity |
|----|-------|--------|----------|
| 5 | Product Family | Must be `Container` or `Non-Container`; drives downstream rule selection | Critical |
| 6 | Item Code | Must exist in InventTable | Critical |
| 7 | Site / Warehouse | Must reference a valid PODS facility in D365 | Critical |
| 8 | Billing Code | Must be a valid billing classification code *(has production error variants)* | Critical |
| 9 | Market Code | Must match the correct geographic market *(has production error variants)* | Critical |
| 10 | Container Reference | Container ID must exist and be available | Critical |
| 11 | Financial Consistency | Quantity signs, value signs, and limits must be internally consistent *(has production error variants)* | Critical |
| 12 | Mutually Exclusive Flags | Conflicting boolean flags must not both be set | Critical |
| 13 | Cross-Border Rules | Special handling required for orders crossing international borders | Critical |
| 14–22 | Additional Line Checks | Further line-level validations (pricing tiers, SKU alignment, service type codes) | Critical |
| 23–27 | *(Production-discovered)* | Additional line checks found in production | Critical |

---

# Address Validations (IDs 28–35)

Triggered when a scheduled date is present on the order line.

| ID | Check | Severity |
|----|-------|----------|
| 28 | Street address required *(has production error variants)* | Critical |
| 29 | City required *(has production error variants)* | Critical |
| 30 | State required | Critical |
| 31 | Postal code required | Critical |
| 32 | Country required | Critical |
| 33 | Address type must be valid: `Residential` or `Commercial` *(has production error variants)* | Critical |
| 34–35 | Additional address checks | Critical / Warning |

---

# Key Validation Concepts

## Referential Integrity
Confirm that Customer ID, Container ID, and Job Site Address all exist and are active in master data before allowing D365 sync.

## Cross-Field Logical Validation
- FPU date must be after IDEL date.
- All date fields must be internally consistent.
- Quantity and value sign combinations must follow financial rules.

## Legal Entity Assignment
- Direct Bill orders must be created under the correct legal entity for their geographic market.
- Legal entity is determined by the franchise territory, not the customer's address.

## SKU-to-Price Tier Alignment
- Order pricing must match the customer's contracted rates in the ERP.
- Any deviation between Salesforce CPQ pricing and D365 pricing tiers = flag for review.

## Account Type & Pricing Rules
- Account type classification (Residential vs Commercial) must match the pricing rules applied to the order.
- Mismatches cause incorrect billing code selection downstream.

---

# Common D365 Sync Failure Patterns

| Failure | Root Cause | Detection |
|---------|------------|-----------|
| Blank Line Identity | `Order_Line_Identity__c` missing in CPQ — prevents sync to D365 | SQL monitor on DBO_SALESLINE |
| Booked-to-Invoice Stuck | Order line in D365 stuck in `Booked` status; not transitioning to `Invoiced` | Cross-check CPQ status vs DBO_SALESLINE |
| Account not in CustTable | Customer created in Salesforce but not yet synced to D365 | Integration delay — check sync queue |
| Invalid Billing Code | Billing code used in CPQ not present in D365 classification table | Rules Engine check at order creation |
| Address Missing on Scheduled Line | Scheduled date added but address fields not populated | Triggered check on IDs 28–35 |
