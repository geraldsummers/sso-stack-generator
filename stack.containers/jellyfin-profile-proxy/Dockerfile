FROM python:3.12-slim

WORKDIR /app
COPY stack.containers/jellyfin-profile-proxy/jellyfin_profile_proxy.py /app/jellyfin_profile_proxy.py

RUN useradd -r -u 10022 -g root jellyproxy \
    && chown -R jellyproxy:root /app

USER jellyproxy
EXPOSE 8080

CMD ["python", "/app/jellyfin_profile_proxy.py"]
