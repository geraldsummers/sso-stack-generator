FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r searchservice && useradd -r -g searchservice searchservice

WORKDIR /app

COPY stack.kotlin/search-service/build/libs/search-service-1.0-SNAPSHOT-all.jar /app/app.jar

RUN chown -R searchservice:searchservice /app

USER searchservice

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8098/health || exit 1

EXPOSE 8098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
