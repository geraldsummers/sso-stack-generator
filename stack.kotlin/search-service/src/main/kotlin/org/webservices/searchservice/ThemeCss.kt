package org.webservices.searchservice

fun stackThemeCss(): String = """
    :root {
      color-scheme: dark;
      --ws-bg: #111827;
      --ws-bg-alt: #0b1215;
      --ws-surface: #1f2937;
      --ws-surface-alt: #18212f;
      --ws-line: #334155;
      --ws-text: #f4f7f6;
      --ws-muted: #a7b0b8;
      --ws-primary: #14b8a6;
      --ws-primary-strong: #0f766e;
      --ws-primary-soft: rgba(20,184,166,0.16);
      --ws-accent: #f97316;
      --ws-accent-soft: rgba(249,115,22,0.16);
      --ws-accent-text: #431407;
      --ws-error: #ef4444;
    }
    body {
      background: radial-gradient(circle at 18% 0%, var(--ws-primary-soft), transparent 34rem),
        linear-gradient(180deg, var(--ws-bg-alt), var(--ws-bg)) !important;
      color: var(--ws-text) !important;
    }
    .search-box,
    .result {
      background: var(--ws-surface) !important;
      border: 1px solid var(--ws-line) !important;
      color: var(--ws-text) !important;
    }
    h1,
    .result-title {
      color: var(--ws-primary) !important;
    }
    #searchInput {
      background: var(--ws-bg-alt) !important;
      border-color: var(--ws-line) !important;
      color: var(--ws-text) !important;
    }
    #searchInput:focus {
      border-color: var(--ws-primary) !important;
    }
    button,
    .mode-btn.active {
      background: var(--ws-primary) !important;
      color: #061313 !important;
    }
    button:hover {
      background: var(--ws-accent) !important;
      color: var(--ws-accent-text) !important;
    }
    .mode-btn:not(.active) {
      background: var(--ws-surface-alt) !important;
      color: var(--ws-muted) !important;
    }
    .result-snippet,
    .result-meta,
    .empty-state {
      color: var(--ws-muted) !important;
    }
    .error {
      background: var(--ws-error) !important;
    }
""".trimIndent()
