# Built with the repository root as the Docker context (see Dockerfile.dockerignore beside this
# file), so the build can read the repository's parent POM by relativePath. No registry and no
# token: every dependency comes from Maven Central.

# Stage 1: Build
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS backend-build

# The layout mirrors the repository, so the pom's relativePath resolves unchanged.
WORKDIR /src/apps/accounting-mcp
COPY build/parent/pom.xml /src/build/parent/pom.xml
COPY apps/accounting-mcp/pom.xml .
RUN mvn -B dependency:go-offline -q
COPY apps/accounting-mcp/src ./src
RUN mvn -B package -DskipTests -q

# Stage 2: Runtime
FROM docker.io/library/eclipse-temurin:25-jre

LABEL org.opencontainers.image.source="https://github.com/manyfold-dk/manyfold-platform"
LABEL org.opencontainers.image.description="Dinero accounting integration (MCP + REST passthrough) for one Dinero organisation"

ENV LANGUAGE='en_US:en'
WORKDIR /deployments

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

COPY --chown=185 --from=backend-build /src/apps/accounting-mcp/target/quarkus-app/lib/ /deployments/lib/
COPY --chown=185 --from=backend-build /src/apps/accounting-mcp/target/quarkus-app/*.jar /deployments/
COPY --chown=185 --from=backend-build /src/apps/accounting-mcp/target/quarkus-app/app/ /deployments/app/
COPY --chown=185 --from=backend-build /src/apps/accounting-mcp/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185

ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
