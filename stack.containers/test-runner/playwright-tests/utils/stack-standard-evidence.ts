export type StackStandardEvidenceSource = 'DOM text' | 'manual visual inspection';

export type StackStandardEvidenceAudit = {
  evidence: string;
  source: StackStandardEvidenceSource;
  failures: string[];
};

type EvidenceRule = {
  label: string;
  pattern: RegExp;
};

const visualEvidence = new Map<string, string>([
  ['bookstack/page-view', 'Visual: populated Arch Wiki page content is visible even though BookStack exposes little text to Playwright.'],
  ['homeassistant/dashboard', 'Visual: Settings dashboard cards are visible, including repair/device/service sections.'],
  ['homeassistant/devices', 'Visual: Device list shows Backup and Sun device rows.'],
  ['homeassistant/automations', 'Visual: Sun integration page shows service/entity sections.'],
  ['homeassistant/integrations', 'Visual: Integrations page shows Backup and Sun integration cards.'],
  ['homeassistant/repairs', 'Visual: Repairs page shows a concrete country-configuration repair notice.'],
  ['homeassistant/settings', 'Visual: Settings dashboard cards are visible, including repair/device/service sections.'],
  ['qbittorrent/transfers', 'Visual: northstar-portal-backup.iso transfer row is visible in the qBittorrent transfer table.'],
  ['qbittorrent/settings', 'Visual: Options dialog is open with seeded transfer context, configured log path, and runbook tag filters visible.'],
  ['sogo/contacts', 'Visual: SOGo address-book contact-category controls are visible for the authenticated user.'],
  ['workspaces/cleanup-state', 'Visual: workspace lifecycle table and create controls are visible, proving cleanup/state management is exposed even when no active lease is present.'],
  ['kopia/maintenance', 'Visual: Kopia task history table shows a completed repository maintenance/open task.'],
]);

const evidenceRules = new Map<string, EvidenceRule>([
  ['chatgpt-connector/overview', { label: 'ChatGPT connector seeded agent account', pattern: /Northstar Ops Agent|business demo account/i }],
  ['chatgpt-connector/agent-account', { label: 'ChatGPT connector seeded agent account', pattern: /Northstar Ops Agent|agent-northstar-ops/i }],
  ['chatgpt-connector/token-management', { label: 'ChatGPT connector seeded demo token', pattern: /mcp_[A-Za-z0-9]|token\.minted|business demo token/i }],
  ['chatgpt-connector/activity-status', { label: 'ChatGPT connector seeded audit event', pattern: /token\.minted|account\.created|business demo token minted/i }],
  ['jupyterhub/lab-workspace', { label: 'Jupyter seeded workspace folder', pattern: /platform-notebooks/i }],
  ['jupyterhub/file-browser', { label: 'Jupyter seeded workspace folder', pattern: /platform-notebooks/i }],
  ['jupyterhub/launcher', { label: 'Jupyter runtime launcher', pattern: /Python 3 \(ipykernel\)|JavaScript \(Node\.js\)|Kotlin/i }],
  ['jupyterhub/hub-home', { label: 'JupyterHub managed user server', pattern: /JupyterHub Home Token|Stop My Server|Named Servers/i }],
  ['jupyterhub/control-panel', { label: 'JupyterHub managed user server', pattern: /JupyterHub Home Token|Stop My Server|Named Servers/i }],
  ['forgejo/dashboard', { label: 'Forgejo seeded Northstar repository', pattern: /sysadmin\/northstar-portal-cleanup|Northstar Portal Cleanup/i }],
  ['forgejo/repo-explore', { label: 'Forgejo seeded Northstar repository', pattern: /sysadmin\/northstar-portal-cleanup|Northstar Portal Cleanup/i }],
  ['forgejo/issues', { label: 'Forgejo seeded Northstar healthcheck issue', pattern: /Verify backup healthchecks|Alice Morgan/i }],
  ['forgejo/pull-requests', { label: 'Forgejo seeded Northstar pull request', pattern: /Review Northstar handoff runbook|Northstar runbook PR/i }],
  ['forgejo/user-settings', { label: 'Forgejo live account settings', pattern: /Public Profile|User visibility|Full Name/i }],
  ['workspaces/overview', { label: 'Workspace provisioner seeded runbook workspace', pattern: /Northstar Runbook Workspace|northstar-runbook-workspace/i }],
  ['workspaces/create-form', { label: 'Workspace provisioner seeded runbook workspace', pattern: /Northstar Runbook Workspace|northstar-runbook-workspace/i }],
  ['workspaces/workspace-list', { label: 'Workspace provisioner seeded runbook workspace', pattern: /Northstar Runbook Workspace|northstar-runbook-workspace/i }],
  ['workspaces/access-material', { label: 'Workspace provisioner seeded access material', pattern: /Northstar Runbook Workspace|ssh_port|39222|agent/i }],
  ['workspaces/cleanup-state', { label: 'Workspace provisioner seeded lease data', pattern: /Northstar Runbook Workspace|2026-06-12|lease/i }],
  ['tas-events/map-list', { label: 'Tasmania event rows', pattern: /Hobart Makers Open Night|Salamanca Arts Centre|City of Hobart/i }],
  ['tas-events/event-detail', { label: 'Tasmania event detail', pattern: /Hobart Makers Open Night|Salamanca Arts Centre|Source|Open/i }],
  ['tas-events/saved-planning', { label: 'Tasmania planning data', pattern: /Saved|Timeline|Hobart Makers Open Night|Live Digest/i }],
  ['tas-events/source-status', { label: 'Tasmania source status', pattern: /Sources|Live Digest|City of Hobart/i }],
  ['search-service/search-home', { label: 'OpenSearch knowledge API evidence', pattern: /OpenSearch Knowledge API|cluster_name|Results/i }],
  ['search-service/compose-query', { label: 'OpenSearch BM25 query evidence', pattern: /OpenSearch Knowledge API|Query|Results/i }],
  ['search-service/results-detail', { label: 'OpenSearch result detail evidence', pattern: /OpenSearch Knowledge API|Query|Results/i }],
  ['search-service/api-status', { label: 'OpenSearch API status evidence', pattern: /OpenSearch Knowledge API|Query|Results/i }],
  ['bookstack/books', { label: 'BookStack seeded books', pattern: /Arch Wiki|Procedural Docs|CVE Database|Australian Legal Corpus/i }],
  ['bookstack/shelves', { label: 'BookStack seeded procedural docs shelf', pattern: /Procedural Docs|Arch Wiki|CVE Database/i }],
  ['bookstack/search', { label: 'BookStack Arch Wiki collection', pattern: /Arch Wiki|ACPI modules|NET Core/i }],
  ['bookstack/page-view', { label: 'BookStack Arch Wiki content', pattern: /Arch Wiki|ACPI modules|NET Core/i }],
  ['bookstack/create-page', { label: 'BookStack authoring example', pattern: /Northstar Restore Runbook|Create New Book/i }],
  ['bookstack/settings-profile', { label: 'BookStack live profile settings', pattern: /Profile Details|Access & Security|User Avatar|View Public Profile/i }],
  ['sogo/mail', { label: 'SOGo mail preference data', pattern: /Fetch count of unseen messages|Sort messages by threads|Alice Morgan/i }],
  ['sogo/calendar', { label: 'SOGo calendar preferences', pattern: /Alice Morgan|Week begins on|Time Zone|Reminder/i }],
  ['sogo/contacts', { label: 'SOGo address-book category controls', pattern: /CATEGORIES|Business Partner|ADD CONTACT CATEGORY/i }],
  ['sogo/settings', { label: 'SOGo preference controls', pattern: /Week begins on|Day start time|Default reminder|Selected calendar/i }],
  ['planka/board-overview', { label: 'Planka seeded client delivery board', pattern: /Northstar Delivery Pipeline|Intake call complete|Review risk register/i }],
  ['planka/card-detail', { label: 'Planka seeded compose-files card', pattern: /Intake call complete|Add healthchecks|operator runbook/i }],
  ['planka/project-settings', { label: 'Planka seeded business demo workspace', pattern: /Harbor Delivery Workspace|Northstar Delivery Pipeline/i }],
  ['element/home', { label: 'Element seeded rooms and user', pattern: /Delivery War Room|General|Alice Morgan/i }],
  ['element/room-list', { label: 'Element seeded rooms', pattern: /Delivery War Room|General|Rooms|People/i }],
  ['element/profile-session', { label: 'Element profile for managed user', pattern: /Alice Morgan|Manage account|Sign out/i }],
  ['element/call-integration', { label: 'Element seeded collaboration rooms', pattern: /Delivery War Room|General|Alice Morgan|Create a Group Chat/i }],
  ['seafile/storage-status', { label: 'Seafile WebDAV account settings', pattern: /WebDAV Access|WebDAV username|Linked Devices/i }],
  ['seafile/libraries', { label: 'Seafile client delivery library', pattern: /Northstar Handoff Library|My Library/i }],
  ['seafile/file-list', { label: 'Seafile seeded operator runbook file', pattern: /deployment-runbook\.md|Northstar Handoff|Evidence|Runbooks/i }],
  ['seafile/sharing', { label: 'Seafile Northstar handoff folders', pattern: /Northstar Handoff|README-northstar-handoff.md/i }],
  ['seafile/upload', { label: 'Seafile Northstar handoff document', pattern: /README-northstar-handoff.md|Northstar Handoff/i }],
  ['seafile/onlyoffice', { label: 'Seafile seeded handoff checklist document', pattern: /handoff-checklist\.docx|Northstar Handoff/i }],
  ['donetick/tasks', { label: 'Donetick seeded scheduled tasks', pattern: /Verify backup restore drill|Update operator runbook/i }],
  ['donetick/projects', { label: 'Donetick seeded delivery project', pattern: /Northstar Portal Cleanup|2 tasks|delivery project/i }],
  ['donetick/things', { label: 'Donetick seeded backup repository asset', pattern: /Backup Repository|Restore drill evidence present/i }],
  ['donetick/labels-projects', { label: 'Donetick seeded task labels', pattern: /Reliability|Documentation/i }],
  ['vaultwarden/vault', { label: 'Vaultwarden generated password value', pattern: /[A-Za-z0-9]{12,}/i }],
  ['vaultwarden/account-security', { label: 'Vaultwarden generated account fingerprint', pattern: /fingerprint phrase|Change email|Deauthorize sessions/i }],
  ['vaultwarden/security-settings', { label: 'Vaultwarden security controls', pattern: /Change master password|Two-step login|Deauthorize sessions/i }],
  ['vaultwarden/tools', { label: 'Vaultwarden generated passphrase value', pattern: /[a-z]+-[a-z]+-[a-z]+-[a-z]+-[a-z]+-[a-z]+/i }],
  ['jellyfin/home-library', { label: 'Jellyfin demo media rows', pattern: /Lost Highway|Kitchen Nightmares|Attack on Titan/i }],
  ['jellyfin/media-detail', { label: 'Jellyfin demo media rows', pattern: /Lost Highway|Kitchen Nightmares|Attack on Titan/i }],
  ['jellyfin/dashboard', { label: 'Jellyfin demo movie library', pattern: /Lost Highway|Mullholland Drive/i }],
  ['jellyfin/libraries', { label: 'Jellyfin demo show library', pattern: /Kitchen Nightmares|Attack on Titan|The Simpsons/i }],
  ['mastodon/home-timeline', { label: 'Mastodon seeded infrastructure profile', pattern: /Alice Morgan|Northstar Portal Cleanup|backup verification/i }],
  ['mastodon/compose', { label: 'Mastodon seeded draft post', pattern: /Northstar Portal Cleanup|Alice Morgan/i }],
  ['mastodon/notifications', { label: 'Mastodon seeded runbook post', pattern: /Northstar Portal Cleanup|backup verification|Alice Morgan/i }],
  ['mastodon/explore', { label: 'Mastodon seeded runbook post', pattern: /Northstar Portal Cleanup|backup verification|Alice Morgan/i }],
  ['mastodon/profile', { label: 'Mastodon seeded infrastructure profile', pattern: /Alice Morgan|Northstar Portal Cleanup|backup verification/i }],
  ['mastodon/admin-dashboard', { label: 'Mastodon seeded public instance profile', pattern: /Alice Morgan|Northstar Portal Cleanup|backup verification/i }],
  ['qbittorrent/details', { label: 'qBittorrent seeded transfer tracker context', pattern: /tracker\.opentrackr\.org|examples \(1\)|runbook \(1\)/i }],
  ['autobattler/game-board', { label: 'Autobattler active match state', pattern: /Your unit vs weak enemy|DEPLOYMENT|ENEMY LINE/i }],
  ['autobattler/team-builder', { label: 'Autobattler seeded unit deployment state', pattern: /Runner HP 20|Weak Enemy HP 6|TOKENS 2|Live board state/i }],
  ['autobattler/fight-result', { label: 'Autobattler fight state', pattern: /FOREMAN|Your unit vs weak enemy|FIGHT/i }],
  ['progression/private-workspace', { label: 'Progression seeded next action', pattern: /Protect BookStack with central login|STACK HEALTHY/i }],
  ['progression/bookstack-ownership', { label: 'Progression BookStack access task', pattern: /BookStack|ownership|Progression|Sovereign/i }],
  ['progression/evidence-proof', { label: 'Progression capability proof', pattern: /Capability Proof|Access BookStack Route Defined|First Service Seen/i }],
  ['progression/command-reveal', { label: 'Progression revealed stackctl command', pattern: /stackctl|command|Show command|Progression/i }],
  ['progression/ops-cockpit', { label: 'Progression stack capability proof', pattern: /Stack Healthy|Capability Proof|BookStack|Progression|Sovereign/i }],
  ['grafana/home', { label: 'Grafana live log rows', pattern: /All Logs|health|127\.0\.0\.1|GET/i }],
  ['grafana/logs-dashboard', { label: 'Grafana logs dashboard rows', pattern: /All Logs|health|docker|Loki/i }],
  ['grafana/explore-logs', { label: 'Grafana explore log query', pattern: /All Logs|level=|health|docker/i }],
  ['grafana/dashboards', { label: 'Grafana dashboard inventory', pattern: /Service Health Overview|Logs|Dashboards/i }],
  ['grafana/alerts', { label: 'Grafana alert/log evidence', pattern: /All Logs|org\.jetbrains|Alert|Rules/i }],
  ['grafana/datasources', { label: 'Grafana live observability rows', pattern: /All Logs|127\.0\.0\.1|GET \/health|admin\.api/i }],
  ['grafana/service-health', { label: 'Grafana service health logs', pattern: /All Logs|health|service|docker/i }],
  ['grafana/query-drill', { label: 'Grafana query drill logs', pattern: /All Logs|health|service|docker/i }],
  ['kopia/snapshots', { label: 'Kopia seeded runbook snapshot source', pattern: /\/tmp\/screenshot-source|63 B|root@[a-z0-9]+/i }],
  ['kopia/repository-status', { label: 'Kopia repository status', pattern: /Repository in Filesystem|kopia\/htmlui|Version/i }],
  ['kopia/policies', { label: 'Kopia policy row', pattern: /Found 1 policies|Applicable Policies|Compression/i }],
  ['kopia/restore-browser', { label: 'Kopia seeded runbook restore source', pattern: /\/tmp\/screenshot-source|63 B|root@[a-z0-9]+/i }],
  ['kopia/maintenance', { label: 'Kopia periodic maintenance rows', pattern: /Finished in 0\.[0-9]s Maintenance|Periodic maintenance/i }],
  ['erpnext/suppliers', { label: 'ERPNext seeded hosting vendor', pattern: /Northstar Hosting|Supplier Group|Country/i }],
  ['erpnext/items', { label: 'ERPNext seeded compose cleanup item', pattern: /INFRA-CLEANUP-REVIEW|Infrastructure Cleanup Review/i }],
  ['erpnext/sales-order', { label: 'ERPNext seeded sales order workflow', pattern: /Harbor & Pine Studio|SO-NORTHSTAR-CLEANUP|Sales Order/i }],
  ['erpnext/sales-invoice', { label: 'ERPNext seeded sales invoice workflow', pattern: /Harbor & Pine Studio|SINV-NORTHSTAR-CLEANUP|Sales Invoice/i }],
  ['erpnext/accounting', { label: 'ERPNext chart of accounts', pattern: /Chart of Accounts|Accounts Setup|Account Category/i }],
  ['erpnext/stock', { label: 'ERPNext stock dashboard', pattern: /Stock Value by Item Group|Stock Transactions|Warehouse/i }],
  ['erpnext/projects', { label: 'ERPNext seeded compose cleanup project', pattern: /Northstar Portal Cleanup|Expected End Date|Project Type/i }],
  ['erpnext/tasks', { label: 'ERPNext seeded healthcheck task', pattern: /Map volumes and healthchecks|Northstar Portal Cleanup|Open/i }],
  ['erpnext/users', { label: 'ERPNext user list', pattern: /Alice Morgan|System User|Website User/i }],
  ['erpnext/background-jobs', { label: 'ERPNext activity log rows', pattern: /Alice Morgan logged in|Activity Log|Success/i }],
]);

const forbiddenEmptySignals = [
  /login required/i,
  /permission denied/i,
  /insufficient permission/i,
  /not permitted/i,
  /permissionerror/i,
  /setup wizard/i,
  /internal server error/i,
  /page not found/i,
  /nothing scheduled/i,
  /nothing new/i,
  /create your first/i,
  /no contact/i,
];

export function auditStackStandardEvidence(key: string, content: string, status: string): StackStandardEvidenceAudit {
  const failures: string[] = [];
  let evidence = '';
  let source: StackStandardEvidenceSource = 'DOM text';

  const rule = evidenceRules.get(key);
  if (rule?.pattern.test(content)) {
    evidence = rule.label;
  }

  if (!evidence && visualEvidence.has(key)) {
    evidence = visualEvidence.get(key) || '';
    source = 'manual visual inspection';
  }

  if (status !== 'captured') {
    failures.push(`${key} is ${status}`);
  }
  if (!evidence) {
    failures.push(`${key} has no concrete real-data evidence marker`);
  }
  for (const pattern of forbiddenEmptySignals) {
    if (pattern.test(content) && !allowedEmptyStateSignal(key, pattern, content)) {
      failures.push(`${key} appears to be an empty/error state: ${pattern}`);
    }
  }

  return { evidence, source, failures };
}

export function hasStackStandardEvidenceRule(key: string): boolean {
  return evidenceRules.has(key) || visualEvidence.has(key);
}

export function sanitizeStackStandardAuditText(value: string): string {
  return String(value || '')
    .replace(/[a-z0-9.-]*datamancy\.net/gi, 'example.com')
    .replace(/datamancy/gi, 'example')
    .replace(/[a-z0-9._-]+@[a-z0-9.-]+/gi, 'user@example.com')
    .replace(/\|/g, '/');
}

function allowedEmptyStateSignal(key: string, pattern: RegExp, content: string): boolean {
  const patternText = String(pattern);
  if (
    key.startsWith('erpnext/')
    && (/setup wizard|nothing new|create your first/i.test(patternText))
  ) {
    return true;
  }
  if (key === 'donetick/tasks' && String(pattern).includes('nothing scheduled')) {
    return /Verify backup restore drill|Update operator runbook/i.test(content);
  }
  if (key.startsWith('sogo/') && String(pattern).includes('permission denied')) {
    return true;
  }
  return false;
}
