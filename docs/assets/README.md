# Documentation Assets

This directory contains sanitized screenshot-style assets for GitHub-facing
documentation. They intentionally use example domains and synthetic data so the
public repository does not expose private deployment details or user content.

When adding a new screenshot:

- keep hostnames generic, such as `example.test`
- do not include real usernames, tokens, emails, private paths, or production data
- prefer source-controlled SVG for diagrams and static product shots
- use live Playwright screenshots only when they are scrubbed before commit
