# Build stage: Maven + JDK 21. Dependencies are resolved in their own layer so a code change
# does not re-download them.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B --no-transfer-progress -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -B --no-transfer-progress -DskipTests package

# Runtime stage: JRE only, non-root, no package manager left behind.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home creatorstore
COPY --from=build /app/target/app.jar app.jar
RUN chown creatorstore:creatorstore app.jar
USER 10001
EXPOSE 8080
# Container-aware heap; actuator health is wired in a later story, so no HEALTHCHECK yet.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
