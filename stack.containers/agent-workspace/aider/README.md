# Aider runtime notes

Use `agent-aider-doctor` to validate the local Aider runtime wiring.
Use `agent-aider-run` to execute non-interactive tasks inside the owned workspace.

Primary environment:

- `AIDER_MODEL`
- `AIDER_OLLAMA_API_BASE`
- `AIDER_EDIT_FORMAT` (optional)
- `AIDER_TIMEOUT` (optional)

`AIDER_OLLAMA_API_BASE` should point at the Ollama API root exposed by the stack, for example `https://models.<domain>/ollama/cpu`.
