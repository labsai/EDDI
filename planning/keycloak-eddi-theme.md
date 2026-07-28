# Keycloak Login Theme — EDDI Corporate Identity

> **Status: IMPLEMENTED and verified against a running Keycloak 26.0 container.** Three claims in the
> original draft turned out to be wrong under test and are corrected in place below — see F17, F18
> and F19, which are the findings that only a live run could produce. Measured results are in §6.1.
>
> All Keycloak-internal claims in this document were verified against
> the Keycloak **26.0.0** source tree and against **PatternFly 5.3.1** (the version Keycloak 26.0
> bundles). Re-verification commands are given in [§3](#3-verified-facts) so a future upgrade can be
> re-checked cheaply.
>
> **Scope:** `docker-compose.auth.yml` (dev/self-host overlay) + `install.sh --with-auth`. Login theme
> only. No Java changes, no Manager changes.

---

## 1. Goal

When a user is redirected from the EDDI Manager or Workforce UI to Keycloak, they currently jump from
a dark, amber-on-near-black interface to stock Keycloak blue-on-white. The login page should read as
part of EDDI.

**Non-goal:** a general-purpose Keycloak theming framework. This is one login theme matching one
palette. It follows the system colour scheme by default, with an explicit override in the header.

---

## 2. Approach in one paragraph

Ship a `login` theme named `eddi` that inherits from `keycloak.v2` and carries **no FreeMarker overrides**. It works in three layers:

1. **`kcHtmlClass=login-pf pf-v5-theme-dark`** in `theme.properties` puts PatternFly 5's built-in dark
   theme class on `<html>`. That single line flips every PF5 component the login pages use — inputs,
   checkboxes, alerts, tiles, data lists, helper text, tooltips — to dark, including the ones we would
   never think to enumerate.
2. **A `:root` block of `--pf-v5-global--*` token overrides** in `eddi-login.css` recolours PF's dark
   defaults (bluish `#1b1d21` / `#1fa7f8`) to EDDI's stone/amber palette. The root-level tokens PF
   declares under `:where(.pf-v5-theme-dark)` have **specificity 0**, so a plain `:root` rule beats
   them regardless of load order.

   **But that is only half the story, and it is where the first draft of this plan was wrong.** The
   dark theme *also* re-points components at a **different tier of global tokens** than the light
   theme does, via component-scoped rules (F17). Overriding `--primary-color--100` alone leaves the
   sign-in button Keycloak blue, because under `.pf-v5-theme-dark` the button reads
   `--primary-color--300`. The `:root` block must therefore cover both tiers — see F18 for the map.
3. **A short list of targeted rules** for the handful of things tokens cannot express: the logo, the
   page background, the card border, dark-on-amber button text, and Chrome's autofill override.

Everything else — activation, asset delivery — is realm config plus one bind mount.

---

## 3. Verified facts

These are the load-bearing facts. Each one has been checked; do not re-derive them, but **do**
re-check them if the Keycloak image tag moves off `26.0`.

| # | Fact | Why it matters | Re-verify with |
|---|------|----------------|----------------|
| F1 | `keycloak.v2/login/theme.properties` is `parent=keycloak`, `styles=css/styles.css`, `stylesCommon=vendor/patternfly-v5/patternfly.min.css vendor/patternfly-v5/patternfly-addons.css` | Our `styles` value must name `css/styles.css`; `stylesCommon` must be left alone | `curl -s https://raw.githubusercontent.com/keycloak/keycloak/26.0.0/themes/src/main/resources/theme/keycloak.v2/login/theme.properties` |
| F2 | **`styles` replaces, it does not append.** Theme properties are merged key-by-key with the child winning | Omitting `css/styles.css` silently drops all base styling. Unlisted parent files simply are not loaded | — |
| F3 | `css/styles.css` resolves up the inheritance chain, so our theme does not need to ship a copy | Lets us list a file we do not own | Devtools: the request for `.../login/eddi/css/styles.css` returns 200 |
| F4 | `template.ftl` emits `stylesCommon` links **before** `styles` links | Our CSS loads last | `curl` the template, look at the two `<#list>` blocks |
| F5 | **`div.kc-logo-text` does not exist in `keycloak.v2`.** The rule is present in `styles.css` but the element is *not* in `template.ftl` — it is dead CSS inherited from the v1 theme | Overriding `div.kc-logo-text` renders **nothing**. This killed the previous plan | `curl` the template and grep for `kc-logo-text` → no match |
| F6 | The real brand element is `<div id="kc-header-wrapper" class="pf-v5-c-brand">${kcSanitize(msg("loginTitleHtml",(realm.displayNameHtml!'')))?no_esc}</div>`, styled `font-size:29px; text-transform:uppercase; letter-spacing:3px; color:… !important` | The logo must be applied to `#kc-header-wrapper`, and its **text content comes from `realm.displayNameHtml`** | same |
| F7 | `realm.displayName` and `realm.displayNameHtml` are **different fields**. `displayName` feeds `<title>` via the `loginTitle` message; `displayNameHtml` feeds the header | Setting only `displayName` puts no text in the header | — |
| F8 | `template.ftl` contains `<link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />` | Dropping `favicon.ico` into our `resources/img/` overrides it via the same chain as F3 | `curl` the template, grep `favicon` |
| F9 | `keycloak.v2/login/styles.css` contains only **12 rules**. Essentially all visual styling comes from `stylesCommon` (PatternFly) | Theming = theming PatternFly, not theming Keycloak | — |
| F10 | Keycloak 26.0 bundles `@patternfly/patternfly@^5.3.1`. The vendor CSS is generated at build time and is **not** in git (raw.githubusercontent 404s on it) | To inspect it, use the npm CDN, not the KC repo | `curl -s https://raw.githubusercontent.com/keycloak/keycloak/26.0.0/themes/package.json \| grep patternfly` |
| F11 | PatternFly 5.3.1 ships `.pf-v5-theme-dark` (117 occurrences), declared as `:where(.pf-v5-theme-dark){…}` — **specificity 0** — and it sets `color-scheme: dark` | This is the highest-leverage lever in the whole change, *and* it can never out-specify our `:root` overrides | `curl -sL https://cdn.jsdelivr.net/npm/@patternfly/patternfly@5.3.1/patternfly.min.css \| grep -c pf-v5-theme-dark` |
| F12 | The dark theme remaps `--pf-v5-global--BackgroundColor--light-100` to `#1b1d21` | Matters because the login card uses `--pf-v5-c-login__main--BackgroundColor: var(--pf-v5-global--BackgroundColor--light-100)`, i.e. the *light* token. Without the remap the card would stay white | — |
| F13 | `kcHtmlClass` is **not** set in `keycloak.v2`; it is inherited from the v1 `keycloak` theme as `login-pf`. `kcBodyClass` is undefined | (a) `.login-pf body` selectors are valid here. (b) Overriding `kcHtmlClass` **must keep `login-pf`** or the background rule stops matching | `curl` the v1 `theme.properties` |
| F14 | Primary buttons resolve `--pf-v5-c-button--m-primary--Color` from `--pf-v5-global--Color--light-100`, which the dark theme sets to `#e0e0e0` | Light text on amber `#f59e0b` is ≈2.1:1 — a WCAG failure. The button text colour must be overridden explicitly to near-black | — |
| F15 | The EDDI palette below is real, not invented: `#f59e0b` (100×), `#fafaf9` (17×), `#27272a` (9×), `#18181b` (8×), `#a1a1aa` (3×), `#0c0a09` (3×) all appear in the built Manager assets under `src/main/resources/META-INF/resources/` | The CI tokens are correct — but **counting occurrences does not tell you a token's role**, and that caused a real bug: `#0c0a09` is the Manager's `--color-primary-foreground` (the label on an amber fill), not its background. The dark background is `#09090b`. Read the Manager's `.dark{…}` block, do not infer from frequency | `grep -roh -- "--color-[a-z-]*:#0c0a09" src/main/resources/META-INF/resources/assets/*.css \| sort -u` |
| F16 | `logo_eddi.png` is **500×159** (ratio 3.14) and is a **white** wordmark on transparency | Sizes correctly on a dark background; the box must respect ~3.14:1 or use `background-size: contain` with height-only sizing | — |
| **F17** | **`:where()` specificity 0 does NOT mean `:root` always wins.** PF's dark theme also sets *component* variables on *component elements*: `:where(.pf-v5-theme-dark) .pf-v5-c-login { --pf-v5-c-login__main--BackgroundColor: … }`. Custom-property resolution takes the value from the **nearest element** that declares it, so a declaration on `.pf-v5-c-login` beats one on `:root` no matter the specificity | This is why token overrides alone are not automatically sufficient. The fix is to override the *global* the component rule points at, not to fight it with component rules | `tr '}' '\n' < pf.css \| grep ':where(.pf-v5-theme-dark) .pf-v5-c-'` |
| **F18** | Under `.pf-v5-theme-dark` the login components read a **different tier of globals**: primary button background ← `primary-color--300` (not `--100`); primary button label ← `primary-color--400`; login card ← `BackgroundColor--300` (not `--light-100`); form control ← `BackgroundColor--400` (not `--100`); form-control underline ← `BorderColor--400`; field-level error text ← `danger-color--200` | The `--300` / `--400` tiers are not spares — they are the live tokens. All are set in `eddi-login.css` | Same command as F17, then read the declarations |
| **F19** | **`"temporary": true` on an imported credential does NOT create an `UPDATE_PASSWORD` required action** in Keycloak 26.0. After import, `GET /admin/realms/eddi/users?username=eddi` returns `"requiredActions":[]` and the seeded users log straight through | Corrects this plan's earlier claim that update-password is "the first page every seeded user sees" — it is not. The page is still reachable (admin-forced reset) and is verified, but via a forced required action. **Also makes the existing comment at the top of `docker-compose.auth.yml` ("password change required on first login") inaccurate** — left untouched here as out of scope | `curl -s -H "Authorization: Bearer $T" $KC/admin/realms/eddi/users?username=eddi \| grep requiredActions` |

### 3.1 The full `kc*Class` → PatternFly map

`keycloak.v2` renders its forms through FreeMarker macros whose classes come from theme properties.
These are the **actual** class names present in the DOM — use these, do not guess:

| Element | Class |
|---|---|
| Page / container / card | `pf-v5-c-login` / `pf-v5-c-login__container` / `pf-v5-c-login__main` |
| Card sections | `pf-v5-c-login__main-header`, `pf-v5-c-login__main-body`, `pf-v5-c-login__main-footer-band` |
| Page title ("Sign in to your account") | `pf-v5-c-title pf-m-3xl` |
| Form / group / label | `pf-v5-c-form` / `pf-v5-c-form__group` / `pf-v5-c-form__label` |
| Text & password inputs | `pf-v5-c-form-control` |
| Password-visibility toggle | `pf-v5-c-button pf-m-control` |
| Submit button | `pf-v5-c-button pf-m-primary` |
| Secondary / link buttons | `pf-v5-c-button pf-m-secondary` / `pf-v5-c-button pf-m-link` |
| Remember-me checkbox | `pf-v5-c-check`, `pf-v5-c-check__input`, `pf-v5-c-check__label` |
| Alerts (errors) | `pf-v5-c-alert pf-m-inline pf-v5-u-mb-md`, `…__title`, `…__description` |
| Field-level error text | `pf-v5-c-helper-text__item-text pf-m-error kc-feedback-text` |
| Error status icon | `pf-v5-c-form-control__icon pf-m-status` |
| Authenticator picker | `pf-v5-c-data-list` (+ `select-auth-box-*`) |
| OTP device list | `pf-v5-c-tile` |
| Recovery-codes panel | `pf-v5-c-panel pf-m-raised` |
| Social/IdP links | `pf-v5-c-login__main-footer-links-item-link` |

---

## 4. Deliverables

```
keycloak/
├── eddi-realm.json                                  # MODIFY
└── themes/
    └── eddi/
        └── login/
            ├── theme.properties                     # NEW
            └── resources/
                ├── css/eddi-login.css               # NEW
                ├── js/
                │   ├── eddi-a11y.js                 # NEW (ARIA wiring)
                │   └── eddi-theme.js                # NEW (colour-scheme control)
                ├── fonts/
                │   ├── noto-sans-latin-variable.woff2      # NEW (copy)
                │   ├── noto-sans-latin-ext-variable.woff2  # NEW (copy)
                │   ├── noto-sans-cyrillic-variable.woff2   # NEW (copy)
                │   └── noto-sans-greek-variable.woff2      # NEW (copy)
                └── img/
                    ├── logo_eddi.png                # NEW (copy)
                    └── favicon.ico                  # NEW (copy)
docker-compose.auth.yml                              # MODIFY
install.sh                                           # MODIFY
docs/changelog.md                                    # MODIFY (mandatory, same commit)
```

No `template.ftl`, no `login.ftl`, no `messages/`. Every FreeMarker file we do not ship is a file we
do not have to re-diff on every Keycloak upgrade.

---

### 4.1 `keycloak/themes/eddi/login/theme.properties` — NEW

```properties
# EDDI login theme — see planning/keycloak-eddi-theme.md
#
# Verified against Keycloak 26.0.0 / PatternFly 5.3.1.

parent=keycloak.v2

# `styles` REPLACES the inherited value rather than appending to it. The parent's
# css/styles.css MUST be listed explicitly or all base styling is dropped; it is
# resolved up the theme inheritance chain, so this theme does not ship a copy.
# Our file is listed last so it wins at equal specificity.
styles=css/styles.css css/eddi-login.css

# Adds PatternFly 5's built-in dark theme to <html>. `login-pf` is inherited from
# the v1 `keycloak` theme (keycloak.v2 does not set kcHtmlClass itself) and MUST be
# kept — the `.login-pf body` background rule depends on it.
kcHtmlClass=login-pf pf-v5-theme-dark

# NOTE: `stylesCommon` and `import=common/keycloak` are deliberately NOT set here.
# Both are inherited unchanged from keycloak.v2; redeclaring them would pin values
# that Keycloak may legitimately change between versions.
```

---

### 4.2 `keycloak/themes/eddi/login/resources/css/eddi-login.css` — NEW

> **This section deliberately does not reproduce the stylesheet.** It used to, and the copy drifted
> from the shipped file within a day — the plan's snippet still mapped `--BackgroundColor--300` to the
> border colour and had no `--400` tier at all, which is exactly the bug F18 describes. A second copy
> of 700 lines of CSS is a liability, not documentation. **Read the file itself**; it is heavily
> commented, and every non-obvious decision carries its reasoning inline.

What the file is responsible for, in order:

1. **`@font-face`** — four Noto Sans variable subsets (Latin, Latin-ext, Cyrillic, Greek), split by
   `unicode-range`. See the comment above them for the measured download cost.
2. **The EDDI palette** as `--eddi-*` tokens, taken from the Manager's own token block.
3. **The PatternFly token map** — `--pf-v5-global--*` reassigned to those tokens. This is where F18
   matters: both the `--100` and the `--300`/`--400` tiers must be set, because the dark theme
   re-points components at the higher tier.
4. **Light mode** — the same palette in the Manager's light values, applied by two selectors: a
   `prefers-color-scheme` media query for the default, and `[data-eddi-theme="light"]` for an explicit
   choice from the header control.
5. **Targeted rules** for what tokens cannot express: the logo, the page background and bloom, the
   card and its light-line signature, the merged password field, the locale picker, the colour-scheme
   toggle, and typography.

The three rules that are easiest to get wrong, and why:

| Rule | Why it is not obvious |
|---|---|
| `#kc-header-wrapper` gets the logo | `div.kc-logo-text` is dead CSS in `keycloak.v2` (F5) |
| `.pf-v5-c-button.pf-m-primary` sets its label colour explicitly | Token inheritance gives light-on-amber at ~2.1:1 (F14) |
| `.pf-v5-c-login__main-header { column-gap: 0 }` | Track sizing does not fix the picker's alignment — `minmax(0, 1fr)` and `min-width: 0` were both tried live and neither moved it |


### 4.3 Image assets — NEW (copies)

| Source (in repo) | Destination |
|---|---|
| `src/main/resources/META-INF/resources/logo_eddi.png` | `keycloak/themes/eddi/login/resources/img/logo_eddi.png` |
| `src/main/resources/META-INF/resources/eddi-icon.ico` | `keycloak/themes/eddi/login/resources/img/favicon.ico` |

Copy them **byte-for-byte** and confirm afterwards — these are binaries and a mangled copy fails
silently as a broken image:

```bash
cmp src/main/resources/META-INF/resources/logo_eddi.png keycloak/themes/eddi/login/resources/img/logo_eddi.png && echo "logo OK"
cmp src/main/resources/META-INF/resources/eddi-icon.ico keycloak/themes/eddi/login/resources/img/favicon.ico && echo "favicon OK"
```

The favicon override needs no CSS: the template already requests `${url.resourcesPath}/img/favicon.ico`
and theme inheritance serves ours in preference to the parent's (F8).

> Duplication is deliberate. A Keycloak theme directory is a self-contained unit that must be
> mountable into a container that has no access to the EDDI application's classpath resources. Do not
> try to share the files via a symlink — symlinks do not survive the `install.sh` download path.

---

### 4.4 `keycloak/eddi-realm.json` — MODIFY

```diff
 {
   "realm": "eddi",
   "enabled": true,
+  "displayName": "EDDI",
+  "displayNameHtml": "EDDI",
+  "loginTheme": "eddi",
   "sslRequired": "none",
```

Both display fields are required and they do different jobs (F7):

- `displayName` → the `loginTitle` message → the browser `<title>` ("Sign in to EDDI").
- `displayNameHtml` → the content of `#kc-header-wrapper` → the text our CSS pushes off-screen so the
  logo has an accessible name. Leaving it empty renders the logo with no accessible name at all.

Keep `displayNameHtml` as plain text. It is rendered through `kcSanitize(...)?no_esc`, so an inline
`<img>` would also work, but it would need an absolute URL (the theme's `resourcesPath` is not
available in realm JSON) and would break whenever the host or port changes.

---

### 4.5 `docker-compose.auth.yml` — MODIFY

```diff
     volumes:
       - keycloak-data:/opt/keycloak/data
       - ./keycloak:/opt/keycloak/data/import:ro
+      # Custom login theme. Mount the single theme directory, not ./keycloak/themes
+      # as /opt/keycloak/themes — the latter would shadow the whole custom-theme root.
+      - ./keycloak/themes/eddi:/opt/keycloak/themes/eddi:ro
```

No new environment variables. Theme activation lives in the realm JSON.

Two things worth knowing and **not** worth changing:

- The theme tree sits *inside* `./keycloak`, which is also mounted as Keycloak's realm-import
  directory. Keycloak's directory import only consumes `*.json` at the top level of that directory,
  so a `themes/` subdirectory is inert. Confirm once via the log check in §6 rather than assuming.
- `start-dev` disables theme and template caching, so CSS edits are picked up on browser reload with
  no container restart. This is dev-only behaviour — see [§8](#8-known-gaps-and-risks) for production.

---

### 4.6 `install.sh` — MODIFY (two changes)

#### 4.6.1 Ship the theme files (blocker if skipped)

Around [install.sh:646](../install.sh), `kc_files` fetches the realm JSON and nothing else. With the
new bind mount in place, a missing theme directory means Docker **creates an empty host directory**
and mounts it — Keycloak then resolves `loginTheme: eddi` to a theme with no `login/` type and the
branding silently disappears (or errors). Every `install.sh --with-auth` user hits this.

```diff
-    local kc_files=("keycloak/eddi-realm.json")
+    local kc_files=(
+      "keycloak/eddi-realm.json"
+      "keycloak/themes/eddi/login/theme.properties"
+      "keycloak/themes/eddi/login/resources/css/eddi-login.css"
+      "keycloak/themes/eddi/login/resources/img/logo_eddi.png"
+      "keycloak/themes/eddi/login/resources/img/favicon.ico"
+    )
```

The existing loop already `mkdir -p`s each file's parent and prefers a local `$SCRIPT_DIR` copy, so
no other structural change is needed. But **downgrade the failure mode for the theme assets**: the
loop currently calls `fail` (aborts the install) on any download error. A missing realm JSON is fatal;
a missing PNG is cosmetic, and these URLs will 404 on `${EDDI_BRANCH}` until this work merges.

> **Read `resolve_compose_files()` in `install.sh` rather than a snippet here** — an earlier copy of
> this loop drifted from the shipped code within a day. The semantics that matter:
>
> - **`keycloak/eddi-realm.json` is fatal.** Without it there is no realm.
> - **Every theme resource is non-fatal but all-or-nothing.** A failed download removes that file and
>   marks the theme incomplete; after the loop, an incomplete theme deletes the whole
>   `keycloak/themes/eddi` directory. This matters: a *missing* theme is safe (Keycloak logs "Failed
>   to find LOGIN theme" and serves the built-in one, HTTP 200 — verified), but a *partial* one is
>   not, because `theme.properties` would still resolve and the page would render with its stylesheet
>   or fonts 404ing.
> - **No temp-file staging is used.** `curl -f` can leave a partial file, which is why the failed
>   target is removed — and the whole-directory discard above makes per-file atomicity moot.

#### 4.6.2 Apply `loginTheme` to already-provisioned realms (blocker if skipped)

`--import-realm` **skips realms that already exist**, and `keycloak-data` is a named volume. Editing
`eddi-realm.json` therefore has **no effect on any existing installation** — the login page stays
blue and the natural conclusion is "the CSS didn't work".

`configure_keycloak_client()` already obtains an admin token and demonstrates the GET-modify-PUT
pattern. Extend it with an idempotent realm update, called right after the client update:

```bash
# Ensure the EDDI login theme is applied. Realm import is one-shot — it skips
# realms that already exist — so upgrades need this to be set through the API.
# GET-modify-PUT the full representation (same reason as the client update above).
realm_json=$(curl -sf -H "Authorization: Bearer ${admin_token}" \
  "${kc_base}/admin/realms/eddi" 2>/dev/null) || realm_json=""

if [[ -n "$realm_json" ]]; then
  if [[ "$json_tool" == "jq" ]]; then
    updated_realm=$(echo "$realm_json" | jq \
      '.loginTheme = "eddi" | .displayName = "EDDI" | .displayNameHtml = "EDDI"')
  else
    updated_realm=$(echo "$realm_json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['loginTheme'] = 'eddi'
d['displayName'] = 'EDDI'
d['displayNameHtml'] = 'EDDI'
print(json.dumps(d))")
  fi
  curl -sf -X PUT -H "Authorization: Bearer ${admin_token}" \
    -H "Content-Type: application/json" -d "${updated_realm}" \
    "${kc_base}/admin/realms/eddi" >/dev/null 2>&1 \
    || warn "Could not set the EDDI login theme on the realm"
fi
```

Match the surrounding style: guard on `$json_tool`, tolerate failure with `warn`, never `fail`.

For **local development**, the equivalent is to drop the volume:

```bash
docker compose -f docker-compose.yml -f docker-compose.auth.yml down -v
```

⚠️ `-v` destroys `keycloak-data` — all users, credentials and sessions. Fine for the seeded dev realm,
never suggest it as a general upgrade step.

---

## 5. How it works end to end

```
Browser hits a protected Manager route
  └─> redirect to  http://localhost:8180/realms/eddi/protocol/openid-connect/auth?...
        └─> Keycloak resolves the login theme
              realm.loginTheme = "eddi"
                └─> /opt/keycloak/themes/eddi/login   (bind mount)
                      theme.properties: parent=keycloak.v2
                        └─> keycloak.v2  ──parent──>  keycloak (v1)  ──parent──>  base
                              (property lookup walks this chain child-first;
                               resource lookup walks the same chain, which is how
                               css/styles.css and the parent's FreeMarker resolve)
        └─> template.ftl renders:
              <html class="login-pf pf-v5-theme-dark">        <- our kcHtmlClass
                <link .../vendor/patternfly-v5/patternfly.min.css>   (stylesCommon, inherited)
                <link .../vendor/patternfly-v5/patternfly-addons.css>
                <link .../login/eddi/css/styles.css>       (parent's, via inheritance)
                <link .../login/eddi/css/eddi-login.css>   (ours, last)
                <link rel="icon" .../login/eddi/img/favicon.ico>   (ours, via inheritance)
                ...
                <div id="kc-header-wrapper" class="pf-v5-c-brand">EDDI</div>
        └─> cascade resolves:
              PF5 base            -> light defaults
              :where(.pf-v5-theme-dark)  (specificity 0) -> PF dark defaults
              our :root                  (specificity 0,1,0, and last) -> EDDI palette   ← wins
              our targeted rules  -> logo, background, card border, button text, autofill
```

The upgrade-resilience argument in one line: we own **zero** FreeMarker, **one** property that adds a
vendor class, and **one** stylesheet that is ~90% CSS custom properties. A Keycloak upgrade can only
break this by renaming PatternFly tokens or by removing the `#kc-header-wrapper` element — both are
loud, single-symptom failures, not silent drift.

---

## 6. Verification protocol

Run in order. Steps 1–4 are mechanical and are the ones that actually catch wiring mistakes; the
visual steps only catch taste.

**1 — Fresh start (required; see §4.6.2).**

```bash
docker compose -f docker-compose.yml -f docker-compose.auth.yml down -v
docker compose -f docker-compose.yml -f docker-compose.auth.yml up -d
```

**2 — Keycloak accepted the theme.** Empty output is the pass condition:

```bash
docker compose -f docker-compose.yml -f docker-compose.auth.yml logs keycloak | grep -iE "theme|import" | grep -iE "warn|error|not found|failed"
```

**3 — The theme is active and every asset resolves.** This is the single highest-value check: it
proves activation, the `styles`-replaces trap, and the asset paths in one shot.

```bash
KC=http://localhost:8180
LOGIN="$KC/realms/eddi/protocol/openid-connect/auth?client_id=eddi-frontend&redirect_uri=http%3A%2F%2Flocalhost%3A7070%2F&response_type=code&scope=openid"
HTML=$(curl -s "$LOGIN")

echo "$HTML" | grep -q 'class="login-pf pf-v5-theme-dark"' && echo "PASS html class" || echo "FAIL html class"
echo "$HTML" | grep -q 'eddi-login.css'                    && echo "PASS eddi css linked" || echo "FAIL eddi css linked"
echo "$HTML" | grep -q 'login/eddi/css/styles.css'         && echo "PASS parent css linked" || echo "FAIL parent css linked"

# every stylesheet, icon and script the page references must return 200
echo "$HTML" | grep -oE 'href="[^"]*\.(css|ico)"' | cut -d'"' -f2 | sort -u | while read -r p; do
  printf "%s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "$KC$p")"
done
```

A `404` on `.../css/styles.css` is the exact signature of getting the `styles` property wrong.

**4 — Logo and favicon are served as real images.**

```bash
curl -sI "$KC/resources/$(echo "$HTML" | grep -oE 'resources/[^/]+/login/eddi' | head -1 | cut -d/ -f2)/login/eddi/img/logo_eddi.png" | head -3
```

Simpler equivalent: open devtools, Network tab, confirm `logo_eddi.png` is `200` and `image/png` with
a non-zero size.

**5 — Visual pass** at `http://localhost:8180/realms/eddi/account` (redirects to login):

- Near-black `#0c0a09` background, no Keycloak background photo, no blue.
- EDDI wordmark in the header, no uppercase "EDDI" text visible.
- EDDI favicon in the tab (hard-reload; favicons cache aggressively).
- Card is `#18181b` with a visible hairline border.
- Inputs are dark with legible text and an amber focus ring.
- "Sign In" is amber with **dark** text.
- Links ("Forgot Password?") are amber.

**6 — Pages beyond the sign-in form.** All are served by this theme and all have different markup:

- **Update Password** — reachable whenever an admin forces a reset. Contrary to what this plan
  originally assumed, the seeded `"temporary": true` credentials do **not** trigger it (F19), so to
  test it you must force the action first:
  ```bash
  # with $T = admin token, $U = user id from /admin/realms/eddi/users?username=user
  curl -s -H "Authorization: Bearer $T" "$KC/admin/realms/eddi/users/$U" \
    | sed 's/"requiredActions":\[\]/"requiredActions":["UPDATE_PASSWORD"]/' \
    | curl -s -X PUT -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
        -d @- "$KC/admin/realms/eddi/users/$U"
  ```
- **Wrong password** — inline red alert, `#ef4444`, legible on dark.
- **Empty username** — field-level helper text plus the error status icon.
- **Forgot Password** — `$KC/realms/eddi/login-actions/reset-credentials?client_id=eddi-frontend`.
- **Error page** — visit an auth URL with a bogus `client_id`.

**7 — Autofill.** Let the browser save the credentials, log out, return to the login page and confirm
the autofilled fields are dark, not pale yellow/blue.

**8 — Responsive.** Resize to 375px wide; card, logo and buttons stay usable.

**9 — Contrast.** Spot-check the amber button and amber links with any contrast checker; both must
clear 4.5:1. (`#0c0a09` on `#f59e0b` ≈ 10:1; `#fbbf24` on `#18181b` ≈ 9:1.)

---

### 6.1 Measured results (Keycloak 26.0, fresh volume)

Computed styles read from the live page via `getComputedStyle`, not eyeballed:

| Surface | Measured | Expected |
|---|---|---|
| `<html>` class | `login-pf pf-v5-theme-dark` | ✅ |
| Page background | `#0c0a09` | ✅ stone-950 |
| Login card | `#18181b`, border `#27272a`, radius 8px | ✅ |
| Inputs | bg `#18181b`, text `#fafaf9`, border `#27272a` | ✅ |
| Input focus | border `#f59e0b` | ✅ |
| Primary button | bg `#f59e0b`, label `#0c0a09` | ✅ |
| Links | `#fbbf24` | ✅ |
| Labels | `#a1a1aa` | ✅ |
| Field error text | `#f87171` | ✅ after the F18 fix |
| Logo | `url(.../login/eddi/img/logo_eddi.png)`, 56px, `text-indent: -9999px` | ✅ |
| Favicon | `.../login/eddi/img/favicon.ico`, HTTP 200 | ✅ |
| `<title>` | `Sign in to EDDI` | ✅ |
| `color-scheme` | `dark` (native checkbox/scrollbar) | ✅ |

Contrast (computed, WCAG AA needs 4.5:1):

| Pair | Ratio |
|---|---|
| Button label on amber | **9.20:1** |
| Links on card | **10.61:1** |
| Labels on card | **6.91:1** |
| Error text on card | **6.40:1** |
| Error-page message on card | **17.72:1** |

Robustness and process checks from the review pass:

| Check | Result |
|---|---|
| `loginTheme: eddi` with an **empty** theme dir (the failed-download state) | HTTP **200**, falls back to the built-in theme, logs `ERROR … Failed to find LOGIN theme eddi`. A cosmetic failure cannot break authentication — which is what makes the non-fatal `warn` in `install.sh` the right call |
| §6 step 2 + step 3 run against that broken state | Both **fail as designed** — the verification protocol is not vacuous |
| `install.sh` realm update executed against a realm reset to `loginTheme: ""` | **204**, all three fields set, idempotent on re-run |
| Every selector in `eddi-login.css` audited against the live DOM | One dead selector found and removed (`.pf-v5-c-login__main-header-desc` — in no `keycloak.v2` template). `.pf-v5-c-login__main-footer-band` kept: emitted by `login.ftl`, hidden only by `registrationAllowed: false` |
| Keyboard focus ring (real `Tab`, not programmatic `.focus()`) | `solid 2px #f59e0b`, **8.25:1** on the card — past WCAG 2.2's 3:1 for non-text indicators. Note `:focus-visible` does **not** match programmatic focus, so scripted checks alone would have reported it missing |
| Autofill rule selector `.pf-v5-c-form-control > input` | Structurally correct (matches 2 inputs). The rendered autofill appearance was **not** exercised — it needs saved browser credentials |

Flows exercised end to end, all correctly themed: sign-in · failed login (error text + status icon) ·
update password (forced required action) · re-authenticate (`prompt=login`, username-hidden variant) ·
logout confirmation · forgot password · error page (`Client not found`). Realm import log clean — no
theme or import warnings, confirming the `themes/` subdirectory inside the import mount is inert.
Mobile at 375×812: no horizontal overflow, logo visible, controls 309px wide.

---

## 7. What this supports

| Surface | Themed? | Notes |
|---|---|---|
| Sign-in form | ✅ | Primary target |
| Update Password (required action) | ✅ | Verified via a forced action — *not* triggered by the seeded `"temporary": true` credentials (F19) |
| Re-authenticate (`prompt=login`) | ✅ | Username-hidden variant, different markup |
| Logout confirmation | ✅ | |
| Update Profile / Verify Email | ✅ | Shared template + PF classes |
| Forgot Password / Reset Credentials | ✅ | `resetPasswordAllowed: true` in the realm |
| OTP setup & OTP login | ✅ | `pf-v5-c-tile` list covered by dark tokens |
| WebAuthn / recovery codes | ✅ | `pf-v5-c-panel` covered by dark tokens |
| Authenticator selection | ✅ | `pf-v5-c-data-list` covered by dark tokens |
| Error / info / expired / logout-confirm pages | ✅ | Shared template |
| Terms, IdP review, social login buttons | ✅ | Present but unused in this realm |
| Registration | n/a | `registrationAllowed: false` |
| Browser tab title & favicon | ✅ | Via `displayName` + `resources/img/favicon.ico` |
| **Account console** (`/realms/eddi/account`) | ❌ | Separate theme *type*; still stock Keycloak |
| **Admin console** (`:8180/admin`) | ❌ | Separate theme type, and a different realm |
| **Email templates** | ❌ | Separate theme type; password-reset mails stay unbranded |
| **Welcome page** (`:8180/`) | ❌ | Server-level, not realm-level |
| Other realms (incl. `master`) | ❌ | `loginTheme` is scoped to the `eddi` realm — deliberate |

Adding `account` or `email` later means adding sibling directories under `keycloak/themes/eddi/` with
their own `theme.properties`; the palette block in `eddi-login.css` is directly reusable. Out of scope
here.

---

## 8. Known gaps and risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Realm import is one-shot** | Existing installs see no change | §4.6.2 API update + `down -v` for dev. Call this out in the changelog entry |
| **Only the login theme is dark** | Users who reach the account console see a light page | Documented in §7. Follow-up work if desired |
| **Production CSS caching** | `url.resourcesPath` embeds the Keycloak version, so in non-`start-dev` mode browsers cache `eddi-login.css` for a year; edits do not propagate until the KC version changes | Only affects a production Keycloak, which this repo does not ship. Noted in the CSS header |
| **The image tag is `26.0`, a floating minor** | A 26.0.x bump could in principle move PF or the template | Everything relies on stable PF5 tokens and one element id. §3 lists re-verification commands; §6 step 3 catches breakage immediately |
| **Keycloak 26.2+ / PF6** | PF6 renames `--pf-v5-*` to `--pf-t-*`; the token block would need a rewrite | Not a today problem. Confine the change to `eddi-login.css` when it comes |
| **`#kc-header-wrapper` is a `<div>`, not a heading** | The login page has no `<h1>`-level landmark — a pre-existing Keycloak trait, not one we introduce | Accepted. Our text-indent replacement at least preserves the accessible name |
| **Theme dir lives inside the realm-import mount** | Theoretical import confusion | §6 step 2 asserts a clean import log |

---

## 9. Rollback

- **Config-only:** in the Keycloak admin console → realm `eddi` → Themes → Login theme → `keycloak.v2`.
  Takes effect immediately, no restart.
- **Code:** `git revert` the commit, then `docker compose … down -v && up -d` (or run the §4.6.2 API
  call with `loginTheme` set to `""`). The bind mount is inert once `loginTheme` no longer names it.

---

## 10. Commit checklist

Per [AGENTS.md](../AGENTS.md) §2:

1. Branch from `origin/main` — `git fetch origin main && git checkout -b feat/keycloak-eddi-theme origin/main`.
   Do **not** use a `claude/*` branch name.
2. Stage files individually (`git add keycloak/themes/… docker-compose.auth.yml …`). Never `git add .`.
3. **`docs/changelog.md` must be updated in the same commit on the same branch.** New entry at the
   top, following the existing format. It must state: the `keycloak.v2` `kc-logo-text` finding (F5),
   why `styles` must list the parent stylesheet (F2), the PF5 dark-theme layering (F11), and — most
   importantly for users — that **existing installations need the API update or a `down -v`**, because
   realm import will not re-apply `loginTheme`.
4. `./mvnw compile` is not required — no Java changed. Say so in the PR rather than skipping silently.
5. Ask before pushing.
