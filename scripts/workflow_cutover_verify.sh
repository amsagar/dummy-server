#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
AUTH_HEADER="${AUTH_HEADER:-}"

if [[ -z "${AUTH_HEADER}" ]]; then
  echo "Set AUTH_HEADER='Authorization: Bearer <token>' before running."
  exit 1
fi

echo "Workflow migration reconciliation snapshot:"
curl -sS -H "${AUTH_HEADER}" "${BASE_URL}/workflow/migration/dry-run"
echo
echo "Sample workflow list:"
curl -sS -H "${AUTH_HEADER}" "${BASE_URL}/workflow/processes"
echo
echo "Sample analytics:"
curl -sS -H "${AUTH_HEADER}" "${BASE_URL}/workflow/runs/analytics?limit=10&offset=0"
echo
echo "Verification calls completed."
