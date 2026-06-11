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
      title: "Repeatable Build",
      copy: "The stack is generated from explicit inputs, not hand-edited on a server.",
      image: "assets/build-deploy-verify.svg",
      href: `${docsBase}/build-system.md`,
    },
    {
      title: "SSO And Secrets",
      copy: "Identity, access boundaries, and secrets handling are documented up front.",
      image: "assets/trust-boundary.svg",
      href: `${docsBase}/security-and-auth.md`,
    },
    {
      title: "Supervised Services",
      copy: "Services are started through a defined host contract, not a one-off compose pile.",
      image: "assets/systemd-orchestration.svg",
      href: `${docsBase}/systemd-graph.md`,
    },
    {
      title: "Verification",
      copy: "Readiness checks and contract tests are part of delivery and handoff.",
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
      fit: "For a private base: login, docs, passwords, backups, and basic visibility.",
      items: [
        "Central login and routing",
        "Private docs and passwords",
        "Service homepage/catalog",
        "Basic monitoring and backups",
        "Verification and handoff notes",
      ],
    },
    {
      name: "Ops Platform",
      fit: "For teams replacing several internal SaaS tools with one owned workspace.",
      items: [
        "Everything in Secure Core",
        "Files, projects, and internal docs",
        "Mail, calendar, and contacts",
        "Chat and Git hosting where useful",
        "Deeper observability and operations notes",
      ],
    },
    {
      name: "AI Sovereign Lab",
      fit: "For technical workflows that need private notebooks, search, and workspaces.",
      items: [
        "Everything in Ops Platform",
        "JupyterHub and disposable workspaces",
        "OpenSearch and Qdrant",
        "Knowledge ingestion and search workflows",
        "Connector tooling where scoped",
      ],
    },
  ];

  const audience = [
    "Privacy-conscious small teams",
    "Small businesses reducing SaaS costs",
    "Open-source-friendly operators",
    "Technical founders",
    "Makerspaces, co-ops, and community groups",
    "Clients willing to own domain, DNS, and admin decisions",
  ];

  const painPoints = [
    "subscription sprawl",
    "fragmented logins",
    "unclear offboarding",
    "unproven backups",
    "weak internal docs",
    "vendor lock-in",
    "private data across vendors",
    "no operating surface",
  ];

  const buyerProof = [
    {
      label: "Inspect the source",
      text: "The public repo shows the generator, docs, and website source before a call is booked.",
    },
    {
      label: "Check the operating model",
      text: "Build, deploy, verify, recovery, and support boundaries are written down.",
    },
    {
      label: "Start smaller",
      text: "The offer begins with a tool-list audit so unsuitable replacements can be ruled out early.",
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
        el("a", { className: "brand", href: "#top", text: "SSO Stack Generator" }),
        el("div", { className: "nav-links" }, [
          el("a", { href: "#buyer-proof", text: "Buyer Proof" }),
          el("a", { href: "#packages", text: "Packages" }),
          el("a", { href: "#repo-proof", text: "Repo" }),
          el("a", { href: "#fit", text: "Who It's For" }),
          el("a", { href: "#next", text: "Audit" }),
        ]),
      ]),
      el("section", { className: "hero", id: "top" }, [
        el("div", { className: "hero-copy" }, [
          el("p", { className: "eyebrow", text: "Upwork buyers: verify before you message" }),
          el("h1", { text: "Private infrastructure you can inspect first." }),
          el("p", {
            className: "lede",
            text:
              "I help small teams replace scattered SaaS tools with a client-owned open-source platform: shared login, docs, files, passwords, monitoring, backups, and optional AI workspaces.",
          }),
          el("p", {
            className: "mission",
            text: "This page exists so you can inspect the proof repo before starting a stack audit.",
          }),
          el("div", { className: "actions", "aria-label": "Calls to action" }, [
            externalLink(links.intake, "Start with a stack audit", "button primary"),
            externalLink(links.repo, "Inspect the repo", "button"),
            externalLink(links.packages, "Compare packages", "button"),
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
          el("h2", { text: "Your tools grew one subscription at a time. Now nobody owns the system." }),
        ]),
        el("p", {
          text:
            "Most small teams do not need a heavyweight enterprise platform. They need one private place for access, docs, files, passwords, monitoring, backups, and operational knowledge, with clear limits on what should stay SaaS.",
        }),
      ]),
      list(painPoints, "pill-list"),
    ]);
  }

  function renderBuyerProof() {
    return el("section", { className: "band compact", id: "buyer-proof" }, [
      sectionHeading(
        "Buyer proof",
        "A product page can make claims. A repo lets you check them.",
        "Use this page as the public proof layer next to the Upwork product listing: inspect the source, read the scope boundaries, and decide whether a stack audit is worth your time.",
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
          el("h2", { text: "A private stack with a defined handoff, not a mystery server." }),
        ]),
        el("div", { className: "stacked-copy" }, [
          el("p", {
            text:
              "The delivery starts with your current tool list, users, data, and operating needs. From there, we decide what is worth replacing, what should stay SaaS, and which package is the smallest useful fit.",
          }),
          el("p", {
            text:
              "When a private stack is the right move, the repo-backed generator provides a repeatable build, explicit deployment contract, verification steps, and buyer-readable documentation.",
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
        "The live site points back to inspectable source.",
        "This Cloudflare Pages site is intentionally simple. It publishes the buyer proof layer while linking every technical claim back to public repository documents.",
      ),
      el("div", { className: "repo-panel" }, [
        el("div", {}, [
          el("span", { className: "status-dot", text: "Public repository" }),
          el("h3", { text: "geraldsummers/sso-stack-generator" }),
          el("p", {
            text:
              "The repository contains the generator source, package boundaries, deployment contract, verification notes, and this website's source.",
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
        "Choose the smallest useful platform.",
        "The audit should reduce scope, not inflate it. Each package is a starting point for a private platform, not a promise that every app should be migrated.",
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
          el("h2", { text: "Teams that want ownership without hiding operations." }),
        ]),
        el("div", {}, [
          el("p", {
            className: "section-note",
            text:
              "The strongest projects have a clear owner, a real reason to reduce SaaS dependency, and enough tolerance for visible operational decisions.",
          }),
          list(audience, "check-list"),
        ]),
      ]),
    ]);
  }

  function renderNextStep() {
    return el("section", { className: "cta", id: "next" }, [
      el("p", { className: "eyebrow", text: "Next step" }),
      el("h2", { text: "Bring the messy tool list." }),
      el("p", {
        text:
          "Send the current SaaS list, users, admin owner, domain situation, migration needs, and support expectations. I will map what can be replaced, what should stay SaaS, and what a private stack would actually need.",
      }),
      el("div", { className: "actions centered" }, [
        externalLink(links.intake, "Open audit checklist", "button primary"),
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
        renderBuyerProof(),
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
