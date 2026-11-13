FROM eclipse-temurin:17-jre

RUN useradd --create-home --shell /usr/sbin/nologin appuser
WORKDIR /app
COPY target/normalization-service-*.jar app.jar
RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8090
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
