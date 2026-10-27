# White-Labeling Every UI

**Status:** Plan, revision 2 (2026-10-24). Phase 1 is in progress on `feat/white-label-ui`.
**Scope:** the Manager (`/manage`, `/welcome`, `/workforce`), the Chat UI (`/chat`), the backend pages and documents a browser user sees (root redirect, Swagger UI, OpenAPI info, A2A agent cards), the Platform Operator's persona, and the Keycloak login theme. No change to engine behaviour.

---

## 0. Revision 2: what changed and why

Revision 1 kept the brand in `eddi.branding.*` properties only. A review pass and the maintainers' answers to its open questions changed it:

| # | Change | Reason |
|---|---|---|
| R1 | **The brand lives in the database and is edited on a Manager settings page.** Properties become the *seed* used while no brand document exists, plus `eddi.branding.locked=true` for GitOps installs, where the page turns read-only and properties are authoritative | Maintainer decision: a settings page is the right approach. A rebrand is an admin task, not a redeploy |
| R2 | The brand is **mutable at runtime**, so every cache keys on a brand *revision*: the head-substitution cache, asset ETags and the Caffeine read cache. Cluster nodes converge through a short TTL (30 s) plus immediate local invalidation on write. The NATS bus is not needed for a value that changes a few times per year | Follows from R1 |
| R3 | **"Powered by EDDI" is switchable**, default on | Maintainer decision |
| R4 | The **docs link is out of scope** for now. It stays as it is, and the question is parked (§8) | Maintainer decision |
| R5 | **Correction:** the update check is already opt-in. `fetchLatestEddiRelease` runs only on the button press or the per-reload opt-in, so it stays off by default. What remains is a *visible* leak: the update card and banner name EDDI and GitHub. **When branded, the update UI is hidden** unless `showUpdateCheck` is set | Revision 1 wrongly said it polls by default |
| R6 | **Swagger:** the EDDI logo is the Quarkus `META-INF/branding/` convention, which is fixed at **build time**. A runtime brand therefore needs EDDI to answer the Swagger UI's logo and stylesheet requests itself. This stays a spike, now with a known starting point | Maintainer pointer; checked against `META-INF/branding/{logo.png,style.css}` |
| R7 | **Keycloak cannot read EDDI's database.** The settings page instead offers *Download login theme*: a ZIP generated from the current brand on top of `keycloak/themes/eddi`, plus the realm `displayName` to set | Follows from R1 |
| R8 | Tailwind v4 compiles `amber-500` to `var(--color-amber-500)`. Remapping the amber scale globally would re-colour every amber utility in one line, but it is **rejected**, because amber also means *warning* and warnings must stay amber. Instead, a full `brand-50…950` scale is **derived in CSS** from the primary colour with `color-mix(in oklch, …)`, so an admin picks one or two colours, not eleven | Avoids a second palette per brand and keeps warning semantics |
| R9 | The Operator's system prompt is **baked into the operator agent when it is created** (`operator.ts:474`). A rebrand therefore reaches an existing operator only through the existing revision-upgrade flow. The branding endpoints are **left off the operator allow-list on purpose**: a logo change is visible to every user and is a one-click page action, so an LLM tool adds risk without saving work. The prompt only tells admins where the page is | Checked in code; this is the kind of capability-widening that the `planning/*.md` security sections forbid by default |
| R10 | **Uploads make SVG a stored-XSS vector on our own origin.** The upload is rejected if it contains `<script`, `<foreignObject`, `on*=` attributes or `javascript:`, and SVGs are always served with a sandbox CSP | Follows from R1. Revision 1 only had operator-mounted files |
| R11 | i18next runs with `escapeValue: false`, so the product name goes straight into React text. React escapes text, but any `Trans`/HTML path would not, so `<` and `>` are rejected in names | Checked in `src/i18n/config.ts:166` |
| R12 | New REST endpoints touch the Manager's mock layer (MSW handlers, `openapi-operations.json`, `unhandled-api-recorder`) and `DocumentedRestPathsTest`. This is listed per phase | Those gates fail late if forgotten |

---

## 1. Goal

An admin can run EDDI under their own product name, logo, icon and colours. After that, no visible `EDDI`, `Labs.ai`, `labs.ai` link or EDDI amber/blue remains in any screen an end user or admin reaches. This covers titles, loaders, sidebars, empty states, error messages, link previews and the operator's chat replies. The one exception is the optional "Powered by" line. One image serves every brand, and a rebrand needs neither a rebuild nor a restart.

**Non-goals:**

- **Renaming technical identifiers.** These stay unchanged:
  - `eddi.*` properties
  - env vars such as `EDDI_VAULT_MASTER_KEY`
  - `X-EDDI-*` / `X-Eddi-Signature` headers
  - `eddi-*` localStorage keys
  - `eddi-chat-*` postMessage types
  - the Keycloak realm and client ids
  - the Docker image and Java packages

  They are contracts. The UI stops *showing* them as a product name; it does not rename them.
- **Per-tenant brands on a shared instance.** Tenancy is not built (`multi-tenancy-plan.md`). §4.6 keeps the seam open for it.
- **The docs link** (R4), the documentation site and `eddi-website`.

---

## 2. Inventory (sweep of 2026-10-24)

| Surface | Where the brand lives | How it can be changed today |
|---|---|---|
| Manager HTML shells (`manage.html`, `welcome.html`, `workforce.html`) | `<title>`, meta description, favicon `/eddi-icon.svg`, loader `<img src="/img/logo_eddi.png">`, "Loading EDDI Manager…", loader colours (spinner `#d4a843`) | It can't. The shells are static and served unchanged by `RestManagerResource` / `RestWelcomeResource` / `RestWorkforceResource` |
| Manager runtime title | `use-document-title.ts:19` `` `… — EDDI Manager` `` | It can't |
| Manager logos | `src/assets/logo_eddi.png` (inlined as a data URI) in `sidebar.tsx` and `workforce-sidebar.tsx`, shown with `invert dark:invert-0`; inline SVG "E." monogram at `sidebar.tsx:150-173`; `public/eddi-icon.svg` on the landing page, workforce sidebar and onboarding hero | It can't |
| Manager colours | Tailwind v4 `@theme` + `.dark` tokens in `src/index.css` (amber primary `#f59e0b`). **731 hard-coded `*-amber-NNN` utilities in 111 files bypass the tokens** | It can't |
| Manager strings | 11 locale files with about 80 literal "EDDI" values each, and no interpolation. About 170 TSX `t(key, "…EDDI…")` fallbacks and literals, such as `` `EDDI ${version}` `` in the sidebar, alt texts, A2A defaults in `agent-detail.tsx` and operator error strings | It can't |
| Manager links | `docs.labs.ai` (sidebar, coordinator page) is parked under R4. The update card and banner name EDDI and link GitHub and Docker Hub (the check itself is opt-in, R5) | It can't |
| Platform Operator | `system-prompt.ts` "You are the EDDI Platform Operator" plus about 15 EDDI lines, persisted at creation; `agentName: "EDDI Platform Operator"` | It can't |
| Chat shell (`chat.html`) | title "EDDI Chat", meta/og description, og:image, favicon, `theme-color #1a1a1a` | It can't |
| Chat runtime | `DEFAULT_TITLE = "EDDI"`, logo default, accent `#113B92`, `document.title` `` `${name} — EDDI Chat` `` | Per embed, through URL params (`title`, `hideLogo`, 16 colour and font params). There is no logo param. `ChatConfig.accentColor` is stored but never applied |
| Root `/` redirect | `index.html` "EDDI — Redirecting" | It can't |
| Swagger UI | `quarkus.swagger-ui.title/footer`; logo, favicon and style from `META-INF/branding/*` (build time) | Title and footer through properties; the logo can't |
| OpenAPI document | `quarkus.smallrye-openapi.info-*` | Through properties (undocumented) |
| A2A agent card | `AgentCardService`: `AgentProvider("EDDI", …)`, `"EDDI Agent " + id`, `"EDDI conversational AI agent"` | It can't |
| Keycloak login | `keycloak/themes/eddi` | By copying the theme |

**Already there and reused:**

- The **runtime config script pattern**. `/manage/__auth_config__.js` sets `window.__EDDI_AUTH__` and `/chat/__config__.js` sets `window.__EDDI_CHAT__`. Both are uncached, `@PermitAll`, loaded before the bundle, and keep `script-src 'self'`.
- The Chat's URL-param colour overrides, with `isSafeCssColor` / `isSafeFontFamily`.
- The Manager's token layer, where Tailwind v4 utilities resolve CSS variables at runtime.
- The Keycloak theme, which is already built on `:root` token overrides.
- The **singleton-settings store pattern**: `IGlobalVariableStore` with a Mongo `GlobalVariableStore` and a `PostgresGlobalVariableStore` twin, selected in `DataStoreProducers`.

---

## 3. Design in one paragraph

A single **brand document** (product name, short name, tagline, colours, toggles and up to five small image assets) is stored in the database. An admin edits it on **Settings → Branding** in the Manager. `eddi.branding.*` properties seed it field by field (a field the document leaves unset falls back to its property, then to the EDDI default) and, with `eddi.branding.locked=true`, override it permanently. A `BrandingService` validates every value on write and serves the current brand from a short-TTL cache keyed on a revision number. The brand reaches every UI in three ways, all same-origin:

- **Fixed asset URLs** under `/branding/*`, which serve the uploaded image or fall back to the bundled EDDI one, so the CSP never widens.
- **A runtime script and a stylesheet**: `/branding/__brand__.js` sets `window.__EDDI_BRAND__`, and `/branding/brand.css` holds validated CSS custom properties.
- **Serve-time substitution** of the few `<head>` values that browsers and link-preview crawlers read before any JavaScript runs.

The UIs read the product name from one place, i18next interpolates it into every string, and colours come from tokens only. With no brand set, every byte of output is today's EDDI branding.

---

## 4. Design

### 4.1 The brand document

```json
{
  "revision": 7,
  "productName": "Acme Assist",
  "shortName": "Acme",
  "tagline": "AI teams for Acme",
  "colors": {
    "primary": "#0f766e", "primaryForeground": "#ffffff",
    "darkPrimary": "#2dd4bf", "darkPrimaryForeground": "#042f2e",
    "chatAccent": null
  },
  "poweredBy": true,
  "showUpdateCheck": false,
  "assets": { "logo": "sha256:…", "logoDark": null, "icon": "sha256:…", "favicon": null, "ogImage": null },
  "updatedAt": "2026-10-24T10:00:00Z",
  "updatedBy": "admin"
}
```

- **Fields.** Every field is optional. `null` means "unset": the matching `eddi.branding.*` seed property applies, and without one the EDDI default.
  - `chatAccent: null` follows `primary`.
  - The dark colours, when unset, follow the light ones.
  - Asset bytes live in a separate collection or table keyed by slot and hash. The document holds only the hash, which doubles as the ETag.
- **Seed properties** use the same names in kebab case: `eddi.branding.product-name`, `eddi.branding.color.primary`, and so on. Images come from `eddi.branding.assets-dir` with fixed file names (`logo.svg|png|webp`, `logo-dark.*`, `icon.*`, `favicon.ico`, `og-image.png`).
- **`eddi.branding.locked=true`.** Properties and `assets-dir` are authoritative, `PUT` answers 409 with a message naming the property, and the page shows the brand read-only with that explanation.
- **Validation is one validator, used for writes and for the seed.** An invalid seed fails startup with the property name, the same posture as `AuthStartupGuard`.
  - **Colours** must match a strict grammar (`#rgb`, `#rrggbb`, or `rgb()`, `hsl()`, `oklch()` with numeric arguments only), because they are emitted into a stylesheet.
  - **Names** have length caps (product name 40, short name 12, tagline 120), and control characters, `<` and `>` are rejected (R11).
  - **Contrast:** when `primaryForeground` on `primary` is below 4.5:1, the write succeeds with a `warnings` array that the page shows; the seed logs a WARN. It does not refuse.
- **Assets.**
  - **Allowed types:** PNG, WebP, ICO (favicon slot only) and SVG.
  - **Type check:** decided by magic bytes, never by the file name or the client's `Content-Type`.
  - **Size cap:** 512 KB.
  - **SVG rejection:** rejected if it contains `<script`, `<foreignObject`, an `on…=` attribute, `javascript:`, or an external `href` / `xlink:href` (anything except `#…` or `data:image/…`) (R10).
- **Concurrency.** `PUT` takes the `revision` it was based on and answers 409 on a mismatch. Two admins editing at once is rare, but a silent lost update to the logo is worse than a 409.
- **Audit:** an INFO log line with the user, the old revision, the new revision and the changed field names. The document also records `updatedBy`.

### 4.2 REST

| Method and path | Role | Purpose |
|---|---|---|
| `GET /administration/branding` | `eddi-admin` | The current document, plus `locked` and the `source` (`database` / `properties` / `default`) |
| `PUT /administration/branding` | `eddi-admin` | Replace the text fields, colours and toggles; returns the document and `warnings` |
| `PUT /administration/branding/assets/{slot}` | `eddi-admin` | Upload one image as a raw body (`slot` is one of `logo, logoDark, icon, favicon, ogImage`) |
| `DELETE /administration/branding/assets/{slot}` | `eddi-admin` | Revert that slot to the default |
| `DELETE /administration/branding` | `eddi-admin` | Reset everything to the seed or the default |
| `GET /administration/branding/keycloak-theme.zip` | `eddi-admin` | The R7 download (Phase 6) |
| `GET /branding/{logo,logo-dark,icon,favicon.ico,og-image}` | `@PermitAll` | The current asset or the bundled default |
| `GET /branding/__brand__.js`, `GET /branding/brand.css` | `@PermitAll` | Runtime delivery (§4.3) |

- **Roles:** `eddi-admin` only. REST has no role hierarchy, and a brand is deployment identity, so `eddi-editor` is not enough.
- **Public endpoints:** `/branding/*` is public because shells and loaders need it before a token exists. It exposes only what the shells already show.
- **HTTP permissions:** add `/branding/*` to the permit list (`application.properties:989-992`) and to the micrometer path-tag patterns, like `/img/.+`.
- **Asset response headers:**
  - `Cache-Control: public, max-age=300`
  - `ETag` set to the content hash
  - `X-Content-Type-Options: nosniff`
  - for SVG, `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`

### 4.3 Delivery to the SPAs

1. **`/branding/__brand__.js`** sets `window.__EDDI_BRAND__={branded, revision, productName, shortName, tagline, poweredBy, showUpdateCheck, chat:{accent}}`.
   - It uses one shared JS-string escaper, and the two existing config scripts move onto it too.
   - It is `no-cache`, like the existing config scripts.
   - Each shell loads it with a `<script src>` before its bundle.
   - The reader is a `brand.ts` per UI (the pattern of `auth-config.ts` / `chat-config.ts`), which holds the EDDI defaults, so the Vite dev server and MSW mock mode work with no backend.
2. **`/branding/brand.css`** is rendered from validated colours only.
   - For the Manager it sets `:root{--color-primary:…;--color-primary-foreground:…;--color-ring:…;--color-sidebar-accent:…}` and `.dark{…}`. For the Chat it sets `:root{--chat-accent:…}`.
   - It is loaded with a `<link>` *after* the bundle CSS.
   - When unbranded it is an empty response.
   - The brand scale (R8) lives in the bundles' own CSS as `color-mix` expressions over `--color-primary`, so the server never computes shades.
3. **Head substitution.**
   - The four shells and the root `index.html` carry `%BRAND_PRODUCT_NAME%`, `%BRAND_TAGLINE%`, `%BRAND_THEME_COLOR%` and asset paths `/branding/icon`.
   - The resource classes replace these, HTML-escaped, and cache the result per (shell, revision).
   - Vite only rewrites `%VITE_*%` / `%MODE%`-style placeholders, so `%BRAND_*%` passes through the build. A test pins this.
   - A small Vite `transformIndexHtml` plugin fills in the defaults for the dev server and mock mode.
4. **Live update.** After a save, the settings page reloads the brand globals (re-fetching `__brand__.js`), updates i18next's default variables and swaps the `brand.css` link with a `?r=<revision>` query string. The admin sees the change without a reload. Other open tabs pick it up on their next load.

**Rejected alternative: build-time branding.** It breaks the requirement that one image serves every brand.

### 4.4 The Manager

- **Colours** (the biggest single job).
  - Introduce semantic tokens: `primary`, a derived `brand-50…950` (R8), and `warning`.
  - Classify each of the 731 amber utilities:
    - **brand** becomes `primary` or `brand-N`;
    - **warning** becomes `warning` (still amber).
  - The classification is per use, and is the real effort, so it goes one PR per page directory.
  - Fold in the loader spinner (`#d4a843` becomes `var(--color-primary)`), the scrollbar hex values and the `review-launch.tsx` palette.
  - Third-party colours (Slack purple) stay.
  - **Guard:** a test that fails on any `amber-\d` class outside an allow-list that only shrinks.
- **Strings.**
  - Set `interpolation.defaultVariables = { productName, shortName }` from `brand.ts` before `i18nReady` resolves.
  - Each locale value falls into one of three classes:
    1. **Product mentions** become `{{productName}}`.
    2. **Engine-version gates** ("Needs EDDI 6.6 or later") become "Needs server version 6.6 or later".
    3. **Technical identifiers** stay as they are.
  - Class 1 is changed by script across all 11 locales. Classes 2 and 3 are done by hand in `en.json` and mirrored to the other locales.
  - **Guard:** no `\bEDDI\b` in any locale value or `t()` fallback, except `EDDI_[A-Z_]+`, `X-Eddi-`, `eddi\.` and the Powered-by key.
  - Watch the three i18n gates: `i18n:check`, plural categories, and the translation-debt baseline, which only the full Vitest run checks.
- **Logos.**
  - The sidebars use `/branding/logo` and `/branding/logo-dark` via `<picture>` / `.dark`.
  - Drop `invert dark:invert-0`: it turns a colour logo into its negative.
  - The "E." monogram becomes `/branding/icon` when an icon is uploaded, else `shortName[0]` in the existing SVG.
  - Alt and aria labels become `productName`.
- **Titles:** `` `${page} — ${productName} Manager` ``. The "Manager" and "Workforce" suffixes stay translatable.
- **Update UI** (R5): hidden when `branded && !showUpdateCheck`. Unbranded behaviour is unchanged.
- **About dialog** (sidebar footer): the product name and version, plus "Powered by EDDI" with the Apache 2.0 notice when `poweredBy` is set (R3).
- **Settings → Branding page** (Phase 4):
  - fields with live preview: a mini sidebar, a primary button and a chat bubble in light and dark;
  - a contrast warning;
  - asset drop zones with type and size errors;
  - Reset;
  - a read-only state with an explanation when `locked`;
  - a 409 conflict reload.
  - Route, nav entry and i18n keys in all 11 locales; MSW handlers and fixtures; and an `openapi-operations.json` update.

### 4.5 The Chat UI

- **Precedence:** URL params > (later) per-agent appearance > brand > built-in defaults. Existing embeds are unaffected.
- `DEFAULT_TITLE`, the logo (`/branding/logo`) and the accent come from `window.__EDDI_BRAND__`. `document.title` becomes `` `${name} — ${productName}` ``.
- **Fix the existing gap:** apply `ChatConfig.accentColor` through `applyColorOverrides`.
- `chat.html` gets head substitution; `theme-color` comes from the brand primary.
- When branded, the demo route `/chat/demo/showcase` (EDDI marketing content) answers 404.

### 4.6 Seams for later

- **Per-agent chat appearance:** an optional `appearance {displayName, avatarUrl, accentColor}` on the agent, returned by the existing `GET /agents/{id}/profile`. This is the agent designer's choice and is config-driven. It needs its own design pass: `AgentConfiguration`, the ZIP export/import and the agent editor.
- **Per-tenant brands:** `BrandingService.current(request)` is the only entry point, even though Phase 1 ignores the request. A host-based resolver can then slot in; the caches already key on revision and would key on (brand, revision).

### 4.7 The Platform Operator

- The prompt builds `You are the ${productName} Platform Operator` and refers to "this deployment".
- One sentence keeps the engine's real name: "This platform is built on the EDDI engine; its documentation and `eddi.*` properties use that name." Without it, the operator would deny that its own docs and properties exist.
- New operators are named `` `${productName} Platform Operator` ``. Identification must stay on the `[eddi-platform-operator]` marker only, never the name. Verify this first, because about 40 fixtures use the old name.
- **The prompt is persisted at creation (R9).** Document that after a rebrand, the operator picks up the new name through *Upgrade*.
- The branding endpoints stay **off** the allow-list (R9); the prompt names Settings → Branding instead.
- Bump `operator-revision.json`. Operator PRs land one at a time.

### 4.8 Backend-visible surfaces

| Surface | Change |
|---|---|
| Root `index.html` | Head substitution |
| OpenAPI `info-*`, Swagger `title` / `footer` | These are build-time-ish Quarkus config. Serve the brand's title in the OpenAPI document through an `OASFilter` that reads `BrandingService`, and document the properties |
| Swagger logo and stylesheet (R6) | **Spike:** answer the Swagger UI's logo and `style.css` requests with brand-aware responses through a Vert.x route that runs before the static handler. If Quarkus serves them from a location a route cannot shadow, document the leak |
| A2A agent card | `AgentProvider(productName, …)`; the fallback name and description use `productName`; `agent-detail.tsx` follows |
| MCP server info | Default the server name to `productName`, if the extension allows changing it at runtime; otherwise document it |
| Outbound `User-Agent`, logs | Leave as is. They are technical and honestly identify the engine |

### 4.9 Keycloak

The settings page offers **Download login theme** (R7). It is a ZIP of `keycloak/themes/eddi` renamed `brand`, with a generated `brand-overrides.css` (the same validated colours, mapped onto the PF5 token tiers from `keycloak-eddi-theme.md` F18), the logo and favicon, and a README giving the realm `displayName` / `displayNameHtml` values to set. `install.sh --with-auth` and the Helm chart gain an optional theme mount, with a chart version bump plus `EXPECTED_CHART_VERSION`.

---

## 5. Security

- **No new script, style or image origins.** The CSP strings do not change.
- **Three output contexts, three escapers:** JS string (`__brand__.js`), CSS (validator only; no free-form CSS is ever emitted), and HTML text and attributes (head substitution). Each gets hostile-input tests: `</script>`, `";alert(1)//`, `red;}body{display:none`, `url(javascript:…)`, `expression(…)`, `<img onerror>`.
- **Assets:** the content type is checked by magic bytes, SVGs are filtered and sandboxed on serve, sizes are capped, and the slot is a closed enum, so no path from the request reaches storage or the filesystem.
- **Writes** need `eddi-admin`, use optimistic concurrency and are logged. The routes are not on the operator allow-list.

---

## 6. Phases

All phases are on `feat/white-label-ui`, one commit per phase. They can be split into PRs into `main` later; stacked PRs would skip CI.

| # | Phase | Contents | Size |
|---|---|---|---|
| 1 | **Backend brand store and delivery** | `BrandingConfig` (seed and lock), the brand model, `IBrandingStore` plus Mongo and Postgres twins wired in `DataStoreProducers`, `BrandingService` (validation, cache, revision), the admin REST of §4.2 (except the Keycloak ZIP), the public `/branding/*` assets, `__brand__.js`, `brand.css`, head substitution in the five shells' resources, permit paths, the shared JS escaper adopted by the two config scripts, unit tests including §5, `docs/white-labeling.md` plus `SUMMARY.md`, and a changelog fragment. The shells gain the placeholders and the Vite dev plugin. **No visible change when unbranded** | L |
| 2 | **Manager token cleanup** | Brand scale, amber classification, loader and scrollbar colours, the no-new-amber guard | L |
| 3 | **Manager strings, logos, titles, update UI, About** | §4.4 except the settings page | L |
| 4 | **Settings → Branding page** | §4.4 page, live update, mocks, contract snapshot | M |
| 5 | **Chat UI** | §4.5 | S |
| 6 | **Operator, backend surfaces, Keycloak ZIP** | §4.7–4.9, operator revision bump, Helm | M |
| 7 | **Brand-leak E2E** (§7) | Lands last, as the acceptance test | M |

---

## 7. Verification

- **Brand-leak E2E** is the acceptance test.
  - A Playwright project sets a test brand ("Zebra Labs", teal, a test logo) through the REST API, then walks every top-level route of `/welcome`, `/manage`, `/workforce` and `/chat`.
  - On each page it asserts that the visible text, `document.title`, every `alt` / `aria-label`, and the raw HTML's `<title>` and `og:*` contain no `\bEDDI\b`, `labs.ai` or `Labs.ai`. The only allow-listed exceptions are the Powered-by line and admin pages that show `eddi.*` properties.
  - It also asserts that every `<img>` is served from `/branding/`, and that the computed `--color-primary` is the test brand's.
  - It then resets the brand and checks that the same routes still show EDDI.
- **Unit guards:**
  - no new `amber-\d`;
  - no literal `EDDI` in locales or `t()` fallbacks;
  - `%BRAND_*%` survives the Vite build;
  - escaper, validator and SVG-filter tests.
- **Real runtime:** after a full `./mvnw package` (never `-DskipUi`), run the image in Docker with both Mongo and Postgres. Rebrand through the page, and confirm the shells, cache headers and permit paths in the jar. In-memory tests have hidden persistence-boundary bugs before.
- **Accessibility:** run axe on the branded profile.

---

## 8. Decisions and open questions

| Question | Answer |
|---|---|
| Can Powered-by be switched off? | **Yes**: `poweredBy`, default on (R3) |
| Docs link when branded | **Parked** (R4) |
| Swagger logo | It is the build-time `META-INF/branding` convention; runtime override spike in Phase 6 (R6) |
| Update check default | **Off, as it already is**; the update UI is hidden when branded (R5) |
| Properties or a settings page | **A settings page**, with properties as seed and lock (R1) |
| Can the operator change branding? | No, by design (R9). Revisit only on request |
