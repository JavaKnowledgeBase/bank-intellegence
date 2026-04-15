FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests clean package

FROM gcr.io/distroless/java21-debian12
WORKDIR /app

COPY --from=builder /workspace/target/orchestration-service-*.jar app.jar

EXPOSE 8084
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
