# Built with the repository root as the Docker context (see Dockerfile.dockerignore beside this
# file), so the backend can read the repository's parent POM by relativePath. No registry and
# no token: every dependency comes from Maven Central and npm.

# Stage 1: Build frontend
FROM docker.io/library/node:24-alpine AS frontend-build

WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10.28.0 --activate
COPY apps/website/frontend/package.json apps/website/frontend/pnpm-lock.yaml* ./
RUN pnpm install --frozen-lockfile
COPY apps/website/frontend/ .
RUN pnpm build

# Stage 2: Build backend (frontend dist is baked into the JAR)
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS backend-build

# The layout mirrors the repository, so the pom's relativePath resolves unchanged.
WORKDIR /src/apps/website/backend
COPY build/parent/pom.xml /src/build/parent/pom.xml
COPY apps/website/backend/pom.xml .
RUN mvn -B dependency:go-offline -q
COPY apps/website/backend/src ./src
# Copy frontend dist into classpath resources BEFORE Maven build
# so Quarkus packages them inside the application JAR
COPY --from=frontend-build /app/dist/ ./src/main/resources/META-INF/resources/
RUN mvn -B package -DskipTests -q

# Stage 3: Runtime
FROM docker.io/library/eclipse-temurin:26-jre

LABEL org.opencontainers.image.source="https://github.com/manyfold-dk/manyfold-platform"
LABEL org.opencontainers.image.description="Website application for manyfold.dk"

ENV LANGUAGE='en_US:en'
WORKDIR /deployments

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

# Copy backend artifacts (frontend dist already baked into app JAR)
COPY --chown=185 --from=backend-build /src/apps/website/backend/target/quarkus-app/lib/ /deployments/lib/
COPY --chown=185 --from=backend-build /src/apps/website/backend/target/quarkus-app/*.jar /deployments/
COPY --chown=185 --from=backend-build /src/apps/website/backend/target/quarkus-app/app/ /deployments/app/
COPY --chown=185 --from=backend-build /src/apps/website/backend/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185

ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
