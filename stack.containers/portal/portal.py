#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


DOMAIN = os.environ.get("DOMAIN", "example.test")
CONTRACTS_PATH = Path(os.environ.get("PORTAL_CONTRACTS_PATH", "/contracts/service-contracts.json"))
LOCK_PATH = Path(os.environ.get("PORTAL_COMPONENT_LOCK_PATH", "/contracts/components.lock.json"))
PROFILES_PATH = Path(os.environ.get("PORTAL_PROFILES_PATH", "/contracts/portal-profiles.json"))


def read_json(path: Path, default: dict) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default
    except json.JSONDecodeError as exc:
        return {"error": f"invalid json in {path}: {exc}"}


def selected_components() -> list[str]:
    lock = read_json(LOCK_PATH, {"components": []})
    values = lock.get("components", [])
    if isinstance(values, list):
        return [str(value) for value in values]
    return []


def contracts() -> dict:
    return read_json(CONTRACTS_PATH, {"components": {}})


def profile_contract() -> dict:
    return read_json(PROFILES_PATH, {"profiles": []})


def portal_modules() -> list[dict]:
    selected = set(selected_components())
    catalog = contracts().get("components", {})
    modules: list[dict] = []
    for component in sorted(selected):
        contract = catalog.get(component)
        if not isinstance(contract, dict):
            continue
        portal = contract.get("portal", {})
        if not isinstance(portal, dict) or portal.get("visible") is False:
            continue
        host = portal.get("hrefHost") or contract.get("primaryHost") or component
        href = "/" if host == "apex" else f"https://{host}.{DOMAIN}/"
        if path := portal.get("path"):
            href = href.rstrip("/") + str(path)
        modules.append(
            {
                "component": component,
                "name": contract.get("name", component),
                "description": portal.get("description") or contract.get("description", ""),
                "category": portal.get("category", "Platform"),
                "href": href,
                "auth": contract.get("auth", {}).get("mode", "unknown"),
                "profiles": portal.get("profiles", []),
                "evidence": contract.get("evidence", {}).get("expectations", []),
                "screenshots": contract.get("screenshots", []),
                "slo": contract.get("slo", {}),
            }
        )
    return modules


def profile_widgets() -> list[dict]:
    modules = portal_modules()
    contract_profiles = profile_contract().get("profiles", [])
    widgets = []
    for entry in contract_profiles:
        if not isinstance(entry, dict):
            continue
        profile = str(entry.get("id") or "").strip()
        if not profile:
            continue
        declared_services = {str(value) for value in entry.get("services", []) if isinstance(value, str)}
        matching = [
            module for module in modules
            if profile in module.get("profiles", []) or module.get("component") in declared_services
        ]
        widgets.append(
            {
                "profile": profile,
                "name": entry.get("name", profile),
                "defaultView": entry.get("defaultView", ""),
                "purpose": entry.get("purpose", ""),
                "moduleCount": len(matching),
                "modules": [module["component"] for module in matching[:8]],
            }
        )
    return widgets


INDEX_HTML = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Stack Portal</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #101417;
      --panel: #171d20;
      --panel-strong: #1f282b;
      --text: #eef4f1;
      --muted: #aab7b2;
      --line: #314044;
      --teal: #37d3b7;
      --gold: #e6ba5e;
      --red: #ef7d78;
      --blue: #76a9fa;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background: linear-gradient(180deg, rgba(16, 20, 23, .96), rgba(16, 20, 23, 1)), #101417;
      color: var(--text);
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    header {
      display: grid;
      gap: 1rem;
      grid-template-columns: 1fr auto;
      align-items: end;
      padding: 2rem clamp(1rem, 4vw, 3rem) 1.25rem;
      border-bottom: 1px solid var(--line);
      background: rgba(16, 20, 23, .75);
      position: sticky;
      top: 0;
      backdrop-filter: blur(14px);
      z-index: 2;
    }
    h1 { margin: 0; font-size: clamp(1.8rem, 3vw, 3.2rem); line-height: 1; letter-spacing: 0; }
    .lede { margin: .55rem 0 0; max-width: 58rem; color: var(--muted); font-size: 1rem; }
    .pill {
      border: 1px solid var(--line);
      background: var(--panel);
      border-radius: 999px;
      padding: .5rem .8rem;
      color: var(--muted);
      white-space: nowrap;
    }
    main { padding: 1.25rem clamp(1rem, 4vw, 3rem) 3rem; }
    .profile-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
      gap: .75rem;
      margin-bottom: 1.25rem;
    }
    .profile {
      border: 1px solid var(--line);
      background: var(--panel);
      border-radius: 8px;
      padding: .9rem;
      min-height: 6rem;
    }
    .profile strong { display: block; text-transform: capitalize; }
    .profile span { display: block; color: var(--muted); margin-top: .35rem; }
    .toolbar {
      display: flex;
      gap: .75rem;
      flex-wrap: wrap;
      align-items: center;
      margin-bottom: 1rem;
    }
    .toolbar input {
      min-width: min(28rem, 100%);
      flex: 1 1 18rem;
      color: var(--text);
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: .8rem .9rem;
      font: inherit;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
      gap: .85rem;
    }
    .module {
      display: grid;
      gap: .75rem;
      align-content: start;
      min-height: 13rem;
      border: 1px solid var(--line);
      background: linear-gradient(180deg, var(--panel-strong), var(--panel));
      border-radius: 8px;
      padding: 1rem;
      text-decoration: none;
      color: inherit;
    }
    .module:hover, .module:focus-visible { border-color: var(--teal); outline: none; }
    .module h2 { margin: 0; font-size: 1.08rem; line-height: 1.25; letter-spacing: 0; }
    .module p { margin: 0; color: var(--muted); line-height: 1.45; }
    .meta { display: flex; flex-wrap: wrap; gap: .35rem; margin-top: auto; }
    .tag {
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: .25rem .5rem;
      color: var(--muted);
      font-size: .82rem;
    }
    .tag.auth { color: var(--teal); }
    .tag.slo { color: var(--gold); }
    .empty, .error {
      border: 1px solid var(--line);
      background: var(--panel);
      border-radius: 8px;
      padding: 1rem;
      color: var(--muted);
    }
    .error { border-color: rgba(239, 125, 120, .65); color: #ffd8d6; }
  </style>
</head>
<body>
  <header>
    <div>
      <h1>Stack Portal</h1>
      <p class="lede">Generated from the selected service contracts for this stack.</p>
    </div>
    <div class="pill" id="status">Loading modules</div>
  </header>
  <main>
    <section class="profile-strip" id="profiles" aria-label="Profile dashboards"></section>
    <div class="toolbar">
      <input id="filter" type="search" placeholder="Filter modules" autocomplete="off">
    </div>
    <section class="grid" id="modules" aria-label="Portal modules"></section>
  </main>
  <script>
    const modulesEl = document.getElementById('modules');
    const profilesEl = document.getElementById('profiles');
    const statusEl = document.getElementById('status');
    const filterEl = document.getElementById('filter');
    let modules = [];

    function tag(text, kind = '') {
      return `<span class="tag ${kind}">${text}</span>`;
    }

    function renderProfiles(profiles) {
      profilesEl.innerHTML = profiles.map((profile) => `
        <article class="profile">
          <strong>${profile.name || profile.profile}</strong>
          <span>${profile.defaultView || 'dashboard'} · ${profile.moduleCount} contract-backed modules</span>
        </article>
      `).join('');
    }

    function renderModules() {
      const query = filterEl.value.trim().toLowerCase();
      const visible = modules.filter((module) => {
        return !query || [module.name, module.description, module.category, module.component].join(' ').toLowerCase().includes(query);
      });
      if (visible.length === 0) {
        modulesEl.innerHTML = '<div class="empty">No portal modules match the current filter.</div>';
        return;
      }
      modulesEl.innerHTML = visible.map((module) => `
        <a class="module" href="${module.href}" data-component="${module.component}">
          <div>${tag(module.category)} ${tag(module.auth, 'auth')}</div>
          <h2>${module.name}</h2>
          <p>${module.description}</p>
          <div class="meta">
            ${module.evidence.length ? tag(`${module.evidence.length} evidence checks`) : tag('evidence pending')}
            ${module.screenshots.length ? tag(`${module.screenshots.length} screenshots`) : ''}
            ${module.slo.availability ? tag(module.slo.availability, 'slo') : ''}
          </div>
        </a>
      `).join('');
    }

    async function loadPortal() {
      try {
        const [modulesResponse, profilesResponse] = await Promise.all([
          fetch('/api/modules'),
          fetch('/api/profiles')
        ]);
        if (!modulesResponse.ok || !profilesResponse.ok) throw new Error('portal api unavailable');
        modules = await modulesResponse.json();
        const profiles = await profilesResponse.json();
        statusEl.textContent = `${modules.length} active modules`;
        renderProfiles(profiles);
        renderModules();
      } catch (error) {
        statusEl.textContent = 'Portal error';
        modulesEl.innerHTML = '<div class="error">Portal module contracts could not be loaded.</div>';
      }
    }

    filterEl.addEventListener('input', renderModules);
    loadPortal();
  </script>
</body>
</html>
"""


class PortalHandler(BaseHTTPRequestHandler):
    server_version = "WebservicesPortal/1.0"

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self.respond_json({"status": "ok"})
        elif path == "/api/modules":
            self.respond_json(portal_modules())
        elif path == "/api/profiles":
            self.respond_json(profile_widgets())
        elif path in {"/", "/index.html"}:
            self.respond_text(INDEX_HTML, "text/html; charset=utf-8")
        else:
            self.respond_json({"error": "not found"}, status=404)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[portal] {self.address_string()} {fmt % args}")

    def respond_json(self, payload: object, status: int = 200) -> None:
        body = json.dumps(payload, sort_keys=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def respond_text(self, payload: str, content_type: str, status: int = 200) -> None:
        body = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    bind = os.environ.get("PORTAL_BIND", "0.0.0.0")
    port = int(os.environ.get("PORTAL_PORT", "8080"))
    ThreadingHTTPServer((bind, port), PortalHandler).serve_forever()


if __name__ == "__main__":
    main()
