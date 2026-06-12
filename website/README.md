# Website

This directory contains the buyer-facing Cloudflare Pages site for the public
proof repo and Upwork proof package.

The current positioning is Platform Zero: a modular private stack approach for
teams evaluating role-aware client, employee, operator, and AI surfaces. The
website should stay aligned with the Upwork product page, video, and screenshot
set while keeping claims tied to deployment scope.

It is a dependency-free CSR site:

- `index.html` is the shell
- `app.js` renders the page client-side
- `styles.css` owns layout and visual treatment
- `_headers` and `_redirects` are Cloudflare Pages static config files

## Local Preview

From the repository root:

```bash
python3 -m http.server 4173 --directory website
```

Then open `http://127.0.0.1:4173/`.

## Cloudflare Pages

Use these settings:

- Framework preset: None
- Build command: empty
- Build output directory: `website`
- Root directory: repository root

The deployed site intentionally links to absolute GitHub URLs for source and
docs proof. Do not use `../docs/...` links here; those work in a checkout but
not when only `website/` is published.

## Update Checklist

- proof links point to the public repo
- package claims match `docs/packages.md`
- client/employee/operator service boundaries match the generated portal
- optional modules are described as scope-dependent, not guaranteed defaults
- no compliance overclaims
- CTA points to `docs/client-intake.md`
- local preview renders with JavaScript enabled
