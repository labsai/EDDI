#!/usr/bin/env node
/**
 * Derive the auth E2E tier's Keycloak realm from the canonical one.
 *
 * `helm/eddi/files/eddi-realm.json` is the realm EDDI actually ships — same
 * bytes as `k8s/overlays/auth/eddi-realm.json` — and it deliberately seeds
 * every account with NO credential, and turns the password grant off on the
 * public SPA client (see helm/eddi/templates/NOTES.txt). The administrator used
 * to ship as eddi/eddi and the two unprivileged fixtures as viewer/viewer and
 * user/user, all of which logged straight in — and because `eddi-frontend` is
 * public with direct access grants on, one `curl` against a reachable Keycloak
 * returned a token good enough to run LLM turns.
 *
 * The auth tier still needs authenticated identities that are actually
 * *allowed* somewhere, or it could only ever assert 401s and 403s and would
 * pass just as happily against a backend that rejects everybody. It also
 * exchanges username/password for tokens rather than driving the login form.
 *
 * So the test realm is generated, never committed: this reads the canonical
 * file, gives each fixture a throwaway password, re-enables the password grant
 * on the SPA client for this throwaway realm only, and writes the result where
 * the compose file mounts it. Copying the realm into the repo instead is what
 * produced `ui/manager/keycloak/eddi-realm.json`, which drifted until its
 * client id (`eddi-manager`) and role names (`admin`/`editor`/`viewer`) matched
 * nothing the backend serves or enforces — a Keycloak setup that could not have
 * logged anyone in, with no test to say so.
 *
 * Every assumption below is asserted rather than assumed, so a change to the
 * shipped realm fails here — loudly, naming the field — instead of quietly
 * leaving the tier testing nothing.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "../../..");
const SOURCE = resolve(REPO_ROOT, "helm/eddi/files/eddi-realm.json");
const OUT = resolve(HERE, "../.keycloak-test-realm/eddi-realm.json");

/** The identity the tier authenticates as, and the password it is given here. */
export const ADMIN_USER = "eddi";
export const ADMIN_PASSWORD = "e2e-admin-password";

/**
 * The accounts the tier logs in as. The canonical realm seeds all three users
 * with their roles and NO password; the passwords below exist only in the
 * generated realm.
 */
export const ROLE_FIXTURES = {
  // eddi-viewer is in there deliberately: EDDI has no role hierarchy, so an
  // account without it is refused every MCP read tool (McpToolUtils.requireRole
  // is a literal hasRole). The realm grants the administrator all three.
  admin: { username: ADMIN_USER, password: ADMIN_PASSWORD, roles: ["eddi-admin", "eddi-editor", "eddi-viewer"] },
  user: { username: "user", password: "e2e-user-password", roles: ["eddi-user"] },
  viewer: { username: "viewer", password: "e2e-viewer-password", roles: ["eddi-viewer"] },
};

/** The public client the backend's __auth_config__.js names. */
export const SPA_CLIENT_ID = "eddi-frontend";

function fail(message) {
  throw new Error(`${SOURCE}: ${message}`);
}

export function buildTestRealm(sourceJson) {
  const realm = JSON.parse(sourceJson);

  if (realm.realm !== "eddi") fail(`expected realm "eddi", found "${realm.realm}"`);

  const spa = (realm.clients ?? []).find((c) => c.clientId === SPA_CLIENT_ID);
  if (!spa) {
    fail(`no "${SPA_CLIENT_ID}" client — that is the id the backend hardcodes into`
      + " /manage/__auth_config__.js, so the SPA would ask for a client this realm does not have");
  }
  if (!spa.publicClient) {
    fail(`"${SPA_CLIENT_ID}" must stay a public client — it is a browser SPA and cannot keep a secret`);
  }
  if (spa.directAccessGrantsEnabled) {
    fail(`"${SPA_CLIENT_ID}" ships with direct access grants enabled. That is a security regression in`
      + " the shipped realm: a public client with the password grant hands a token to anyone who can reach"
      + " Keycloak and knows (or guesses) one password — fix the realm rather than this script");
  }
  // The tier exchanges username/password for a token rather than driving the
  // login form, so the throwaway realm — and only it — turns the grant back on.
  spa.directAccessGrantsEnabled = true;

  const realmRoles = new Set((realm.roles?.realm ?? []).map((r) => r.name));
  for (const [tier, fixture] of Object.entries(ROLE_FIXTURES)) {
    const user = (realm.users ?? []).find((u) => u.username === fixture.username);
    if (!user) fail(`no "${fixture.username}" user, which the ${tier} fixture needs`);

    const actual = [...(user.realmRoles ?? [])].sort();
    const expected = [...fixture.roles].sort();
    if (actual.join(",") !== expected.join(",")) {
      fail(`user "${fixture.username}" now carries [${actual}] rather than [${expected}];`
        + " update ROLE_FIXTURES and the expectations in e2e/auth/ together");
    }
    for (const role of fixture.roles) {
      if (!realmRoles.has(role)) fail(`realm role "${role}" is gone`);
    }

    // No fixture may ship a password — privileged or not. This script supplies
    // every one of them, for the throwaway realm only.
    if ((user.credentials ?? []).length > 0) {
      fail(`"${fixture.username}" now ships a credential. That is a security regression in the`
        + " shipped realm (NOTES.txt says every account ships without one) — fix that rather than this script");
    }
    user.credentials = [{ type: "password", value: fixture.password, temporary: false }];
  }

  return realm;
}

const realm = buildTestRealm(readFileSync(SOURCE, "utf8"));
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(realm, null, 2)}\n`, "utf8");
console.log(`wrote ${OUT} from ${SOURCE} (${realm.users.length} users, ${realm.clients.length} clients)`);
