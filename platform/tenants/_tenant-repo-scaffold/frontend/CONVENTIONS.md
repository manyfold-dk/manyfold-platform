# Tenant Frontend Conventions

Seed conventions for a tenant's Vue frontend (for example an internal, role-gated portal).
Copy this into the tenant repo (e.g. `apps/<app>/frontend/CONVENTIONS.md`) as its
starting standard, then keep it current as the app grows.

These conventions are **distilled from the platform's reference apps** -- primarily an
internal, role-gated, OIDC-protected, Danish-language, mobile-first application kept in its
own repository (called "the reference app" below), with the platform's website frontend
(`apps/website/frontend`) consulted only for Prettier and Playwright. A tenant repo
must not depend on the platform repository or on the reference app's repository
(ADR-0033); this document is the transfer mechanism. Governance and ownership: see
[ADR-0039](../../../../docs/adr/0039-frontend-design-handoff.md).

## Table of Contents

- [Do / Don't](#do--dont)
- [1. Stack & Tooling](#1-stack--tooling)
- [2. Project Structure & Naming](#2-project-structure--naming)
- [3. Components](#3-components)
- [4. Styling & Tokens](#4-styling--tokens)
- [5. State & Auth](#5-state--auth)
- [6. Routing & Role-Gating](#6-routing--role-gating)
- [7. API / Data Layer](#7-api--data-layer)
- [8. Forms, i18n & Danish Formatting](#8-forms-i18n--danish-formatting)
- [9. Quality Gates](#9-quality-gates)
- [10. Where a Feature's Files Go](#10-where-a-features-files-go)

## Do / Don't

| ✅ Do | ❌ Don't |
|------|---------|
| Headless Tailwind 4 + your own primitives | Add PrimeVue / Vuetify / Naive / Element |
| oauth2-proxy + `GET /api/me` + Pinia `auth.ts` | Add `@keycloak/keycloak-js` or store JWTs client-side |
| Native `fetch` | Add `axios` / a fetch-wrapper lib |
| SPA, served by Quarkus from `META-INF/resources` | SSR / Nuxt |
| Pinia (only if state is shared) | Vuex / a second state lib |
| `da-DK` locale via `Intl` | Mix `en-DK` / `da-DK` |

## 1. Stack & Tooling

Mandated by [ADR-0004](../../../../docs/adr/0004-application-stack-tool-selection.md);
pin these floors:

| Tool | Version | Notes |
|------|---------|-------|
| Vue | `^3.5` | Composition API + `<script setup>` |
| Vite | `^8.0` | `@tailwindcss/vite` plugin |
| TypeScript | `~6.0` | `strict: true` |
| pnpm | `10.x` | canonical package manager |
| Node | `24` | CI runner version |

`package.json` scripts (match the reference app):

```json
"scripts": {
  "dev": "vite",
  "build": "vite build",
  "preview": "vite preview",
  "test:unit": "vitest run",
  "lint": "eslint . --fix",
  "format": "prettier --write src/"
}
```

`vite.config.ts`: set a **unique dev port** (e.g. `5178`) to avoid clashes
with other apps/worktrees; proxy `/api` → `http://localhost:8081`; honour
`VITE_BASE_URL` (defaults to `/`).

## 2. Project Structure & Naming

```
src/
├── App.vue                # root shell: auth gate + header/nav
├── main.ts
├── assets/main.css        # Tailwind import + @theme tokens
├── components/            # feature SFCs (PascalCase); admin/ for role-gated screens
│   └── admin/
├── mobile/                # MobileTabBar, BottomSheet, MobileHeader (if mobile)
├── stores/                # Pinia, kebab-case files: auth.ts, <feature>.ts
├── composables/           # use* functions: useMediaQuery.ts, ...
├── utils/                 # logout.ts, formatters, icon SVG helpers
├── api/client.ts          # REST client + hand-written request/response types
└── router/index.ts        # routes + role guard
```

Naming: components **PascalCase** (`RunbookList.vue`); composables **`use*`**; stores
**kebab-case** (`venue-wines.ts`); types suffixed `Summary` / `Detail` / `Request` /
`Response`. Type-based top-level folders; `admin/` and `mobile/` group by area.

## 3. Components

`<script setup lang="ts">` only. Type props/emits with generics; no runtime
validators; `withDefaults` only when defaults are needed.

```vue
<script setup lang="ts">
import { buildLogoutHref } from "@/utils/logout";
defineProps<{ email: string }>();
const emit = defineEmits<{ retry: [] }>();
const logoutHref = buildLogoutHref();
</script>
```

**No UI component library** and there is no shared design system to import -- build
your own headless primitives with Tailwind. New tenant frontends **should seed a small
typed primitive set** (`Button`, `Card`, `Modal`, `Table`, `Badge`, `Input`, `Toast`,
`EmptyState`, `Skeleton`) rather than repeating inline Tailwind; if it proves reusable
across apps, promote it to the estate's private baseline repository (ADR-0039, "promote,
don't copy").
Icons: inline SVG (no icon library).

## 4. Styling & Tokens

Tailwind 4 + CSS custom properties. Tokens live in a `@theme {}` block in
`src/assets/main.css`; reference them as utilities (`bg-accent`, `text-...`):

```css
@import "tailwindcss";
@theme {
  --font-sans: "DM Sans", system-ui, sans-serif;
  --color-surface: #ffffff;
  --color-border: #e5e5e5;
  --color-accent: #6b21a8;        /* replace with the tenant brand */
  --color-accent-hover: #581c87;
}
```

Light theme only (no dark mode unless the brief asks for it). Use `env(safe-area-inset-*)`
vars for mobile notch support. Reusable Tailwind `@utility`s (`skeleton`, `tabular-nums`,
`hide-scrollbar`) are encouraged.

## 5. State & Auth

Pinia, composition style (`defineStore('name', () => {...})`). Auth is the canonical
shared store. **Auth is delegated to an oauth2-proxy sidecar** -- the SPA never handles
tokens; it reads the current user from `GET /api/me` and gates on roles:

```ts
export const useAuthStore = defineStore("auth", () => {
  const user = ref<CurrentUserProfile | null>(null);
  async function ensureLoaded() { /* dedup inflight, call getCurrentUser() */ }
  const roles = computed(() => user.value?.roles ?? []);
  function hasRole(r: AppRole) { /* user < editor < admin */ }
  return { user, roles, ensureLoaded, hasRole };
});
```

Start from four files: `stores/auth.ts` (the sketch above); `components/AuthGate.vue`
(shows a loading state until the first `/api/me` call settles, `AccessPending` when the user
is signed in but holds no app role, and the app otherwise); `components/AccessPending.vue`
(names the signed-in email, offers retry and sign-out); and `utils/logout.ts` (builds the
oauth2-proxy `/oauth2/sign_out` link whose `rd` is the Keycloak end-session URL, so sign-out
also ends the realm session). Where the reference app is available to the tenant, copy those
four files from it verbatim as the starting point, then set the tenant's own realm and
oauth2-proxy client id in `utils/logout.ts`; otherwise write them from these descriptions.
On 401, the proxy redirects to `/oauth2/start`; the SPA detects the opaque redirect and
navigates there. **Do not** add `@keycloak/keycloak-js`.

## 6. Routing & Role-Gating

vue-router, all routes lazy-loaded, role gating via `meta.requiresRole` checked in a
`beforeEach` guard:

```ts
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: "/", name: "home", component: () => import("@/components/HomePage.vue") },
    { path: "/runbooks", name: "runbooks",
      component: () => import("@/components/RunbookList.vue"),
      meta: { requiresRole: "editor" } },
  ],
});
router.beforeEach(async (to) => {
  const auth = useAuthStore();
  const need = to.meta.requiresRole as AppRole | undefined;
  if (!need) return true;
  try { await auth.ensureLoaded(); } catch { return true; }
  return auth.hasRole(need) ? true : { name: "home" };
});
```

No formal layout components; `App.vue` holds header/nav and `<RouterView />`.

## 7. API / Data Layer

REST over native `fetch` (no axios, no GraphQL, no TanStack Query). Request/response
types are **hand-written**, co-located in `api/client.ts`. No `Authorization` header in
the SPA -- the oauth2-proxy injects the bearer.

```ts
const API_BASE = import.meta.env.VITE_API_URL || "/api";
export async function getCurrentUser(): Promise<CurrentUserProfile> {
  const res = await fetch(`${API_BASE}/me`);
  if (!res.ok) throw new Error(`Failed to load user: ${res.status}`);
  return res.json();
}
```

Error/loading state is held per-store (or per-component) with `ref`s. **Recommended
evolution:** generate `client.ts` types from the backend's OpenAPI schema instead of
hand-writing -- decide this per app and record it if you adopt it.

## 8. Forms, i18n & Danish Formatting

No form/validation library and no `vue-i18n` in the reference apps -- validate inline.
Danish money/date formatting via `Intl`, standardized on **`da-DK`** (the reference
apps inconsistently mix `en-DK`; do not copy that):

```ts
const dkk = new Intl.NumberFormat("da-DK", {
  style: "currency", currency: "DKK", minimumFractionDigits: 0,
});
dkk.format(73); // "73 kr." -- render as "73,-" in UI where required
```

Put formatters in a `utils/` module and reuse them. Decide DA-only vs DA/EN at app
start; if DA/EN, introduce `vue-i18n` and record the decision.

## 9. Quality Gates

- **ESLint** flat config: `@eslint/js` recommended + `typescript-eslint` recommended +
  `eslint-plugin-vue` `flat/recommended`; `vue/multi-word-component-names` off.
- **Prettier** (`.prettierrc`, from website): `semi: false`, `singleQuote: true`,
  `tabWidth: 2`, `trailingComma: "none"`, `printWidth: 100`.
- **Tests**: Vitest + `@vue/test-utils` for unit (stores/primitives), Playwright for an
  e2e smoke. Tests live in `src/**/__tests__/*.spec.ts`. Stub `matchMedia` /
  `ResizeObserver` in `src/test-setup.ts`.
- **CI** (tenant `.github/workflows/ci.yml`): a `frontend-checks` job running
  `pnpm install --frozen-lockfile && pnpm lint && pnpm test:unit && pnpm build` --
  all must pass before the image is built. See the CI scaffold
  ([../README.md](../README.md)).

## 10. Where a Feature's Files Go

For a new feature (e.g. "Runbooks"), following the layout above:

| Concern | File |
|---------|------|
| List/detail views | `src/components/RunbookList.vue`, `src/components/RunbookDetail.vue` |
| Admin-only screens | `src/components/admin/RunbookEditor.vue` |
| Shared state | `src/stores/runbooks.ts` |
| Reusable logic | `src/composables/useRunbooks.ts` |
| API + types | add functions/types to `src/api/client.ts` |
| Routes | add lazy records to `src/router/index.ts` with `meta.requiresRole` |
| Tests | `src/stores/__tests__/runbooks.spec.ts`, component specs alongside |
| Mobile entry | add a tab in `src/mobile/MobileTabBar.vue` (if applicable) |
