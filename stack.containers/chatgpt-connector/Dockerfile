FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r chatgptconnector && useradd -r -g chatgptconnector chatgptconnector

WORKDIR /app

COPY stack.kotlin/chatgpt-connector/build/libs/chatgpt-connector-1.0-SNAPSHOT-all.jar /app/app.jar

RUN mkdir -p /data && chown -R chatgptconnector:chatgptconnector /app /data

USER chatgptconnector

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8130/health || exit 1

EXPOSE 8130
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
