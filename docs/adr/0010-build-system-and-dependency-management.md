# ADR 0010: Build System and Dependency Management

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Implementation Details](#implementation-details)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [References](#references)
- [Notes](#notes)

## Status

Accepted

## Context

The Manyfold Platform consists of multiple applications with different technology stacks: a Java/Quarkus backend and a Vue/Vite frontend. Each requires its own build tooling, dependency management, and quality gates. Additionally, CI/CD pipelines need efficient dependency caching to avoid lengthy rebuilds.

### Requirements

1. **Build Reproducibility**: Builds must produce identical outputs given the same inputs
2. **Dependency Locking**: Lock files must ensure consistent dependency versions
3. **Quality Gates**: Automated code quality checks (linting, formatting, testing)
4. **CI/CD Caching**: Dependencies must be cached to speed up pipeline builds
5. **Developer Experience**: Local builds must be fast and intuitive
6. **Code Coverage**: Enforce minimum test coverage thresholds
7. **Security**: Vulnerability scanning for dependencies

### Technology Stack

| Component | Language | Build Tool | Package Manager |
|-----------|----------|------------|-----------------|
| Backend | Java 25 | Maven 3.9 | Maven Central |
| Frontend | TypeScript | Vite 6 | pnpm 10 |

> Versions are now pinned centrally in the parent POM of the private baseline repository
> and consumed by inheritance. See ADR-0038 (private).

## Decision

We will use the following build and dependency management strategy:

- **Backend**: Maven with Quarkus plugin, strict quality gates, JaCoCo coverage
- **Frontend**: pnpm with lockfile, ESLint/Prettier, Vitest for testing
- **CI/CD**: Tekton tasks with persistent volume caches for dependencies
- **Quality Enforcement**: Build fails on quality gate violations

### Build Commands Summary

```bash
# Backend (Java/Quarkus)
mvn clean package              # Build
mvn test                       # Unit tests
mvn verify                     # Full verification (lint, test, coverage)
mvn spotless:apply             # Format code
mvn quarkus:dev                # Dev mode with hot reload

# Frontend (Vue/Vite)
pnpm install --frozen-lockfile # Install deps (CI)
pnpm install                   # Install deps (development)
pnpm build                     # Production build
pnpm test:unit                 # Unit tests
pnpm lint && pnpm format       # Lint and format
pnpm dev                       # Dev server
```

## Rationale

### Why Maven for Backend?

**Quarkus Native Integration**:
- Quarkus provides first-class Maven support via `quarkus-maven-plugin`
- Dev mode (`mvn quarkus:dev`) enables live reload during development
- Native image builds are fully supported
- Quarkus BOM manages all dependency versions

**Mature Ecosystem**:
- Industry standard for Java projects
- Extensive plugin ecosystem for quality tools
- IDE integration in all major editors
- Well-understood by Java developers

**Reproducible Builds**:
- Dependency versions declared in `pom.xml`
- Quarkus BOM ensures compatible versions
- Maven Enforcer plugin validates environment

**Why Not Gradle?**:
- Quarkus supports both, but Maven is more common
- Simpler configuration for standard builds
- Less abstraction/magic than Groovy/Kotlin DSL
- Team familiarity with Maven

### Why pnpm for Frontend?

**Disk Efficiency**:
- Content-addressable storage shares packages across projects
- Hard links instead of copying, saving significant disk space
- Faster installs due to package reuse

**Strict Dependency Resolution**:
- Prevents accessing undeclared dependencies (unlike npm)
- Each package can only access its declared dependencies
- Catches dependency issues earlier

**Speed**:
- Parallel installation of packages
- Efficient caching mechanism
- Faster than npm and yarn for most operations

**Lockfile**:
- `pnpm-lock.yaml` ensures reproducible installs
- `--frozen-lockfile` in CI prevents accidental updates
- Clear diff in version control

**Why Not npm or yarn?**:
- npm: Less strict, allows phantom dependencies
- yarn: Good option, but pnpm is more disk-efficient
- Both viable but pnpm offers best combination of features

### Maven Quality Gates

The backend uses multiple quality gate plugins configured in `pom.xml`:

**Spotless (Formatting)**:
```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <configuration>
    <java>
      <eclipse>
        <file>${project.basedir}/eclipse-formatter.xml</file>
      </eclipse>
      <removeUnusedImports/>
      <importOrder>
        <order>java,jakarta,io.quarkus,io.smallrye,dk.manyfold,</order>
      </importOrder>
    </java>
  </configuration>
</plugin>
```

**Checkstyle (Style Rules)**:
```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <configuration>
    <configLocation>checkstyle.xml</configLocation>
    <failsOnError>true</failsOnError>
    <includeTestSourceDirectory>true</includeTestSourceDirectory>
  </configuration>
</plugin>
```

**SpotBugs (Bug Detection)**:
```xml
<plugin>
  <groupId>com.github.spotbugs</groupId>
  <artifactId>spotbugs-maven-plugin</artifactId>
  <configuration>
    <effort>Max</effort>
    <threshold>Low</threshold>
  </configuration>
</plugin>
```

**PMD (Static Analysis)**:
```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-pmd-plugin</artifactId>
  <configuration>
    <failOnViolation>true</failOnViolation>
    <rulesets>
      <ruleset>${project.basedir}/pmd-ruleset.xml</ruleset>
    </rulesets>
  </configuration>
</plugin>
```

**JaCoCo (Coverage)**:
```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <configuration>
    <rules>
      <rule>
        <element>BUNDLE</element>
        <limits>
          <limit>
            <counter>LINE</counter>
            <value>COVEREDRATIO</value>
            <minimum>0.50</minimum>
          </limit>
        </limits>
      </rule>
    </rules>
  </configuration>
</plugin>
```

### Frontend Quality Gates

**ESLint (Linting)**:
- Configured via `eslint.config.js`
- Vue plugin for template linting
- TypeScript plugin for type-aware rules
- Auto-fix on save in VS Code

**Prettier (Formatting)**:
- Consistent code style across frontend
- Integrated with ESLint
- Auto-format on save

**Vitest (Unit Testing)**:
- Vite-native test runner
- Fast execution with HMR support
- Compatible with Jest API

**vue-tsc (Type Checking)**:
- Type checking for Vue SFC files
- Runs before production builds
- Catches type errors in templates

### CI/CD Dependency Caching

Tekton pipelines use persistent volume caches for dependencies:

**Maven Cache Task**:
```yaml
# infrastructure/tekton/base/tasks/maven.yaml
workspaces:
  - name: source
    description: Workspace containing the source code
  - name: maven-cache
    description: Workspace for Maven local repository cache
    optional: true
steps:
  - name: maven-build
    script: |
      if [ -d "$(workspaces.maven-cache.path)" ]; then
        mkdir -p "$(workspaces.maven-cache.path)/.m2/repository"
        export MAVEN_OPTS="-Dmaven.repo.local=$(workspaces.maven-cache.path)/.m2/repository"
        echo "Using Maven cache at $(workspaces.maven-cache.path)/.m2/repository"
      fi
      mvn -B -ntp ${GOALS} ${EXTRA_ARGS}
```

**pnpm Cache Task**:
```yaml
# infrastructure/tekton/base/tasks/pnpm.yaml
workspaces:
  - name: source
    description: Workspace containing the source code
  - name: pnpm-cache
    description: Workspace for pnpm store cache
    optional: true
steps:
  - name: pnpm-build
    script: |
      if [ -d "$(workspaces.pnpm-cache.path)" ]; then
        export PNPM_STORE_DIR="$(workspaces.pnpm-cache.path)/store"
        mkdir -p "${PNPM_STORE_DIR}"
        pnpm config set store-dir "${PNPM_STORE_DIR}"
        echo "Using pnpm cache at ${PNPM_STORE_DIR}"
      fi
      pnpm install --frozen-lockfile
```

**Cache Persistence**:
- Caches are backed by hostPath volumes on macOS
- Location: `~/.cache/manyfold-platform/tekton-caches/`
- Survives cluster deletion and recreation
- Dramatically reduces build times (60-80% faster)

### Build Lifecycle Phases

> **Update 2026-09-23.** The lint configuration moved from the website backend to
> `build/lint/` and the repository parent POM (`build/parent/pom.xml`), shared by both Java
> services. All lint checks now run in `verify`, and Spotless only checks: CI used to run
> `spotless:apply`, which rewrote files instead of failing. The phase table below is the
> original layout.

**Backend (Maven)**:

| Phase | Description | Plugins |
|-------|-------------|---------|
| validate | Check formatting, style | Spotless, Checkstyle |
| compile | Compile Java sources | compiler-plugin |
| test | Run unit tests | Surefire |
| verify | Code coverage, static analysis | JaCoCo, SpotBugs, PMD |
| package | Create JAR | Quarkus plugin |

**Frontend (pnpm)**:

| Command | Description |
|---------|-------------|
| pnpm install | Install dependencies |
| pnpm lint | ESLint checks |
| pnpm format | Prettier formatting |
| pnpm test:unit | Vitest unit tests |
| pnpm build | vue-tsc + Vite production build |

## Implementation Details

### Backend pom.xml Structure

```xml
<project>
  <groupId>dk.manyfold.website</groupId>
  <artifactId>website-backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <quarkus.platform.version>3.30.6</quarkus.platform.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-bom</artifactId>
        <version>${quarkus.platform.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <!-- Core Quarkus dependencies -->
  <dependencies>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-rest</artifactId>
    </dependency>
    <!-- ... -->
  </dependencies>

  <build>
    <plugins>
      <!-- Enforcer, Quarkus, Compiler, Surefire, JaCoCo, Checkstyle, SpotBugs, PMD, Spotless -->
    </plugins>
  </build>
</project>
```

### Frontend package.json Structure

```json
{
  "name": "website-frontend",
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "preview": "vite preview",
    "test:unit": "vitest",
    "lint": "eslint . --fix",
    "format": "prettier --write src/"
  },
  "dependencies": {
    "vue": "^3.5.13",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "vite": "^6.0.5",
    "typescript": "~5.7.2",
    "vitest": "^2.1.8",
    "eslint": "^9.17.0",
    "prettier": "^3.4.2"
  }
}
```

### Directory Structure

```
apps/
└── website/
    ├── backend/
    │   ├── pom.xml                 # Maven build config
    │   ├── checkstyle.xml          # Checkstyle rules
    │   ├── spotbugs-exclude.xml    # SpotBugs exclusions
    │   ├── pmd-ruleset.xml         # PMD rules
    │   ├── eclipse-formatter.xml   # Spotless formatter config
    │   └── src/
    │       ├── main/java/          # Production code
    │       └── test/java/          # Test code
    └── frontend/
        ├── package.json            # pnpm config
        ├── pnpm-lock.yaml          # Lockfile
        ├── vite.config.ts          # Vite config
        ├── eslint.config.js        # ESLint config
        ├── tsconfig.json           # TypeScript config
        └── src/
            ├── components/         # Vue components
            ├── views/              # Page views
            └── router/             # Vue Router
```

## Consequences

### Positive

- **Reproducible Builds**: Lockfiles ensure consistent dependency versions
- **Fast CI Builds**: Persistent caches reduce build times by 60-80%
- **Quality Assurance**: Automated quality gates catch issues early
- **Developer Productivity**: Hot reload in both backend and frontend
- **Clear Standards**: Enforced formatting and style rules
- **Test Coverage**: Minimum thresholds prevent coverage regression
- **Type Safety**: Full TypeScript and Java type checking

### Negative

- **Build Complexity**: Multiple plugins and configuration files
- **Learning Curve**: Developers need to understand both Maven and pnpm
- **Quality Gate Strictness**: May slow down initial development
- **Cache Management**: Persistent caches can become stale or corrupted
- **Tool Versions**: Must keep build tools in sync across environments

### Mitigations

- **Complexity**: Well-documented build commands in CLAUDE.md
- **Learning Curve**: Standard tools that most developers know
- **Strictness**: Quality gates can be bypassed with `-DskipTests` when needed
- **Cache**: Cleanup CronJob periodically clears stale caches
- **Versions**: Devcontainer ensures consistent tool versions

## Alternatives Considered

### Gradle for Backend

- **Pros**: More flexible, Kotlin DSL, faster incremental builds
- **Cons**: More complex, less familiar to team, Quarkus docs prefer Maven
- **Decision**: Rejected - Maven is simpler and more widely understood

### npm for Frontend

- **Pros**: Default Node.js package manager, most examples use npm
- **Cons**: Less strict, larger disk usage, slower
- **Decision**: Rejected - pnpm offers better performance and strictness

### yarn for Frontend

- **Pros**: Good workspaces support, reliable
- **Cons**: Less disk-efficient than pnpm, two package managers to know
- **Decision**: Rejected - pnpm is more efficient

### Bazel for Monorepo

- **Pros**: Excellent caching, language-agnostic, scales well
- **Cons**: Complex setup, steep learning curve, overkill for current size
- **Decision**: Rejected - too complex for current needs

### GitHub Actions for CI Caching

- **Pros**: Built-in caching, widely used
- **Cons**: Not self-hosted, using Tekton for CI
- **Decision**: Rejected - committed to Tekton (ADR-0006)

## References

- [Maven Documentation](https://maven.apache.org/guides/)
- [Quarkus Maven Plugin](https://quarkus.io/guides/maven-tooling)
- [pnpm Documentation](https://pnpm.io/)
- [Vite Documentation](https://vite.dev/)
- [ADR-0004: Application Stack Selection](0004-application-stack-tool-selection.md)
- [ADR-0006: CI/CD Tooling Selection](0006-cicd-tooling-selection.md)
- Backend configuration: `apps/website/backend/pom.xml`
- Frontend configuration: `apps/website/frontend/package.json`
- Tekton tasks: `infrastructure/tekton/base/tasks/`

## Notes

This decision was made during Phase 1 of platform development (January 2026). The build system balances reproducibility, performance, and developer experience. Revisit if:

- Build times become unacceptable despite caching
- Quality gates prove too restrictive for rapid iteration
- Team expertise shifts toward Gradle or other tools
- Monorepo tooling (Nx, Turborepo) becomes beneficial
