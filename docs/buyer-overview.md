# Buyer Overview

## What This Solves

Small teams often rely on scattered SaaS tools for identity, documents, files,
passwords, monitoring, backups, and internal knowledge. That can create
fragmented accounts, unclear offboarding, weak ownership, private data spread
across vendors, and no coherent operating surface.

This project demonstrates a private, client-owned open-source infrastructure
pattern for small groups that need capability without becoming dependent on one
vendor.

## What You Get

You get a private platform your team owns, with shared login, useful apps,
monitoring, backups, and documentation.

Typical capabilities include:

- single sign-on and group-based access
- service portal/catalog
- private docs and files
- password management
- monitoring and logs
- backups and restore planning
- optional project, chat, Git, and data/AI workspaces
- deployment verification

## What Makes It Different

This is not a loose collection of containers. It is a generated, documented,
SSO-backed, verified platform bundle.

The stack separates local build from host deploy. Local builds produce a
secret-free bundle. The target host renders runtime secrets, starts supervised
services, and runs verification before the platform is trusted.

## Proof That It Works

The proof is inspectable in the repo:

- [Build System](build-system.md)
- [Security And Auth](security-and-auth.md)
- [Systemd Graph](systemd-graph.md)
- [Testing](testing.md)
- [Operations](operations.md)
- [Recovery](recovery.md)
- [Threat Model](threat-model.md)
- [Service Maturity](service-maturity.md)

## Good Fit

This is a good fit for privacy-conscious small teams, technical founders,
small businesses reducing SaaS dependency, makerspaces, co-ops, community
groups, and open-source-friendly operators.

It works best when the client is willing to own domain/DNS/admin decisions and
either operate the stack or retain support.

## Poor Fit

This is a poor fit for one-click desktop app expectations, no ongoing care
expectations, urgent same-day managed service needs, unscoped compliance
requirements, or teams that want all operational details hidden.

## Typical Engagement

A typical engagement starts with a tool-list audit:

1. Review current SaaS tools, users, data, and operational pain points.
2. Decide what should be replaced and what should stay SaaS.
3. Choose a package and host profile.
4. Prepare domain, DNS, secrets, and backup destination.
5. Deploy, verify, document, and hand off the stack.

## What I Need From You

- current SaaS/tool list
- expected users and admin owner
- domain/DNS access plan
- required services
- migration needs
- backup expectations
- uptime expectations
- support expectations
- deal breakers

## Next Step

Send me your current SaaS/tool list. I will tell you what can be replaced, what
should stay SaaS, and what a private stack would look like.
