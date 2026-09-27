# Linking a New GHCR Package to the Repository

**Placeholders.** Replace each placeholder with the value of your installation before you use a URL:

| Placeholder | Value |
|---|---|
| `<org>` | The GitHub organisation that owns the container packages |
| `<repo>` | The repository in the image path `ghcr.io/<org>/<repo>/<package-name>`. The `org.opencontainers.image.source` label of the image names the same repository |
| `<package-name>` | The last segment of the image path, for example `slack-bot` or `website-backend` |

Some packages are at the organisation root, with the image path `ghcr.io/<org>/<package-name>`. For such a package, remove `<repo>%2F` from the package URLs below.

## Table of Contents

- [Why This Is Manual](#why-this-is-manual-observed-behavior)
- [Prerequisites](#prerequisites)
- [Procedure](#procedure)
- [Verification](#verification)
- [Affected Packages](#affected-packages)

## Why This Is Manual (Observed Behavior)

GitHub's docs ([Connecting a repository to a container image using the command line](https://docs.github.com/en/packages/learn-github-packages/connecting-a-repository-to-a-package#connecting-a-repository-to-a-container-image-using-the-command-line)) state that pushing an image with the `org.opencontainers.image.source` label pointing at a repo URL is sufficient to connect the package — even when the push originates outside GitHub Actions.

In practice on this platform, that has not held: pushes from Tekton with OCI labels present on the website images (tracked in issue #72 of the instance repository) did not auto-link, and packages had to be linked manually via the UI.

As of April 2026 there is also no REST or GraphQL API to perform the linking programmatically (verified against [docs.github.com/rest/packages/packages](https://docs.github.com/en/rest/packages/packages)).

**Procedure for new packages:** push first with the OCI label in place, then *check* whether GitHub auto-linked — and only fall through to the manual UI step if it did not.

> **Note (cleanup):** Linking is *not* required for the GHCR retention workflow. The repo-scoped `GITHUB_TOKEN` only sees repo-linked packages, so the cleanup job uses a dedicated org-wide PAT (`GHCR_CLEANUP_TOKEN`, `read:packages`+`delete:packages`) that sees all org packages regardless of linkage. Link a package mainly so it appears under the repo's Packages sidebar.

## Prerequisites

- Owner/admin access to the `<org>/<repo>` repository
- The package has been pushed at least once with `org.opencontainers.image.source` set to `https://github.com/<org>/<repo>`

## Procedure

### Step 0: Verify whether auto-link worked

After the first labeled push, check whether the package already shows under the repo's Packages sidebar at `https://github.com/<org>/<repo>`. If yes, you are done. If no, continue with steps 1–4 below.

### Manual link (only if Step 0 shows no auto-link)

1. Navigate to the package settings page:

   ```
   https://github.com/orgs/<org>/packages/container/<repo>%2F<package-name>/settings
   ```

   Replace `<package-name>` with the package name, for example `slack-bot` or `website-backend`.

2. Scroll to **Connect this package to a repository**.
3. Select `<org>/<repo>` from the dropdown.
4. Click **Connect repository**.

## Verification

Confirm the package now appears at:

```
https://github.com/<org>/<repo>/pkgs/container/<repo>%2F<package-name>
```

And under the repo's **Packages** sidebar at `https://github.com/<org>/<repo>`.

## Affected Packages

Run this procedure once for each new app, after the first successful pipeline run that pushes the image of the app.
The link status of each package is visible on the package settings page of step 1.

Linking is **optional** for cleanup (see note above) -- link a package mainly so it shows under the repo's Packages sidebar.
