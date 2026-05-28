FROM eclipse-temurin:21-jre-jammy

RUN useradd -r -u 10028 -g root progression \
    && mkdir -p /app /runtime/progression \
    && chown -R progression:root /app /runtime

WORKDIR /app

COPY stack.kotlin/progression/build/libs/progression-1.0.0-all.jar /app/progression.jar
COPY stack.containers/progression/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

EXPOSE 8130
USER progression
ENTRYPOINT ["/entrypoint.sh"]
