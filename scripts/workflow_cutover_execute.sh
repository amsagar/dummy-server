#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
AUTH_HEADER="${AUTH_HEADER:-}"

if [[ -z "${AUTH_HEADER}" ]]; then
  echo "Set AUTH_HEADER='Authorization: Bearer <token>' before running."
  exit 1
fi

echo "Running final ToolChain -> Workflow migration..."
curl -sS -X POST -H "${AUTH_HEADER}" "${BASE_URL}/workflow/migration/run"
echo
echo "Migration call completed."
