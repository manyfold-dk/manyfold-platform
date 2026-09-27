# ADR 0055: Upstream Versions in the Public Platform Repository

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [Alternatives considered](#alternatives-considered)
- [References](#references)

## Status

Accepted (2026-09-23), on the owner's decision. A recorded deviation from the baseline
publication rule PUBLISH-02, scoped to the public platform repository of
[ADR-0054](0054-public-platform-and-private-instance-repositories.md).

## Context

PUBLISH-02 says a public repository receives no exact version in use; the publication gate
enforces it as the `exact version` shape. For `estate-baseline` that cost little: its versions are
CI tools, allowed file by file. The public platform repository is different. Its generic content
is mostly pinned upstream components: Crossplane provider packages, Helm chart and application
versions in values, operator images, demo-cluster tooling. A dry run of the generic directories
on 2026-09-23 found 164 version hits.

Keeping every production version private would mean each pinned upstream component in the public
repository needs a patch in the instance repository that restores the real version, for every provider and chart.
The public repository would then pin nothing real, and it would no longer read as a working
GitOps repository, which is what the owner wants it to show.

## Decision

1. **Upstream open-source component versions may be public** in the platform repository, even
   when production runs the same version: provider and function packages, chart versions, the
   upstream images a chart or manifest names, demo and CI tooling.
2. **This estate's own versions stay private**, as ADR-0054 D4 already says for image tags: tags
   and digests of images built from this estate's source, deploy commits, and anything the deploy
   engines bump. They live in the instance repository's overlays.
3. **Other production detail stays private**: sizes, node types, hostnames, addresses, and which
   components run where. A version is allowed; the instance around it is not.
4. **The gate still runs on every file.** Upstream versions pass through `exact version` rows in
   the repository's `.publish-allow.tsv`, file by file, each group with a comment that names
   this ADR. A version outside an allowed file is still a stop.

## Consequences

- Anyone can read which upstream versions the platform pins, and so its patch level for those
  components. Renovate keeps them current; the exposure is the time between a disclosed
  vulnerability and its bump, which exists whether or not the pin is public, because running
  versions are also observable from outside for some components.
- The public repository renders as it runs: the same pins, no hidden patches.
- The allow file grows with the repository. Review of `.publish-allow.tsv` is part of every
  population pull request.

## Alternatives considered

- **Strict PUBLISH-02** (production versions only in the instance repository, patched in): rejected by the owner
  for the cost and for the resulting public repository, which would not show the real pins.
- **A different public version set** (the demo pinned independently of production): keeps the
  letter of the rule, but two version sets drift, and the public one would not be the one that is
  tested in production.

## References

- [ADR-0054](0054-public-platform-and-private-instance-repositories.md) (D4: no image tag of
  this estate's builds on the public side)
- The public platform split design of 2026-09-23 (private), population rules
