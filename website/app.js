(function () {
  const repoBase = "https://github.com/geraldsummers/sso-stack-generator";
  const docsBase = `${repoBase}/blob/dev/docs`;
  const treeBase = `${repoBase}/tree/dev`;

  const links = {
    repo: repoBase,
    docs: `${treeBase}/docs`,
    websiteSource: `${treeBase}/website`,
    packages: `${docsBase}/packages.md`,
    intake: `${docsBase}/client-intake.md`,
    buyerOverview: `${docsBase}/buyer-overview.md`,
  };

  const proofCards = [
    {
      title: "Generated Stack",
      copy: "The stack is generated from explicit inputs, so selected modules stay reviewable and changeable.",
      image: "assets/build-deploy-verify.svg",
      href: `${docsBase}/build-system.md`,
    },
    {
      title: "Access Boundary",
      copy: "Keycloak/OIDC separates client surfaces, employee tools, and operator controls according to deployment policy.",
      image: "assets/trust-boundary.svg",
      href: `${docsBase}/security-and-auth.md`,
    },
    {
      title: "Supervised Runtime",
      copy: "Services are deployed through a defined host contract with lifecycle and verification hooks.",
      image: "assets/systemd-orchestration.svg",
      href: `${docsBase}/systemd-graph.md`,
    },
    {
      title: "Handoff Proof",
      copy: "Delivery scope includes the agreed verification, docs, recovery notes, and next steps.",
      image: "assets/verification-suite.svg",
      href: `${docsBase}/testing.md`,
    },
  ];

  const proofLinks = [
    ["Service Standard", "service-standard.md"],
    ["Service Catalog", "services.md"],
    ["Operations", "operations.md"],
    ["Recovery", "recovery.md"],
    ["Restore Drill", "restore-drill.md"],
    ["Threat Model", "threat-model.md"],
    ["Update And Rollback", "update-and-rollback.md"],
    ["Service Maturity", "service-maturity.md"],
    ["Evaluation Guide", "evaluation-guide.md"],
    ["Proof Checklist", "proof-checklist.md"],
  ];

  const packages = [
    {
      name: "Private Core",
      fit: "For deployments that need owned login, docs, files, backups, and a clear portal.",
      items: [
        "Portal, Caddy, Keycloak, and selected service routing",
        "BookStack knowledge base and Seafile files as selected modules",
        "Vaultwarden or another scoped secrets/password workflow",
        "Backup and basic observability options",
        "Verification, docs, and handoff notes by scope",
      ],
    },
    {
      name: "Team Platform",
      fit: "For deployments that benefit from scoped client and employee surfaces.",
      items: [
        "Private Core plus selected collaboration modules",
        "Huly as an employee work cockpit option",
        "Planka or Donetick for scoped client-visible work",
        "SOGo, Element, Forgejo, and ERPNext where they fit",
        "Documented client/employee/operator service lanes",
      ],
    },
    {
      name: "AI And Automation Lab",
      fit: "For deployments that need agent-friendly automation or private technical workspaces.",
      items: [
        "Team Platform plus selected technical modules",
        "JupyterHub and disposable workspaces for technical workflows",
        "Qdrant/OpenSearch-backed knowledge options",
        "Scoped AI connector bridge",
        "Agent-ready containers and automation paths by design",
      ],
    },
  ];

  const operationModes = [
    {
      name: "Handoff / Self-Service",
      fit: "For clients who want a deployed, documented stack under their own operation.",
      items: [
        "You own the server, domain, accounts, and admin decisions",
        "You get verification results, docs, and next maintenance steps",
        "Planned support or updates are scoped separately",
      ],
    },
    {
      name: "Managed Operations",
      fit: "For clients who want a private stack but prefer scoped ongoing operational help.",
      items: [
        "I stay involved for planned updates, monitoring review, and troubleshooting",
        "Coverage, response times, access, and billing are agreed up front",
        "Emergency or 24/7 support is only included when explicitly contracted",
      ],
    },
  ];

  const serviceSurfaces = [
    {
      lane: "Client surface",
      copy: "Tools customers, members, or external collaborators use when enabled for a deployment.",
      services: ["Portal", "BookStack", "Planka", "Seafile", "SOGo", "Element", "Donetick"],
    },
    {
      lane: "Employee surface",
      copy: "Internal work and operating context for the people running delivery, where the deployment needs it.",
      services: ["Huly", "ERPNext", "Forgejo", "JupyterHub", "Workspaces", "Progression"],
    },
    {
      lane: "Operator surface",
      copy: "Control surfaces for keeping selected services visible, recoverable, and auditable.",
      services: ["Grafana", "Prometheus", "Alertmanager", "Kopia", "Keycloak", "Caddy"],
    },
  ];

  const audience = [
    "Small teams deciding which tools belong under their control",
    "Agencies evaluating client-facing delivery portals",
    "Open-source-friendly businesses",
    "Technical founders evaluating agent-ready infrastructure",
    "Communities, co-ops, and private organizations",
    "Clients willing to define ownership, access, and support expectations",
  ];

  const boundaryQuestions = [
    "ownership boundary",
    "client surface",
    "employee work",
    "operator controls",
    "data location",
    "backup proof",
    "handoff path",
    "automation scope",
  ];

  const buyerProof = [
    {
      label: "Inspect the generator",
      text: "The public repo shows the generator, module contracts, docs, tests, and website source.",
    },
    {
      label: "Check the boundaries",
      text: "Client, employee, and operator surfaces become explicit instead of being treated as one shared app pile.",
    },
    {
      label: "Migrate in stages",
      text: "The migration path identifies what to replace now, what to keep, and what to consider later.",
    },
  ];

  function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    Object.entries(attrs).forEach(([key, value]) => {
      if (value === false || value === null || value === undefined) {
        return;
      }
      if (key === "className") {
        node.className = value;
        return;
      }
      if (key === "text") {
        node.textContent = value;
        return;
      }
      node.setAttribute(key, value === true ? "" : value);
    });
    children.forEach((child) => {
      node.append(child instanceof Node ? child : document.createTextNode(child));
    });
    return node;
  }

  function externalLink(href, label, className) {
    return el("a", {
      className,
      href,
      target: "_blank",
      rel: "noopener noreferrer",
      text: label,
    });
  }

  function list(items, className) {
    return el(
      "ul",
      { className },
      items.map((item) => el("li", { text: item })),
    );
  }

  function sectionHeading(eyebrow, title, copy) {
    return el("div", { className: "section-heading" }, [
      el("p", { className: "eyebrow", text: eyebrow }),
      el("h2", { text: title }),
      copy ? el("p", { text: copy }) : "",
    ]);
  }

  function renderHeader() {
    return el("header", { className: "site-header" }, [
      el("nav", { className: "nav", "aria-label": "Primary" }, [
        el("a", { className: "brand", href: "#top", text: "Platform Zero" }),
        el("div", { className: "nav-links" }, [
          el("a", { href: "#surfaces", text: "Surfaces" }),
          el("a", { href: "#buyer-proof", text: "Proof" }),
          el("a", { href: "#operation-modes", text: "Ops Modes" }),
          el("a", { href: "#packages", text: "Packages" }),
          el("a", { href: "#repo-proof", text: "Repo" }),
          el("a", { href: "#fit", text: "Who It's For" }),
          el("a", { href: "#next", text: "Message" }),
        ]),
      ]),
      el("section", { className: "hero", id: "top" }, [
        el("div", { className: "hero-copy" }, [
          el("p", { className: "eyebrow", text: "Upwork buyers: verify before you message" }),
          el("h1", { text: "Platform Zero: the private stack for your team." }),
          el("p", {
            className: "lede",
            text:
              "A modular self-hosted stack for bringing client, employee, operator, and AI surfaces under one owned system.",
          }),
          el("p", {
            className: "mission",
            text: "Start with discovery. Add only the modules that fit the deployment.",
          }),
          el("div", { className: "actions", "aria-label": "Calls to action" }, [
            externalLink(links.intake, "Review the checklist", "button primary"),
            externalLink(links.repo, "Inspect the repo", "button"),
            externalLink(links.packages, "Compare modules", "button"),
          ]),
        ]),
        el("img", {
          className: "hero-image",
          src: "assets/platform-home.svg",
          alt: "Sanitized generated platform homepage screenshot",
        }),
      ]),
    ]);
  }

  function renderProblem() {
    return el("section", { className: "band" }, [
      el("div", { className: "section-grid" }, [
        el("div", {}, [
          el("p", { className: "eyebrow", text: "The boundary problem" }),
          el("h2", { text: "The hard part is not hosting apps. It is drawing the operating boundary." }),
        ]),
        el("p", {
          text:
            "A useful private stack starts by deciding which services belong under your control, which remain external, who sees each surface, where data lives, and how recovery and handoff are proven.",
        }),
      ]),
      list(boundaryQuestions, "pill-list"),
    ]);
  }

  function renderSurfaces() {
    return el("section", { className: "band", id: "surfaces" }, [
      sectionHeading(
        "Role-aware surfaces",
        "Client and employee surfaces are separated by deployment policy.",
        "Keycloak enforces who enters each surface. The Portal presents different tools by audience. Employees manage client surfaces while clients remain outside internal tools.",
      ),
      el(
        "div",
        { className: "surface-grid" },
        serviceSurfaces.map((surface) =>
          el("article", { className: "surface-card" }, [
            el("h3", { text: surface.lane }),
            el("p", { text: surface.copy }),
            list(surface.services, "surface-list"),
          ]),
        ),
      ),
    ]);
  }

  function renderBuyerProof() {
    return el("section", { className: "band compact", id: "buyer-proof" }, [
      sectionHeading(
        "Buyer proof",
        "Product pages make claims. Repos show the delivery model.",
        "Use this page as the public proof layer next to the Upwork product listing: inspect the source, review the module boundaries, and decide whether this approach fits your migration.",
      ),
      el(
        "div",
        { className: "proof-points" },
        buyerProof.map((item) =>
          el("article", { className: "proof-point" }, [
            el("h3", { text: item.label }),
            el("p", { text: item.text }),
          ]),
        ),
      ),
    ]);
  }

  function renderOffer() {
    return el("section", { className: "band quiet" }, [
      el("div", { className: "section-grid" }, [
        el("div", {}, [
          el("p", { className: "eyebrow", text: "The offer" }),
          el("h2", { text: "A modular private stack for cases where ownership matters." }),
        ]),
        el("div", { className: "stacked-copy" }, [
          el("p", {
            text:
              "The delivery starts with your current tools, users, data, client access, employee workflows, and automation goals. From there, we identify what belongs in the private stack, what should stay SaaS, and which modules are the smallest useful fit.",
          }),
          el("p", {
            text:
              "When a private stack is the right move, there are two engagement modes: a repo-backed handoff for your team to operate, or managed operations where I stay responsible for agreed ongoing work.",
          }),
        ]),
      ]),
    ]);
  }

  function renderOperationModes() {
    return el("section", { className: "band", id: "operation-modes" }, [
      sectionHeading(
        "Operation modes",
        "Decide who runs the stack after launch.",
        "The same underlying stack supports self-service handoff or managed operations. The difference is ongoing responsibility, response expectations, and support scope.",
      ),
      el(
        "div",
        { className: "mode-grid" },
        operationModes.map((mode) =>
          el("article", { className: "mode-card" }, [
            el("h3", { text: mode.name }),
            el("p", { className: "fit", text: mode.fit }),
            list(mode.items),
          ]),
        ),
      ),
    ]);
  }

  function renderRepoProof() {
    const proofCardNodes = proofCards.map((card) => {
      const link = externalLink(card.href, "", "proof-card");
      link.append(
        el("span", { className: "proof-card-inner" }, [
          el("img", { src: card.image, alt: "" }),
          el("strong", { text: card.title }),
          el("span", { text: card.copy }),
        ]),
      );
      return link;
    });

    return el("section", { className: "band proof", id: "repo-proof" }, [
      sectionHeading(
        "Repo proof",
        "The proof site points back to inspectable source.",
        "This Cloudflare Pages site is intentionally simple. It publishes the buyer proof layer while linking technical claims back to public repository documents.",
      ),
      el("div", { className: "repo-panel" }, [
        el("div", {}, [
          el("span", { className: "status-dot", text: "Public repository" }),
          el("h3", { text: "geraldsummers/sso-stack-generator" }),
          el("p", {
            text:
              "The repository contains generator source, service module contracts, deployment notes, verification notes, and this website's source.",
          }),
        ]),
        el("div", { className: "repo-actions" }, [
          externalLink(links.repo, "Repository", "button primary"),
          externalLink(links.docs, "Docs folder", "button"),
          externalLink(links.websiteSource, "Site source", "button"),
        ]),
      ]),
      el("div", { className: "proof-grid" }, proofCardNodes),
      el(
        "div",
        { className: "proof-links", "aria-label": "More proof links" },
        proofLinks.map(([label, file]) => externalLink(`${docsBase}/${file}`, label)),
      ),
    ]);
  }

  function renderPackages() {
    return el("section", { className: "band", id: "packages" }, [
      sectionHeading(
        "Packages",
        "Choose the smallest useful stack.",
        "Packages are starting points, not fixed promises. Final scope depends on the actual deployment.",
      ),
      el(
        "div",
        { className: "cards" },
        packages.map((item) =>
          el("article", { className: "card" }, [
            el("h3", { text: item.name }),
            el("p", { className: "fit", text: item.fit }),
            list(item.items),
          ]),
        ),
      ),
    ]);
  }

  function renderFit() {
    return el("section", { className: "band", id: "fit" }, [
      el("div", { className: "section-grid" }, [
        el("div", {}, [
          el("p", { className: "eyebrow", text: "Works best for" }),
          el("h2", { text: "Teams that want private tools with clear ownership expectations." }),
        ]),
        el("div", {}, [
          el("p", {
            className: "section-note",
            text:
              "The strongest projects have a clear owner, a specific reason to reduce or reorganize SaaS dependency, and an explicit choice about client access, employee workflows, and ongoing operations.",
          }),
          list(audience, "check-list"),
        ]),
      ]),
    ]);
  }

  function renderNextStep() {
    return el("section", { className: "cta", id: "next" }, [
      el("p", { className: "eyebrow", text: "Next step" }),
      el("h2", { text: "Bring the current tool list." }),
      el("p", {
        text:
          "Send the current SaaS list, user groups, client-facing needs, employee workflows, domain situation, migration goals, and support expectations. I will map what to replace, what should stay SaaS, and what a private stack actually needs.",
      }),
      el("div", { className: "actions centered" }, [
        externalLink(links.intake, "Open migration checklist", "button primary"),
        externalLink(links.buyerOverview, "Read buyer overview", "button"),
      ]),
    ]);
  }

  function renderApp() {
    const root = document.querySelector("[data-app-root]");
    root.replaceChildren(
      renderHeader(),
      el("main", {}, [
        renderProblem(),
        renderSurfaces(),
        renderBuyerProof(),
        renderOffer(),
        renderOperationModes(),
        renderRepoProof(),
        renderPackages(),
        renderFit(),
        renderNextStep(),
      ]),
    );
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", renderApp);
  } else {
    renderApp();
  }
})();
