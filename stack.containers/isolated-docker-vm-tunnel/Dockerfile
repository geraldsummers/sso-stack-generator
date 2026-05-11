FROM alpine@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659

RUN apk add --no-cache \
    ca-certificates \
    curl \
    openssh-client \
    socat \
    wget

RUN addgroup -S tunnel && \
    adduser -S -D -h /home/tunnel -s /sbin/nologin -G tunnel -u 10001 tunnel && \
    addgroup -S -g 1000 hostuser && \
    adduser -S -D -h /home/tunnel -s /sbin/nologin -G hostuser -u 1000 hostuser && \
    install -d -m 0700 -o tunnel -g tunnel /home/tunnel/.ssh && \
    install -d -m 0770 -o tunnel -g tunnel /run/docker-labware

# Build context is the repo root, so copy from the container source path explicitly.
COPY stack.containers/isolated-docker-vm-tunnel/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

USER tunnel:tunnel

ENTRYPOINT ["/entrypoint.sh"]
