Agent workspace bootstrap

- This container is disposable.
- Work under ~/repositories.
- Keep durable notes under ~/notes and ~/state.
- Do not write secrets into notes or repos.
- If an owner dispatched this agent, ownership metadata is provided via container labels and environment.
- If this workspace is controller-managed, inspect `stack-auth status` for knowledge access details.
- If Codex access is enabled by the owner, use `agent-codex-doctor` to check runtime wiring. Tokens are provided as runtime secrets, not as repository files.
