/**
 * PODS Order Validation — Mock API Server
 * Covers all 43 tools needed for the POC.
 * Run: node mock-server.js
 * Base URL: http://localhost:3001
 *
 * Demo order 10045 has 4 intentional issues baked in:
 *  1. CRITICAL — Post-FPU auto-renew still ACTIVE (overbilling)
 *  2. CRITICAL — Ghost assignment: PodHunter CID doesn't match order CID
 *  3. CRITICAL — D365 invoice line stuck in Booked (sync failure)
 *  4. HIGH     — Duplicate RBMS calendar job for same container
 */

const express = require("express");
const cors = require("cors");
const app = express();
app.use(cors());
app.use(express.json());
app.use((req, _res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);
  next();
});

// ─────────────────────────────────────────────
// SHARED MOCK DATA
// ─────────────────────────────────────────────

const ORDER = {
  id: 10045,
  orderNumber: "SF-2024-10045",
  status: "Active",
  serviceType: "Onsite Storage",
  customerId: 7821,
  customerName: "Hartwell Construction LLC",
  accountType: "Commercial",
  legalEntity: "PODS Southeast LLC",
  marketCode: "ATL-01",
  billingCode: "COMM-RENT-30",
  containerSize: "16ft",
  containerId: "C-88234",
  jobSiteAddress: {
    street: "4402 Peachtree Rd NE",
    city: "Atlanta",
    state: "GA",
    postalCode: "30319",
    country: "US",
    addressType: "Commercial",
  },
  quoteId: "Q-10045-A",
  quoteExpiryDate: "2024-12-01",
  createdAt: "2024-09-15T08:30:00Z",
  updatedAt: "2024-11-20T14:22:00Z",
};

const ORDER_LINES = [
  {
    id: "OL-10045-1",
    orderId: 10045,
    lineIdentity: "OLI-10045-001",
    productFamily: "Container",
    itemCode: "PODS-16FT-RENT",
    sku: "RENT-COMM-16",
    description: "16ft Container Monthly Rental",
    billingCode: "COMM-RENT-30",
    marketCode: "ATL-01",
    quantity: 1,
    unitPrice: 289.0,
    totalPrice: 289.0,
    status: "Booked", // INTENTIONAL ISSUE #3 — should be Invoiced after FPU
    autoRenew: true, // INTENTIONAL ISSUE #1 — should be false after FPU
    containerId: "C-88234",
    startDate: "2024-09-18",
    endDate: null,
  },
  {
    id: "OL-10045-2",
    orderId: 10045,
    lineIdentity: "OLI-10045-002",
    productFamily: "Non-Container",
    itemCode: "PODS-DEL-FEE",
    sku: "FEE-DEL-COMM",
    description: "Delivery Fee",
    billingCode: "FEE-DEL",
    marketCode: "ATL-01",
    quantity: 1,
    unitPrice: 149.0,
    totalPrice: 149.0,
    status: "Invoiced",
    autoRenew: false,
    containerId: null,
    startDate: "2024-09-18",
    endDate: "2024-09-18",
  },
];

const ORDER_LEGS = [
  {
    id: "LEG-10045-1",
    orderId: 10045,
    legCode: "IDEL",
    legName: "Initial Delivery",
    status: "Completed",
    scheduledDate: "2024-09-18",
    completedDate: "2024-09-18",
    timestamp: "2024-09-18T10:15:00Z",
    driverId: "DRV-441",
    parentLegId: null,
    sequence: 1,
  },
  {
    id: "LEG-10045-2",
    orderId: 10045,
    legCode: "FPU",
    legName: "Final Pickup",
    status: "Completed",
    scheduledDate: "2024-11-20",
    completedDate: "2024-11-20",
    timestamp: "2024-11-20T09:45:00Z", // FPU recorded
    driverId: "DRV-512",
    parentLegId: "LEG-10045-1",
    sequence: 2,
  },
];

const CONTAINER = {
  id: "C-88234",
  size: "16ft",
  status: "Available",
  onRent: true, // INTENTIONAL — should be false after FPU
  currentCustomerId: 7821,
  currentOrderId: 10045,
  physicalLocation: "Customer Site",
  custody: "Customer",
  lastMoveDate: "2024-09-18",
  condition: "Good",
};

const ORDER_TIMELINE = [
  { event: "Order Created", timestamp: "2024-09-15T08:30:00Z", system: "Salesforce CPQ", actor: "CSR Agent" },
  { event: "Order Synced to D365", timestamp: "2024-09-15T08:35:00Z", system: "D365 F&O", actor: "Integration" },
  { event: "Order Synced to RBMS", timestamp: "2024-09-15T08:36:00Z", system: "RBMS", actor: "Integration" },
  { event: "Appointment Booked", timestamp: "2024-09-16T11:00:00Z", system: "Cheetah", actor: "Customer" },
  { event: "IDEL Completed", timestamp: "2024-09-18T10:15:00Z", system: "POET", actor: "Driver DRV-441" },
  { event: "Rent Line Generated", timestamp: "2024-09-18T10:20:00Z", system: "D365 F&O", actor: "Billing Engine" },
  { event: "FPU Completed", timestamp: "2024-11-20T09:45:00Z", system: "POET", actor: "Driver DRV-512" },
  // NOTE: D365 NOT updated after FPU — intentional issue #3
];

const CUSTOMER_ACCOUNT = {
  id: 7821,
  name: "Hartwell Construction LLC",
  accountType: "Commercial",
  status: "Active",
  creditLimit: 50000,
  paymentTerms: "Net30",
  billingAddress: {
    street: "4402 Peachtree Rd NE",
    city: "Atlanta",
    state: "GA",
    postalCode: "30319",
    country: "US",
  },
  primaryContact: "Mark Hartwell",
  email: "billing@hartwellconstruction.com",
  phone: "404-555-0188",
};

const QUOTE = {
  id: "Q-10045-A",
  orderId: 10045,
  customerId: 7821,
  status: "Accepted",
  expiryDate: "2024-12-01",
  baseRate: 289.0,
  fuelSurcharge: 18.5,
  totalMonthlyRate: 307.5,
  rateLockedUntil: "2024-12-18",
  createdAt: "2024-09-10T00:00:00Z",
};

const ACTIVE_RENTAL_LINES = [
  {
    containerId: "C-88234",
    orderId: 10045,
    lineId: "OL-10045-1",
    quantity: 1,
    autoRenew: true,
    status: "Active",
    startDate: "2024-09-18",
  },
  // Quantity total = 1, no overlap — but autoRenew is the problem
];

// ─────────────────────────────────────────────
// GROUP A — DaaS / Salesforce CPQ
// ─────────────────────────────────────────────

// 1. GetOrder
app.get("/daas/orders/:id", (req, res) => {
  if (parseInt(req.params.id) !== 10045) return res.status(404).json({ error: "Order not found" });
  res.json(ORDER);
});

// 2. GetOrdersByCustomer
app.get("/daas/orders", (req, res) => {
  if (parseInt(req.query.customerId) !== 7821) return res.json([]);
  res.json([ORDER]);
});

// 3. GetOrderLines
app.get("/daas/orders/:id/lines", (req, res) => {
  if (parseInt(req.params.id) !== 10045) return res.status(404).json({ error: "Order not found" });
  res.json(ORDER_LINES);
});

// 4. GetOrderLegs
app.get("/daas/orders/:id/legs", (req, res) => {
  if (parseInt(req.params.id) !== 10045) return res.status(404).json({ error: "Order not found" });
  res.json(ORDER_LEGS);
});

// 5. GetContainer
app.get("/daas/containers/:id", (req, res) => {
  if (req.params.id !== "C-88234") return res.status(404).json({ error: "Container not found" });
  res.json(CONTAINER);
});

// 6. GetOrderTimeline
app.get("/daas/orders/:id/timeline", (req, res) => {
  if (parseInt(req.params.id) !== 10045) return res.status(404).json({ error: "Order not found" });
  res.json(ORDER_TIMELINE);
});

// 7. GetCustomerAccount
app.get("/daas/customers/:id", (req, res) => {
  if (parseInt(req.params.id) !== 7821) return res.status(404).json({ error: "Customer not found" });
  res.json(CUSTOMER_ACCOUNT);
});

// 8. GetQuoteDetails
app.get("/daas/quotes/:id", (req, res) => {
  if (req.params.id !== "Q-10045-A") return res.status(404).json({ error: "Quote not found" });
  res.json(QUOTE);
});

// 9. GetActiveRentalLines
app.get("/daas/containers/:id/rental-lines", (req, res) => {
  if (req.params.id !== "C-88234") return res.status(404).json({ error: "Container not found" });
  res.json(ACTIVE_RENTAL_LINES);
});

// 10. GetCaseById
app.get("/daas/cases/:id", (req, res) => {
  res.json({
    id: req.params.id,
    orderId: 10045,
    customerId: 7821,
    type: "Billing Dispute",
    status: "Open",
    subject: "Auto-renew charge after container picked up",
    description: "Customer was charged for an extra month after FPU was completed on Nov 20.",
    priority: "High",
    createdAt: "2024-11-22T09:00:00Z",
    assignedTo: "Billing Support Team",
  });
});

// 11. GetCasesByOrder
app.get("/daas/cases", (req, res) => {
  if (parseInt(req.query.orderId) !== 10045 && parseInt(req.query.customerId) !== 7821) return res.json([]);
  res.json([
    {
      id: "CASE-88821",
      orderId: 10045,
      customerId: 7821,
      type: "Billing Dispute",
      status: "Open",
      subject: "Auto-renew charge after container picked up",
      priority: "High",
      createdAt: "2024-11-22T09:00:00Z",
    },
  ]);
});

// ─────────────────────────────────────────────
// GROUP B — D365 F&O
// ─────────────────────────────────────────────

// 12. CheckD365OrderStatus
app.get("/d365/orders/:id", (req, res) => {
  res.json({
    orderId: req.params.id,
    d365SyncStatus: "Synced",
    lastSyncedAt: "2024-09-15T08:35:00Z",
    invoicingStatus: "Active", // INTENTIONAL ISSUE #3 — should be Closed after FPU
    fpuReceivedByD365: false, // FPU not propagated to D365
    notes: "FPU event recorded in POET on 2024-11-20 but not yet received by D365.",
  });
});

// 13. CheckD365InvoiceLines
app.get("/d365/saleslines", (req, res) => {
  res.json([
    {
      lineId: "OL-10045-1",
      orderId: req.query.orderId || 10045,
      containerId: "C-88234",
      status: "Booked", // INTENTIONAL ISSUE #3 — stuck in Booked, should be Invoiced
      autoRenew: true,
      quantity: 1,
      unitPrice: 289.0,
      lastUpdated: "2024-09-18T10:20:00Z",
      d365TableRef: "DBO_SALESLINE",
    },
  ]);
});

// 14. CheckRentGeneration
app.get("/d365/rent", (req, res) => {
  res.json({
    containerId: req.query.containerId || "C-88234",
    activeRentalLines: 1,
    autoRenewActive: true, // INTENTIONAL ISSUE #1
    lastRentGeneratedDate: "2024-11-18T00:00:00Z",
    nextRentGenerationDate: "2024-12-18T00:00:00Z",
    fpuRecorded: true,
    fpuDate: "2024-11-20",
    warning: "Auto-renew is still ACTIVE despite FPU being recorded on 2024-11-20. Next charge will generate on 2024-12-18.",
  });
});

// 15. CheckBillingAnniversary
app.get("/d365/billing/anniversary", (req, res) => {
  res.json({
    containerId: req.query.containerId || "C-88234",
    rentAnniversaryDate: "2024-12-18",
    billingCliffWindowStart: "2024-12-16T00:00:00Z",
    currentDate: "2024-11-27",
    daysUntilAnniversary: 21,
    fpuCompleted: true,
    fpuDate: "2024-11-20",
    riskLevel: "CRITICAL",
    riskReason: "FPU completed but auto-renew not disabled. Charge of $289 will generate on 2024-12-18 unless corrected.",
  });
});

// 16. GetD365CustomerMaster
app.get("/d365/customers/:id", (req, res) => {
  res.json({
    customerId: req.params.id,
    existsInCustTable: true,
    accountName: "Hartwell Construction LLC",
    accountStatus: "Active",
    paymentTerms: "Net30",
    creditLimit: 50000,
    legalEntity: "PODS Southeast LLC",
  });
});

// 17. GetD365InventTable
app.get("/d365/inventory/:itemCode", (req, res) => {
  res.json({
    itemCode: req.params.itemCode,
    existsInInventTable: true,
    description: "16ft Container Monthly Rental",
    productFamily: "Container",
    status: "Active",
  });
});

// 18. GetD365PriceTiers
app.get("/d365/price-tiers", (req, res) => {
  res.json({
    sku: req.query.sku || "RENT-COMM-16",
    contractedRate: 289.0,
    currentOrderRate: 289.0,
    rateMatch: true,
    effectiveFrom: "2024-09-18",
    lockedUntil: "2024-12-18",
    fuelSurcharge: 18.5,
  });
});

// 19. GetLegalEntityConfig
app.get("/d365/legal-entities", (req, res) => {
  res.json({
    marketCode: req.query.marketCode || "ATL-01",
    expectedLegalEntity: "PODS Southeast LLC",
    orderLegalEntity: "PODS Southeast LLC",
    match: true,
    region: "Southeast US",
  });
});

// 20. GetBillingSnapshot
app.get("/d365/billing/snapshot", (req, res) => {
  res.json({
    containerId: req.query.containerId || "C-88234",
    snapshotDate: "2024-11-18",
    lockedRate: 289.0,
    lockedFuelSurcharge: 18.5,
    periodStart: "2024-11-18",
    periodEnd: "2024-12-18",
    rateChangesPending: false,
  });
});

// 21. CheckRateLock
app.get("/d365/billing/rate-lock", (req, res) => {
  res.json({
    containerId: req.query.containerId || "C-88234",
    rateLocked: true,
    lockedRate: 289.0,
    lockExpiresAt: "2024-12-18",
    midMonthModificationDetected: false,
    rateDrift: false,
  });
});

// ─────────────────────────────────────────────
// GROUP C — RBMS
// ─────────────────────────────────────────────

// 22. CheckRBMSMove
app.get("/rbms/moves", (req, res) => {
  res.json([
    {
      moveId: "MOV-10045-1",
      orderId: req.query.orderId || 10045,
      legCode: "IDEL",
      scheduledDate: "2024-09-18",
      completedDate: "2024-09-18",
      status: "Completed",
      driverId: "DRV-441",
      fromLocation: "SC-ATL-01",
      toLocation: "Customer Site — 4402 Peachtree Rd NE",
    },
    {
      moveId: "MOV-10045-2",
      orderId: req.query.orderId || 10045,
      legCode: "FPU",
      scheduledDate: "2024-11-20",
      completedDate: "2024-11-20",
      status: "Completed",
      driverId: "DRV-512",
      fromLocation: "Customer Site — 4402 Peachtree Rd NE",
      toLocation: "SC-ATL-01",
    },
  ]);
});

// 23. CheckRBMSCalendar
app.get("/rbms/calendar", (req, res) => {
  res.json({
    containerId: req.query.containerId || "C-88234",
    calendarEntries: [
      {
        jobId: "JOB-10045-FPU-1",
        jobType: "Final Pickup",
        scheduledDate: "2024-11-20",
        driverId: "DRV-512",
        status: "Completed",
        isGhost: false,
      },
      {
        jobId: "JOB-10045-FPU-2", // INTENTIONAL ISSUE #4 — duplicate job
        jobType: "Final Pickup",
        scheduledDate: "2024-11-20",
        driverId: "DRV-498",
        status: "Active", // not removed after first driver completed it
        isGhost: true,
        warning: "Duplicate FPU job — second driver still has this on their calendar.",
      },
    ],
    duplicatesFound: 1,
    ghostEntriesFound: 1,
  });
});

// 24. CheckSyncStatus
app.get("/rbms/sync", (req, res) => {
  res.json({
    orderId: req.query.orderId || 10045,
    rbmsSyncStatus: "Partial",
    lastSyncedAt: "2024-11-20T10:00:00Z",
    fpuSentToSalesforce: true,
    fpuSentToD365: false, // integration delay
    integrationLagMinutes: null,
    integrationLagDays: 7,
    warning: "FPU event sent to Salesforce but D365 has not acknowledged receipt after 7 days.",
  });
});

// 25. GetRoutingJobs
app.get("/rbms/routing-jobs", (req, res) => {
  res.json([
    {
      jobId: "JOB-10045-IDEL",
      orderId: req.query.orderId || 10045,
      jobType: "Initial Delivery",
      assignedDriver: "DRV-441",
      status: "Completed",
      scheduledDate: "2024-09-18",
    },
    {
      jobId: "JOB-10045-FPU-1",
      orderId: req.query.orderId || 10045,
      jobType: "Final Pickup",
      assignedDriver: "DRV-512",
      status: "Completed",
      scheduledDate: "2024-11-20",
    },
  ]);
});

// 26. CheckJobType
app.get("/rbms/job-type-check", (req, res) => {
  res.json({
    orderId: req.query.orderId || 10045,
    checks: [
      { legCode: "IDEL", salesforceLegType: "Initial Delivery", rbmsJobType: "Initial Delivery", match: true },
      { legCode: "FPU", salesforceLegType: "Final Pickup", rbmsJobType: "Final Pickup", match: true },
    ],
    mismatchFound: false,
  });
});

// ─────────────────────────────────────────────
// GROUP D — POET
// ─────────────────────────────────────────────

// 27. GetPOETTimestamps
app.get("/poet/legs", (req, res) => {
  res.json([
    {
      legId: "LEG-10045-1",
      orderId: req.query.orderId || 10045,
      legCode: "IDEL",
      poetTimestamp: "2024-09-18T10:15:00Z",
      recordedBy: "DRV-441",
      propagatedToSalesforce: true,
      propagatedToD365: true,
      salesforceTimestamp: "2024-09-18T10:18:00Z",
      d365Timestamp: "2024-09-18T10:20:00Z",
    },
    {
      legId: "LEG-10045-2",
      orderId: req.query.orderId || 10045,
      legCode: "FPU",
      poetTimestamp: "2024-11-20T09:45:00Z",
      recordedBy: "DRV-512",
      propagatedToSalesforce: true,
      propagatedToD365: false, // INTENTIONAL — D365 never got it
      salesforceTimestamp: "2024-11-20T09:50:00Z",
      d365Timestamp: null,
      warning: "FPU timestamp propagated to Salesforce but NOT to D365. D365 is still billing.",
    },
  ]);
});

// 28. GetLegCompletionStatus
app.get("/poet/leg-status", (req, res) => {
  res.json({
    orderId: req.query.orderId || 10045,
    legs: [
      { legCode: "IDEL", status: "Completed", timestampExists: true },
      { legCode: "FPU", status: "Completed", timestampExists: true, d365Acknowledged: false },
    ],
    allLegsTimestamped: true,
    allLegsAcknowledgedByD365: false,
  });
});

// 29. GetErrorLogs
app.get("/poet/errors", (req, res) => {
  res.json([
    {
      logId: "ERR-20241120-001",
      orderId: req.query.orderId || 10045,
      timestamp: "2024-11-20T10:05:00Z",
      system: "D365 Integration",
      errorCode: "INT-5032",
      message: "FPU event POST to D365 endpoint timed out. Retry queue exhausted after 3 attempts.",
      resolved: false,
    },
  ]);
});

// ─────────────────────────────────────────────
// GROUP E — PodHunter
// ─────────────────────────────────────────────

// 30. GetContainerLocation
app.get("/podhunter/containers/:id/location", (req, res) => {
  res.json({
    containerId: req.params.id,
    physicalLocation: "SC-ATL-01 — PODS Atlanta Warehouse", // Back at warehouse after FPU
    custodyType: "Warehouse",
    lastUpdated: "2024-11-20T11:00:00Z",
    storageCenter: "SC-ATL-01",
    coordinates: { lat: 33.749, lng: -84.388 },
  });
});

// 31. CheckGhostAssignment
app.get("/podhunter/containers/:id/assignment", (req, res) => {
  res.json({
    containerId: req.params.id,
    podhunterAssignedCustomerId: 7821, // Still showing old customer INTENTIONAL ISSUE #2
    podhunterAssignedOrderId: 10045,
    onRentInPodHunter: true, // Should be false — FPU done
    activeOrderInSalesforce: false, // Salesforce knows order is done
    mismatch: true,
    warning: "GHOST ASSIGNMENT DETECTED — Container still marked On Rent in PodHunter with customer 7821 but FPU was completed 2024-11-20. PodHunter record was not updated post-FPU.",
  });
});

// 32. GetContainerCustodyHistory
app.get("/podhunter/containers/:id/custody", (req, res) => {
  res.json([
    { event: "Dispatched from Warehouse", timestamp: "2024-09-18T08:00:00Z", from: "SC-ATL-01", to: "Customer Site", custody: "Customer" },
    { event: "Delivered to Customer", timestamp: "2024-09-18T10:15:00Z", custody: "Customer", recordedBy: "DRV-441" },
    { event: "FPU Completed", timestamp: "2024-11-20T09:45:00Z", from: "Customer Site", to: "SC-ATL-01", custody: "Warehouse", recordedBy: "DRV-512" },
    // NOTE: PodHunter record NOT updated to reflect custody return — ghost persists
  ]);
});

// ─────────────────────────────────────────────
// GROUP F — Cheetah
// ─────────────────────────────────────────────

// 33. GetAppointments
app.get("/cheetah/appointments", (req, res) => {
  res.json([
    {
      appointmentId: "APT-10045-1",
      orderId: req.query.orderId || 10045,
      type: "Initial Delivery",
      scheduledDate: "2024-09-18",
      timeWindow: "08:00–12:00",
      status: "Completed",
      confirmedByCustomer: true,
    },
    {
      appointmentId: "APT-10045-2",
      orderId: req.query.orderId || 10045,
      type: "Final Pickup",
      scheduledDate: "2024-11-20",
      timeWindow: "08:00–12:00",
      status: "Completed",
      confirmedByCustomer: true,
    },
  ]);
});

// 34. CheckScheduledPickup
app.get("/cheetah/pickup-status", (req, res) => {
  res.json({
    containerId: req.query.containerId || "C-88234",
    fpuScheduled: true,
    fpuScheduledDate: "2024-11-20",
    fpuCompleted: true,
    fpuCompletedDate: "2024-11-20",
    withinBillingCliffWindow: false,
    nextAnniversaryDate: "2024-12-18",
    daysUntilAnniversary: 21,
    recommendedAction: "FPU completed. Verify D365 and PodHunter are updated to prevent billing continuation.",
  });
});

// 35. GetDriverConfirmation
app.get("/cheetah/driver-confirmation", (req, res) => {
  res.json({
    orderId: req.query.orderId || 10045,
    driverId: "DRV-512",
    confirmedPickup: true,
    confirmationTimestamp: "2024-11-20T09:50:00Z",
    notes: "Container picked up. Customer signed release form.",
  });
});

// ─────────────────────────────────────────────
// GROUP G — Snowflake (Historical Analytics)
// ─────────────────────────────────────────────

// 36. GetCustomerOrderHistory
app.get("/snowflake/customer-order-history", (req, res) => {
  res.json({
    customerId: req.query.customerId || 7821,
    totalOrdersLast12Months: 3,
    averageContainersPerOrder: 1.3,
    currentOrderContainers: 1,
    outlierDetected: false,
    historicalNorm: "1–2 containers per order",
  });
});

// 37. GetOrderFrequencyPattern
app.get("/snowflake/order-frequency", (req, res) => {
  res.json({
    customerId: req.query.customerId || 7821,
    averageOrdersPerQuarter: 1.0,
    currentQuarterOrders: 1,
    velocitySpike: false,
    notes: "Order frequency is within normal range for this customer.",
  });
});

// 38. GetServiceMixHistory
app.get("/snowflake/service-mix", (req, res) => {
  res.json({
    customerId: req.query.customerId || 7821,
    historicalServiceTypes: ["Onsite Storage"],
    currentServiceType: "Onsite Storage",
    inconsistencyDetected: false,
    notes: "Customer consistently uses Onsite Storage. No service type switch detected.",
  });
});

// 39. GetSeasonalBaseline
app.get("/snowflake/seasonal-baseline", (req, res) => {
  res.json({
    marketCode: req.query.marketCode || "ATL-01",
    currentMonth: "November",
    expectedVolumeRange: { min: 80, max: 130 },
    actualVolume: 104,
    deviationDetected: false,
  });
});

// 40. GetAverageTimeToPickup
app.get("/snowflake/avg-time-to-pickup", (req, res) => {
  res.json({
    servicePattern: "Onsite Storage",
    marketCode: req.query.marketCode || "ATL-01",
    historicalAvgDays: 68,
    thisOrderDays: 63,
    anomalyDetected: false,
    notes: "63 days is within 1 standard deviation of the 68-day average for Onsite Storage in ATL-01.",
  });
});

// ─────────────────────────────────────────────
// GROUP H — Knowledge Base
// ─────────────────────────────────────────────

// 41. SearchKnowledgeBase
app.get("/kb/search", (req, res) => {
  res.json({
    query: req.query.q || "",
    results: [
      {
        articleId: "KB-4421",
        title: "How to disable Auto-Renew after FPU",
        summary: "Navigate to order line in Salesforce CPQ, set Auto-Renew to false, save. Then trigger D365 sync manually.",
        applicableTo: "Post-FPU billing correction",
        lastUpdated: "2024-08-15",
      },
      {
        articleId: "KB-3819",
        title: "Resolving Ghost Assignments in PodHunter",
        summary: "Use PodHunter Admin portal to manually clear customer association and reset container status to Available.",
        applicableTo: "Ghost assignment resolution",
        lastUpdated: "2024-07-02",
      },
    ],
  });
});

// 42. GetSimilarCases
app.get("/kb/similar-cases", (req, res) => {
  res.json([
    {
      caseId: "CASE-71204",
      type: req.query.type || "Post-FPU Auto-Renew",
      summary: "Container picked up Oct 3 but auto-renew charge generated Oct 18. D365 FPU event missing.",
      resolution: "Manually triggered D365 sync. Auto-renew disabled. Credit issued for erroneous charge.",
      resolvedIn: "2 days",
      occurredAt: "2024-10-18",
    },
    {
      caseId: "CASE-68932",
      type: "D365 Sync Failure",
      summary: "FPU recorded in POET but INT-5032 timeout prevented D365 update. Invoice continued for 9 days.",
      resolution: "DevOps requeued the integration event. D365 updated. Billing corrected.",
      resolvedIn: "3 days",
      occurredAt: "2024-09-05",
    },
  ]);
});

// 43. GetResolution
app.get("/kb/resolution/:articleId", (req, res) => {
  const resolutions = {
    "KB-4421": {
      articleId: "KB-4421",
      title: "How to disable Auto-Renew after FPU",
      steps: [
        "1. Open order in Salesforce CPQ",
        "2. Navigate to Order Lines tab",
        "3. Find the active rental line for the container",
        "4. Set Auto-Renew = false",
        "5. Save the record",
        "6. Trigger D365 sync from Integration Console",
        "7. Verify DBO_SALESLINE shows autoRenew = false within 15 minutes",
      ],
      team: "Billing Support",
      urgency: "Complete before next anniversary date",
    },
    "KB-3819": {
      articleId: "KB-3819",
      title: "Resolving Ghost Assignments in PodHunter",
      steps: [
        "1. Log into PodHunter Admin Portal",
        "2. Search container by ID",
        "3. Click 'Clear Customer Assignment'",
        "4. Set container status to 'Available'",
        "5. Verify Salesforce reflects same status",
      ],
      team: "Operations Support",
      urgency: "Resolve within 48 hours to prevent inventory errors",
    },
  };
  const r = resolutions[req.params.articleId];
  if (!r) return res.status(404).json({ error: "Article not found" });
  res.json(r);
});

// ─────────────────────────────────────────────
// START SERVER
// ─────────────────────────────────────────────

const PORT = 3001;
app.listen(PORT, () => {
  console.log(`\nPODS Mock API Server running on http://localhost:${PORT}`);
  console.log(`\nDemo order: 10045  |  Customer: 7821  |  Container: C-88234`);
  console.log(`\nIntentional issues in demo data:`);
  console.log(`  [CRITICAL] Auto-renew still ACTIVE after FPU → GET /d365/rent?containerId=C-88234`);
  console.log(`  [CRITICAL] Ghost assignment in PodHunter    → GET /podhunter/containers/C-88234/assignment`);
  console.log(`  [CRITICAL] D365 invoice line stuck in Booked → GET /d365/saleslines?orderId=10045`);
  console.log(`  [HIGH]     Duplicate RBMS calendar job       → GET /rbms/calendar?containerId=C-88234`);
  console.log(`\nAll 43 tools available. Press Ctrl+C to stop.\n`);
});
