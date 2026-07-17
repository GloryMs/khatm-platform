# KH-0.3 Phase-0 closure: build + runtime base MUST match the toolchain in pom.xml, which is
# Java 21 (maven.compiler.release=21). The original Dockerfile pinned Temurin 17 here, which made
# `docker build` fail at `mvn package` (a JDK 17 compiler rejects --release 21) — so every CI job
# that builds an image (trivy image, compose-smoke, GHCR push) was impossible until this was bumped.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
