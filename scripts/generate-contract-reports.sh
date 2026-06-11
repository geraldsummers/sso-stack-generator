#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
CATALOG="$ROOT_DIR/stack.config/components.json"
CONTRACTS="$ROOT_DIR/stack.config/service-contracts.json"
LOCK_FILE=""
OUTPUT_DIR=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  ./scripts/generate-contract-reports.sh --lock <components.lock.json> --output-dir <dir> [--catalog <components.json>] [--contracts <service-contracts.json>]

Generates JSON-first reports for selected components: contracts, module
selection, evidence coverage, backup topology, access/offboarding, SLOs, and
security posture.
EOF_USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --catalog)
      CATALOG="$2"
      shift
      ;;
    --contracts)
      CONTRACTS="$2"
      shift
      ;;
    --lock)
      LOCK_FILE="$2"
      shift
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf '[contract-reports] ERROR: unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

[ -f "$CATALOG" ] || { printf '[contract-reports] ERROR: missing catalog: %s\n' "$CATALOG" >&2; exit 1; }
[ -f "$CONTRACTS" ] || { printf '[contract-reports] ERROR: missing contracts: %s\n' "$CONTRACTS" >&2; exit 1; }
[ -f "$LOCK_FILE" ] || { printf '[contract-reports] ERROR: missing lock file: %s\n' "$LOCK_FILE" >&2; exit 1; }
[ -n "$OUTPUT_DIR" ] || { printf '[contract-reports] ERROR: missing --output-dir\n' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { printf '[contract-reports] ERROR: missing required command: jq\n' >&2; exit 1; }

mkdir -p "$OUTPUT_DIR"
generated_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

selected_filter='
  def selected_contracts:
    ($lock[0].components // []) as $selected
    | $selected
    | map({component: ., contract: $contracts[0].components[.]})
    | map(select(.contract != null));
'

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile catalog "$CATALOG" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    contractVersion: \$contracts[0].contractVersion,
    components: (selected_contracts | map({key: .component, value: .contract}) | from_entries)
  }" > "$OUTPUT_DIR/contracts.json"

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile catalog "$CATALOG" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    selectedComponents: \$lock[0].components,
    defaultComponents: \$catalog[0].defaultComponents,
    modules: (
      selected_contracts
      | map({
          component,
          name: .contract.name,
          moduleType: .contract.moduleType,
          primaryHost: .contract.primaryHost,
          portal: .contract.portal
        })
    )
  }" > "$OUTPUT_DIR/module-selection.json"

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    components: (
      selected_contracts
      | map({
          component,
          evidence: .contract.evidence,
          screenshots: .contract.screenshots,
          artifactJson: .contract.artifacts.json
        })
    )
  }" > "$OUTPUT_DIR/evidence-coverage.json"

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    components: (
      selected_contracts
      | map({
          component,
          state: .contract.state,
          backup: .contract.backup,
          restore: .contract.restore
        })
    )
  }" > "$OUTPUT_DIR/backup-topology.json"

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    components: (
      selected_contracts
      | map({
          component,
          auth: .contract.auth,
          rbac: .contract.rbac,
          access: .contract.access
        })
    )
  }" > "$OUTPUT_DIR/access-offboarding.json"

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    components: (
      selected_contracts
      | map({
          component,
          slo: .contract.slo,
          observability: .contract.observability
        })
    )
  }" > "$OUTPUT_DIR/slo.json"

jq -n \
  --arg generatedAt "$generated_at" \
  --slurpfile contracts "$CONTRACTS" \
  --slurpfile lock "$LOCK_FILE" \
  "$selected_filter
  {
    generatedAt: \$generatedAt,
    components: (
      selected_contracts
      | map(select(.contract.moduleType != \"bundle\"))
      | map({
          component,
          routes: .contract.routes,
          auth: .contract.auth,
          rbac: .contract.rbac,
          securityOwner: .contract.access.owner,
          evidence: (.contract.evidence.expectations | map(select(startswith(\"crowdsec\") or startswith(\"auth\") or contains(\"denied\") or contains(\"boundary\"))))
        })
    )
  }" > "$OUTPUT_DIR/security.json"

printf '[contract-reports] wrote reports to %s\n' "$OUTPUT_DIR" >&2
