# White-Labeling

EDDI can run under your product name, logo, icon and colours. One image serves every brand, and a rebrand needs neither a rebuild nor a restart.

> **Status.** This page describes what is built so far: the brand store, its admin API and its delivery to the browser, and the Manager's **Branding** page for editing it. The shells (page titles, descriptions, favicons, link previews and the loading spinner) are branded, and so is the Manager: its colours, its strings, logos, page titles, update UI and About dialog. The Chat UI's own screens, the Platform Operator, Swagger UI and the Keycloak login theme are not branded yet; the [white-labeling plan](../planning/white-label-ui-plan.md) lists what follows. With no brand set, EDDI looks exactly as it always has.

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

## Not branded yet

These still show EDDI, whatever the brand:

- In the Manager: the Platform Operator (its persona, prompt and activation screen), the A2A agent-card defaults on the agent page, a few model-parameter notes from the parameter catalog, and the editor's syntax colours. The MSW demo data names EDDI too, but it only appears without a backend.
- The Chat UI widget's default title, logo and accent.
- Swagger UI, the OpenAPI document and the A2A agent cards.
- The Keycloak login theme.

The [plan](../planning/white-label-ui-plan.md) schedules each of them. Technical identifiers such as `eddi.*` properties, `X-EDDI-*` headers and storage keys are contracts, and they are never renamed.

## Development

Under `npm run dev` (and MSW mock mode), a small Vite plugin in each UI (`scripts/vite-plugin-brand.mjs`) fills in the EDDI defaults and serves `/branding/*`. The dev server therefore always loads with the EDDI brand, even when the backend it proxies has a brand set.

In MSW mock mode the Branding page works end to end without a backend. The mock (`src/test/mocks/branding-handlers.ts`) keeps a brand in memory and, after a save or an upload, answers `__brand__.js`, `brand.css` and uploaded images from it, so the live update can be seen; a reload starts from the EDDI defaults again. Two variants: `sessionStorage["eddi-mock-branding-locked"] = "true"` shows a locked brand, and saving with the tagline `trigger a conflict` answers as if another admin had saved first. `vite build` leaves the `%BRAND_*%` placeholders in the shells for EDDI to fill in, and a test in each UI checks that. The Manager's dev-only entry `index.html`, which serves deep links such as `/manage/agents` under `npm run dev`, loads the brand script and stylesheet like the built shells.
