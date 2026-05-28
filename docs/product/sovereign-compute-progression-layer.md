# Sovereign Compute Progression Layer

The progression layer is a serious-game apprenticeship layer for the real
stack. It is not a gamified dashboard, and it does not reward engagement for
its own sake.

The stack itself is the curriculum. A user starts by safely opening a private
workspace, then learns to see, secure, recover, observe, change, automate, and
operate the stack from `stackctl` and eventually zsh.

## Product Goal

The product should move a user through this path:

```text
I use apps someone else set up.
-> I understand one service.
-> I control access.
-> I know where data lives.
-> I can restore it.
-> I can read failures.
-> I can change safely.
-> I can operate from shell.
-> I can federate trust.
-> I can hand this off.
-> I can add services.
-> I can teach someone else.
```

The end state is not that power users get a shell. People become power users
during the process, and the shell becomes their cockpit.

## Design Constitution

These rules are product constraints:

1. Reward real capability, never mere engagement.
2. Rewards require verified evidence, not manual checkbox completion.
3. Use one homogeneous ownership path with many control planes.
4. Early dashboards reduce intimidation.
5. Later dashboards increase command.
6. Expert detail is always available, but not foregrounded too early.
7. Earlier dashboards are never discarded.
8. No streaks, leaderboards, fake urgency, guilt loops, random rewards, or
   engagement bait.
9. Red and urgent UI are reserved for real operational risk.
10. No raw secrets may appear in UI, logs, screenshots, tests, CLI output, or
    evidence.
11. The dashboard and CLI read the same state.
12. Federation is late-game, not first-run.
13. AI is not a runtime dependency for progression.
14. The shell is revealed gradually, never forced as the first step.
15. The system should help users need less scaffolding over time.

The interface is scaffolding. The shell is fluency.

## Ownership Path

The path is homogeneous:

```text
Use
-> See
-> Secure
-> Map State
-> Prove Recovery
-> Observe Failure
-> Change Safely
-> Protect Secrets
-> Build Knowledge
-> Operate from CLI
-> Wield zsh
-> Federate Trust
-> Handoff
-> Extend Stack
-> Teach
```

Users interact through progressively denser control planes:

```text
Guided UI
-> custom dashboards
-> power dashboards
-> stackctl
-> zsh
-> declarative config
-> generator/source
-> raw infra escape hatch
```

The path does not split into beginner and expert tracks. Density, velocity, and
control plane change.

## State Model

The UI is not the source of truth. Progression reads and writes four persistent
state files under `runtime/progression/`:

- `desired-state.json`: what source/config says should exist.
- `actual-state.json`: what scanners can see.
- `verified-state.json`: what probes and tests have proven.
- `progress-state.json`: what stack-level and user-level milestones are
  complete.

Rewards depend on verified state. Stack-level progress and user-level progress
are distinct. A stack may have proven restore evidence before a new user has
learned what that evidence means.

## MVP: Own BookStack

BookStack is the first owned service because it touches a browser app, docs,
route, identity, database, upload storage, backup, restore, logs, and runbooks.

The first vertical slice includes these dashboards:

- Your Private Workspace
- BookStack Ownership
- Who Can Enter
- The Doors
- Where Your Data Lives
- Recovery Gym
- Shell Cockpit preview

The first slice includes these capabilities:

- show one calm next action
- show BookStack route, access, state, backup, health, and commands
- verify BookStack access evidence
- map BookStack volume and database state
- record or surface restore drill evidence
- reveal equivalent `stackctl` commands

The first rewards are:

- Workspace Entered
- First Service Seen
- First Door Secured
- State Mapped
- Restore Proven
- Command Revealed

## `stackctl`

The CLI mirrors dashboard truth and never prints raw secrets. MVP commands:

```bash
stackctl progress
stackctl progress next
stackctl progress show <task>
stackctl services show bookstack
stackctl routes list
stackctl access audit
stackctl verify access.bookstack
stackctl persistence show bookstack
stackctl restore drill bookstack --temporary
stackctl evidence show restore.bookstack
stackctl logs bookstack
```

All commands should support machine-readable output with `--json` where useful.
Verification failures exit nonzero.

## Implementation Source

The source-owned implementation lives in:

- `stack.config/progression/` for schemas, tasks, dashboards, and reward rules
- `stack.kotlin/progression/` for the shared engine, API server, and CLI
- `stack.compose/progression.yml` for the protected dashboard service
- `scripts/stackctl.sh` for the deployed host command wrapper

The public root implements Keycloak/OIDC as the identity adapter. Downstream
spins may add Authelia or site-specific service adapters without changing the
public ownership path.

## Complete System

The full system is complete when it supports:

- one ownership path
- the custom dashboard ladder
- evidence-backed rewards
- real stack scanners
- separate stack and user progress
- BookStack-first service ownership
- Caddy route literacy
- Keycloak/OIDC access literacy
- persistence and restore literacy
- Grafana/Loki/Alloy observability literacy
- SOPS secret literacy
- `stackctl` CLI mirror
- zsh cockpit graduation
- maintenance rituals
- federation and handoff readiness
- stack builder final tier
- GUI, CLI, screenshot, accessibility, and anti-overwhelm tests

Every step must prove a real capability. Every dashboard should teach a real
system. Every reward should map to operational ownership.
