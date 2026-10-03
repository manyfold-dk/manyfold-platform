---
status: accepted
deviation-from: baseline v1.1
field: manifest.image-digest
tenant-value: "<image>, without tag or digest, in the bases of the applications an installation's CI builds"
baseline-value: "<image>@sha256:<digest>"
reason: "this repository builds and deploys no image; the installation's overlay sets the tag or digest of each image its CI builds from these sources"
reconverge: "when the checker reads the rendered workload instead of the base file, or when these bases no longer name an image that an installation builds"
paths:
  - apps/accounting-mcp/base/deployment.yaml
  - apps/slack-bot/base/deployment.yaml
  - apps/website/base/backend-deployment.yaml
---

# ADR 0058: Untagged Images in Application Bases

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-10-03), on the owner's decision. A recorded deviation from the baseline
conformance rule `manifest.image-digest`, in the form the
[conformance README](https://github.com/manyfold-dk/estate-baseline/blob/main/scripts/conformance/README.md#deviation-adr-contract)
prescribes. The frontmatter above is what the conformance checker parses. Its `paths:` limits
the deviation to the three files it names.

## Context

The rule `manifest.image-digest` requires every workload `image:` line to be `@sha256:`-pinned.
The checker reads each manifest file as it is, not the workload that Kustomize renders.

Three application bases in this repository name an image without a tag or a digest:

| File | Image name |
|---|---|
| `apps/accounting-mcp/base/deployment.yaml` | `.../accounting-mcp` |
| `apps/slack-bot/base/deployment.yaml` | `.../slack-bot` |
| `apps/website/base/backend-deployment.yaml` | `.../website-backend` |

An installation's CI builds these images from the sources under `apps/`. The installation's
overlay then sets the tag, or the tag and digest, with a Kustomize `images:` entry. This
repository builds no image and deploys nothing
([ADR-0054](0054-public-platform-and-private-instance-repositories.md) D3). It must also hold no
tag or digest of an image built from this estate's source (ADR-0054 D4,
[ADR-0055](0055-upstream-versions-in-the-public-platform-repository.md) decision 2).

## Decision

1. The three application bases above name their image without a tag or a digest. The
   conformance finding in those files is an accepted deviation.
2. The installation overlay that deploys one of these applications sets the image reference. The
   installation decides between a per-build tag and a `<tag>@<digest>` pin. The installation's
   own conformance check covers that choice.
3. Every other workload image in this repository, upstream or not, stays `<tag>@<digest>`
   (ADR-0055 decision 1). The deviation does not cover it.

## Rationale

The base cannot carry a digest that is correct for every installation. Each installation builds
its own images, so each build has its own digest. A placeholder digest would render a workload
that pulls nothing.

Only the overlay knows which build runs. The overlay is therefore the place where the pin
belongs, and the installation's conformance check is the place where it is enforced.

## Consequences

**Positive:**

- A base stays usable by any installation: it names the image, and the overlay names the build.
- The deviation covers three files. The checker still reports an unpinned image in every other
  file of this repository, including a new application base.

**Negative:**

- A new application base whose image an installation builds needs its path added to `paths:`
  above. The pull request that adds the base adds the path.
- The checker cannot see whether an overlay pins the image. That check lives in the
  installation's repository.

## References

- [ADR-0054: A Public Platform Repository and a Private Instance Repository](0054-public-platform-and-private-instance-repositories.md)
- [ADR-0055: Upstream Versions in the Public Platform Repository](0055-upstream-versions-in-the-public-platform-repository.md)
- [ADR-0006: CI/CD Tooling Selection](0006-cicd-tooling-selection.md)
- [Conformance README](https://github.com/manyfold-dk/estate-baseline/blob/main/scripts/conformance/README.md)
