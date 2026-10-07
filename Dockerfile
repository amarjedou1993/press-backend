# ---------- Build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -Dmaven.test.skip=true \
 && cp target/*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination /extract

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl fontconfig \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home app \
 && mkdir -p /var/lib/press-accreditation/storage \
 && chown -R app:app /var/lib/press-accreditation

WORKDIR /app
COPY --from=build --chown=app:app /extract/dependencies/ ./
COPY --from=build --chown=app:app /extract/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extract/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extract/application/ ./

USER app
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Duser.timezone=Africa/Nouakchott"
VOLUME /var/lib/press-accreditation/storage
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]