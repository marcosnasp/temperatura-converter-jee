FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM quay.io/wildfly/wildfly:32.0.1.Final-jdk21
USER root
# cria usuario Elytron a partir de env APP_USERNAME/PASSWORD no boot
COPY --chown=jboss:jboss scripts/add-user.sh /opt/jboss/wildfly/scripts/add-user.sh
RUN chmod +x /opt/jboss/wildfly/scripts/add-user.sh
USER jboss
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/temperatura.war
# habilita metrics/health/telemetry/openapi se ainda nao estiverem (WildFly 32 ja traz via standalone-microprofile.xml)
EXPOSE 8080 9990
ENTRYPOINT ["/opt/jboss/wildfly/scripts/add-user.sh"]
