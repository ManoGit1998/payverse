# syntax=docker/dockerfile:1
# Build the jar first (make build), then: docker compose --profile core build transaction-service
# Multi-arch base (linux/arm64 + amd64), layered jar, non-root user.

FROM eclipse-temurin:21-jre AS extract
WORKDIR /build
COPY target/transaction-service-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 payverse && useradd --system --uid 10001 --gid payverse --no-create-home payverse
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8084
ENV MALLOC_ARENA_MAX=2
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
