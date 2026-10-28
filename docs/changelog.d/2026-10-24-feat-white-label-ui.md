## 📝 docs(planning): white-labeling plan for every UI (2026-10-24)

**Repo:** EDDI (`feat/white-label-ui`)

### What changed and why

Added [`planning/white-label-ui-plan.md`](../../planning/white-label-ui-plan.md): an inventory of every place the Manager, the Chat UI, the backend-served pages, the Platform Operator and the Keycloak login theme show the EDDI brand, and a phased design to replace it with an admin-defined brand without a rebuild or restart.

### Design decisions

- The brand is one database document edited on a Manager **Settings → Branding** page. `eddi.branding.*` properties seed it, and `eddi.branding.locked=true` makes them authoritative for GitOps installs.
- Delivery stays same-origin: fixed `/branding/*` asset URLs with a bundled fallback, a runtime `/branding/__brand__.js` plus `/branding/brand.css`, and serve-time `<head>` substitution in the HTML shells. The CSP does not change.
- Technical identifiers (`eddi.*` properties, `X-EDDI-*` headers, storage keys, postMessage types) are contracts and are not renamed.
- "Powered by EDDI" is switchable and on by default. The update check stays opt-in, and its UI is hidden when branded. The docs link is parked.
- The branding endpoints are deliberately left off the Platform Operator's allow-list.

### Next

Phase 1 of the plan, the backend brand store and delivery.

## 🎨 feat(branding): brand store, admin REST and same-origin delivery — white-label phase 1 (2026-10-24)

**Repo:** EDDI (`feat/white-label-ui`)

### What changed and why

Phase 1 of the [white-labeling plan](../../planning/white-label-ui-plan.md): the backend brand store and its delivery to every UI. With no brand set, nothing visible changes. The page is [White-Labeling](../white-labeling.md).

- **Brand store.** `configs/branding`: one `BrandDocument` per deployment, behind `IBrandingStore`. It has a MongoDB `BrandingStore` (collections `branding` and `brandingassets`) and a `PostgresBrandingStore` (tables `branding` and `branding_assets`), picked in `DataStoreProducers`. Images are stored per slot (`logo`, `logoDark`, `icon`, `favicon`, `ogImage`, a closed enum); the document holds only their `sha256:` hashes. Writes are compare-and-set on the revision.
- **`BrandingConfig`** (`@ConfigMapping`, `eddi.branding.*`) seeds every field the stored document leaves unset. With `locked=true` the properties are authoritative.
- **`BrandingService`** is the single entry point (`current(BrandRequest)`). It resolves locked → document → seed → defaults field by field, and validates writes and the seed with one validator (`BrandValidator`, `BrandAssetValidator`); an invalid seed fails startup naming the property. It caches for 30 s with immediate local invalidation, answers 409 on a stale revision or when locked, and writes an INFO audit line.
- **Admin REST** `IRestBrandingAdmin` at `/administration/branding` (`eddi-admin`): `GET`, `PUT`, `DELETE`, and `PUT`/`DELETE /assets/{slot}`.
- **Public delivery** `IRestBranding` at `/branding/*` (`@PermitAll`, hidden from OpenAPI): the five images with a bundled fallback, plus `__brand__.js` and `brand.css`. `/branding/*` joins the static-assets permit list and the micrometer path patterns.
- **Head substitution.** The five shells (`manage`, `welcome`, `workforce`, `chat`, root `index.html`) carry `%BRAND_PRODUCT_NAME%`, `%BRAND_TAGLINE%` and `%BRAND_THEME_COLOR%`, `/branding/*` favicons, the brand script and the brand stylesheet. `BrandedShellRenderer` fills them in, HTML-escaped and cached per (shell, revision), for the four shell resources. `BrandedShellRoute`, a Vert.x route, does the same for the static `/`, `/index.html` and `/<shell>.html` paths.
- **One JS-string escaper,** `JsGlobalScript`. `__auth_config__.js` and `/chat/__config__.js` moved onto it with byte-identical output.
- **Dev server.** `scripts/vite-plugin-brand.mjs` in both UIs fills the defaults and serves `/branding/*` under `npm run dev` and MSW mode. A test in each UI runs a real `vite build` over the real shells and checks that the placeholders survive and that `brand.css` comes after the bundle's stylesheet. The MSW passthrough list gained `*/branding/*`.
- **Docs:** `docs/white-labeling.md`, a White-labeling section in `configuration-reference.md`, and `SUMMARY.md`. `ConfigurationReferenceCoverageTest` now reads `@ConfigMapping` interfaces, so mapped properties are held to the same documented-and-real rule as `@ConfigProperty` ones. `ReservedPrincipalsTest` classifies `sha256:` (the image hash) as a non-principal prefix.
- **Verified through the persistence boundary.** `BrandingStoreMongoIT` and `BrandingStorePostgresIT` share one contract (`BrandingStoreContract`). Their first run found `isEmpty()` serialized as an `"empty"` property that the reader then refused, so every stored brand was unreadable; `BrandingJsonTest` now pins the round trip without Docker.

### Design decisions

- **Field-wise merge, and a monotonic revision.** A `null` in the document means "the seed's value, else EDDI's". Reset writes an empty document under the next revision rather than deleting it, so caches keyed on the revision can never serve a stale page.
- **`brand.css` is linked as the first child of `<body>`, not in `<head>`.** Vite injects the bundle's stylesheet at the end of `<head>`, so a `<head>` link would come first and lose to the bundle's `.dark` block. The build test pins the order.
- **Per-shell default taglines** (`BrandedShell`) keep every unbranded description byte-identical. The welcome and chat descriptions differed before branding existed.
- **`/branding/favicon.ico` for the chat shell,** which already used an ICO. The favicon falls back to the icon, and the dark logo and og-image fall back to the logo, so a brand with only a logo and an icon leaves no EDDI image behind.
- **The SVG filter is stricter than the plan.** On top of `<script>`, `<foreignObject>`, `on…=`, `javascript:` and external `href`, it refuses namespace-prefixed elements, `<animate>`/`<set>`, entity declarations, character references (which would hide `javascript:` from a text check), `@import` and external CSS `url()`.
- **`brand.css` also sets `--color-sidebar-accent-foreground`**, so the sidebar accent keeps the brand's contrast pair.
- **Not on the Platform Operator's allow-list** (plan R9). `openapi-operations.json` gains the five admin operations.

### What's next

Phases 2–7 of the plan, starting with the Manager token cleanup.
