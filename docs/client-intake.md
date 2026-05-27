# Client Intake

Use this intake to decide whether a private open-source stack is a good fit and
which package should be scoped.

## Current Tools

- What SaaS tools are you currently paying for?
- Which tools are painful, expensive, risky, or privacy-sensitive?
- Which tools are working well and should probably stay SaaS?

## Users And Groups

- How many users need access?
- Who should be admin?
- What groups/roles are needed?
- Who approves access changes and offboarding?

## Domains And DNS

- What domain should services use?
- Who controls DNS?
- Are there existing services or records that must not be disrupted?

## Required Services

- Which services are required at launch?
- Which services are optional later?
- Which native/mobile clients are required?

## Data Migration

- What data needs migration?
- How large is the data?
- Which data can be archived instead of migrated?
- Who validates migrated data?

## Security Requirements

- Are there regulated data requirements?
- Are MFA, admin separation, or audit expectations required?
- Are there external users, contractors, or guests?

## Backup Requirements

- What must be backed up?
- Where should backups live?
- What restore time is acceptable?
- How much data loss is acceptable after an incident?

## Uptime Expectations

- What outage window is acceptable?
- Are maintenance windows available?
- Is single-host deployment acceptable?
- Is high availability required as a separate scope?

## Admin Ownership

- Who owns domain/DNS decisions?
- Who owns server access?
- Who owns application admin accounts?
- Who signs off on handoff?

## Native/Mobile Clients

- Which mobile or desktop apps are required?
- Do they support SSO?
- Are app passwords or service-specific tokens acceptable?

## Budget And Timeline

- What budget range is realistic?
- What timeline matters?
- Is this planned work or emergency replacement?

## Deal Breakers

- Do you want ongoing support after deployment?
- What would make this project a failure?
- What must remain outside the private stack?

## Fit Score

| Signal | Good | Risk |
| --- | --- | --- |
| Owns domain/DNS | yes | no |
| Can name required apps | yes | vague |
| Has admin owner | yes | no |
| Has backup expectations | yes | no |
| Expects no ongoing care | no | yes |
| Requires compliance | scoped | vague/assumed |
| Needs same-day emergency support | no | yes |

## Notes

Start with the current SaaS/tool list. The first useful output is usually a
short recommendation: what can be replaced, what should stay SaaS, and what a
private stack would look like.
