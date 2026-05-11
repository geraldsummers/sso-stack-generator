#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8888}"
ROOT_DIR="${JUPYTER_ROOT_DIR:-$HOME}"
TOKEN="${JUPYTER_TOKEN:-}"
BASE_URL="${NOTEBOOK_BASE_URL:-/}"

mkdir -p "$HOME/.jupyter/lab/user-settings/@jupyterlab/apputils-extension"
cat > "$HOME/.jupyter/lab/user-settings/@jupyterlab/apputils-extension/themes.jupyterlab-settings" <<'JSON'
{
  "theme": "JupyterLab Dark",
  "theme-scrollbars": true
}
JSON
chmod 0600 "$HOME/.jupyter/lab/user-settings/@jupyterlab/apputils-extension/themes.jupyterlab-settings"

if [[ "$BASE_URL" != /* ]]; then
  BASE_URL="/$BASE_URL"
fi
if [[ "$BASE_URL" != */ ]]; then
  BASE_URL="$BASE_URL/"
fi

exec python3 -m jupyterlab \
  --ip=0.0.0.0 \
  --port "$PORT" \
  --no-browser \
  --ServerApp.allow_remote_access=True \
  --ServerApp.base_url="$BASE_URL" \
  --ServerApp.token="$TOKEN" \
  --ServerApp.password='' \
  --ServerApp.root_dir="$ROOT_DIR"
