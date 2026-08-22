FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /deployments
COPY --from=build /build/target/order-service.jar /deployments/app.jar
EXPOSE 8082
RUN chgrp -R 0 /deployments && chmod -R g=u /deployments
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
