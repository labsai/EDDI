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
