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
      title: "Generated Deployment",
      copy: "Selected modules are generated from explicit inputs, so launch choices stay reviewable and changeable.",
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
      fit: "Owned login, docs, files, backups, and a clear portal.",
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
      fit: "Client and employee work surfaces under one access model.",
      items: [
        "Private Core plus selected collaboration modules",
        "Huly as an employee work cockpit option",
        "Client-facing kanban/checklist modules where useful",
        "SOGo, Element, Forgejo, and ERPNext where they fit",
        "Documented client/employee/operator service lanes",
      ],
    },
    {
      name: "AI And Automation Lab",
      fit: "Agent-friendly automation and private technical workspaces.",
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
      fit: "For teams that want deployed, documented infrastructure under their own operation.",
      items: [
        "The client owns the server, domain, accounts, and admin decisions",
        "Delivery includes verification results, docs, and next maintenance steps",
        "Planned support or updates are scoped separately",
      ],
    },
    {
      name: "Managed Operations",
      fit: "For teams that want scoped ongoing operational help.",
      items: [
        "Planned updates, monitoring review, and troubleshooting stay in scope by agreement",
        "Coverage, response times, access, and billing are agreed up front",
        "Emergency or 24/7 support is only included when explicitly contracted",
      ],
    },
  ];

  const serviceSurfaces = [
    {
      lane: "Client surface",
      copy: "Tools customers, members, or external collaborators use when enabled for a deployment.",
      services: ["Portal", "BookStack", "Seafile", "SOGo", "Element", "Donetick", "OnlyOffice"],
    },
    {
      lane: "Employee surface",
      copy: "Internal work and operating context for the people running delivery.",
      services: ["Huly", "ERPNext", "Forgejo", "JupyterHub", "Workspaces", "Progression"],
    },
    {
      lane: "Operator surface",
      copy: "Control surfaces for visibility, recovery, access review, and audit evidence.",
      services: ["Grafana", "Prometheus", "Alertmanager", "Kopia", "Keycloak", "Caddy"],
    },
  ];

  const audience = [
    "Small teams that need private business software without enterprise-suite weight",
    "Agencies evaluating client-facing delivery portals",
    "Open-source-friendly businesses",
    "Technical founders evaluating agent-ready infrastructure",
    "Communities, co-ops, and private organizations",
    "Clients ready to define access, data, migration, and support expectations",
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
              "A modular self-hosted business platform for client portals, employee work, operations, and AI-ready technical workflows.",
          }),
          el("p", {
            className: "mission",
            text: "Launch fresh, or replace existing tools in stages.",
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
          el("p", { className: "eyebrow", text: "The operating boundary" }),
          el("h2", { text: "Business software becomes useful when access, work, data, and recovery are clear." }),
        ]),
        el("p", {
          text:
            "Platform Zero starts by defining the services, roles, data locations, migration path, recovery expectations, and launch responsibility. Cleanup and scattered SaaS replacement are handled as part of that boundary work.",
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
        "Keycloak enforces access. The Portal presents the right surface for each audience. Employees manage client work without exposing internal tools.",
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
        "This public proof layer links the Upwork listing to source, module boundaries, docs, and verification evidence.",
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
          el("h2", { text: "A portable businessware base with selected open-source services wired together." }),
        ]),
        el("div", { className: "stacked-copy" }, [
          el("p", {
            text:
              "Delivery starts with tools, users, data, client access, employee workflows, and automation goals. From there, the selected services become a coherent launch plan instead of unrelated applications.",
          }),
          el("p", {
            text:
              "Two engagement modes are available: repo-backed handoff for the client team to operate, or managed operations with agreed ongoing responsibility.",
          }),
        ]),
      ]),
    ]);
  }

  function renderOperationModes() {
    return el("section", { className: "band", id: "operation-modes" }, [
      sectionHeading(
        "Operation modes",
        "Decide who runs the system after launch.",
        "The same delivery model supports self-service handoff or managed operations. The difference is responsibility, response expectations, and support scope.",
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
        "Technical claims link back to public repository documents, module contracts, and generated evidence.",
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
        "Choose the smallest useful launch shape.",
        "Packages are starting points. Final scope follows the selected services, migration path, and operating mode.",
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
          el("h2", { text: "Teams that need integrated business tools without heavy enterprise platforms." }),
        ]),
        el("div", {}, [
          el("p", {
            className: "section-note",
            text:
              "The strongest projects have a clear owner, defined user groups, a specific migration or launch goal, and an explicit choice about ongoing operations.",
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
          "Send the current tool list, user groups, client-facing needs, employee workflows, domain situation, migration goals, and support expectations. The response maps what to launch first, what to replace later, and what should stay outside the deployment.",
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
