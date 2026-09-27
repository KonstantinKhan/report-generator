# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :report-server:shadowJar -q --no-daemon

# --- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN mkdir -p /data/reports

COPY --from=build /workspace/report-server/build/libs/report-server-all.jar /app/report-server.jar

ENV REPORT_OUTPUT_DIR=/data/reports
ENV SERVER_PORT=8080

# LOODSMAN_BASE_URL, LOODSMAN_DB_NAME, LOODSMAN_USERNAME, LOODSMAN_PASSWORD are required and
# must be provided at `docker run` time (-e ...); the server fails fast at startup if missing.

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/report-server.jar"]
