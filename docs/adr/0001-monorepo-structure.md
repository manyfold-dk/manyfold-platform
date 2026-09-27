# ADR 0001: Monorepo Structure

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Repository Structure](#repository-structure)
- [Implementation Details](#implementation-details)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [References](#references)
- [Notes](#notes)

## Status

Accepted

Narrowed 2026-08-09 by ADR-0049 (private) for exactly one app. That app moved to a
repository of its own because a group-facing agent needs write access to that site and
nothing else, and GitHub App permissions are per-repository, not per-path. This is not a
move toward polyrepo: every argument below still holds, and the rule stands for every
other app.

## Context

The Manyfold Platform aims to host multiple applications (3-5 anticipated in the near term) alongside shared platform infrastructure and common libraries. We needed to decide between a monorepo approach (single repository containing all code) versus a polyrepo approach (separate repositories for each service/component).

### Key Considerations

1. **Code Sharing**: High importance on sharing Java libraries and utilities across services
2. **Team Structure**: Single owner/developer (no multi-team coordination complexity)
3. **Deployment Cadence**: Independent releases desired for different services
4. **Technology Stack**: Primarily Java with potential for polyglot future
5. **Development Workflow**: GitOps-driven with automation focus

### Evaluation

**Monorepo Advantages**:
- Simplified dependency management for shared code (no versioning/publishing overhead)
- Atomic commits across shared libraries and consuming applications
- Single source of truth for the entire platform
- Easier to enforce consistent standards (linting, formatting, testing)
- Simplified local development setup (one clone, one IDE workspace)
- Better visibility into cross-service impacts
- No coordination overhead with single developer

**Monorepo Challenges**:
- Potential for larger repository size over time
- Risk of tight coupling between services if not disciplined
- Build complexity requiring proper tooling (Gradle multi-module)
- Need for path-based CI/CD filtering

**Polyrepo Advantages**:
- Strict service boundaries and ownership
- Independent repository history and permissions
- Smaller, focused repositories

**Polyrepo Challenges**:
- Complex dependency management (must publish/version shared libraries)
- Coordinating changes across repositories
- Multiple clones required for full system development
- Difficult to make atomic changes across service boundaries
- Higher cognitive overhead with multiple repos

## Decision

We will use a **monorepo structure** for the Manyfold Platform.

### Rationale

1. **Code Sharing Priority**: The "very important" need for code sharing across 3-5 services strongly favors monorepo. Publishing and versioning shared libraries across multiple repositories would create unnecessary overhead.

2. **Single Developer Efficiency**: As a solo developer, the coordination benefits of polyrepo (team independence) don't apply, while the simplicity of monorepo (single clone, unified view) provides significant efficiency gains.

3. **Independent Deployments Achievable**: Modern GitOps tools (ArgoCD/Flux) and CI/CD path filtering enable independent service deployments despite a single repository.

4. **Atomic Changes**: When modifying shared libraries, we can update all consumers in a single commit, ensuring consistency and avoiding version skew.

5. **Future Flexibility**: Starting with monorepo doesn't prevent future extraction of services to separate repositories if team structure changes or services become truly independent products.

## Repository Structure

```
manyfold-platform/
├── apps/                    # Application services (independently deployable)
├── libs/                    # Shared libraries and utilities
├── platform/                # Platform infrastructure configs
├── infrastructure/          # Infrastructure as Code
├── docs/                    # Documentation and ADRs
├── scripts/                 # Automation scripts
└── .github/workflows/       # CI/CD pipelines
```

See `README.md` for detailed directory structure.

## Implementation Details

> **Amendment (2026-06-12):** The build-system details below (Gradle multi-module,
> `libs/` as Gradle subprojects) were superseded by
> [ADR-0010: Build System and Dependency Management](0010-build-system-and-dependency-management.md)
> (Maven). The monorepo-structure decision itself stands. `libs/` is currently empty
> and unused.

### Build System
- **Tool**: Gradle multi-module
- **Structure**: Root `build.gradle` with subprojects for each app and library
- **Incremental Builds**: Only rebuild changed modules and their dependents

### CI/CD Strategy
- Path-based filtering in GitHub Actions (or chosen CI platform)
- Example: Changes to `apps/website/**` trigger only website build/test/deploy
- Changes to `libs/common-utils/**` trigger rebuilds of all dependent apps

### GitOps Deployment
- ArgoCD or Flux configured with separate Applications per service
- Each Application watches its specific directory in the monorepo
- Services deploy independently based on directory changes
- Platform infrastructure managed separately from applications

### Dependency Management
- Shared libraries in `/libs/` consumed as Gradle subprojects
- No need for artifact publishing/versioning for internal libraries
- External dependencies managed via Gradle dependency resolution

## Consequences

### Positive
- Simplified development workflow (single clone, single build command)
- Easy refactoring across service boundaries
- Consistent tooling and standards across all code
- Faster iteration on shared libraries (no publish/version cycle)
- Better discoverability of code and patterns

### Negative
- Requires discipline to maintain service boundaries
- Build system must be properly configured for incremental builds
- Git operations may become slower with repository growth (mitigated by shallow clones, sparse checkouts if needed)
- CI/CD must be carefully configured with path filtering

### Mitigations
- Document service boundaries and architectural principles
- Use Gradle's incremental build capabilities
- Implement path-based CI/CD triggering from day one
- Monitor repository size and consider Git LFS for large binaries
- Maintain clear separation between `/apps/`, `/libs/`, and `/platform/` directories

## Alternatives Considered

### Polyrepo with Published Libraries
Rejected because:
- High overhead for single developer to manage multiple repos
- Complexity of versioning and publishing shared libraries
- Difficult to make atomic changes across service boundaries
- Our "very important" code sharing needs favor tight integration

### Monorepo with Separate Deployment Repos
Rejected because:
- Adds complexity without clear benefit in our context
- GitOps tools already support monorepo patterns well
- Would still need to solve code sharing within development repo

## References

- [Monorepo Tools](https://monorepo.tools/)
- [Google's Monorepo Philosophy](https://cacm.acm.org/magazines/2016/7/204032-why-google-stores-billions-of-lines-of-code-in-a-single-repository/fulltext)
- [Gradle Multi-Module Projects](https://docs.gradle.org/current/userguide/multi_project_builds.html)
- [ArgoCD Monorepo Support](https://argo-cd.readthedocs.io/en/stable/user-guide/directory/)

## Notes

This decision was made during initial platform setup (January 2026). As the platform evolves and potentially scales to larger teams, this decision should be revisited if:
- Team grows beyond 5-10 people
- Services become truly independent products with separate lifecycles
- Repository size causes significant performance issues
- Service ownership models require strict repository-level access control
