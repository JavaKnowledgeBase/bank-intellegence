FROM maven:3.9.8-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml ./
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY customer-agent-service/pom.xml customer-agent-service/pom.xml
COPY fraud-detection-service/pom.xml fraud-detection-service/pom.xml
COPY loan-prescreen-service/pom.xml loan-prescreen-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY orchestration-service/pom.xml orchestration-service/pom.xml
COPY customer-agent-service/src customer-agent-service/src

RUN mvn -pl customer-agent-service -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/customer-agent-service/target/customer-agent-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
