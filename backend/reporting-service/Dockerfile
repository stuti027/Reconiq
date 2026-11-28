# Build: docker build -t reconiq/reporting-service backend/reporting-service
# Run:   docker run -p 8095:8095 --env-file <file> reconiq/reporting-service

# Stage 1: compile and package with Maven (dependencies cached in their own layer).
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Stage 2: only the JRE and the jar; no Maven, no source code.
FROM eclipse-temurin:17.0.15_6-jre-noble
RUN groupadd --system app && useradd --system --gid app --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/target/reporting-service-*.jar app.jar
USER app
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
