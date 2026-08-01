FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/mock-bank-0.0.1-SNAPSHOT.jar /app/mock-bank.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/mock-bank.jar"]