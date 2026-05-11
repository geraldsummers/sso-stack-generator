FROM debian:bookworm-slim

ARG USERNAME=agent
ARG UID=1000
ARG GID=1000
ARG KOTLIN_VERSION=2.0.21
ARG KOTLIN_COMPILER_SHA256=0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a

ENV DEBIAN_FRONTEND=noninteractive \
    HOME=/home/${USERNAME} \
    PATH=/home/${USERNAME}/bin:/usr/local/go/bin:/opt/kotlin/current/bin:${PATH} \
    AGENT_TOOLCHAINS_DIR=/opt/agent-toolchains

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    build-essential \
    ca-certificates \
    coreutils \
    curl \
    fd-find \
    findutils \
    git \
    gnupg \
    jq \
    less \
    make \
    openssh-server \
    openssh-client \
    procps \
    python3 \
    python3-pip \
    python3-venv \
    ripgrep \
    rsync \
    sed \
    sqlite3 \
    sudo \
    tree \
    unzip \
    util-linux \
    vim-tiny \
    wget \
    xz-utils \
    zsh \
    default-jdk \
    golang-go \
 && ln -sf /usr/bin/fdfind /usr/local/bin/fd \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
 && chmod a+r /etc/apt/keyrings/docker.gpg \
 && . /etc/os-release \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" > /etc/apt/sources.list.d/docker.list \
 && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
 && chmod a+r /etc/apt/keyrings/nodesource.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
 && apt-get install -y --no-install-recommends nodejs \
 && curl -fsSL "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" -o /tmp/kotlin.zip \
 && echo "${KOTLIN_COMPILER_SHA256}  /tmp/kotlin.zip" | sha256sum -c - \
 && mkdir -p /opt/kotlin \
 && unzip -q /tmp/kotlin.zip -d /opt/kotlin \
 && ln -sfn /opt/kotlin/kotlinc /opt/kotlin/current \
 && ln -sfn /opt/kotlin/current/bin/kotlinc /usr/local/bin/kotlinc \
 && ln -sfn /opt/kotlin/current/bin/kotlin /usr/local/bin/kotlin \
 && npm install -g @openai/codex typescript \
 && python3 -m venv /opt/aider-venv \
 && /opt/aider-venv/bin/pip install --no-cache-dir aider-chat==0.86.2 \
 && rm -rf /var/lib/apt/lists/* /root/.cache /tmp/kotlin.zip

RUN groupadd -g ${GID} ${USERNAME} \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash ${USERNAME} \
 && printf '%s\n' "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} \
 && chmod 0440 /etc/sudoers.d/${USERNAME}

RUN mkdir -p \
    /home/${USERNAME}/bin \
    /home/${USERNAME}/repositories \
    /home/${USERNAME}/notes/daily \
    /home/${USERNAME}/notes/projects \
    /home/${USERNAME}/notes/incidents \
    /home/${USERNAME}/notes/decisions \
    /home/${USERNAME}/state/session-journal \
    /home/${USERNAME}/state/summaries \
    /home/${USERNAME}/state/discoveries \
    /home/${USERNAME}/state/checkpoints \
    /home/${USERNAME}/outputs \
    /home/${USERNAME}/tmp \
    /home/${USERNAME}/templates \
    /home/${USERNAME}/.config \
    /home/${USERNAME}/.local/share/agent \
    /home/${USERNAME}/.aider \
    /home/${USERNAME}/.ssh \
    /opt/agent-toolchains/bin \
    /opt/agent-toolchains/aider \
    /opt/agent-toolchains/codex \
    /opt/agent-toolchains/profiles \
    /var/run/sshd \
 && chmod 700 /home/${USERNAME}/.ssh \
 && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME}

COPY --chown=${USERNAME}:${USERNAME} agents.md /home/${USERNAME}/AGENTS.md
COPY --chown=${USERNAME}:${USERNAME} README_FIRST.md /home/${USERNAME}/README_FIRST.md
COPY bin/ /opt/agent-toolchains/bin/
COPY aider/ /opt/agent-toolchains/aider/
COPY codex/ /opt/agent-toolchains/codex/
COPY profiles/ /opt/agent-toolchains/profiles/

RUN printf '%s\n' \
    '# Daily Note' \
    '' \
    '## Date' \
    'YYYY-MM-DD' \
    '' \
    '## Goals' \
    '-' \
    '' \
    '## Work done' \
    '-' \
    '' \
    '## Findings' \
    '-' \
    '' \
    '## Open threads' \
    '-' \
    '' \
    '## Next actions' \
    '-' \
    > /home/${USERNAME}/templates/daily-note.md

RUN printf '%s\n' \
    '# Project Note' \
    '' \
    '## Purpose' \
    '-' \
    '' \
    '## Current status' \
    '-' \
    '' \
    '## Important facts' \
    '-' \
    '' \
    '## Useful commands' \
    '- command:' \
    '- command:' \
    '' \
    '## Risks / gotchas' \
    '-' \
    '' \
    '## Next steps' \
    '-' \
    > /home/${USERNAME}/templates/project-note.md

RUN printf '%s\n' \
    '# Incident' \
    '' \
    '## Summary' \
    '-' \
    '' \
    '## Symptoms' \
    '-' \
    '' \
    '## Cause' \
    '-' \
    '' \
    '## Actions taken' \
    '-' \
    '' \
    '## Verification' \
    '-' \
    '' \
    '## Follow-up' \
    '-' \
    > /home/${USERNAME}/templates/incident.md

RUN printf '%s\n' \
    '# Decision Log' \
    '' \
    '## Decision' \
    '-' \
    '' \
    '## Why' \
    '-' \
    '' \
    '## Alternatives considered' \
    '-' \
    '' \
    '## Consequences' \
    '-' \
    > /home/${USERNAME}/templates/decision-log.md

RUN find /opt/agent-toolchains/bin -type f -exec chmod 0755 {} + \
 && find /opt/agent-toolchains/codex -type f -name '*.sh' -exec chmod 0755 {} + \
 && find /opt/agent-toolchains/aider -type f -name '*.sh' -exec chmod 0755 {} + \
 && find /opt/agent-toolchains/profiles -type f \( -name 'install.sh' -o -name 'verify.sh' \) -exec chmod 0755 {} + \
 && ln -sf /opt/aider-venv/bin/aider /usr/local/bin/aider \
 && ln -sf /opt/agent-toolchains/bin/agent-install-profile /usr/local/bin/agent-install-profile \
 && ln -sf /opt/agent-toolchains/bin/agent-verify-profile /usr/local/bin/agent-verify-profile \
 && ln -sf /opt/agent-toolchains/bin/agent-list-profiles /usr/local/bin/agent-list-profiles \
 && ln -sf /opt/agent-toolchains/bin/agent-sync-profile-state /usr/local/bin/agent-sync-profile-state \
 && ln -sf /opt/agent-toolchains/bin/agent-aider-run /usr/local/bin/agent-aider-run \
 && ln -sf /opt/agent-toolchains/bin/agent-aider-doctor /usr/local/bin/agent-aider-doctor \
 && ln -sf /opt/agent-toolchains/bin/agent-codex-run /usr/local/bin/agent-codex-run \
 && ln -sf /opt/agent-toolchains/bin/agent-codex-doctor /usr/local/bin/agent-codex-doctor \
 && ln -sf /opt/agent-toolchains/bin/stack-auth /usr/local/bin/stack-auth \
 && ln -sf /opt/agent-toolchains/bin/stack-search /usr/local/bin/stack-search \
 && ln -sf /opt/agent-toolchains/bin/stack-doc-get /usr/local/bin/stack-doc-get \
 && ln -sf /opt/agent-toolchains/bin/agent-install-profile /home/${USERNAME}/bin/agent-install-profile \
 && ln -sf /opt/agent-toolchains/bin/agent-verify-profile /home/${USERNAME}/bin/agent-verify-profile \
 && ln -sf /opt/agent-toolchains/bin/agent-list-profiles /home/${USERNAME}/bin/agent-list-profiles \
 && ln -sf /opt/agent-toolchains/bin/agent-sync-profile-state /home/${USERNAME}/bin/agent-sync-profile-state \
 && ln -sf /opt/agent-toolchains/bin/agent-aider-run /home/${USERNAME}/bin/agent-aider-run \
 && ln -sf /opt/agent-toolchains/bin/agent-aider-doctor /home/${USERNAME}/bin/agent-aider-doctor \
 && ln -sf /opt/agent-toolchains/bin/agent-codex-run /home/${USERNAME}/bin/agent-codex-run \
 && ln -sf /opt/agent-toolchains/bin/agent-codex-doctor /home/${USERNAME}/bin/agent-codex-doctor \
 && ln -sf /opt/agent-toolchains/bin/stack-auth /home/${USERNAME}/bin/stack-auth \
 && ln -sf /opt/agent-toolchains/bin/stack-search /home/${USERNAME}/bin/stack-search \
 && ln -sf /opt/agent-toolchains/bin/stack-doc-get /home/${USERNAME}/bin/stack-doc-get \
 && printf '%s\n' \
    'export PATH="$HOME/bin:$PATH"' \
    'export EDITOR=vim' \
    'export VISUAL=vim' \
    'export LESS=-FRX' \
    'export AGENT_TOOLCHAINS_DIR=/opt/agent-toolchains' \
    'cd "$HOME/repositories" 2>/dev/null || cd "$HOME"' \
    >> /home/${USERNAME}/.bashrc \
 && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME}

USER ${USERNAME}
WORKDIR /home/${USERNAME}/repositories

CMD ["/bin/bash"]
