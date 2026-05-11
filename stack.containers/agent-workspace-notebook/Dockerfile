FROM debian:bookworm-slim

ARG USERNAME=agent
ARG UID=1000
ARG GID=1000

ENV DEBIAN_FRONTEND=noninteractive \
    HOME=/home/${USERNAME} \
    PATH=/home/${USERNAME}/bin:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    python3 \
    python3-pip \
    tini \
 && python3 -m pip install --break-system-packages --no-cache-dir jupyterlab==4.2.5 \
 && rm -rf /var/lib/apt/lists/* /root/.cache

RUN mkdir -p /usr/local/share/jupyter/lab/settings \
 && printf '%s\n' \
    '{' \
	    '  "@jupyterlab/apputils-extension:themes": {' \
	    '    "theme": "JupyterLab Dark",' \
	    '    "theme-scrollbars": true' \
	    '  }' \
    '}' \
    > /usr/local/share/jupyter/lab/settings/overrides.json

RUN groupadd -g ${GID} ${USERNAME} \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash ${USERNAME}

RUN mkdir -p /home/${USERNAME}/bin \
 && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME}

COPY start-notebook.sh /usr/local/bin/start-notebook

RUN chmod 0755 /usr/local/bin/start-notebook

USER ${USERNAME}
WORKDIR /home/${USERNAME}

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "exec /usr/local/bin/start-notebook"]
