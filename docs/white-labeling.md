# White-Labeling

EDDI can run under your product name, logo, icon and colours. One image serves every brand, and a rebrand needs neither a rebuild nor a restart.

> **Status.** This page describes what is built so far: the brand store, its admin API and its delivery to the browser, and the Manager's **Branding** page for editing it. The shells (page titles, descriptions, favicons, link previews and the loading spinner) are branded, and so is the Manager: its colours, its strings, logos, page titles, update UI and About dialog. The Chat UI's title, logo and accent follow the brand too, and so do the [Platform Operator](#the-platform-operator), the [OpenAPI document, Swagger UI, A2A agent cards and the MCP server info](#api-surfaces), and — as a [download](#the-keycloak-login-page) — the Keycloak login theme. What is left is listed under [Not branded](#not-branded), and an end-to-end test checks the rest ([Verification](#verification)); the [white-labeling plan](../planning/white-label-ui-plan.md) has the design. With no brand set, EDDI looks exactly as it always has.

## Where the brand comes from

The brand is one document per deployment, stored in the database (MongoDB or PostgreSQL), and edited by an admin on the Manager's [Branding page](#the-branding-page) or through the [REST API](#rest-api) below.

Each field is resolved on its own, in this order:

1. With `eddi.branding.locked=true`: the `eddi.branding.*` properties and `eddi.branding.assets-dir` only. The stored document is ignored.
2. Otherwise, the stored document.
3. Then the `eddi.branding.*` properties, as the **seed**.
4. Then the EDDI defaults.

So a fresh install with properties set comes up branded, and an admin can still change any field at runtime. A field left empty in the document falls back to its property, or to the EDDI default.

Further fallbacks:

- The dark colours follow the light ones.
- The chat accent follows the primary colour.
- The short name follows the product name.
- Among the images, the dark logo and the link-preview image use the logo, and the favicon uses the icon. A slot with nothing to borrow shows the bundled EDDI image.

### Properties

All of them are listed, with defaults, in the [configuration reference](configuration-reference.md#white-labeling):

```properties
eddi.branding.product-name=Acme Assist
eddi.branding.short-name=Acme
eddi.branding.tagline=AI teams for Acme
eddi.branding.color.primary=#0f766e
eddi.branding.color.primary-foreground=#ffffff
eddi.branding.color.dark-primary=#2dd4bf
eddi.branding.color.dark-primary-foreground=#042f2e
eddi.branding.color.chat-accent=#0f766e
eddi.branding.powered-by=true
eddi.branding.show-update-check=false
eddi.branding.assets-dir=/deployments/branding
```

The images in `assets-dir` use fixed names: `logo.svg` (or `.png`, `.webp`), `logo-dark.*`, `icon.*`, `favicon.ico` (or `.png`, `.svg`) and `og-image.*`. As with any Quarkus property, the environment variable form works too, for example `EDDI_BRANDING_PRODUCT_NAME`.

**Every value is validated at startup.** An invalid value fails startup, and the error names the property. This is the same validation a REST write goes through.

### Locking the brand

`eddi.branding.locked=true` is for installs that keep their brand in Git, such as a Helm values file. The properties are then the whole brand, and every write to `/administration/branding` answers `409` with a message naming the property. `GET` still answers and shows the brand in effect, with `"locked": true`, and the Branding page shows it read-only.

## The Branding page

**Admin → Branding** (`/manage/branding`) in the Manager edits the brand. It is listed only for `eddi-admin`, like the API it calls, and the command palette follows the same rule.

- **Name**: product name, short name and tagline. An empty field is unset, so the property seed or the EDDI default applies; the placeholder shows what that is.
- **Colours**: the primary colour and the text on it, the same pair for dark mode (empty follows light), and the chat widget's accent (empty follows the primary). Each has a colour picker and a text field that takes any colour the [validator](#what-is-validated) accepts. A badge shows the WCAG contrast of each primary pair as you type.
- **Options**: the "Powered by" notice and the update check.
- **Preview**: a small sidebar, a primary button, a highlight, the whole `brand-50…950` scale and a chat bubble, in light and dark side by side. It uses the same colour formulas as `brand.css`, so it shows what a save will look like, whatever brand is in effect around it.
- **Images**: a drop zone per slot, with the image it shows now, Replace and Remove. An image applies as soon as it is uploaded. The page checks the 512 KB cap before sending, and explains each refusal: an SVG that could run script, a wrong type, too large, or locked. With a name set but no logo, it says that the sidebar shows the name as text; with no icon, that the browser tab keeps the default icon.

**Saving** sends the whole form with the revision it was loaded at. Every value is first checked in the browser with the server's rules, and whatever the server still refuses is shown on the field it names. If someone else saved in the meantime, the page does not overwrite their change: it says so and offers **Reload latest**, which loads their version so you can apply your edit again. An image upload does not conflict with your own unsaved text edits. Contrast warnings from the server are shown after a save. **Reset to defaults** asks for confirmation, then clears every stored value and image.

**Login page.** **Download login theme** saves `brand-keycloak-theme.zip`: the Keycloak login theme with the *saved* brand applied (see [The Keycloak login page](#the-keycloak-login-page)). It stays available while the brand is locked.

**Locked** (`eddi.branding.locked=true`): the page shows the brand from the properties, read-only, with a notice naming the property and the source.

**Live update.** After a save, an upload or a reset, the page applies the new brand to its own tab without a reload. It swaps `brand.css` and the favicon for `?r=<revision>` URLs, loads `__brand__.js` again through a new script element (never `eval`, so the Content-Security-Policy is unchanged), and hands the result to the Manager's brand store. The name in every string, the logos, the colours and the page title change at once. Other open tabs pick the brand up when they next load, and other cluster nodes within 30 seconds.

## What is validated

Brand values end up in HTML, in a script and in a stylesheet, so every value is checked on write and again on output.

| Field | Rule |
|---|---|
| `productName` | At most 40 characters |
| `shortName` | At most 12 characters |
| `tagline` | At most 120 characters |
| All three names | No `<`, `>`, control characters or bidirectional overrides |
| Colours | `#rgb`, `#rrggbb`, or `rgb()`, `rgba()`, `hsl()`, `hsla()`, `oklch()` with plain numbers only. Nothing else, so no value can close a CSS declaration or call `url()`, `var()` or `expression()` |

A `primaryForeground` below 4.5:1 contrast on `primary` (WCAG AA) is accepted. The write answers with a `warnings` entry, and a seed logs a WARN. The check covers hex and `rgb()` colours.

### Images

| Rule | Detail |
|---|---|
| Types | PNG, WebP and SVG. ICO is accepted for the favicon slot only |
| Type check | Decided by the file's magic bytes, never by its name or `Content-Type` |
| Size | At most 512 KB |

An SVG opened directly is a document on EDDI's own origin, so an SVG is refused if it contains any of these:

- `<script>`, `<foreignObject>`, `<iframe>`, `<embed>`, `<object>`, `<animate>` or `<set>`, with or without a namespace prefix
- an event-handler attribute (`on…=`)
- a `javascript:` URL, even with whitespace inside it
- an XML entity declaration
- a character reference (`&#…;`)
- an `@import`
- an `href`, `xlink:href` or CSS `url()` that points anywhere except `#fragment` or `data:image/…`

Every SVG is additionally served with a sandboxing `Content-Security-Policy`, so it cannot run script even if a check were bypassed.

## REST API

The admin endpoints need the `eddi-admin` role. REST has no role hierarchy and a brand is deployment identity, so `eddi-editor` is not enough. They are deliberately not available to the Platform Operator.

| Method and path | Purpose |
|---|---|
| `GET /administration/branding` | The stored document, the brand in effect, `locked`, and `source` (`database`, `properties` or `default`) |
| `PUT /administration/branding` | Replace the text fields, colours and toggles |
| `DELETE /administration/branding` | Reset: clear every stored value and image, so the seed or EDDI applies again |
| `PUT /administration/branding/assets/{slot}` | Upload one image as the raw request body. `slot` is `logo`, `logoDark`, `icon`, `favicon` or `ogImage` |
| `DELETE /administration/branding/assets/{slot}` | Revert that slot to the seed or bundled image |
| `GET /administration/branding/keycloak-theme.zip` | The Keycloak login theme with the brand applied, as `brand-keycloak-theme.zip` ([below](#the-keycloak-login-page)) |

### Reading the brand

```json
{
  "document": {
    "revision": 7,
    "productName": "Acme Assist",
    "shortName": null,
    "tagline": "AI teams for Acme",
    "colors": { "primary": "#0f766e", "primaryForeground": "#ffffff", "darkPrimary": null, "darkPrimaryForeground": null, "chatAccent": null },
    "poweredBy": null,
    "showUpdateCheck": null,
    "assets": { "logo": "sha256:…", "logoDark": null, "icon": null, "favicon": null, "ogImage": null },
    "updatedAt": "2026-10-24T10:00:00Z",
    "updatedBy": "admin"
  },
  "effective": { "revision": 7, "source": "database", "branded": true, "productName": "Acme Assist", "shortName": "Acme Assist", "...": "..." },
  "locked": false,
  "source": "database",
  "warnings": []
}
```

`document` holds what is stored; a `null` field there is unset. `effective` is the brand after the seed and the defaults are applied.

### Editing it

`PUT` takes the text fields, colours and toggles, plus the `revision` you read:

```bash
curl -X PUT http://localhost:7070/administration/branding \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"revision": 7, "productName": "Acme Assist", "tagline": "AI teams for Acme",
       "colors": {"primary": "#0f766e", "primaryForeground": "#ffffff"}}'

curl -X PUT http://localhost:7070/administration/branding/assets/logo \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary @logo.svg
```

- **It replaces the whole document.** A field you leave out, or send as `null` or empty, is cleared. Images are not part of this body and are left as they are.
- **The revision is required.** If someone else saved in the meantime, the answer is `409` with the `currentRevision`. Read the brand again and reapply your change. The revision only ever grows, through a reset too.
- **Error responses** are JSON shaped like `{"error": "…", "violations": [{"field": "colors.primary", "message": "…"}]}`, with these statuses:
  - `400` for an invalid value or an SVG that could run script
  - `409` when the brand is locked or the revision is stale
  - `413` for an image over the size cap
  - `415` for an image type the slot does not accept
- **Every write is audited** with an INFO log line naming the user, the old and the new revision, and the fields that changed. The document also records `updatedBy` and `updatedAt`.

## How the brand reaches the browser

Everything is served from EDDI's own origin, so the Content-Security-Policy does not change.

| Path | What it is |
|---|---|
| `/branding/logo`, `/branding/logo-dark`, `/branding/icon`, `/branding/favicon.ico`, `/branding/og-image` | The current image, or the bundled EDDI one. Served with `Cache-Control: public, max-age=300`, an `ETag` equal to the content hash, and `X-Content-Type-Options: nosniff` |
| `/branding/__brand__.js` | Sets `window.__EDDI_BRAND__` = `{branded, revision, productName, shortName, tagline, poweredBy, showUpdateCheck, chat: {accent}, assets: {logo, logoDark, icon, favicon, ogImage}}`. `assets` says, per slot, whether the brand has an image of its own (uploaded or from `assets-dir`), as booleans; the hashes stay on the admin API. Uncached |
| `/branding/brand.css` | The brand's colour tokens, empty when no colour is set. Uncached |

These paths are public, like the pages that load them: a browser needs them before it has a token, and they show only what those pages already show.

`brand.css` sets the Manager's `--color-primary`, `--color-primary-foreground`, `--color-ring`, `--color-sidebar-accent` and `--color-sidebar-accent-foreground` for light (`:root`) and dark (`.dark`) mode, and the Chat UI's `--chat-accent`. Each page loads it after its own stylesheet, so the brand's values win.

With a primary colour set, it also carries:

- **The Manager's brand scale**, `--color-brand-50` to `--color-brand-950`, in both blocks. Step 500 is the primary itself. Lighter steps mix it with white and darker ones with black, as fixed `color-mix(in oklch, var(--color-primary) N%, white|black)` text, so the browser derives every shade and the server computes none. Because each block re-declares the scale, dark mode derives it from the dark primary.
- **`--brand-loader-accent`** for the spinner the HTML shells show before the app has loaded. The spinner sits on a dark background, so it takes the dark primary.

The Manager has three colour scales in place of Tailwind's amber palette, and all three equal amber when nothing is branded:

| Scale | Used for | Follows the brand |
|---|---|---|
| `brand-*` | Accents, selection, primary emphasis | Yes |
| `warn-*` | Caution: warnings, human-in-the-loop pauses and pending approvals, unsaved-changes markers, in-progress and degraded states, the mock-data banner, secret and rotation notices | No, it stays amber |
| `ochre-*` | A fixed categorical hue, not a warning: one colour of a per-type or per-category palette, such as resource-type badges, editor section accents, avatar colours, syntax colours, cost figures and search highlights | No, it stays amber |

So a teal brand turns the accents teal, while a warning still reads as a warning and a per-type badge keeps its colour. `warn` and `ochre` render the same; they are separate so that code says what the colour means. Pick `warn` when the colour asks the reader to look at something, and `ochre` when amber is just that item's colour next to blue, emerald and the rest. A test (`no-hardcoded-amber.test.ts`) fails on any `amber-N` utility or brand-gold hex in the Manager's sources, so new code has to pick one of the three.

The HTML pages are rendered with the brand before they are sent: `/manage`, `/welcome`, `/workforce`, `/chat` and the root redirect at `/`. This covers the page title, the description, the favicon, the loader logo and text, and for `/chat` the `theme-color` and link-preview (`og:*`) tags.

### Caching

- The brand is cached for 30 seconds on each node, and a write clears the cache on the node that made it at once.
- In a cluster, the other nodes show a change within those 30 seconds.
- Rendered pages are cached per brand revision.
- Browsers revalidate images after at most five minutes, and an unchanged image costs one `304`.

## The Manager

Besides its colours (above), the Manager takes the brand from `window.__EDDI_BRAND__`, read once by `src/lib/brand.ts` with the EDDI defaults as a fallback for every field:

- **Strings.** Every mention of the product in the 11 locales is `{{productName}}`, an i18next default variable set from the brand before the first render, so "Welcome to EDDI" reads "Welcome to Acme Assist". Engine-version notes say "server version 6.6" rather than naming EDDI. Technical names stay as they are: `EDDI_*` variables, `X-EDDI-*` headers, `eddi.*` properties, the `eddi-v1` webhook signature and the "EDDI Cluster" Grafana dashboard. A test (`no-eddi-brand-literal.test.ts`) fails on a literal "EDDI" in a locale value, a `t()` fallback, JSX text or an `alt`/`aria-label`/`title`/`placeholder`.
- **Logos.** The sidebars show `/branding/logo` in light mode and `/branding/logo-dark` in dark mode. Unbranded, both are the white EDDI wordmark, which the light sidebar inverts to near-black as before; a branded logo is shown as uploaded and never inverted. **So upload a logo that reads on a light background, plus a dark-mode logo if it does not read on a dark one.** A brand without a logo of its own shows its product name as a text wordmark instead of the EDDI one. The collapsed sidebar, the landing page, About and the Workforce pages show `/branding/icon`; a brand without an icon of its own gets the first letter of its short name on the primary colour instead (unbranded, the collapsed sidebar keeps EDDI's "E." monogram). Image URLs carry `?r=<revision>`, so a replaced image shows at once despite the five-minute cache. Alt texts and labels are the product name. The HTML shells' loader and the favicon still show the bundled EDDI image while nothing is uploaded.
- **Page titles** read `<page> — <product> Manager`.
- **The update UI is hidden when branded**: the Updates page and its menu entry, the update banner, and the automatic check, which then never contacts GitHub even if an admin had ticked it before. `showUpdateCheck: true` brings it back.
- **About.** The Help menu in the sidebar footer, and the version under it when the update UI is hidden, open an About dialog with the product name, tagline and version. While `poweredBy` is on (the default) it adds "Powered by EDDI" and the Apache 2.0 notice.

## The Chat UI

The Chat UI widget (`/chat`) reads `window.__EDDI_BRAND__` in `src/api/brand.ts`, with the same EDDI defaults as the Manager. With no brand set it looks exactly as before.

- **Header title**: the product name, in place of "EDDI".
- **Header logo**: `/branding/logo?r=<revision>` when a logo is uploaded. A brand without one shows its product name as text, never EDDI's logo. The alt text is the product name.
- **Tab title**: `<agent> — <product>`, or the product name alone. Unbranded it stays `<agent> — EDDI Chat`.
- **Accent**: the brand's chat accent, which falls back to the primary colour. It colours the accent, the user bubbles, the input focus ring and the quick replies, the same set of tokens `?accentColor=` sets. `brand.css` also sets `--chat-accent` for the first paint.
- **Head**: the title, description, `theme-color`, favicon and `og:*` tags are substituted on the server (above).
- **Demo route**: under a brand, `/chat/demo/showcase` shows a neutral "not available" notice instead of EDDI's showcase script.

**Precedence**, highest first:

1. URL parameters (`?title=`, `?accentColor=` and the other colour parameters, `?hideLogo=`)
2. The embedder's `ChatConfig` (`title`, `logoUrl`, `accentColor`)
3. The agent's own appearance (planned; not built)
4. The deployment brand
5. The built-in defaults

So an existing embed that sets its own title or colours looks the same under any brand. `ChatConfig.accentColor` is now applied as well. Before, it was stored but never reached the page. A test (`no-eddi-brand-literal.test.ts`) fails on "EDDI" in a locale value, a string literal or JSX text outside a short allow-list of unbranded defaults.

## The Platform Operator

The operator takes the product name: it introduces itself as the *<product> Platform Operator* and talks about "this deployment". One sentence of its fixed preamble still names the engine — the platform is built on the EDDI engine, whose documentation and `eddi.*` properties use that name — so that it finds and cites its own docs and properties under any brand. A new operator agent is named `<product> Platform Operator`.

- **A rebrand reaches an existing operator through Upgrade.** Its instructions are written onto the agent when it is activated, and are never edited in place. After a rebrand, open **Operator** and choose **Upgrade** (or Reconfigure): the replacement is built with the new name. Until then the operator keeps the name it was set up with; everything else keeps working.
- **The operator is recognised by a marker, never by its name.** Activation, upgrade, replacement, the trust check and the discovery of unregistered operators all read the `[eddi-platform-operator]` marker on the agent's descriptor, so renaming the agent or the product cannot make the Manager lose or mistake its operator. Only operators set up before the marker existed are found by a name containing "operator".
- **It cannot change the brand.** The branding endpoints are deliberately not among its tools, reads included: a brand change is seen by every user at once, and it is one click on the page. Asked to rebrand, it names the Admin → Branding page and what it controls. When the admin is on that page, the operator's screen context says `branding`.

## API surfaces

| Surface | Under a brand | Unbranded |
|---|---|---|
| OpenAPI document (`/openapi`) | `info.title` is `<product> API`, and the LABS.AI contact block is dropped. The description and the Apache 2.0 licence stay | Unchanged |
| Swagger UI (`/q/swagger-ui`) | The page title, the topbar logo (the brand's logo, or its name as text when it has none), no LABS.AI footer, the brand's favicon (or icon), and the stylesheet's accents in the brand's colours. The PUT method keeps its amber, as a method colour next to GET's blue and POST's green | The files Quarkus generated, byte for byte |
| A2A agent cards (`/.well-known/agent-card.json`, `/.well-known/agent.json`, `/a2a/agents/{id}/agent.json`) | `provider.organization` is the product name; an agent with no name or description gets `<product> Agent <id>` and `<product> conversational AI agent`, and its default skill and the token description name the product. The agent page's card preview follows | Unchanged |
| MCP `initialize` (`/mcp`) | `serverInfo.name` and `title` are the product name | `eddi`, from `quarkus.application.name` |

All four read the brand per request, so a rebrand needs no restart: the OpenAPI document and Swagger UI change at once, an MCP client sees it on its next connection, and A2A discovery within about a minute (the brand and the agent roster are each cached for 30 seconds).

- **OpenAPI.** SmallRye builds the document once at startup, and an `OASFilter` cannot brand it even per request: SmallRye applies the `quarkus.smallrye-openapi.info-*` properties *after* every filter, so the configured title and contact would win. Instead a route ahead of the OpenAPI handler (`BrandedOpenApiRoute`) takes the document SmallRye serves (cached, unchanged) and, while a brand is set, rewrites `info.title` and drops `info.contact` on the way out, for `/openapi`, `.json`, `.yaml` and `.yml`, negotiating the format as the handler does. Unbranded the request never reaches it. A branded document is re-serialised by Jackson: the same content, different whitespace.
- **Swagger UI.** Quarkus writes `quarkus.swagger-ui.title` and `footer` into the page, and takes its logo, favicon and stylesheet from `META-INF/branding/`, all at build time. A route ahead of the static handler (`BrandedSwaggerUiRoute`) answers the page, `logo.png`, `favicon.ico` and `style.css` from the brand while one is set, and leaves every request to the static handler while none is. Under `quarkus:dev` the generated files live in the build directory rather than on the classpath, so Swagger UI stays unbranded there; a packaged jar or image is branded. A favicon only changes when the brand has a favicon or icon of its own.
- **MCP.** An explicit `quarkus.mcp.server.server-info.name` wins over the brand. Set it to keep a fixed server name whatever the brand says.

## The Keycloak login page

Keycloak cannot read EDDI's database, so the login page cannot follow the brand by itself. **Download login theme** on the Branding page (or `GET /administration/branding/keycloak-theme.zip`, `eddi-admin`) gives you the login theme EDDI ships (`keycloak/themes/eddi`), renamed `brand`, with the brand applied:

| File | What it is |
|---|---|
| `brand/login/theme.properties` | The shipped file, with `css/brand-overrides.css` appended to `styles`, so it loads last |
| `brand/login/resources/css/brand-overrides.css` | Generated from the validated colours. It re-declares the theme's `--eddi-*` palette — the dark colours on `:root`, since dark is the theme's default, and the light ones under its two light-mode selectors — and sets both PatternFly 5 token tiers explicitly: `--pf-v5-global--primary-color--100`/`--200` for the light theme and `--300`/`--400` (the button and its label) under `.pf-v5-theme-dark`, plus `--keycloak-card-top-color`, the 4px rule keycloak.v2 draws above the card (PatternFly blue otherwise; the shipped theme sets it to its amber). Warnings stay amber. Empty when nothing is branded |
| `brand/login/resources/img/brand-logo.*`, `brand-logo-dark.*` | The brand's logo and dark-mode logo, when it has them. Without a logo of its own the page shows the realm's display name as text instead of EDDI's wordmark |
| `brand/login/resources/img/favicon.ico` | The brand's favicon, or its icon, when that is an ICO or PNG. Keycloak links the favicon by this name, so an SVG cannot replace it and the shipped one stays (the README says so) |
| `brand/README.md` | The install steps, and the realm values to set: `loginTheme: brand`, `displayName` (the tab title, "Sign in to …") and `displayNameHtml` (the heading behind the logo, HTML-escaped) |

Everything else is the shipped theme, unchanged. Only validated colours are written into the stylesheet, each checked again on the way out, and the product name appears only in the README, escaped for JSON and HTML. No part of a request reaches a file path.

**Installing it.** Unzip it next to your compose file and mount it into Keycloak, then set the realm values from the README (Admin Console → Realm settings → General and Themes):

```yaml
# docker-compose.auth.yml, keycloak service — next to the shipped eddi theme
    volumes:
      - ./brand:/opt/keycloak/themes/brand:ro
```

`install.sh --with-auth` and `docker-compose.auth.yml` mount only the shipped `eddi` theme; add the line above yourself. The Helm chart's in-cluster Keycloak mounts no themes at all: with Helm, put the theme on the Keycloak pod's file system the way you manage that Keycloak (an image of your own, or a volume at `/opt/keycloak/themes/brand`). **Download it again after a rebrand** — the ZIP is a snapshot of the saved brand.

## Helm

The chart's `eddi.branding.*` values (`helm/eddi/values.yaml`) set the seed properties; all are empty by default, and an empty one is not rendered:

```yaml
eddi:
  branding:
    productName: "Acme Assist"
    tagline: "AI teams for Acme"
    colors:
      primary: "#0f766e"
      primaryForeground: "#ffffff"
    poweredBy: true          # null keeps the default
    locked: true             # the values are the whole brand; the page turns read-only
    assets:
      configMap: acme-brand  # mounted read-only at mountPath = eddi.branding.assets-dir
      mountPath: /deployments/branding
```

The images go into a ConfigMap of your own under the fixed names (`kubectl create configmap acme-brand --from-file=logo.svg --from-file=icon.png`; PNG, WebP and ICO become `binaryData`). EDDI validates every value at startup, so a bad colour fails the pod with the property named, not at the first page view.

## Not branded

These still show EDDI, whatever the brand:

- The editor's syntax colours in the Manager, and the MSW demo data, which only appears without a backend.
- Swagger UI under `quarkus:dev` (see [API surfaces](#api-surfaces)).
- The outbound `User-Agent` and the logs. They honestly identify the engine, which is what an administrator reading them needs.

Technical identifiers such as `eddi.*` properties, `X-EDDI-*` headers, `eddi://` URIs and storage keys are contracts, and they are never renamed.

## Verification

An end-to-end acceptance test, `ui/manager/e2e/fullstack/brand-leak.fullstack.spec.ts`, proves the brand reaches every page. It needs the real server, because `/branding/*`, the page head and the admin API are all server-side, so it runs in the Manager's `fullstack` Playwright project, which CI's **Backend E2E** job runs against the image built from the same commit (MongoDB and PostgreSQL, one worker).

1. It resets the brand, then sets a test brand through the REST API: "Zebra Labs", short name "Zebra", a tagline, teal `#0f766e` on white, `poweredBy: false`, and an SVG logo and icon (`e2e/fullstack/fixtures/`). It also creates and deploys an empty agent.
2. It walks `/`, `/welcome`, every static `/manage` route, every resource type's list page, every static `/workforce` route, the agent, workflow and Studio pages of its agent, `/chat/production/<agent>` and `/chat/demo/showcase` (57 pages today). The route list is read from `src/app.tsx` and `RESOURCE_TYPES`, so a new page is covered without editing the test.
3. On each page it looks for `EDDI` as a word, `labs.ai`, `Labs.ai` and `LABS.AI` in the visible text, the document title, every `alt`, `aria-label`, `title` and `placeholder`, the live `<meta>` tags, and the raw HTML's `<title>` and `<meta>` contents (description, `og:*`). It also checks that the title names the brand, that every same-origin image and icon is served from `/branding/`, and that `--color-primary` (on `/chat`, `--chat-accent`) is the brand's.
4. It checks that the OpenAPI title is "Zebra Labs API" with no LABS.AI contact, that MCP `initialize` answers `serverInfo.name` "Zebra Labs", and that Swagger UI names the brand and nothing else.
5. It resets the brand and checks that `/welcome`, `/manage`, `/workforce`, the chat demo and the OpenAPI title say EDDI again. The brand is also reset after a failure.

Every hit of a run is reported together, by page, with where it was found and the text around it. Two mentions are allowed, each with its reason in the test: the "EDDI Cluster" Grafana dashboard named on the Coordinator page, and the Branding page's Product name placeholder, which shows the EDDI default it falls back to.

To run it locally, after a full `./mvnw package` (never `-DskipUi`), start the jar on port 7070 against a MongoDB with the same opt-outs as `ui/manager/docker-compose.integration.yml` (`EDDI_SECURITY_ALLOW_UNAUTHENTICATED`, `EDDI_MCP_ALLOW_UNAUTHENTICATED` and `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED` set to `true`), or start that compose file, then in `ui/manager`:

```bash
PORT=7070 E2E_AGAINST_BACKEND=1 npx playwright test --project=fullstack brand-leak
```

Run it on its own or with `--workers=1`: while it runs, the whole deployment wears the test brand.

## Development

Under `npm run dev` (and MSW mock mode), a small Vite plugin in each UI (`scripts/vite-plugin-brand.mjs`) fills in the EDDI defaults and serves `/branding/*`. The dev server therefore always loads with the EDDI brand, even when the backend it proxies has a brand set.

In MSW mock mode the Branding page works end to end without a backend. The mock (`src/test/mocks/branding-handlers.ts`) keeps a brand in memory and, after a save or an upload, answers `__brand__.js`, `brand.css` and uploaded images from it, so the live update can be seen; a reload starts from the EDDI defaults again. Two variants: `sessionStorage["eddi-mock-branding-locked"] = "true"` shows a locked brand, and saving with the tagline `trigger a conflict` answers as if another admin had saved first. `vite build` leaves the `%BRAND_*%` placeholders in the shells for EDDI to fill in, and a test in each UI checks that. The Manager's dev-only entry `index.html`, which serves deep links such as `/manage/agents` under `npm run dev`, loads the brand script and stylesheet like the built shells.
