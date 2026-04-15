FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

COPY pom.xml .
COPY cibap-mcp-server/pom.xml cibap-mcp-server/pom.xml
COPY cibap-mcp-server/src cibap-mcp-server/src

WORKDIR /workspace/cibap-mcp-server
RUN mvn clean package -Dmaven.test.skip=true --batch-mode

FROM gcr.io/distroless/java21-debian12
WORKDIR /app
COPY --from=builder /workspace/cibap-mcp-server/target/cibap-mcp-server-*.jar app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
