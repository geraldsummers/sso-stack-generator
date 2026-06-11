#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[contract-reports-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

lock_file="$tmp_dir/components.lock.json"
reports_dir="$tmp_dir/reports"

cat > "$lock_file" <<'EOF_JSON'
{
  "generatedAt": "test",
  "components": ["core", "portal", "seafile", "onlyoffice", "crowdsec"]
}
EOF_JSON

"$ROOT_DIR/scripts/generate-contract-reports.sh" \
  --catalog "$ROOT_DIR/stack.config/components.json" \
  --contracts "$ROOT_DIR/stack.config/service-contracts.json" \
  --lock "$lock_file" \
  --output-dir "$reports_dir"

for report in contracts module-selection evidence-coverage backup-topology access-offboarding slo security; do
  jq empty "$reports_dir/$report.json"
done

jq -e '.components.portal.name == "Stack Portal"' "$reports_dir/contracts.json" >/dev/null
jq -e '.modules[] | select(.component == "portal" and .portal.visible == true)' "$reports_dir/module-selection.json" >/dev/null
jq -e '.components[] | select(.component == "onlyoffice" and (.evidence.expectations | index("seafile.docx.edit")))' "$reports_dir/evidence-coverage.json" >/dev/null
jq -e '.components[] | select(.component == "seafile" and .backup.policy == "kopia")' "$reports_dir/backup-topology.json" >/dev/null
jq -e '.components[] | select(.component == "portal" and .access.offboarding == "keycloak_group_removal")' "$reports_dir/access-offboarding.json" >/dev/null
jq -e '.components[] | select(.component == "core" and .slo.availability == "99.0%")' "$reports_dir/slo.json" >/dev/null
jq -e '.components[] | select(.component == "crowdsec" and (.evidence | index("crowdsec.simulated_decision")))' "$reports_dir/security.json" >/dev/null

printf '[contract-reports-test] ok\n' >&2
