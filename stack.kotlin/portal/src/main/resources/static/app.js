const state = {
  profiles: [],
  dashboard: null,
  selectedProfile: null,
};

const profileStrip = document.getElementById("profile-strip");
const dashboardRoot = document.getElementById("dashboard-root");
const statusPill = document.getElementById("status-pill");

const toneClass = (tone) => `tone-${tone || "neutral"}`;
const cssVar = (name, fallback) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
const audienceTone = (audience) => audience === "employee" ? "attention" : audience === "client" ? "good" : "neutral";

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function tag(text, tone = "neutral") {
  return `<span class="tag ${toneClass(tone)}">${esc(text)}</span>`;
}

async function getJson(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`${url} returned ${response.status}`);
  return response.json();
}

async function loadProfiles() {
  state.profiles = await getJson("/api/profiles");
  state.selectedProfile = state.profiles[0]?.profile;
  renderProfiles();
  if (state.selectedProfile) await loadDashboard(state.selectedProfile);
}

async function loadDashboard(profileId) {
  statusPill.textContent = "Refreshing";
  state.dashboard = await getJson(`/api/dashboard/${encodeURIComponent(profileId)}`);
  state.selectedProfile = profileId;
  statusPill.textContent = `${state.dashboard.status.overall} · ${state.dashboard.sources.length} sources`;
  renderProfiles();
  renderDashboard();
}

function renderProfiles() {
  profileStrip.innerHTML = state.profiles.map((profile) => `
    <button class="profile-button ${profile.profile === state.selectedProfile ? "active" : ""}" data-profile="${esc(profile.profile)}" type="button">
      <strong>${esc(profile.name)}</strong>
      <span>${esc(profile.defaultView)} · ${profile.moduleCount} modules</span>
    </button>
  `).join("");
  profileStrip.querySelectorAll("[data-profile]").forEach((button) => {
    button.addEventListener("click", () => loadDashboard(button.dataset.profile));
  });
}

function renderDashboard() {
  const dashboard = state.dashboard;
  if (!dashboard) {
    dashboardRoot.innerHTML = `<div class="empty">Dashboard unavailable.</div>`;
    return;
  }

  dashboardRoot.innerHTML = `
    <section class="cockpit-stage ${profileClass(dashboard.profile.id)}" style="--accent:${esc(dashboard.visualLanguage.accent)}">
      <aside class="cockpit-rail">
        <article class="panel role-panel">
          <div class="meta-row">
            ${tag(dashboard.profile.defaultView, "attention")}
            ${tag(dashboard.visualLanguage.motif)}
          </div>
          <h2>${esc(dashboard.profile.name)}</h2>
          <p>${esc(dashboard.profile.purpose)}</p>
          <div class="signal-strip">
            ${dashboard.kpis.slice(0, 4).map(renderSignal).join("")}
          </div>
        </article>
      </aside>

      <section class="panel cockpit-work">
        <div class="section-head">
          <div>
            <h2>${esc(workTitle(dashboard.profile.id))}</h2>
            <p>${esc(workSubtitle(dashboard.profile.id))}</p>
          </div>
          ${tag(`${dashboard.actions.filter((action) => action.href).length}/${dashboard.actions.length} linked actions`, "good")}
        </div>
        <div class="cockpit-action-grid">
          ${dashboard.actions.map(renderCockpitAction).join("")}
        </div>
      </section>

      <aside class="cockpit-context">
        ${dashboard.heroVisuals.map(renderHeroVisual).join("")}
        <article class="panel proof-panel compact-proof">
          ${renderProofDial(dashboard)}
        </article>
      </aside>
    </section>

    <section class="section visual-secondary">
      <div class="section-head">
        <div>
          <h2>Integrated Workflow Panels</h2>
          <p>Role-specific work queues with source-tool drill-through.</p>
        </div>
      </div>
      <div class="widget-grid">${dashboard.widgets.map(renderWidget).join("")}</div>
    </section>

    <section class="section split-grid">
      <div>
        <div class="section-head"><h2>Information Views</h2></div>
        <div class="widget-grid">${dashboard.visualizations.map(renderVisualization).join("")}</div>
      </div>
      <div>
        <div class="section-head"><h2>Next Actions</h2></div>
        <div class="action-list">${dashboard.actions.map(renderAction).join("")}</div>
      </div>
    </section>

    <section class="section split-grid">
      <div>
        <div class="section-head"><h2>Evidence</h2></div>
        <div class="evidence-list">${dashboard.evidence.slice(0, 10).map(renderEvidence).join("")}</div>
      </div>
      <div>
        <div class="section-head"><h2>Source Health</h2></div>
        <div class="source-list">${dashboard.sources.slice(0, 10).map(renderSource).join("")}</div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <div>
          <h2>Selected Modules For This Role</h2>
          <p>Audience labels describe product positioning; Keycloak remains the access boundary.</p>
        </div>
      </div>
      ${renderAudienceFilters(dashboard.modules)}
      <div class="module-grid">${dashboard.modules.map(renderModule).join("")}</div>
    </section>
  `;
}

function profileClass(profileId) {
  return `profile-${String(profileId || "default").replace(/[^a-z0-9-]/gi, "-")}`;
}

function workTitle(profileId) {
  const titles = {
    employee: "Employee Work Cockpit",
    client: "Client Action Portal",
    "platform-operator-security": "Operator Control Plane",
  };
  return titles[profileId] || "Work From Here";
}

function workSubtitle(profileId) {
  const subtitles = {
    employee: "Messages, tasks, docs, files, and AI drafts link to the right source tool.",
    client: "Files, approvals, meetings, invoices, and support stay scoped to client-safe surfaces.",
    "platform-operator-security": "Access, health, backups, restore proof, and deploy checks stay visible without exposing secrets.",
  };
  return subtitles[profileId] || "Each card opens the service behind the next useful action.";
}

function renderAudienceFilters(modules) {
  const counts = modules.reduce((acc, module) => {
    const audience = module.audience || "employee";
    acc[audience] = (acc[audience] || 0) + 1;
    return acc;
  }, {});
  const lanes = ["client", "employee", "either"].filter((lane) => counts[lane]);
  if (!lanes.length) return "";
  return `
    <div class="audience-filter-row" aria-label="Audience lanes">
      ${lanes.map((lane) => `
        <span class="audience-chip ${toneClass(audienceTone(lane))}">
          ${esc(lane)} · ${counts[lane]}
        </span>
      `).join("")}
    </div>
  `;
}

function renderSignal(kpi) {
  return `
    <div class="signal">
      <span>${esc(kpi.label)}</span>
      <strong class="${toneClass(kpi.tone)}">${esc(kpi.value)}</strong>
    </div>
  `;
}

function renderProofDial(dashboard) {
  const total = Math.max(1, dashboard.sources.length);
  const ok = dashboard.sources.filter((source) => source.status === "ok").length;
  const percent = Math.round((ok / total) * 100);
  const circumference = 2 * Math.PI * 42;
  const offset = circumference - (percent / 100) * circumference;
  return `
    <div class="proof-dial">
      <svg viewBox="0 0 100 100" role="img" aria-label="Source coverage ${percent}%">
        <circle class="dial-bg" cx="50" cy="50" r="42"></circle>
        <circle class="dial-fg" cx="50" cy="50" r="42" stroke-dasharray="${circumference}" stroke-dashoffset="${offset}"></circle>
      </svg>
      <div>
        <strong>${percent}%</strong>
        <span>${ok}/${total} sources</span>
      </div>
    </div>
    <div class="proof-mini">
      ${tag(`${dashboard.modules.length} modules`, "good")}
      ${tag(`${dashboard.evidence.length} proof items`, "good")}
      ${tag(dashboard.profile.density)}
    </div>
  `;
}

function renderHeroVisual(visual) {
  const renderers = {
    bars: renderBars,
    sparkline: renderSparkline,
    timeline: renderTimeline,
    heatmap: renderHeatmap,
    funnel: renderFunnel,
    ring: renderRing,
    lanes: renderLanes,
  };
  const renderer = renderers[visual.type] || renderBars;
  return `
    <article class="visual-card visual-${esc(visual.type)} ${visualLayoutClass(visual)}" data-visual-kind="${esc(visual.type)}">
      <div class="visual-head">
        <h3>${esc(visual.title)}</h3>
        ${tag(visual.type, visual.tone)}
      </div>
      ${renderer(visual)}
      <small>${esc(visual.summary)}</small>
    </article>
  `;
}

function renderCockpitAction(action, index) {
  const content = `
    <div class="cockpit-action-index">${index === 0 ? "Start" : `Step ${index + 1}`}</div>
    <div>
      <strong>${esc(action.label)}</strong>
      <p>${esc(action.detail)}</p>
    </div>
    <span class="launch-chip">${action.href ? "Open workflow" : "Needs setup"}</span>
  `;
  const className = `cockpit-action ${index === 0 ? "primary-action" : ""} ${toneClass(action.priority === "high" ? "attention" : "neutral")}`;
  return action.href
    ? `<a class="${className}" href="${esc(action.href)}">${content}</a>`
    : `<article class="${className} disabled-action">${content}</article>`;
}

function visualLayoutClass(visual) {
  if (["sparkline", "timeline"].includes(visual.type)) return "visual-card-wide";
  if (["ring", "lanes"].includes(visual.type)) return "visual-card-compact";
  return "visual-card-medium";
}

function renderBars(visual) {
  const max = Math.max(1, ...visual.points.map((point) => point.value));
  return `
    <div class="visual-bars">
      ${visual.points.map((point) => `
        <div class="visual-bar">
          <span>${esc(point.label)}</span>
          <div class="bar-track"><div class="bar-fill" style="width:${Math.max(5, (point.value / max) * 100)}%"></div></div>
          <strong>${esc(point.value)}</strong>
        </div>
      `).join("")}
    </div>
  `;
}

function renderSparkline(visual) {
  const points = visual.points.length ? visual.points : [{ value: 0 }];
  const max = Math.max(...points.map((point) => point.value), 1);
  const min = Math.min(...points.map((point) => point.value), 0);
  const range = Math.max(1, max - min);
  const coords = points.map((point, index) => {
    const x = points.length === 1 ? 50 : (index / (points.length - 1)) * 100;
    const y = 86 - ((point.value - min) / range) * 72;
    return `${x},${y}`;
  }).join(" ");
  return `
    <svg class="sparkline" viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label="${esc(visual.title)} trend">
      <polyline points="${coords}"></polyline>
      ${points.map((point, index) => {
        const x = points.length === 1 ? 50 : (index / (points.length - 1)) * 100;
        const y = 86 - ((point.value - min) / range) * 72;
        return `<circle cx="${x}" cy="${y}" r="2.2"></circle>`;
      }).join("")}
    </svg>
  `;
}

function renderTimeline(visual) {
  return `
    <div class="timeline">
      ${visual.points.map((point, index) => `
        <div class="timeline-node">
          <span>${esc(point.label)}</span>
          <strong>${esc(point.value)}</strong>
          <i style="height:${Math.max(18, Math.min(86, point.value))}%"></i>
        </div>
      `).join("")}
    </div>
  `;
}

function renderHeatmap(visual) {
  const max = Math.max(1, ...visual.points.map((point) => point.value));
  return `
    <div class="heatmap">
      ${visual.points.map((point) => `
        <div class="heat-cell" style="--heat:${Math.max(.18, point.value / max)}">
          <span>${esc(point.label)}</span>
          <strong>${esc(point.value)}</strong>
        </div>
      `).join("")}
    </div>
  `;
}

function renderFunnel(visual) {
  const max = Math.max(1, ...visual.points.map((point) => point.value));
  return `
    <div class="funnel">
      ${visual.points.map((point) => `
        <div class="funnel-row">
          <span>${esc(point.label)}</span>
          <div style="width:${Math.max(18, (point.value / max) * 100)}%">${esc(point.value)}</div>
        </div>
      `).join("")}
    </div>
  `;
}

function renderRing(visual) {
  const value = Number(visual.value || visual.points[0]?.value || 0);
  const circumference = 2 * Math.PI * 42;
  const offset = circumference - (Math.max(0, Math.min(100, value)) / 100) * circumference;
  return `
    <div class="ring-visual">
      <svg viewBox="0 0 100 100" role="img" aria-label="${esc(visual.title)} ${value}${esc(visual.unit)}">
        <circle class="dial-bg" cx="50" cy="50" r="42"></circle>
        <circle class="dial-fg" cx="50" cy="50" r="42" stroke-dasharray="${circumference}" stroke-dashoffset="${offset}"></circle>
      </svg>
      <strong>${esc(value)}${esc(visual.unit)}</strong>
    </div>
  `;
}

function renderLanes(visual) {
  return `
    <div class="lane-grid">
      ${visual.lanes.map((lane) => `
        <div class="lane">
          <span>${esc(lane.label)}</span>
          <strong class="${toneClass(lane.tone)}">${esc(lane.value)}</strong>
        </div>
      `).join("")}
    </div>
  `;
}

function renderKpi(kpi) {
  return `
    <a class="kpi" href="${esc(kpi.href || "#")}" aria-label="${esc(kpi.label)}">
      <span>${esc(kpi.label)}</span>
      <strong class="${toneClass(kpi.tone)}">${esc(kpi.value)}</strong>
      <p>${esc(kpi.detail)}</p>
    </a>
  `;
}

function renderWidget(widget) {
  return `
    <article class="widget-card">
      <div class="widget-topline">
        <div class="meta-row">${tag(widget.type)} ${tag(widget.tone, widget.tone)}</div>
        ${widget.href ? `<a class="tool-link" href="${esc(widget.href)}">Open tool</a>` : ""}
      </div>
      <h3>${esc(widget.title)}</h3>
      <p>${esc(widget.summary)}</p>
      <div class="item-list">${widget.items.slice(0, 5).map(renderItem).join("")}</div>
    </article>
  `;
}

function renderItem(item) {
  const content = `
    <div>
      <strong>${esc(item.label)}</strong>
      <small>${esc(item.detail)}</small>
    </div>
    <span class="${toneClass(item.tone)}">${esc(item.value)}</span>
  `;
  return item.href
    ? `<a class="item" href="${esc(item.href)}">${content}<em>Open</em></a>`
    : `<div class="item">${content}</div>`;
}

function renderVisualization(viz) {
  const max = Math.max(1, ...viz.points.map((point) => point.value));
  return `
    <article class="viz-card">
      <h3>${esc(viz.title)}</h3>
      <p>${esc(viz.summary)}</p>
      <div class="viz-bars">
        ${viz.points.slice(0, 8).map((point) => `
          <div class="bar-row">
            <span>${esc(point.label)}</span>
            <div class="bar-track"><div class="bar-fill" style="width:${Math.max(6, (point.value / max) * 100)}%"></div></div>
            <strong class="${toneClass(point.tone)}">${esc(point.value)}</strong>
          </div>
        `).join("")}
      </div>
    </article>
  `;
}

function renderAction(action) {
  const content = `
    <div>
      <strong>${esc(action.label)}</strong>
      <p>${esc(action.detail)}</p>
    </div>
    ${tag(action.priority, action.priority === "high" ? "risk" : "neutral")}
  `;
  return action.href
    ? `<a class="action-card" href="${esc(action.href)}">${content}</a>`
    : `<article class="action-card">${content}</article>`;
}

function renderEvidence(item) {
  const content = `
    <div>
      <strong>${esc(item.label)}</strong>
      <small>${esc(item.source)} · ${esc(item.detail)}</small>
    </div>
    ${tag(item.status, item.status === "ok" ? "good" : "attention")}
  `;
  return item.href
    ? `<a class="item" href="${esc(item.href)}">${content}</a>`
    : `<div class="item">${content}</div>`;
}

function renderSource(source) {
  return `
    <div class="source-pill">
      <span>${esc(source.label)}</span>
      <strong class="${toneClass(source.status === "ok" ? "good" : "attention")}">${esc(source.status)}</strong>
    </div>
  `;
}

function renderModule(module) {
  return `
    <a class="module-card" href="${esc(module.href)}">
      <div class="module-meta">
        ${tag(module.category)}
        ${tag(module.audience || "employee", audienceTone(module.audience))}
        ${tag(module.auth, "good")}
        ${module.slo.availability ? tag(module.slo.availability, "attention") : ""}
      </div>
      <h3>${esc(module.name)}</h3>
      <p>${esc(module.description)}</p>
      <div class="module-meta">
        ${tag(`${module.evidence.length} checks`)}
        ${tag(`${module.screenshots.length} shots`)}
        ${tag(`${module.backupTargets.length} backups`)}
      </div>
    </a>
  `;
}

loadProfiles().catch((error) => {
  statusPill.textContent = "Portal error";
  dashboardRoot.innerHTML = `<div class="empty">${esc(error.message)}</div>`;
});
