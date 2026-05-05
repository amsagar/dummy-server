export type DecisionHitPolicy = "FIRST" | "UNIQUE" | "COLLECT";

export interface DecisionTableColumn {
  name: string;
  type: string;
  label?: string;
}

export interface DecisionTableRule {
  id?: string;
  inputs: Record<string, string>;
  outputs: Record<string, any>;
}

export interface DecisionTableDefinition {
  hitPolicy: DecisionHitPolicy;
  inputs: DecisionTableColumn[];
  outputs: DecisionTableColumn[];
  rules: DecisionTableRule[];
}

export interface DecisionTableSummary {
  name: string;
  description?: string;
  hitPolicy: DecisionHitPolicy;
  updatedAt: number;
}

export interface DecisionTableDetail {
  id: string;
  name: string;
  description?: string;
  hitPolicy: DecisionHitPolicy;
  dmnJson: DecisionTableDefinition;
  metadata?: Record<string, unknown>;
  createdAt: number;
  updatedAt: number;
}
