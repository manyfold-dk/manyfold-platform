# ADR 0004: Application Stack Tool Selection

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Implementation Details](#implementation-details)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [Amendments](#amendments)
  - [Nginx Removal — Frontend Served by Quarkus (2026-04)](#nginx-removal--frontend-served-by-quarkus-2026-04)
  - [Second App Path Prefix Removal (2026-04)](#second-app-path-prefix-removal-2026-04)
  - [Second App Cloud HTTPRoute Migration (2026-04)](#second-app-cloud-httproute-migration-2026-04)
- [References](#references)
- [Notes](#notes)

## Status

Accepted

## Context

The Manyfold Platform's first application will be an API-driven website for the Manyfold brand. This requires selecting appropriate frameworks and tools for both backend API services and frontend user interfaces. The choices must align with our core principles of automation, quality, observability, and cloud-native deployment while considering the developer's experience level and learning goals.

### Key Considerations

1. **Architecture Pattern**: API-driven microservices with separate backend and frontend deployments
2. **Cloud-Native Requirements**: Kubernetes-optimized, containerized, observable, self-healing
3. **Developer Experience**: Java background, learning-oriented platform engineering project
4. **Quality Standards**: Automated testing, linting, formatting, type safety
5. **Deployment Efficiency**: Fast startup times, low memory footprint, scalability
6. **Observability**: Built-in metrics, health checks, tracing from day one

## Decision

We will use the following technology stack:

### Backend: Quarkus + Reactive + MicroProfile + GraalVM Native
- **Framework**: Quarkus with MicroProfile specifications
- **Programming Model**: Reactive-first with RESTEasy Reactive and Mutiny
- **Build Tool**: Maven
- **Compilation**: GraalVM native executables (preferred for production)
- **Language**: Java (Latest LTS, currently Java 25)

### Frontend: Vue 3 + Vite + TypeScript
- **Framework**: Vue 3 with Composition API
- **Build Tool**: Vite
- **Language**: TypeScript (preferred)
- **Package Manager**: pnpm or npm

## Rationale

### Backend: Why Quarkus + Reactive + GraalVM?

**Reactive-First Approach**:
- **RESTEasy Reactive (Quarkus REST)**: Non-blocking REST endpoints built on reactive principles
  - Better resource utilization through non-blocking I/O
  - Higher throughput with same resources (more concurrent requests per CPU)
  - Natural fit for cloud-native, event-driven architectures
- **Mutiny**: Intuitive reactive programming library designed for Quarkus
  - Easier to learn than RxJava or Project Reactor
  - Built-in integration with Quarkus ecosystem
  - Uni (single item) and Multi (streams) abstractions
  - Declarative composition of asynchronous operations
- **Hibernate Reactive**: Non-blocking database access
  - Reactive database drivers for PostgreSQL, MySQL, etc.
  - Maintains JPA-like API with reactive semantics
- **Imperative Fallback**: Use blocking APIs when reactive doesn't provide clear benefit
  - Simple CRUD operations without complex composition
  - Integration with blocking third-party libraries
  - Quarkus handles both models seamlessly

**Kubernetes Optimization**:
- Native executables start in milliseconds (vs seconds for JVM)
- Low memory footprint (~10-20 MB per service) enables higher pod density
- Fast startup enables rapid auto-scaling and self-healing
- Reactive model maximizes throughput per pod, reducing infrastructure costs

**Cloud-Native Features**:
- MicroProfile specifications provide standardized patterns for:
  - Health checks (liveness/readiness probes)
  - Metrics (Prometheus integration)
  - OpenTracing (distributed tracing)
  - Config (environment-based configuration)
  - OpenAPI (automatic API documentation)
- Built-in Kubernetes integration and deployment descriptors

**Developer Experience**:
- Developer leverages existing Java expertise
- Live reload in dev mode for fast iteration
- Extensive documentation and growing ecosystem
- Maven familiarity (though new to Java ecosystem specifics)
- Mutiny's intuitive API lowers reactive programming learning curve

**Quality & Standards**:
- Java ecosystem provides mature tooling (Checkstyle, SpotBugs, PMD, Spotless)
- Strong typing and compile-time safety
- Google Java Style Guide provides clear coding standards

**Testing with AssertJ**:
- Fluent, readable assertion API with excellent IDE autocomplete
- Rich set of assertions for collections, exceptions, and complex objects
- Better error messages than Hamcrest when assertions fail
- Native Java 8+ support with lambda-friendly assertions
- Widely adopted in modern Java projects as the preferred assertion library

### Frontend: Why Vue 3?

**Developer Experience**:
- **Approachable Learning Curve**: Simpler and more intuitive than React, especially for developers new to modern frontend frameworks
- **Excellent Documentation**: Vue's official documentation is comprehensive and beginner-friendly
- **Progressive Framework**: Start simple, add complexity as needed (no overwhelming boilerplate)

**Modern Features**:
- Composition API provides clean, composable logic
- Reactive system is intuitive and performant
- Strong TypeScript support for type safety
- Single File Components (SFC) co-locate template, logic, and styles

**Developer Tooling**:
- Vite provides instant hot module replacement (HMR)
- Fast builds and optimized production bundles
- Official Vue DevTools for debugging

**Ecosystem**:
- Vue Router for routing
- Pinia for state management (if needed)
- Large component library ecosystem (Vuetify, PrimeVue, etc.)
- Active community and regular updates

### Why Not React?

React was considered but Vue was chosen for:
- **Simpler mental model**: Less to learn upfront (no JSX quirks, fewer hooks patterns)
- **Less boilerplate**: Cleaner component syntax with SFC
- **Better DX for beginners**: More intuitive reactivity and state management
- **Learning goals**: Vue skills are still highly marketable while being easier to learn

### Why Not Svelte?

Svelte was considered but Vue was chosen for:
- **Larger ecosystem**: More libraries, components, and community support
- **Maturity**: Vue 3 is more battle-tested in production
- **TypeScript integration**: Vue has more mature TS support
- **Corporate backing**: Vue has broader industry adoption

### Why Vite?

- **Performance**: Instant server start, lightning-fast HMR
- **Modern**: Native ESM, optimized builds with Rollup
- **Ecosystem**: First-class support for Vue, TypeScript, and testing tools
- **Standards-aligned**: Embraces web standards rather than custom bundler abstractions

### Why TypeScript?

- **Type Safety**: Catch errors at compile time, especially important for API contracts
- **Developer Experience**: Better autocomplete, refactoring, and documentation
- **Industry Standard**: TypeScript is becoming the default for frontend development
- **API Integration**: Types ensure frontend/backend API contract alignment

## Implementation Details

### Backend Stack
- **Framework**: Quarkus 3.x+ with MicroProfile
- **REST Layer**: RESTEasy Reactive (Quarkus REST) for non-blocking endpoints
- **Reactive Library**: Mutiny for reactive streams and composition
- **Data Access**: Hibernate Reactive with reactive database drivers
- **Build**: Maven with multi-module project structure
- **Runtime**: GraalVM for native compilation (JVM mode for development)
- **Testing**: JUnit 5, REST-assured for API testing, AssertJ for assertions, Testcontainers for integration tests
- **Quality**: Checkstyle, SpotBugs, PMD, Spotless
- **Containerization**: Multi-stage Docker builds producing native executables

### Frontend Stack
- **Framework**: Vue 3.x+ with Composition API
- **Build**: Vite 5.x+
- **Language**: TypeScript (strict mode)
- **Package Manager**: pnpm (preferred for speed and disk efficiency)
- **Testing**: Vitest (unit), Playwright (e2e)
- **Quality**: ESLint (code quality), Prettier (formatting)
- **Containerization**: Multi-stage Docker builds with nginx for static asset serving

### Development Workflow
- **Backend**: `mvn quarkus:dev` for live reload development
- **Frontend**: `pnpm dev` for Vite dev server with HMR
- **Testing**: Automated tests run in CI pipeline before deployment
- **Building**: Native compilation for backend, optimized production build for frontend
- **Deployment**: Separate container images deployed independently via GitOps

## Consequences

### Positive

**Backend (Quarkus + Reactive + GraalVM)**:
- Excellent Kubernetes performance and resource efficiency
- Reactive model maximizes throughput and reduces resource consumption
- Built-in observability reduces custom instrumentation work
- Leverages existing Java knowledge while learning cloud-native and reactive patterns
- Strong ecosystem for quality tooling and standards enforcement
- Native compilation produces production-ready, secure binaries
- Mutiny's intuitive API makes reactive programming more approachable

**Frontend (Vue 3 + Vite)**:
- Fast learning curve with excellent documentation
- Modern developer experience with instant feedback
- TypeScript provides safety for API integration
- Easy to containerize and deploy
- Scalable from simple to complex applications

**Overall**:
- Polyglot architecture (Java backend, TypeScript frontend) demonstrates platform flexibility
- Both stacks are Kubernetes-friendly and cloud-native
- Clear separation of concerns enables independent scaling and deployment
- Observability built-in from day one

### Negative

**Backend**:
- GraalVM native compilation adds build time (5-10 minutes vs seconds for JVM)
- Native compilation has some limitations (reflection, dynamic class loading)
- Steeper learning curve for MicroProfile specifications and reactive programming
- Reactive programming requires different mental model than imperative code
- Debugging reactive code can be more challenging
- Larger container images during development (JVM mode)

**Frontend**:
- Smaller ecosystem than React (though still large)
- Less corporate backing than React or Angular
- Potential need to learn multiple frontend frameworks if team grows

**Overall**:
- Polyglot setup requires multiple toolchains (Maven + npm/pnpm)
- Different testing frameworks and patterns for backend vs frontend
- API contract management requires discipline (OpenAPI helps)

### Mitigations

- Use Quarkus dev mode (JVM) for rapid development, native compilation for production only
- Leverage Quarkus's automatic GraalVM configuration to minimize native compilation issues
- Start with simple reactive patterns, use imperative code where reactive doesn't provide clear benefit
- Document reactive patterns and common use cases in runbooks
- Use Mutiny's excellent documentation and Quarkus guides for learning
- Use OpenAPI specification as contract between frontend and backend
- Generate TypeScript types from OpenAPI spec for type-safe API integration
- Document patterns and best practices for both stacks in runbooks
- Use Testcontainers for integration testing to catch issues before deployment

## Alternatives Considered

### Backend Alternatives

**Spring Boot**:
- Rejected: Slower startup, higher memory footprint than Quarkus
- Quarkus specifically designed for Kubernetes and native compilation

**Micronaut**:
- Rejected: Smaller ecosystem, less MicroProfile standardization
- Quarkus has better GraalVM native support and community
- Quarkus has more intuitive reactive story with Mutiny

**JAX-RS (Blocking REST)**:
- Rejected in favor of RESTEasy Reactive (Quarkus REST)
- Blocking I/O is less efficient for I/O-heavy cloud workloads
- Reactive approach better utilizes resources in containerized environments
- RESTEasy Reactive provides same programming model with better performance

**Go**:
- Rejected: Would require learning new language, losing Java expertise leverage
- Polyglot approach already achieved with Vue frontend

### Frontend Alternatives

**React**:
- Rejected: Steeper learning curve, more boilerplate
- Vue provides similar capabilities with better beginner experience

**Svelte**:
- Rejected: Smaller ecosystem, less mature TypeScript support
- Vue more battle-tested for production applications

**Angular**:
- Rejected: Too heavyweight and opinionated for initial learning
- Vue's progressive nature better for experimentation

**Server-Side Rendering (Next.js, Nuxt)**:
- Deferred: Start with SPA pattern, add SSR if SEO/performance requires
- Keeps initial architecture simpler

## Amendments

### Nginx Removal — Frontend Served by Quarkus (2026-04)

The original implementation details described a two-container deployment — Quarkus for the API and a separate nginx container serving the Vue SPA. This has been removed. Frontend static assets are now placed under `META-INF/resources/` and served directly by Quarkus. Each application ships as a single container that handles both the REST API and the SPA.

A Vert.x `@RouteFilter` takes the place of nginx's `try_files` directive: it provides SPA catch-all routing (falling back to `index.html` for unknown paths), cache headers for content-hashed assets, security headers, and response compression. The nginx containers were unnecessary complexity: external routing is already handled at the gateway layer (Cilium + oauth2-proxy), so the only effect of keeping nginx was an extra proxy hop and a recurring header-buffer bug where large OAuth2 tokens exceeded nginx's default 1 KB buffer size. The `Implementation Details` section's reference to "nginx for static asset serving" is superseded by this change.

### Second App Path Prefix Removal (2026-04)

The frontend of a second application on the platform was originally served under a `/<app>/` path prefix in the local cluster, which required a `VITE_BASE_URL` build-time variable to differ between local and cloud environments and URLRewrite filters in the HTTPRoute manifests. This path prefix has been removed. Local cluster routing now uses a dedicated `<app>.localhost` hostname (matching the cloud subdomain pattern) so a single build artifact works in every environment without any path rewriting. This eliminates environment-specific `VITE_BASE_URL` variation and simplifies the HTTPRoute definitions.

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); the `.localhost` routing above went with it.

### Second App Cloud HTTPRoute Migration (2026-04)

The second application's cloud ingress previously relied on Tailscale-only access with nginx handling all internal routing inside the pod. It now uses the same Cilium Gateway HTTPRoute pattern as the website: path-based splitting at the gateway level routes `/api/` and `/q/` to the Quarkus backend and all remaining paths to the frontend. This eliminates nginx as the sole routing layer, enables proper gateway-level observability and policy, and keeps that application's deployment consistent with the rest of the platform.

## References

- [Quarkus Official Documentation](https://quarkus.io/)
- [Quarkus Reactive Architecture](https://quarkus.io/guides/quarkus-reactive-architecture)
- [RESTEasy Reactive Guide](https://quarkus.io/guides/resteasy-reactive)
- [Mutiny Documentation](https://smallrye.io/smallrye-mutiny/)
- [Hibernate Reactive](https://hibernate.org/reactive/)
- [MicroProfile Specifications](https://microprofile.io/)
- [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/)
- [Vue 3 Documentation](https://vuejs.org/)
- [Vite Documentation](https://vitejs.dev/)
- [TypeScript Handbook](https://www.typescriptlang.org/docs/)
- [Kubernetes-Native Java with Quarkus](https://developers.redhat.com/e-books/kubernetes-native-microservices-quarkus)

## Notes

This decision was made during initial application setup (January 2026). As the platform evolves, this decision should be revisited if:
- Native compilation proves too restrictive or time-consuming
- Frontend complexity requires a different framework or SSR approach
- Team composition changes require different skill set priorities
- Performance characteristics don't meet production requirements
- Ecosystem maturity or support significantly changes
