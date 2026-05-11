#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
AUTH_HEADER="${AUTH_HEADER:-}"

if [[ -z "${AUTH_HEADER}" ]]; then
  echo "Set AUTH_HEADER='Authorization: Bearer <token>' before running."
  exit 1
fi

echo "Fetching workflow migration dry-run stats..."
curl -sS -H "${AUTH_HEADER}" "${BASE_URL}/workflow/migration/dry-run"
echo
echo "Freeze checklist:"
echo "  1) Disable UI entries for /toolchains routes."
echo "  2) Disable write traffic to /api/v1/toolchains* at ingress or gateway."
echo "  3) Pause asynchronous toolchain job workers."
echo "  4) Confirm no in-flight toolchain runs remain in 'running' status."
