# Agent Documentation Corpus

This directory seeds the pipeline `agent_docs` source with public, generic
operator guidance for agent-facing tools in the SSO stack.

The ingestion runner indexes Markdown files from this directory and publishes
them into the shared search pipeline. Site-specific or private agent guidance
should be supplied by a private module or site configuration overlay, not by the
public generator.

## Runtime Contract

- Source id: `agent_docs`
- Container path: `/configs/agent-docs`
- Mount source: `runtime/configs/agent-docs`
- Audience: `agent`

The directory must exist in rendered runtime configs so the ingestion runner can
start and expose its management API endpoints.
