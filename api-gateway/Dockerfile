# Build from the repository root with:
# docker build -f api-gateway/Dockerfile -t api-gateway:latest .

FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY api-gateway/src api-gateway/src

WORKDIR /workspace/api-gateway
RUN mvn clean package -DskipTests --batch-mode

FROM gcr.io/distroless/java21-debian12
WORKDIR /app

COPY --from=builder /workspace/api-gateway/target/api-gateway-*.jar app.jar

EXPOSE 8000
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
