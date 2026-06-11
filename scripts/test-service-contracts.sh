#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[service-contract-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
catalog="$ROOT_DIR/stack.config/components.json"
contracts="$ROOT_DIR/stack.config/service-contracts.json"
caddy_hosts="$ROOT_DIR/stack.containers/test-runner/fixtures/caddy-hosts.txt"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '[service-contract-test] missing required command: %s\n' "$1" >&2
    exit 1
  }
}

require_cmd jq

jq -e '.schemaVersion == 1 and (.components | type == "object")' "$catalog" >/dev/null
jq -e '.contractVersion == 1 and (.components | type == "object")' "$contracts" >/dev/null

catalog_keys="$(mktemp)"
contract_keys="$(mktemp)"
trap 'rm -f "$catalog_keys" "$contract_keys"' EXIT

jq -r '.components | keys[]' "$catalog" > "$catalog_keys"
jq -r '.components | keys[]' "$contracts" > "$contract_keys"
if ! diff -u "$catalog_keys" "$contract_keys" >&2; then
  printf '[service-contract-test] component catalog and service contracts must contain the same component keys\n' >&2
  exit 1
fi

jq -e '
  .components
  | all(.[] ;
      (.name | type == "string" and length > 0) and
      (.description | type == "string" and length > 0) and
      (.moduleType | type == "string" and length > 0) and
      (.primaryHost | type == "string" and length > 0) and
      (.routes | type == "array") and
      (.auth.mode | type == "string" and length > 0) and
      (.rbac.groups | type == "array") and
      (.state.mode | type == "string" and length > 0) and
      (.backup.policy | type == "string" and length > 0) and
      (.backup.targets | type == "array") and
      (.restore.drills | type == "array" and length > 0) and
      (.observability.health | type == "boolean") and
      (.observability.logs | type == "boolean") and
      (.evidence.expectations | type == "array" and length > 0) and
      (.screenshots | type == "array") and
      (.portal.visible | type == "boolean") and
      (.portal.description | type == "string") and
      (.slo | type == "object") and
      (.access.offboarding | type == "string" and length > 0) and
      (.artifacts.json | type == "array" and length > 0)
    )
' "$contracts" >/dev/null || {
  printf '[service-contract-test] every contract must declare route/auth/rbac/state/backup/restore/observability/evidence/screenshot/portal/slo/access/artifact metadata\n' >&2
  exit 1
}

jq -e '
  .components
  | all(.[] | select(.portal.visible == true and .moduleType != "bundle"); (.screenshots | length) > 0)
' "$contracts" >/dev/null || {
  printf '[service-contract-test] visible portal modules must declare screenshot evidence targets\n' >&2
  exit 1
}

jq -e '.components.portal.composeFiles == ["portal.yml"]' "$catalog" >/dev/null
jq -e '.components.homepage.composeFiles == [] and (.components.homepage.dependencies | index("portal"))' "$catalog" >/dev/null
jq -e '.components.apps.dependencies | index("portal") and (index("homepage") | not)' "$catalog" >/dev/null
jq -e '.components.onlyoffice.dependencies | index("seafile")' "$catalog" >/dev/null
jq -e '.components.onlyoffice.capabilities | index("seafile-editor-backend")' "$contracts" >/dev/null
jq -e '.components.observability.dependencies | index("crowdsec")' "$catalog" >/dev/null
jq -e '.components.crowdsec.composeFiles == ["crowdsec.yml"]' "$catalog" >/dev/null
jq -e '.components.crowdsec.evidence.expectations | index("crowdsec.simulated_decision")' "$contracts" >/dev/null

grep -Fq './configs/crowdsec/acquis.yaml:/etc/crowdsec/acquis.yaml:ro' "$ROOT_DIR/stack.compose/crowdsec.yml"
grep -Fq './configs/crowdsec/simulate-alert.sh:/usr/local/bin/webservices-crowdsec-simulate-alert:ro' "$ROOT_DIR/stack.compose/crowdsec.yml"
grep -Fq 'cscli decisions add' "$ROOT_DIR/stack.config/crowdsec/simulate-alert.sh"
grep -Fq 'webservices-simulated-alert' "$ROOT_DIR/stack.config/crowdsec/simulate-alert.sh"

if rg -n 'gethomepage|homepage:3000|ghcr\.io/gethomepage' "$ROOT_DIR/stack.compose" "$ROOT_DIR/stack.config/caddy" >/dev/null; then
  printf '[service-contract-test] gethomepage runtime references must not remain in compose or Caddy\n' >&2
  exit 1
fi

if ! grep -Fxq 'portal' "$caddy_hosts"; then
  printf '[service-contract-test] Caddy host inventory must include portal\n' >&2
  exit 1
fi

jq -r '.components[].routes[]?.host' "$contracts" | sort -u | while IFS= read -r host; do
  [ -n "$host" ] || continue
  if [ "$host" = "apex" ]; then
    continue
  fi
  if ! grep -Fxq "$host" "$caddy_hosts"; then
    printf '[service-contract-test] route host %s missing from Caddy host inventory\n' "$host" >&2
    exit 1
  fi
done

printf '[service-contract-test] ok\n' >&2
