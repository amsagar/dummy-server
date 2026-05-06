Let me fetch every single component on the board in parallel.

Let me fetch the remaining tables and prototype screens I haven't read yet.

Now let me get the remaining tables and the prototype screens:

Now I have read every single component on the board. Here is the complete, deep analysis:

---

# Complete Miro Board Analysis: PODS Order Validation System

## What This Board Is

This is a **comprehensive product design and engineering board** for building a **Post-Entry Order Validation Agentic AI System** for **PODS** (a container rental/logistics company). It covers everything from raw problem discovery through architecture, UI design, and a task backlog. Here's every component broken down.

---

## 1. The Problem (Why This Exists)

**Business context:** PODS operates a container rental business. Customers order containers (Initial Delivery → on-rent period → Final Pickup). The order flows through 5 systems: **Salesforce CPQ (POET)**, **D365 F&O**, **RBMS** (routing/moves), **Cheetah** (driver routing), and **PodHunter**.

**The core pain:** These systems don't stay in sync. The result is:

- Containers billed after pickup (over-billing)
- Containers not billed during rent (revenue leakage)
- Drivers delivering wrong container sizes
- Orders booked without addresses
- Timestamps going missing or arriving null then re-arriving
- Cancellation fees stuck in "Booked" status ($426K+ impacted)
- 139 containers appearing on multiple active orders simultaneously
- 51.5% of all 2,761 service requests are just "Schedule Offbooks Move" — a manual workaround for system failures

**Funnel context:** 2.8M serviceability calls → 1.2M visitors → 600K quotes → **40K orders** (1.4% end-to-end conversion). The quote-to-order drop (6.7%) is the biggest leak.

---

## 2. Order Journey Types (The Domain Model)

The board documents **39 service leg combinations** across 4 journey categories:

| Category | Journey Types | Key Legs |
|---|---|---|
| **Local** | Local Move, Local+Storage Onsite, Local+Storage Warehouse | NEW, MOV, WRT, RDL, FPU |
| **Storage** | Storage Onsite, Storage Warehouse | NEW, WRT, RDL, FPU |
| **Inter-Franchise** | IF Move, IF Onsite, IF Warehouse | NEW, WTW, MOV, RDL, FPU |
| **Edge Cases** | Self Delivery/Pickup, City Service, Cross Border, Hawaii | SID, SFP, SCDEL, CSFPU, CSRED |

**State machine:** NEW → On Rent at Customer Site → (WRT → On Rent at Warehouse → RDL) → FPU. The IDEL timestamp starts billing; the FPU timestamp ends it.

---

## 3. Active Problems (ServiceNow — 18 records, 2 tables)

**Critical (1-Critical):**
- PRB0040295 — Timestamps removed/nulled in CPQ (INTEGRATION) — Fix in Progress
- PRB0040305 — 139 containers on multiple active orders, duplicate auto-renew lines — Root Cause Analysis

**High (2-High):**
- PRB0040272 — Past months MRENT/CPO not added automatically (booked FPU, non-timestamped, no next rent date) — Fix in Progress
- PRB0040274 — No process to add new warehouses to POET/downstream — Fix in Progress
- PRB0040313 — Can't add UTD/Cancellation fee to cancelled containers — Resolved
- PRB0040314 — LAD not giving agents option to schedule earliest date — New
- PRB0040320 — $426K+ cancellation fees stuck in Booked status — Assess
- PRB0040329 — Booked order lines with move numbers missing in RBMS — Assess

**Moderate (3-Moderate):**
- PRB0040278 — Rebooking tool doesn't populate Master_Order_Identity (inflated counts) — Assess
- PRB0040279 — Driver can assign container of different size than booked — Assess
- PRB0040283 — No way to move order from residential to commercial account — Assess
- PRB0040287 — Order lines created without identities/move numbers — Fix in Progress
- PRB0040289 — Moves of CIDs <120M filtered from RBMS day calendar — Assess
- PRB0040291 — Can't adjust discount of booked legs in POET — Assess
- PRB0040295 — Timestamps removed in CPQ — Fix in Progress
- PRB0040300 — Ops Support using Salesforce Inspector Reloaded (no proper persona) — Assess
- PRB0040301 — Services booked without address (11 found) — Assess
- PRB0040312 — ETA not exporting from Cheetah/MG for Tampa market — Fix in Progress
- PRB0040316 — Customers not prompted to sign RA on login (blocks IDEL) — Assess
- PRB0040318 — Transit charged on cancelled order — Assess
- PRB0040331 — Rebooking tool not working as designed — New

**ADO Bugs (7 total, 1 still open):**
- ID 306481 — ApexUnexpectedException when modifying invoiced order lines — **New/Open**
- IDs 211888, 232091, 253919, 259728, 264275, 274758 — All Done

---

## 4. Service Request Data Analysis (2,761 tickets)

**Top findings:**
- **51.5% (1,422)** = "Schedule Offbooks Move" — the single biggest category
- **15.2% (421)** = POET system issues
- **8.8% (243)** = Container showing wrong site/location/address
- **77** = Explicit "unhandled fault" errors

**Sub-categories identified for reclassification:**
- Order Cancellation/Rebook
- Container Assignment Issues
- Account Merge/Management
- Order Conversion Issues
- Move Line/Leg Problems
- System/POET Errors
- Billing/Pricing Problems
- Container Status/Location

**Recommended new ticket categories:** Billing Dispute, Order Conversion, Order Reinstatement, Schedule Offbooks Move (proper), reduce "Other" to true edge cases.

---

## 5. SQL Data Integrity Queries (6 production queries)

These are already-designed Snowflake queries for monitoring:

1. **Blank Line Identity Monitor** — CPQ order lines missing `Order_Line_Identity_c__c` (blocks sync to D365)
2. **Post-FPU Auto-Renew Leakage** — Rental lines still auto-renewing after FPU completed
3. **Yesterday's Post-FPU Rent Generation** — Daily check for erroneous charges
4. **Missed Rent Generation (IDEL to FPU Gap)** — Calculates missing months and missing revenue
5. **Booked-to-Invoice Integration Failure** — Lines stuck in Booked that should be Invoiced, cross-checked against D365 `DBO_SALESLINE`
6. **Container Rental Overlap/Double-Billing** — Containers where sum of active Quantity > 1

**SQL patterns documented separately:**
- Rental Line Filter (`ProductFamily = 'Rental' AND EffectiveTotal <> 0`)
- Active Container Filter (Booked FPU, null timestamp)
- Revenue Leakage Calculation (`DATEDIFF(MONTH, NEXT_START_DATE, NVL(FPU_TIMESTAMP, CURRENT_DATE))`)
- Integration Linkage (CPQ ↔ D365 join key via `Order_Line_Identity_c__c`)
- FPU Event Identification (latest non-reversal FPU per container)
- Active Customer Filter (exclude 'Hold' accounts)
- Dirty Data Exclusion (exclude Cancelled/Terminated orders)

---

## 6. Gap Analysis (28 validation causes, 15 missing from ServiceNow)

**Tracked in ServiceNow (13):** Order status sync, data integrity failures, route planning, gate progression, scheduling conflicts, container assignment, ETA inconsistency, timestamp problems, warehouse/container errors, pricing mismatches, address violations, correction workflows, order processing disruptions.

**NOT tracked anywhere (15 blind spots):**
- Error logging requirements
- Performance monitoring
- Analytics tracking
- Invalid data formats
- Missing/invalid mandatory fields
- Missing mandatory information
- Service leg sequencing issues
- Incorrect sequential logic
- Resubmission protocols
- Restricted product combinations
- Exceeded quantity limits
- Conflicting order parameters
- Master data mismatches (Salesforce/D365/RBMS)
- Unauthorized request sources
- Customer notification requirements

---

## 7. PRD (Product Requirements Document)

**Goal:** Implement a validation framework achieving:
- 99.9% reduction in orders with missing/invalid mandatory fields
- Zero orders with incorrect sequential logic
- Elimination of master data mismatches
- 95% reduction in manual intervention

**Three validation pillars:**
1. Real-time Sequential Validation (service leg sequencing, timestamps, prerequisite gates)
2. Cross-System Integration Checks (Salesforce ↔ D365 ↔ RBMS sync, container availability, ETA consistency)
3. Master Data Controls (address requirements, warehouse/container relationships, account type/pricing rules)

**Out of scope:** Historical data cleanup, UI/UX redesign, performance optimization of existing integrations, custom reporting.

---

## 8. Use Cases & Validation Logic

**Three categories of validation use cases:**

**Sequential & Lifecycle:**
- Broken service sequences (FPU without IDEL) → ML Markov Chain/RNN
- Edge case guardrail (doesn't match 3 main patterns or 4 approved edge cases) → LLM or Rules Engine
- Time-to-pickup anomalies (FPU scheduled 2 days after delivery vs 14-day average) → ML Regression
- Orphaned service detection (FPU with no IDEL ID) → Deterministic Rules Engine

**Historical Pattern:**
- Quantity outlier detection (50 containers vs customer's typical 5)
- Velocity/frequency checks (burst of orders from steady-cadence account)
- Product/service mix inconsistency (customer switches pattern without contract change)
- Seasonal baseline deviations

**Master Data & Compliance:**
- SKU-to-price tier alignment
- Referential integrity (Customer ID and Job Site Address exist and are active)
- Cross-field logical validation (FPU date after IDEL date)
- Address & entity normalization via AI

---

## 9. Post-Entry Validation Architecture ("Triple-Lock")

Three decoupled services:

**1. Anniversary Sentinel (Predictive Guard)**
- Tracks every container's Rent Anniversary Date
- 48h before anniversary: if FPU scheduled but not completed → "High-Stakes Observation"
- 12h before billing cliff: pings driver/carrier API "Is this job still on track?"
- If pickup date pushed past anniversary → notifies State Machine to prepare another month of rent

**2. Custody State Machine (Rate Manager)**
- Creates "Billing Snapshot" on anniversary date
- Locks rate for 30-day block regardless of mid-month modifications
- Tracks Physical State vs Billable State separately
- Prevents "Rate Drift" where mid-month modification accidentally triggers immediate price change

**3. Retroactive Reconciliation Hub (Time Machine)**
- Supports back-dated events (pickup happened 3 days ago, signal arrives today)
- Cross-system reconciliation: compares Order Entry "Close Date" with external telemetry
- If discrepancy found → adjusts "Rent Off" trigger to earlier date
- Generates Discrepancy Queue for Human-in-the-Loop validation when no secondary telemetry exists

---

## 10. Agentic AI System Plan (ReAct Architecture)

**Pattern:** Thought → Action → Observation → Reflection (continuous loop)

**6 validation workflow categories:**
1. Container Assignment & Location (cross-reference CID, verify physical vs logical, detect ghost assignments)
2. Scheduling & Calendar Integrity (compare RBMS calendar with routing jobs, find duplicates/orphans)
3. Timestamp Sequence Validation (build move history chain, block if prior timestamps missing, detect out-of-sequence)
4. Container Size & Pricing Exceptions (12'→16' substitutions, auto-flag for pricing review)
5. Order Data Correction (address completeness, contact fields, zip code validity)
6. Escalation & Policy Compliance (pattern detection for recurring manual interventions, process drift)

**3 phases:**
- Phase 1: Read-only validation agent monitoring order flow
- Phase 2: Automated corrections for low-risk exceptions
- Phase 3: Learning feedback loop from escalation outcomes

---

## 11. Two Architecture Options

### Option A: Azure (Semantic Kernel)
```
Salesforce UI / Teams Bot / Support Portal
        ↓
Azure API Management + Functions
  POST /api/validate-order
  POST /api/diagnose-case
  GET  /api/validation-report/{id}
        ↓
Semantic Kernel Agent
  Planner (ReAct) + Memory (Context) + Plugins + Azure OpenAI GPT-4o
        ↓
Plugin Layer:
  OrderPlugin    (GetOrder, GetOrderLines, GetContainer, GetTimeline)
  ValidationPlugin (RunHeaderVal, RunLineVal, CheckBilling, CheckRent)
  IntegrationPlugin (CheckD365Status, CheckRBMSMove, CheckSyncStatus, GetErrorLogs)
  CasePlugin     (GetCaseInfo, GetHistory, GetAttach)
  KnowledgePlugin (SearchKB, GetSimilar, GetResolution)
  ReportPlugin   (GenerateReport, FormatFindings, CreateSummary)
        ↓
Data: Salesforce CPQ | D365 F&O | Snowflake | RBMS
```

### Option B: Power Automate
```
Power Apps / Teams App (Adaptive Card) / Salesforce (HTTP Callout)
        ↓
Main Orchestrator Flow (HTTP Trigger → Parse JSON → Call Child Flows → AI Builder → Return)
  ├── Get Order Flow (HTTP to DaaS API → Parse → Return Order)
  ├── Validation Flow (Run validations on Services[] and Lines[])
  └── Diagnosis Flow (AI Builder/GPT → ReAct reasoning → Generate report)
        ↓
Data: DaaS Order API | Dataverse (Validation Results, Diagnostic Reports, Case History)
```

---

## 12. UI Prototype (7 screens, fully designed)

**Screen 1 — Login**
- Split layout: left = marketing copy ("Streamline Your Order Processing"), right = login form
- Username/password + SSO option
- Teal (#005f73) brand color

**Screen 2 — Onboarding**
- 3-step wizard: Overview → Setup → Go Live
- 3 feature cards: Order Lifecycle Management, Data Validation Dashboard, Validation & Compliance
- 2 more cards: Error Management, Analytics & Monitoring
- Stats: 98.4% validation rate, 1.2s avg processing, 24/7 monitoring
- "How It All Works Together" banner: Create → Validate → Comply → Complete

**Screen 3 — Order Lifecycle Management**
- 4 KPI cards: Initiated Today (34, +12%), In Processing (87, -3%), Completed (156, +8%), Post-Completion (23)
- 4 lifecycle stage panels: Initiation, Processing, Completion, Post-Completion
  - Initiation: trigger points (manual, API, CSV, email parser), approval tiers (<$5K auto, $5K-$25K manager, >$25K VP)
  - Processing: status transitions (Received→Validating→Approved→Fulfillment or Rejected), handoff protocols
  - Completion: closure checklist (validations passed, payment confirmed, shipping label, customer notified), archival (90 days, read-only, 7yr compliance docs)
  - Post-Completion: returns (4), refunds (2), feedback (17), disputes (1), monitoring (94% CSAT, 67% repeat rate, 2.3 day avg resolution)
- Recent Order Activity table with filter (All/Pending/Flagged)
- Validation Performance panel: pass rate 91.3%, avg time 14.2 min, errors caught 8.7%, top errors (Missing SKU 23, Invalid address 18, Price mismatch 12, Qty limit exceeded 7)

**Screen 4 — Order Data Validation Dashboard**
- 4 KPI cards: Total Orders (1,247 +12.5%), Validated (1,089, 87.3% pass rate), Pending (98, 7.9%), Failed (60, -3.2% vs last week)
- Customer Information panel (92% valid): Contact Details (1,152 passed), Account References (43 warnings), Customer Identifiers (18 failed) + error alert for orders #4821, #4835, #4847
- Product Information panel (85% valid): SKU Numbers (1,098 passed), Specifications (67 incomplete), Product References (34 invalid) + warning for orders #4839, #4856
- Pricing Information panel (94% valid): Price Points (1,178 passed), Discounts (22 warnings), Tax Calculations (9 failed) + error for order #4862 (Montana tax rule)
- Delivery Information panel (88% valid): Shipping Addresses (1,104 passed), Delivery Timeframes (51 missing), Special Instructions (28 incomplete) + warning for orders #4871, #4883
- Recent Validation Activity table (Order ID, Customer, Category, Validation Status, Errors, Timestamp, Action)

**Screens 5, 6, 7** — Additional screens (Pending Validations, Approved Orders, Rejected Orders based on nav) referenced in navigation but content follows same pattern.

---

## 13. Task Backlog (20 tasks)

| Priority | Task | Effort |
|---|---|---|
| High | Requirements Analysis & Validation Rules Documentation | L |
| High | Sequential Validation Engine Design | L |
| High | Service Leg Sequencing Rules Implementation | M |
| High | System Integration Assessment | M |
| High | Cross-System Status Synchronization | L |
| High | Performance Testing & Optimization | L |
| High | User Acceptance Testing | L |
| Medium | Timestamp & Date Validation Logic | M |
| Medium | ETA/Scheduling Data Consistency Checks | M |
| Medium | Container Assignment Validation | M |
| Medium | Prerequisite Completion Gates | M |
| Medium | Address Validation Implementation | M |
| Medium | Exception Handling Framework | M |
| Medium | Warehouse & Container Relationship Validation | M |
| Medium | Account Type & Pricing Rules Enforcement | M |
| Medium | Monitoring & Metrics Implementation | M |
| Medium | Integration Contract Updates | L |
| Medium | In-flight Order Transition Strategy | M |
| Medium | Deployment Planning | M |
| Low | Training Materials Development | M |

---

## 14. Gartner Research Frame

A workflow showing the process used to build this board: Messy Requirements → PRD → Task List. This is the meta-process documentation showing how the board itself was constructed using AI-assisted spec generation.

---

## 15. Visits-to-Orders Customer Journey Template

An empty template frame (Awareness → Interest → Consideration → Conversion & Loyalty) with 36 placeholder cards. Not yet populated — intended for mapping the customer acquisition journey.

---

# Can You Reuse `pods-ov-agent`?

**Yes, absolutely — and it's the right call.** Here's the mapping:

The board specifies a **ReAct agent** with:
- A reasoning loop (Thought → Action → Observation → Reflection)
- A plugin/tool library for querying RBMS, POET, Salesforce, D365, Cheetah, PodHunter
- Memory/context store
- 6 validation workflow categories

Your `pods-ov-agent` project is a generic agent scaffold. You need to:

1. **Keep:** The agent loop structure, tool/plugin registration pattern, memory management, any HTTP client utilities
2. **Add:** The 6 domain-specific validation plugins (Container, Scheduling, Timestamp, Pricing, OrderData, Escalation)
3. **Add:** API connectors for RBMS, POET/Salesforce, D365, Cheetah
4. **Add:** The 6 SQL queries as data integrity tools the agent can invoke
5. **Add:** The 3 post-entry services (Anniversary Sentinel, Custody State Machine, Reconciliation Hub) — these can be separate agents or tools within the main agent
6. **Choose architecture:** Azure (Semantic Kernel + GPT-4o) or Power Automate — the board has both fully designed. Azure gives you more control; Power Automate is faster to deploy if you're already in the Microsoft ecosystem
7. **Build the UI:** The 7-screen prototype is fully spec'd with Tailwind CSS + FontAwesome — it can be built directly from the HTML mockups in the prototype screens

**To confirm what's already in the project**, open it and I can map the existing code to the board's requirements precisely. Want me to do that?