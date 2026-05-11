package org.webservices.workspaceprovisioner

fun stackThemeCss(): String = """
    :root {
      color-scheme: dark;
      --bg: #111827;
      --bg-alt: #0b1215;
      --panel: #1f2937;
      --panel-alt: #18212f;
      --line: #334155;
      --text: #f4f7f6;
      --muted: #a7b0b8;
      --accent: #14b8a6;
      --accent-soft: rgba(20,184,166,0.16);
      --orange: #f97316;
      --orange-soft: rgba(249,115,22,0.16);
      --ok: #22c55e;
      --warn: #f97316;
      --error: #ef4444;
      --link: #5eead4;
    }
    body {
      background: radial-gradient(circle at top left, var(--orange-soft), transparent 32%),
        radial-gradient(circle at bottom right, var(--accent-soft), transparent 28%),
        linear-gradient(180deg, var(--bg-alt) 0%, var(--bg) 100%) !important;
    }
    button {
      background: linear-gradient(135deg, var(--accent), var(--orange)) !important;
      color: #061313 !important;
    }
    .ghost {
      background: transparent !important;
      color: var(--text) !important;
    }
    .pill {
      background: var(--orange-soft) !important;
      color: #fdba74 !important;
    }
    .profile {
      background: var(--accent-soft) !important;
      color: var(--link) !important;
    }
""".trimIndent()
