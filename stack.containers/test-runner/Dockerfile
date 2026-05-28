ARG PLAYWRIGHT_VERSION=1.58.2

FROM eclipse-temurin:21-jre-jammy AS temurin21

FROM mcr.microsoft.com/playwright:v${PLAYWRIGHT_VERSION}-jammy

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=temurin21 /opt/java/openjdk /opt/java/openjdk

# Install base utilities, Docker, SSH, and gosu for privilege dropping.
RUN apt-get update && apt-get install -y \
    bash \
    curl \
    jq \
    ca-certificates \
    systemd \
    libnss3-tools \
    gnupg \
    lsb-release \
    openssh-client \
    gosu \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null \
    && apt-get update \
    && apt-get install -y docker-ce-cli docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

RUN docker compose version

RUN id -u pwuser >/dev/null 2>&1 && \
    groupadd -f docker && usermod -aG docker pwuser

RUN mkdir -p /etc/docker && \
    echo '{"insecure-registries": ["192.168.0.11:5000", "registry:5000", "isolated-registry:5000"], "dns": ["127.0.0.11"]}' > /etc/docker/daemon.json

WORKDIR /app

COPY --chown=pwuser:pwuser stack.containers/test-runner/playwright-tests/package.json /app/playwright-tests/package.json
COPY --chown=pwuser:pwuser stack.containers/test-runner/playwright-tests/package-lock.json /app/playwright-tests/package-lock.json

RUN mkdir -p /app/repo-fixtures /app/stack.containers/test-runner /app/stack.containers && \
    mkdir -p /app/test-results && \
    chown -R pwuser:pwuser /app && \
    mkdir -p /home/pwuser/.docker/buildx && \
    touch /home/pwuser/.docker/config.json && \
    chown -R pwuser:pwuser /home/pwuser/.docker && \
    chmod -R 755 /home/pwuser/.docker && \
    true

ENV DOCKER_CONFIG=/home/pwuser/.docker
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# Install npm dependencies as pwuser so host bind mounts stay on uid/gid 1000.
USER pwuser
WORKDIR /app/playwright-tests
RUN npm ci

USER root

COPY stack.kotlin/test-runner/build/libs/test-runner-*-all.jar /app/test-runner.jar
COPY --chown=pwuser:pwuser stack.containers/test-runner/playwright-tests /app/playwright-tests
COPY --chown=pwuser:pwuser stack.config/progression /app/repo-fixtures/stack.config/progression
COPY --chown=pwuser:pwuser stack.kotlin/progression/src/main/resources/static /app/repo-fixtures/stack.kotlin/progression/src/main/resources/static
COPY stack.containers/agent-workspace /app/stack.containers/agent-workspace
COPY stack.containers/agent-workspace-notebook /app/stack.containers/agent-workspace-notebook
COPY stack.containers/test-runner/fixtures/caddy-hosts.txt /app/repo-fixtures/caddy-hosts.txt
COPY stack.containers/test-runner/fixtures/agent-build-demo /app/stack.containers/test-runner/fixtures/agent-build-demo
COPY stack.containers/test-runner/fixtures/agent-lang /app/stack.containers/test-runner/fixtures/agent-lang
COPY stack.containers/test-runner/fixtures/aider-runtime /app/stack.containers/test-runner/fixtures/aider-runtime
COPY stack.containers/test-runner/container-entrypoint.sh /container-entrypoint.sh

RUN chown -R pwuser:pwuser /app && \
    chmod +x /container-entrypoint.sh && \
    chmod -R a+rX "$PLAYWRIGHT_BROWSERS_PATH"

WORKDIR /app
ENTRYPOINT ["/container-entrypoint.sh"]
CMD ["suite"]
