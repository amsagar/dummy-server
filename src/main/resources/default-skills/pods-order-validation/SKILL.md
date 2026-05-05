---
name: pods-order-validation
description: Validate a PODS order against serviceability, calendar availability, and leg sequence rules.
---

# PODS Order Validation

Use these tools in sequence:
- `pods_get_order_by_id`
- `pods_serviceability`
- `pods_container_availability`
- `decisionTableEvaluate` with `tableName="pods-leg-sequence"`

Workflow:
1. Load the order and extract identifiers, zips, service center, journey type, and legs.
2. Validate destination serviceability for the selected service center.
3. Validate calendar availability for each leg date and service type.
4. Validate leg order by calling `decisionTableEvaluate` for each leg position.
5. Return a structured PASS/FAIL report with remediation actions.
