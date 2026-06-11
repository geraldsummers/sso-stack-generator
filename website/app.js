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
      title: "Build System",
      copy: "Local build, explicit manifest input, secret-free deployable output.",
      image: "assets/build-deploy-verify.svg",
      href: `${docsBase}/build-system.md`,
    },
    {
      title: "Security And Auth",
      copy: "Shared SSO, RBAC boundaries, and deployment-time secret rendering.",
      image: "assets/trust-boundary.svg",
      href: `${docsBase}/security-and-auth.md`,
    },
    {
      title: "Systemd Graph",
      copy: "Per-service compose shards supervised by user-level systemd units.",
      image: "assets/systemd-orchestration.svg",
      href: `${docsBase}/systemd-graph.md`,
    },
    {
      title: "Testing And Verification",
      copy: "Contract tests, readiness checks, and deploy verification are part of the handoff.",
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
      name: "Secure Core",
      fit: "For owners, private operators, tiny teams, households, and small orgs.",
      items: [
        "Caddy and Keycloak",
        "Service homepage/catalog",
        "Vaultwarden and BookStack",
        "Basic monitoring",
        "Backups and verification",
      ],
    },
    {
      name: "Ops Platform",
      fit: "For small teams replacing scattered SaaS tools.",
      items: [
        "Everything in Secure Core",
        "Files and project boards",
        "Mail, calendar, and contacts",
        "Chat and Git hosting",
        "Operational docs and deeper observability",
      ],
    },
    {
      name: "AI Sovereign Lab",
      fit: "For technical teams, data-heavy operators, and agent-assisted workflows.",
      items: [
        "Everything in Ops Platform",
        "JupyterHub",
        "Disposable workspaces",
        "OpenSearch and Qdrant",
        "Connector and ingestion workflows where applicable",
      ],
    },
  ];

  const fitLists = {
    good: [
      "Privacy-conscious small teams",
      "Small businesses reducing SaaS costs",
      "Open-source-friendly operators",
      "Technical founders",
      "Makerspaces, co-ops, and community groups",
      "Clients willing to own domain, DNS, and admin decisions",
    ],
    poor: [
      "One-click desktop app expectations",
      "No ongoing care expectations",
      "Urgent same-day managed service expectations",
      "Regulated compliance without process or legal review",
      "Teams that want all operational detail hidden",
    ],
  };

  const painPoints = [
    "Too many subscriptions",
    "Fragmented accounts",
    "Weak offboarding",
    "Unclear backups",
    "Unclear ownership",
    "Platform lock-in",
    "Private data spread across vendors",
    "No coherent internal operating system",
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
        el("a", { className: "brand", href: "#top", text: "SSO Stack Generator" }),
        el("div", { className: "nav-links" }, [
          el("a", { href: "#repo-proof", text: "Repo Proof" }),
          el("a", { href: "#packages", text: "Packages" }),
          el("a", { href: "#fit", text: "Fit" }),
          el("a", { href: "#next", text: "Next Step" }),
        ]),
      ]),
      el("section", { className: "hero", id: "top" }, [
        el("div", { className: "hero-copy" }, [
          el("p", { className: "eyebrow", text: "Buyer-side repo proof" }),
          el("h1", { text: "Private open-source infrastructure, backed by a public repo." }),
          el("p", {
            className: "lede",
            text:
              "A compact proof page for the SSO Stack Generator: what it offers, how it is built, and where buyers can inspect the source, docs, and verification contract.",
          }),
          el("p", {
            className: "mission",
            text: "Make small groups powerful without making them dependent.",
          }),
          el("div", { className: "actions", "aria-label": "Calls to action" }, [
            externalLink(links.repo, "View proof repo", "button primary"),
            externalLink(links.packages, "See packages", "button"),
            externalLink(links.intake, "Request stack audit", "button"),
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
          el("h2", { text: "Small teams inherit enterprise-shaped problems without enterprise leverage." }),
        ]),
        el("p", {
          text:
            "Small teams often end up with too many SaaS subscriptions, fragmented logins, weak access control, poor backups, vendor lock-in, and operational knowledge scattered across tools they do not control.",
        }),
      ]),
      list(painPoints, "pill-list"),
    ]);
  }

  function renderOffer() {
    return el("section", { className: "band quiet" }, [
      el("div", { className: "section-grid" }, [
        el("div", {}, [
          el("p", { className: "eyebrow", text: "The offer" }),
          el("h2", { text: "A generated, tested private platform your team owns." }),
        ]),
        el("div", { className: "stacked-copy" }, [
          el("p", {
            text:
              "I deploy and adapt private open-source business platforms using a generated, tested stack: Caddy, Keycloak, Docker Compose, systemd user services, SOPS-backed secrets, observability, backups, and verification.",
          }),
          el("p", {
            text:
              "You get a private platform your team owns, with shared login, useful apps, monitoring, backups, and documentation.",
          }),
        ]),
      ]),
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
        "The live page points back to inspectable source.",
        "Cloudflare Pages can publish this directory directly. The proof links are absolute GitHub URLs so the deployed site stays useful outside the repository checkout.",
      ),
      el("div", { className: "repo-panel" }, [
        el("div", {}, [
          el("span", { className: "status-dot", text: "Public repository" }),
          el("h3", { text: "geraldsummers/sso-stack-generator" }),
          el("p", {
            text:
              "The repository contains the generator source, docs, package boundaries, deployment contract, and verification notes used to evaluate the offer.",
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
      sectionHeading("Packages", "Choose the smallest useful platform."),
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
      el("div", { className: "columns" }, [
        el("div", {}, [
          el("p", { className: "eyebrow", text: "Good fit" }),
          el("h2", { text: "Teams that want ownership with visible operations." }),
          list(fitLists.good, "check-list"),
        ]),
        el("div", {}, [
          el("p", { className: "eyebrow", text: "Poor fit" }),
          el("h2", { text: "Projects that need hidden complexity or instant emergency service." }),
          list(fitLists.poor, "check-list muted"),
        ]),
      ]),
    ]);
  }

  function renderNextStep() {
    return el("section", { className: "cta", id: "next" }, [
      el("p", { className: "eyebrow", text: "Next step" }),
      el("h2", { text: "Start with a stack audit." }),
      el("p", {
        text:
          "Send me your current SaaS/tool list. I will tell you what can be replaced, what should stay SaaS, and what a private stack would look like.",
      }),
      el("div", { className: "actions centered" }, [
        externalLink(links.intake, "Prepare a tool-list audit", "button primary"),
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
        renderOffer(),
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
