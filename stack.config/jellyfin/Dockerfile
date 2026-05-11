FROM jellyfin/jellyfin@sha256:1694ff069f0c9dafb283c36765175606866769f5d72f2ed56b6a0f1be922fc37

RUN apt-get update && apt-get install -y --no-install-recommends curl sqlite3 unzip && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /config-templates /opt/jellyfin-plugins/sso-authentication_4.0.0.4 \
    && curl -fsSL \
        https://github.com/9p4/jellyfin-plugin-sso/releases/download/v4.0.0.4/sso-authentication_4.0.0.4.zip \
        -o /tmp/sso-authentication.zip \
    && echo "1e7908585ebee256203a0869dcdfbaf8  /tmp/sso-authentication.zip" | md5sum -c - \
    && unzip -q /tmp/sso-authentication.zip -d /opt/jellyfin-plugins/sso-authentication_4.0.0.4 \
    && rm -f /tmp/sso-authentication.zip

COPY stack.config/jellyfin/entrypoint.sh /entrypoint.sh
COPY stack.config/jellyfin/ffmpeg-websafe.sh /usr/local/bin/jellyfin-ffmpeg-websafe
COPY stack.config/jellyfin/SSO-Auth.xml /config-templates/SSO-Auth.xml
RUN ln -sf /usr/lib/jellyfin-ffmpeg/ffprobe /usr/local/bin/ffprobe \
    && chmod +x /entrypoint.sh /usr/local/bin/jellyfin-ffmpeg-websafe

ENTRYPOINT ["/entrypoint.sh"]
