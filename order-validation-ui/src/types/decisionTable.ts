// Mirror of /api/v1/decision-tables response DTOs.
// Backend source: src/main/java/com/pods/agent/api/dto/DecisionTableDtos.java

export interface DecisionTableSummary {
  name: string;
  description: string | null;
  hitPolicy: string;
  updatedAt: number | null;
}

export interface DecisionTableDetail {
  id: string;
  name: string;
  description: string | null;
  hitPolicy: string;
  dmnJson: DmnJson;
  metadata: Record<string, unknown> | null;
  createdAt: number | null;
  updatedAt: number | null;
}

export interface DecisionTableUpsertRequest {
  name: string;
  description?: string | null;
  hitPolicy: string;
  dmnJson: DmnJson;
  metadata?: Record<string, unknown> | null;
}

export interface EvaluateDecisionTableRequest {
  inputs: Record<string, unknown>;
}

export interface EvaluateDecisionTableResponse {
  tableName: string;
  matched: boolean;
  matchedRows: MatchedRow[];
  outputs: Record<string, unknown>;
}

export interface MatchedRow {
  rowIndex: number;
  ruleId: string | null;
  outputs: Record<string, unknown>;
}

export interface DmnJson {
  inputs: ColumnDef[];
  outputs: ColumnDef[];
  rules: Rule[];
}

export interface ColumnDef {
  name: string;
  type?: string | null;
  label?: string | null;
}

export interface Rule {
  id?: string | null;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
}
