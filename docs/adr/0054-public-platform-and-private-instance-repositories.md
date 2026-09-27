# ADR 0054: A Public Platform Repository and a Private Instance Repository

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
  - [D1: Two repository objects, and which one keeps the history](#d1-two-repository-objects-and-which-one-keeps-the-history)
  - [D2: The line runs through contents, not directories](#d2-the-line-runs-through-contents-not-directories)
  - [D3: Argo CD reads both, and the public side is pinned](#d3-argo-cd-reads-both-and-the-public-side-is-pinned)
  - [D4: Secrets, credentials and image tags never reach the public side](#d4-secrets-credentials-and-image-tags-never-reach-the-public-side)
  - [D5: The public repository is protected where it matters](#d5-the-public-repository-is-protected-where-it-matters)
  - [D6: The split is proven by rendering, before and after](#d6-the-split-is-proven-by-rendering-before-and-after)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Amendment (2026-09-27): no demo tree; throwaway cloud instances instead](#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead)
- [Alternatives considered](#alternatives-considered)
- [References](#references)

## Status

**Accepted** (2026-09-23), on the owner's decision. Proposed the same day during an unattended
run. The owner also answered the spec's open question 4: the public repository takes the name
`manyfold-platform`, so the existing object is renamed and the rename window stays.

Direction chosen by the owner on 2026-09-22: multi-source Argo CD, over a pinned copy with a
single source. Extends [ADR-0001](0001-monorepo-structure.md) (the monorepo becomes two
repositories with one owner) and applies the public/private rule of `estate-baseline`
ADR 0001 ("the standard is public, the values are private") to this repository.

## Context

The public footprint plan ends with this repository public: the platform is the evidence of
how it is built. It cannot be made public as it is:

| Fact | Evidence (2026-09-22 survey) |
|---|---|
| Instance values sit in almost every directory | Hostnames under the instance domain in 263 files; IPv4 literals in 99; tenant names in 137; the tailnet name in 67 |
| Secrets are in the tree | 62 SOPS files, one age recipient, decrypted by KSOPS in the repo server |
| Deploy state is committed daily | 46 of the last 147 commits are CI tag bumps into overlays |
| History is not publishable | Secret-scanner candidates and deleted personal data in history; GitHub keeps unreachable commits retrievable by id, so no rewrite of this repository object makes it safe |
| Overlays depend on bases by relative path | 63 kustomize paths; most Deployment specs live in `overlays/cloud`, not in `base` |
| Argo CD reads this repository alone | 92 Application files name it; 30 are already multi-source (chart plus `$values`) |

A platform-internal application, 60 % of the files, leaves first (ADR-0053, private).

## Decision

### D1: Two repository objects, and which one keeps the history

The **existing** repository object is renamed to a new private name: the *instance
repository* below. It stays private and keeps everything it has: history, SOPS files, Argo CD Applications, overlays,
tenants, plans, OpenTofu inputs, operational scripts.

A **new** repository object takes the name `manyfold-dk/manyfold-platform`. It is created
private, receives only content that passes the publication gate, starts with a fresh history,
and is made public by the owner. Its licence is the Apache-2.0 this repository already carries.

No Application may depend on GitHub's rename redirect: the pinned Argo CD's Git client refuses
HTTP redirects. So the rename is one short, announced window: every reference (Argo CD
`repoURL`s, bootstrap manifests, CI, Renovate, webhooks, sibling repositories) changes to the instance repository
in one merge, the object is renamed at once, and the imperatively applied root Applications are
re-applied; Argo CD reports comparison errors for those minutes and prunes nothing. Only then does
the new public repository take the old name. From that moment a stale `repoURL` would read the
public repository, so a CI check in the instance repository rejects any reference to the old name that is not a
public source pinned to the recorded commit. The spec lists the alternative of not renaming at
all, which removes this window, for the owner to decide first.

### D2: The line runs through contents, not directories

A file is public when a stranger who reads it learns how the platform is built and nothing about
which platform it is (`estate-baseline` ADR 0001). In this repository that means:

| Public (`manyfold-platform`) | Private (the instance repository) |
|---|---|
| Kustomize bases with complete, generic workload specs | Overlays: hostnames, image tags, sizes, node placement |
| Generic Helm values (sizing defaults, feature flags) | Instance Helm values (hostnames, tailnet names, receivers, bucket names) |
| Crossplane XRDs and compositions | Claims and ProviderConfigs |
| Tekton tasks, generic dashboards and alert rules | Alert routing, probe targets, runbook URLs |
| A kind demo: its own Application tree of generic components, upstream images, a throwaway key (superseded: no demo tree, see the [2026-09-27 amendment](#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead)) | The local Application tree (it points at private Applications and images), the cloud and dedicated cluster inputs, `cloud-info.md` |
| ADRs published with values removed (the phase 1 method), generic runbooks | The ADRs as written, plans, reviews, operational runbooks |
| The platform's own tooling scripts with every value an input | Scripts that embed hosts, repositories or tenants |

Application source code (the website, the bots, the brokers, the accounting connector) is
private by default in the instance repository; each is a separate publication decision.

Two kinds of name are kept on the public side because they are the product's public identity,
not a map of the instance: the Crossplane API groups `storage.manyfold.dk` and
`network.manyfold.dk`, and the label key `manyfold.dk/tenant`. Renaming the API groups would
rename live CRDs and break every claim.

### D3: Argo CD reads both, and the public side is pinned

Applications live in the instance repository. Each one reads the public repository **at a pinned commit** and the
private repository at `HEAD`:

- **Kustomize applications:** the overlay in the instance repository lists the public base as a remote resource,
  `https://github.com/manyfold-dk/manyfold-platform//<path>?ref=<commit>`. The repo server
  fetches it over HTTPS without credentials.
- **Helm applications:** the chart source is unchanged; `valueFiles` take generic values from a
  `ref: public` source pinned to the commit and instance values from `ref: values` in the instance repository.

Every reference to the public repository uses **one** commit id, recorded once in the instance repository and
written into the overlays and Applications by a script, with a check that fails on any
reference that differs. Moving the pin is a pull request in the instance repository. **A merge to the public
repository deploys nothing** until that pull request merges.

`check-target-revision.yml` changes accordingly: `HEAD` for sources in the instance repository, a 40-hex commit
equal to the recorded pin for sources in the public repository.

### D4: Secrets, credentials and image tags never reach the public side

The public repository holds no ciphertext, no repository credential, no image tag and no
deploy commit. CI tag bumps push to the instance repository only. The kind demo gets its own age key and fixtures
(superseded: the demo is a throwaway cloud instance, see the [2026-09-27 amendment](#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead));
the production age key stops being usable by a local cluster built from the public repository.

### D5: The public repository is protected where it matters

The public repository gets what `estate-baseline` got: a ruleset that blocks force-push and
deletion, secret scanning with push protection, approval for outside contributors' workflows,
the contributor and security policies, and the publication gate on every push (it is listed in
the private baseline repository's list of public repositories). Because of D3, the review point for anything
that deploys is the pin-moving pull request in the instance repository, not a merge in the public repository.

### D6: The split is proven by rendering, before and after

Every restructuring step is proven with a render harness: every kustomize path and Helm value set
an Application uses is rendered before and after, and the step merges only when the outputs are
identical. Argo CD then sees no change. Remote bases are rendered against the pinned commit the
same way.

## Rationale

Multi-source keeps one authored copy of each generic file, in public, and deploys it. The pin
turns the public repository from a deployment source into a library: its `main` can move freely,
take outside pull requests and run Renovate, while production changes only when the private
repository asks for a specific commit. That is also what makes branch protection on the private
repository less pressing: it is not the one anyone else can propose changes to.

Renaming the old object instead of moving content out of it keeps the operationally dangerous
part (secrets, Applications that own data, history) where it is. The new public object starts
empty and only receives content that passes the gate.

## Consequences

**Positive:**

- The platform's infrastructure code becomes public with a fresh history and no values.
- A public change cannot reach production without a reviewed pin move.
- The render harness makes every step of the split provably a no-op for Argo CD.

**Negative:**

- The Deployment specs have to move from overlays into bases first: a large, mechanical
  refactor across the platform's applications.
- Two repositories to change for a feature that spans a base and its overlay, and a pin to
  move.
- The repo server depends on reaching `github.com` for remote bases at every render (it already
  does for this repository).
- The restore runbooks change: a rebuild needs both repositories and the pin.

**Neutral:**

- GHCR package names under `manyfold-platform/` are package names, not repository links; their
  source labels move to the instance repository.

## Amendment (2026-09-27): no demo tree; throwaway cloud instances instead

Recorded on two owner decisions of 2026-09-27 in the cutover plan (private, umbrella
repository). This amends the decision in place; D1 to D6 stand except where this section says
otherwise.

**No local cluster and no demo tree.** The local kind cluster is retired. Every local overlay,
every local Application and the local cluster scripts leave the instance repository, so the
private column's "local Application tree" in D2 no longer exists. Two statements above are
superseded: the D2 row "A kind demo: its own Application tree of generic components, upstream
images, a throwaway key" and the D4 sentence "The kind demo gets its own age key and fixtures".
The public repository holds no demo Application tree. It is a reference installation: the
generic bases, Helm values, OpenTofu root and tooling that the instance repository deploys, not
a tree that anyone runs as it is.

**The demo is a throwaway cloud instance.** A demo is a Hetzner Cloud instance built from the
public repository and its OpenTofu root: a separate project with its own API token, its own age
key generated at bootstrap, generic components only, upstream images, no private application,
and destroyed when done. It needs no production secret and no private credential, which is what
D4 asked of the kind demo. The rest of D4 is unchanged: the public repository holds no
ciphertext, so no cluster built from it can use the production age key.

**The same path will rehearse major upgrades.** A throwaway instance built from the public
repository at the target versions is also the future rehearsal path for major upgrades: a
Talos or Kubernetes major, a new generation of the network layer or of Argo CD. That path gets
an ADR of its own; nothing in the split depends on it.

**The live cutover accepts the split.** The split is complete when every Application on the
cloud cluster is Synced and Healthy while it reads its generic content from the public
repository, the publication gate is clean, and the public repository answers anonymous access
once the owner makes it public. The rebuild proof, a throwaway instance built from the public
repository, is the first task of the design work after that moment; it is not a condition of
making the repository public. It replaces the earlier acceptance condition, a rebuild rehearsal
on a local kind cluster.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Public repository as the source at `HEAD` | Every public merge would deploy; outside pull requests and Renovate would reach production directly |
| A pinned, drift-checked copy of the public files inside the instance repository, Argo CD single-source | Simpler, but deploys a copy rather than the public code, and every public change needs a re-copy; the owner chose multi-source |
| Rewrite this repository's history and publish it | Unreachable commits stay retrievable by id; tag-bump commits publish the patch state; 62 SOPS files are permanent once public |
| Keep the platform private | Abandons the goal of the public footprint plan |

## References

- [ADR 0001: Monorepo Structure](0001-monorepo-structure.md)
- ADR 0053 (private): a platform-internal application leaves the monorepo
- `estate-baseline` `docs/adr/0001-public-standard-private-values.md`
- Spec: the public platform split design of 2026-09-23 (private)
- Plan: the platform hygiene and public split plan of 2026-09-22 (private, umbrella repository)
- Plan: the public platform cutover plan of 2026-09-27 (private, umbrella repository)
