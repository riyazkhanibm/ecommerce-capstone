# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /deployments
COPY --from=build /build/target/product-service.jar /deployments/app.jar
EXPOSE 8081
# OpenShift runs containers with an arbitrary, non-root UID by default.
# This image does not hardcode USER so it works under the OpenShift
# "restricted-v2" SCC out of the box.
RUN chgrp -R 0 /deployments && chmod -R g=u /deployments
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
