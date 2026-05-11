FROM smallstep/step-ca:latest AS step

FROM eclipse-temurin:21-jre-jammy

ARG TTYD_VERSION=1.7.7

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    curl \
    docker.io \
    jq \
    openssh-client \
    python3 \
    wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=step /usr/local/bin/step /usr/local/bin/step
COPY --from=step /usr/local/bin/step-ca /usr/local/bin/step-ca

RUN set -eux; \
    case "$(uname -m)" in \
      x86_64|amd64) ttyd_asset="ttyd.x86_64" ;; \
      aarch64|arm64) ttyd_asset="ttyd.aarch64" ;; \
      armv7l|armhf) ttyd_asset="ttyd.armhf" ;; \
      *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;; \
    esac; \
    wget -O /usr/local/bin/ttyd "https://github.com/tsl0922/ttyd/releases/download/${TTYD_VERSION}/${ttyd_asset}"; \
    chmod 0755 /usr/local/bin/ttyd

RUN useradd -r -u 10021 -g root provisioner \
    && mkdir -p /app /data /workspace \
    && chown -R provisioner:root /app /data /workspace \
    && usermod -aG docker provisioner

WORKDIR /app

COPY stack.kotlin/workspace-provisioner/build/libs/workspace-provisioner-1.0.0-all.jar /app/workspace-provisioner.jar
COPY stack.containers/workspace-provisioner/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

EXPOSE 8120
USER provisioner
ENTRYPOINT ["/entrypoint.sh"]
