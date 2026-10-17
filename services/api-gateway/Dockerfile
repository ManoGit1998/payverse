# syntax=docker/dockerfile:1
# Build the jar first (make build), then: docker compose build api-gateway
# Multi-arch (linux/arm64 + amd64) Alpine JRE, layered jar, non-root user, readiness healthcheck.

FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /build
COPY target/api-gateway-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 payverse && adduser -S -u 10001 -G payverse -H payverse
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=224m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
