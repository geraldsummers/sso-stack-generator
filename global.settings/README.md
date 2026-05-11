# global.settings

This repo does not use repo-local `global.settings/stack.config.yaml` for the live build path.
The live build path uses an external site bundle passed by explicit manifest path.

Use:
- `./build.sh --manifest /path/to/site/manifest.json`
- `rsync -av --delete ./dist/ <user@host>:~/webservices/`
- `cd ~/webservices && ./deploy.sh`

Minimal site bundle contents:
- `manifest.json`
- `stack.config.yaml`
- `webservices.sops.json`

Provide a host-local SOPS age identity at deploy time, typically via `$HOME/.config/sops/age/keys.txt`.
