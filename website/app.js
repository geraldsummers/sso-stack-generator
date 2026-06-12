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
      copy: "The stack is generated from explicit inputs, so selected modules can be reviewed and changed deliberately.",
      image: "assets/build-deploy-verify.svg",
      href: `${docsBase}/build-system.md`,
    },
    {
      title: "Access Boundary",
      copy: "Keycloak/OIDC can separate client surfaces, employee tools, and operator controls where that boundary is needed.",
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
      copy: "Delivery can include verification, docs, recovery notes, and next steps depending on scope.",
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
        "Portal, Caddy, Keycloak, and service routing where selected",
        "BookStack knowledge base and Seafile files when useful",
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
        "Planka or Donetick for client-visible work when appropriate",
        "SOGo, Element, Forgejo, and ERPNext where they fit",
        "Documented client/employee/operator service lanes",
      ],
    },
    {
      name: "AI And Automation Lab",
      fit: "For deployments that need agent-friendly automation or private technical workspaces.",
      items: [
        "Team Platform plus selected technical modules",
        "JupyterHub and disposable workspaces when needed",
        "Qdrant/OpenSearch-backed knowledge options",
        "AI connector bridge where scoped",
        "Agent-ready containers and automation paths by design",
      ],
    },
  ];

  const operationModes = [
    {
      name: "Handoff / Self-Service",
      fit: "For clients who want a deployed, documented stack they can operate.",
      items: [
        "You own the server, domain, accounts, and admin decisions",
        "You get verification results, docs, and next maintenance steps",
        "Planned support or updates can be scoped separately",
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
      copy: "Tools customers, members, or external collaborators may use when enabled for a deployment.",
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
    "Small teams reviewing whether scattered SaaS accounts should be consolidated",
    "Agencies that may need client-facing delivery portals",
    "Open-source-friendly businesses",
    "Technical founders evaluating agent-ready infrastructure",
    "Communities, co-ops, and private organizations",
    "Clients willing to define ownership, access, and support expectations",
  ];

  const painPoints = [
    "scattered SaaS accounts",
    "clients seeing internal tools",
    "employees hunting across apps",
    "unclear access boundaries",
    "unproven backups",
    "weak client handoff",
    "vendor lock-in",
    "no AI automation surface",
  ];

  const buyerProof = [
    {
      label: "Inspect the generator",
      text: "The public repo shows the generator, module contracts, docs, tests, and website source.",
    },
    {
      label: "Check the boundaries",
      text: "Client, employee, and operator surfaces can be made explicit instead of being treated as one shared app pile.",
    },
    {
      label: "Migrate in stages",
      text: "The migration path can identify what to replace now, what to keep, and what to consider later.",
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
              "A modular self-hosted stack that can combine client, employee, operator, and AI surfaces under one owned system.",
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
          el("p", { className: "eyebrow", text: "The problem" }),
          el("h2", { text: "Many teams accumulate tools one subscription at a time." }),
        ]),
        el("p", {
          text:
            "Some teams benefit from bringing selected client-facing tools, employee work, operations, and AI automation into a private stack with clearer boundaries. Some tools should stay SaaS.",
        }),
      ]),
      list(painPoints, "pill-list"),
    ]);
  }

  function renderSurfaces() {
    return el("section", { className: "band", id: "surfaces" }, [
      sectionHeading(
        "Role-aware surfaces",
        "Client and employee surfaces can be separated when the deployment needs it.",
        "Keycloak can enforce who enters each surface. The Portal can present different tools by audience. Employees may manage client surfaces while clients remain outside internal tools.",
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
        "A product page can make claims. A repo lets you check the delivery model.",
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
              "The delivery starts with your current tools, users, data, client access, employee workflows, and automation goals. From there, we identify what may be worth replacing, what should stay SaaS, and which modules are the smallest useful fit.",
          }),
          el("p", {
            text:
              "When a private stack is the right move, there are two likely engagement modes: a repo-backed handoff your team can operate, or managed operations where I stay responsible for agreed ongoing work.",
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
        "The same underlying stack can be delivered as a self-service handoff or retained as managed operations. The difference is ongoing responsibility, response expectations, and support scope.",
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
          "Send the current SaaS list, user groups, client-facing needs, employee workflows, domain situation, migration goals, and support expectations. I will map what might be replaced, what should stay SaaS, and what a private stack would actually need.",
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
