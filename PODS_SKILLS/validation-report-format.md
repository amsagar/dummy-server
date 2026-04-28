---
name: Validation Report Format
description: Defines the exact output format the agent must use when producing a PODS order validation report. Always follow this structure — no prose summaries, no deviations.
---

# Validation Report Format

When you complete an order validation investigation, you MUST produce the report in exactly this format.
Do not add prose before or after the report. Do not summarize. Use this structure every time.

```
================================================
ORDER VALIDATION REPORT
================================================
Order ID      : [id]
Order Number  : [orderNumber]
Customer      : [customerName]
Investigated  : [current timestamp]
Overall Status: CRITICAL | NEEDS REVIEW | PASS
================================================

CHECK 1 — SERVICE LEG SEQUENCE
Status  : PASS | FLAG | CRITICAL
Pattern : [detected pattern e.g. Onsite Storage — IDEL → FPU]
Issues  : [describe issue or None]

CHECK 2 — TIMESTAMP INTEGRITY
Status  : PASS | FLAG | CRITICAL
Issues  : [describe out-of-sequence or missing timestamps or None]

CHECK 3 — BILLING STATE
Status  : PASS | FLAG | CRITICAL
Anniversary Date : [date]
Auto-Renew Flag  : ACTIVE | DISABLED
Issues  : [describe billing problems or None]

CHECK 4 — D365 SYNC
Status  : PASS | FLAG | CRITICAL
Invoice Line Status  : Booked | Invoiced
FPU Received by D365 : Yes | No
Issues  : [describe sync problems or None]

CHECK 5 — CONTAINER ASSIGNMENT
Status  : PASS | FLAG | CRITICAL
Container ID      : [id]
PodHunter On-Rent : Yes | No
Mismatch Detected : Yes | No
Issues  : [describe ghost assignment or overlap or None]

CHECK 6 — RBMS CALENDAR
Status  : PASS | FLAG | CRITICAL
Duplicates Found : [n]
Ghost Entries    : [n]
Issues  : [describe calendar problems or None]

================================================
ISSUES FOUND
================================================
[CRITICAL] [issue description] — Team: [responsible team]
[HIGH]     [issue description] — Team: [responsible team]
[MEDIUM]   [issue description] — Team: [responsible team]

================================================
RECOMMENDED ACTIONS
================================================
1. [Action] — Assigned to: [Team] — Complete before: [date]
2. [Action] — Assigned to: [Team] — Complete before: [date]
================================================
```

# Severity Definitions

- CRITICAL — actively causing billing errors or blocking operations right now
- HIGH — will cause a problem if not resolved before the next billing anniversary
- MEDIUM — data quality issue with no immediate financial impact
- PASS — no issues found for this check

# Overall Status Rules

- If ANY check is CRITICAL → Overall Status = CRITICAL
- If ANY check is FLAG and none are CRITICAL → Overall Status = NEEDS REVIEW
- If ALL checks are PASS → Overall Status = PASS
