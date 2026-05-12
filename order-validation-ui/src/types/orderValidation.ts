// Type contracts for the order-validation analytics API. Mirrors the
// backend OrderValidationAnalyticsController response shapes. The source
// of truth for the underlying data is the workflow's Step 7 output
// (orderId, legSequence, serviceability[], containerAvailability[]) —
// see src/main/resources/default-skills/pods-order-validation/SKILL.md.

export interface WorkflowSummary {
  id: string;
  name: string;
  version: string;
  description: string | null;
}

export interface DashboardMetrics {
  ordersValidated: number;
  passRate: number; // 0..100
  failedValidations: number;
  avgValidationMs: number | null;
  passFailByCheck: {
    legSequencePass: number;
    legSequenceFail: number;
    serviceabilityPass: number;
    serviceabilityFail: number;
    containerPass: number;
    containerFail: number;
    containerSkipped: number;
  };
  volumeBuckets: VolumeBucket[];
  recentResults: RecentResult[];
}

export interface VolumeBucket {
  dayStartTs: number;
  total: number;
  failures: number;
}

export interface RecentResult {
  instId: string;
  orderId: string;
  journeyType: string | null;
  legSequenceStatus: ResultStatus;
  serviceabilityStatus: ResultStatus;
  containerStatus: ResultStatus;
  overallStatus: OverallStatus;
  startedAt: number;
}

export type ResultStatus = "pass" | "fail" | "exception" | "skipped" | "na" | "unknown";
export type OverallStatus = "clear" | "review" | "failed" | "running" | "error";

export interface OrderQueueRow {
  instId: string;
  orderId: string;
  journeyType: string | null;
  legLines: number | null;
  legSequenceStatus: ResultStatus;
  serviceabilityStatus: ResultStatus;
  containerStatus: ResultStatus;
  overallStatus: OverallStatus;
  startedAt: number;
  endedAt: number | null;
  state: string;
  errorMessage: string | null;
}

export interface OrderQueueResponse {
  total: number;
  passed: number;
  review: number;
  failed: number;
  rows: OrderQueueRow[];
}

export interface LegSequenceSummary {
  totalChecks: number;
  passRate: number;
  failed: number;
  mostCommonFailure: string | null;
  failuresByJourney: { journeyType: string; count: number }[];
  recent: LegSequenceResult[];
  recentTotal: number;
}

export interface LegSequenceResult {
  instId: string;
  orderId: string;
  journeyType: string | null;
  actualSequence: string[];
  matchedRule: string | null;
  valid: boolean;
  message: string | null;
}

export interface ServiceabilitySummary {
  totalChecks: number;
  serviceableRate: number;
  exceptions: number;
  skipped: number;
  exceptionBreakdown: { exceptionType: string; count: number }[];
  recent: ServiceabilityResult[];
  recentTotal: number;
}

export interface ServiceabilityResult {
  instId: string;
  orderId: string;
  lineId: string | null;
  itemCode: string | null;
  originZip: string | null;
  destinationZip: string | null;
  isServiceable: boolean | null;
  exceptionType: string | null;
  status: string | null;
}

export interface ContainerAvailabilitySummary {
  idelLinesChecked: number;
  datesAvailableRate: number;
  skipped: number;
  noAvailability: number;
  skipReasons: { reason: string; count: number }[];
  recent: ContainerAvailabilityResult[];
  recentTotal: number;
}

export interface ContainerAvailabilityResult {
  instId: string;
  orderId: string;
  lineId: string | null;
  itemCode: string | null;
  checked: boolean;
  availableDates: string[];
  skipReason: string | null;
}

export interface RunDetail {
  instId: string;
  orderId: string;
  journeyType: string | null;
  state: string;
  overallStatus: OverallStatus;
  startedAt: number;
  endedAt: number | null;
  durationMs: number | null;
  errorClass: string | null;
  errorMessage: string | null;
  legSequence: LegSequenceResult;
  serviceability: ServiceabilityResult[];
  containerAvailability: ContainerAvailabilityResult[];
  timeline: ActivityTimeline[];
}

export interface ActivityTimeline {
  activityDefId: string;
  type: string | null;
  state: string | null;
  startedAt: number | null;
  endedAt: number | null;
  attempt: number | null;
  errorMessage: string | null;
}

export interface ActivityInvocation {
  activityInstId: string;
  index: number;
  state: string | null;
  startedAt: number | null;
  endedAt: number | null;
  attempt: number | null;
  input: unknown;
  output: unknown;
  errorMessage: string | null;
}

export interface RunSummary {
  instanceId: string;
  defId: string;
  state: string;
  startedAt: number | null;
  endedAt: number | null;
  requesterId: string | null;
  errorClass: string | null;
  errorMessage: string | null;
  result: unknown;
}
