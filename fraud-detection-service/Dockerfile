FROM maven:3.9.8-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml ./
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY customer-agent-service/pom.xml customer-agent-service/pom.xml
COPY fraud-detection-service/pom.xml fraud-detection-service/pom.xml
COPY loan-prescreen-service/pom.xml loan-prescreen-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY orchestration-service/pom.xml orchestration-service/pom.xml
COPY fraud-detection-service/src fraud-detection-service/src

RUN mvn -pl fraud-detection-service -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/fraud-detection-service/target/fraud-detection-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
