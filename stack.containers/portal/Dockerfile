FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN useradd -r -u 10029 -g root portal \
    && mkdir -p /app /contracts \
    && chown -R portal:root /app /contracts

WORKDIR /app

COPY stack.containers/portal/portal.py /app/portal.py

EXPOSE 8080
USER portal

CMD ["python", "/app/portal.py"]
