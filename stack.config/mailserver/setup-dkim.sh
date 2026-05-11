#!/bin/bash
set -euo pipefail
echo "Generating DKIM keys for Rspamd..."
setup config dkim
if supervisorctl status rspamd | grep -q RUNNING; then
    echo "Restarting Rspamd..."
    supervisorctl restart rspamd
else
    echo "Rspamd is not running yet; DKIM configuration will load on service start."
fi
echo "Rspamd DKIM setup complete!"
