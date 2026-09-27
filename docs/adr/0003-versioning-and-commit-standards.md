# ADR 0003: Versioning and Commit Standards

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [Implementation Plan](#implementation-plan)
- [Alternatives Considered](#alternatives-considered)
- [References](#references)
- [Notes](#notes)

## Status

Accepted

## Context

The Manyfold Platform needs clear versioning and commit message standards to:
- Communicate the impact of changes (breaking vs backward-compatible)
- Enable automated version management and release processes
- Generate changelogs automatically
- Facilitate rollbacks and debugging
- Maintain clear project history

Without standardized versioning and commit conventions, it's difficult to:
- Determine what version to release
- Understand what changed between versions
- Automate release workflows
- Track down when bugs were introduced

### Key Considerations

1. **Semantic Versioning**: Clear communication of breaking vs non-breaking changes
2. **Automation**: Versioning should integrate with CI/CD pipelines
3. **Changelog Generation**: Release notes should be automatically generated
4. **Developer Experience**: Commit format should be easy to learn and use
5. **Tooling Support**: Standards should have mature tooling ecosystem
6. **Backward Compatibility**: Changes should be clearly marked

## Decision

We will adopt **Semantic Versioning 2.0.0** combined with **Conventional Commits 1.0.0** to enable fully automated version management and changelog generation.

### 1. Semantic Versioning (SemVer)

All services, libraries, and APIs will follow **Semantic Versioning 2.0.0** (MAJOR.MINOR.PATCH):

- **MAJOR** version: Increment for incompatible API changes or breaking changes
- **MINOR** version: Increment for backward-compatible functionality additions
- **PATCH** version: Increment for backward-compatible bug fixes

**Examples** (`X.Y.Z` stands for any current version):
- MAJOR `1`, MINOR `0`, PATCH `0` → Initial stable release
- `X.Y.Z` → `X.(Y+1).0`: New feature added (backward compatible)
- `X.Y.Z` → `X.Y.(Z+1)`: Bug fix
- `X.Y.Z` → `(X+1).0.0`: Breaking change

**Pre-release versions**:
- Development: `X.Y.Z-SNAPSHOT`
- Release candidates: `X.Y.Z-rc.1`
- Beta releases: `X.Y.Z-beta.1`

**Version Scope**:
- **Applications/Services**: Independent versioning per service
- **Libraries** (in `/libs/`): Semantic versioning for library artifacts
- **APIs**: Major version in URL path (see ADR-0002)

**Automated Versioning**: Version bumps are **fully automated** based on Conventional Commits (see below).

### 2. Conventional Commits

All Git commits **must** follow the **Conventional Commits 1.0.0** specification to enable automated versioning, changelog generation, and clear communication.

**Format**: `<type>[optional scope]: <description>`

**Optional body and footer(s)** may be provided for additional context.

#### Commit Types

**Mandatory types** (trigger version bumps):
- `feat`: A new feature (triggers MINOR version bump)
- `fix`: A bug fix (triggers PATCH version bump)

**Additional types** (no version bump, but included in changelog):
- `docs`: Documentation changes only
- `style`: Code style changes (formatting, whitespace, no logic change)
- `refactor`: Code refactoring (no feature change or bug fix)
- `perf`: Performance improvements
- `test`: Adding or updating tests
- `build`: Changes to build system or dependencies (Maven, Docker, etc.)
- `ci`: Changes to CI/CD configuration (GitHub Actions, ArgoCD, etc.)
- `chore`: Maintenance tasks (updating dependencies, etc.)
- `revert`: Reverting a previous commit

**Breaking changes** (trigger MAJOR version bump):
- Add `BREAKING CHANGE:` in the commit footer, OR
- Append `!` after the type/scope: `feat!: remove deprecated API`

#### Scope (Optional)

Scopes provide context about what part of the codebase changed.

**Examples**:
- `feat(auth): add OAuth2 login support`
- `fix(api): handle null pointer in user endpoint`
- `docs(readme): update installation instructions`
- `build(maven): upgrade Quarkus to 3.6.0`

**Recommended scopes** for this platform:
- Service names: `web-frontend`, `user-service`, `api-gateway`
- Functional areas: `auth`, `api`, `database`, `monitoring`
- Infrastructure: `k8s`, `helm`, `ci`, `docker`

#### Description Guidelines

- Use imperative mood: "add feature" not "added feature"
- Lowercase first letter (after the colon)
- No period at the end
- Keep under 70 characters
- Be clear and concise

#### Body (Optional)

Provide additional context about the change:
- Why the change was made
- What problem it solves
- Any side effects or trade-offs

#### Footer (Optional)

Used for:
- **Breaking changes**: `BREAKING CHANGE: removed support for API v1`
- **Issue references**: `Closes #123`, `Fixes #456`, `Refs #789`
- **Reviewer acknowledgments**: `Reviewed-by: John Doe`

#### Examples

**Feature with scope**:
```
feat(user-service): add email verification endpoint

Implements email verification flow for new user registrations.
Users receive a verification link via email that expires in 24 hours.

Closes #234
```

**Bug fix**:
```
fix(api-gateway): prevent rate limit bypass

Previously, rate limiting could be bypassed by changing the
User-Agent header. This fix validates rate limits based on
client IP address instead.

Fixes #567
```

**Breaking change**:
```
feat(auth)!: migrate to OAuth2

BREAKING CHANGE: The legacy basic auth endpoints have been removed.
All clients must now use OAuth2 authentication flow.

Migration guide: docs/migration/oauth2.md

Closes #890
```

**Chore (no version bump)**:
```
chore(deps): upgrade Maven dependencies

Updates all patch-level dependency versions.
```

**Performance improvement**:
```
perf(database): optimize user query with indexed fields

Reduces query time from 500ms to 50ms by adding composite index
on (email, status) columns.
```

### 3. Automated Tooling

#### Enforcement

**commitlint**: Validates commit messages in CI/CD pipeline
- Configuration file: `.commitlintrc.json`
- Runs on every commit in PR
- Blocks merge if commit messages don't follow convention

**Husky** (Optional): Pre-commit hook to validate locally
- Optional for developers (not mandatory)
- Provides immediate feedback before push
- Reduces CI failures

#### Automation

**semantic-release** or **release-please**: Automatically determines version bumps and generates changelogs based on commits
- Analyzes commits since last release
- Calculates next version number
- Generates CHANGELOG.md
- Creates Git tags
- Publishes releases

**CI/CD integration**: Version bump and release triggered automatically on merge to `main`
- Developer merges PR to main
- CI analyzes commits
- Version bumped automatically
- Changelog updated
- Container images tagged with new version
- GitOps deployment triggered

#### Version Calculation

- `feat` commits → MINOR bump (`X.Y.Z` → `X.(Y+1).0`)
- `fix` commits → PATCH bump (`X.Y.Z` → `X.Y.(Z+1)`)
- `BREAKING CHANGE` → MAJOR bump (`X.Y.Z` → `(X+1).0.0`)
- Other types → No version bump (appear in changelog only)

**Examples**:
- Current version: `X.Y.Z`
- Commits since last release: `fix`, `docs`, `feat`
- Next version: `X.(Y+1).0` (MINOR bump due to `feat`)

- Current version: `X.Y.Z`
- Commits since last release: `fix`, `fix`, `chore`
- Next version: `X.Y.(Z+1)` (PATCH bump due to `fix`)

- Current version: `X.Y.Z`
- Commits since last release: `feat!`, `fix`
- Next version: `(X+1).0.0` (MAJOR bump due to breaking change)

### 4. Changelog Generation

Changelogs are automatically generated from commit messages and organized by type.

**Example CHANGELOG.md**:
```markdown
# Changelog

## [X.(Y+1).0] - 2026-01-15

### Features
- **user-service**: add email verification endpoint (#234)
- **api-gateway**: implement rate limiting (#245)

### Bug Fixes
- **auth**: fix token expiration validation (#567)
- **database**: prevent connection pool exhaustion (#589)

### Performance Improvements
- **database**: optimize user query with indexed fields (#601)

### Documentation
- **readme**: update installation instructions (#612)

## [X.Y.(Z+1)] - 2026-01-10

### Bug Fixes
- **api-gateway**: prevent rate limit bypass (#567)
- **frontend**: fix responsive layout on mobile (#578)
```

## Consequences

### Positive

- **Clear Change Communication**: SemVer communicates impact of changes instantly
- **Automated Versioning**: No manual version bump decisions
- **Generated Changelogs**: Release notes created automatically
- **Developer Experience**: Clear commit format reduces cognitive overhead
- **Rollback Capability**: Easy to identify which version to roll back to
- **Dependency Management**: Clear understanding of breaking vs safe updates
- **CI/CD Integration**: Fully automated release pipeline
- **Audit Trail**: Complete history of what changed and why

### Negative

- **Learning Curve**: Developers must learn Conventional Commits format
- **Commit Message Discipline**: Requires careful commit message writing
- **Tooling Setup**: Initial effort to configure commitlint and semantic-release
- **Potential for Mistakes**: Incorrect commit type can trigger wrong version bump

### Mitigations

- Document standards in CLAUDE.md and developer onboarding
- Implement commitlint in CI/CD to enforce Conventional Commits
- Optional Husky pre-commit hooks for local validation
- IDE plugins for Conventional Commits (IntelliJ, VSCode)
- Provide commit message templates and examples
- Code review process to catch incorrect commit types
- Allow manual override for version bumps if needed (emergency use)

## Implementation Plan

1. **Phase 1**: Document standards (this ADR + CLAUDE.md update)
2. **Phase 2**: Set up Conventional Commits tooling
   - Add `.commitlintrc.json` configuration
   - Configure commitlint in CI/CD
   - Create commit message templates
   - Optional: Set up Husky for local validation
3. **Phase 3**: Configure automated versioning
   - Choose between semantic-release or release-please
   - Configure CI/CD to trigger releases on merge to main
   - Set up CHANGELOG.md generation
   - Test automated version bumping
4. **Phase 4**: Create documentation and examples
   - Conventional Commits cheat sheet
   - Example commit messages for common scenarios
   - Troubleshooting guide
5. **Phase 5**: Apply to all services
   - Tag current versions
   - Begin using automated versioning for new changes

## Alternatives Considered

### Alternative: Non-Semantic Versioning (Calendar Versioning)

**Format**: `YYYY.MM.DD` or `YYYY.MM.PATCH`

**Rejected Because**:
- Doesn't communicate backward compatibility
- Less clear about breaking changes
- SemVer is industry standard for libraries and APIs
- Better alignment with dependency management tools
- Harder to determine safe upgrade paths

### Alternative: Manual Version Bumping

Developers manually decide version numbers.

**Rejected Because**:
- Prone to human error
- Inconsistent versioning decisions
- Requires coordination across team
- Slower release process
- No automation benefits

### Alternative: Unstructured Commit Messages

Allow free-form commit messages without conventions.

**Rejected Because**:
- Cannot automate version bumping
- Cannot generate changelogs
- Harder to understand project history
- Poor integration with tooling
- Inconsistent communication

### Alternative: GitFlow with Manual Releases

Use GitFlow branching with manual version management.

**Rejected Because**:
- More complex branching model
- Manual version management overhead
- Slower release cycle
- Conventional Commits works well with simpler trunk-based development

## References

- [Semantic Versioning 2.0.0](https://semver.org/)
- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
- [Conventional Commits Cheatsheet](https://gist.github.com/qoomon/5dfcdf8eec66a051ecd85625518cfd13)
- [The Ultimate Guide to Microservices Versioning Best Practices](https://www.opslevel.com/resources/the-ultimate-guide-to-microservices-versioning-best-practices)
- [commitlint - Lint commit messages](https://commitlint.js.org/)
- [semantic-release - Automated version management](https://github.com/semantic-release/semantic-release)
- [release-please - Automated releases based on Conventional Commits](https://github.com/googleapis/release-please)
- [ADR-0002: Resource Naming Conventions](0002-resource-naming-conventions.md) - Related naming decisions

## Notes

This ADR establishes versioning and commit standards for the Manyfold Platform. These standards enable fully automated version management and release workflows.

**Review Triggers**:
- Automation tooling changes or improvements
- Team feedback on commit message friction
- Discovery of versioning edge cases
- Integration issues with semantic-release or release-please
- Scaling to multiple teams with different release cadences
