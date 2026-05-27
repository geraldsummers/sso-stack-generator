FROM smallstep/step-ca:0.30.2@sha256:a2b17872915c193259b75a5474c398326f41bd199f0842093e52cf4182bc8270 AS step

FROM eclipse-temurin:21-jre-jammy

ARG TTYD_VERSION=1.7.7
ARG TTYD_SHA256_AMD64=8a217c968aba172e0dbf3f34447218dc015bc4d5e59bf51db2f2cd12b7be4f55
ARG TTYD_SHA256_ARM64=b38acadd89d1d396a0f5649aa52c539edbad07f4bc7348b27b4f4b7219dd4165
ARG TTYD_SHA256_ARMHF=8240c8438b68d3b10b0e1a4e7c914d70fca6a7606b516f40bf40adfa1044d801

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
      x86_64|amd64) ttyd_asset="ttyd.x86_64"; ttyd_sha256="${TTYD_SHA256_AMD64}" ;; \
      aarch64|arm64) ttyd_asset="ttyd.aarch64"; ttyd_sha256="${TTYD_SHA256_ARM64}" ;; \
      armv7l|armhf) ttyd_asset="ttyd.armhf"; ttyd_sha256="${TTYD_SHA256_ARMHF}" ;; \
      *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;; \
    esac; \
    wget -O /usr/local/bin/ttyd "https://github.com/tsl0922/ttyd/releases/download/${TTYD_VERSION}/${ttyd_asset}"; \
    printf '%s  /usr/local/bin/ttyd\n' "${ttyd_sha256}" | sha256sum -c -; \
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
