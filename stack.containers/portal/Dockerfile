FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -r -u 10029 -g root portal \
    && mkdir -p /app /contracts \
    && chown -R portal:root /app /contracts

COPY stack.kotlin/portal/build/libs/portal-1.0-SNAPSHOT-all.jar /app/portal.jar

EXPOSE 8080
USER portal

CMD ["java", "-jar", "/app/portal.jar"]
