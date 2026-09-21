import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderPage, userEvent } from "@/test/test-utils";
import { ConnectionDetailPage } from "@/pages/connection-detail";
import { toStoredConnection, type ConnectionConfiguration } from "@/lib/api/connections";
import { validateConnection } from "@/lib/connection-validation";

/**
 * Every backend-valid connection shape, through the editor and back.
 *
 * The contract drift these would have caught: `binding` derived from the type
 * alone rewrote a CALLER_SUPPLIED document to SERVICE on the first unrelated
 * edit, and sent an empty `valueTemplate` the backend refuses on that binding.
 * Nothing in the suite loaded such a document and looked at what came back
 * out; every existing round trip started from a shape the derivation happened
 * to agree with.
 *
 * So: one fixture per shape the backend's `ConnectionConfiguration.validate()`
 * accepts, loaded into the detail page, one unrelated field edited, and the
 * PUT body compared to the loaded document. Equality is modulo null/absent —
 * the backend serialises unused fields as `null` and `toStoredConnection`
 * omits them, and both mean the same thing to a document store — but never
 * modulo a *value*: a binding, a flag or a template that changes is the drift.
 */

const FIXTURES: Record<string, ConnectionConfiguration> = {
  "STATIC / SERVICE": {
    name: "amplitude",
    description: "Org-wide analytics key",
    authType: "STATIC",
    binding: "SERVICE",
    allowUnverifiedPrincipal: false,
    staticAuth: {
      headerName: "Authorization",
      valueTemplate: "Bearer ${vault:amplitude-key}",
      username: null,
      passwordRef: null,
    },
    oauth: null,
    baseUrlAllowlist: ["https://amplitude.com"],
    timeoutMs: null,
  },
  "STATIC / CALLER_SUPPLIED": {
    name: "gnowbe",
    description: "Each caller brings their own key",
    authType: "STATIC",
    binding: "CALLER_SUPPLIED",
    allowUnverifiedPrincipal: false,
    staticAuth: { headerName: "x-api-key", valueTemplate: null, username: null, passwordRef: null },
    oauth: null,
    baseUrlAllowlist: ["https://api.gnowbe.com"],
    timeoutMs: null,
  },
  "BASIC / SERVICE": {
    name: "jira-basic",
    description: "HTTP Basic against Jira",
    authType: "BASIC",
    binding: "SERVICE",
    allowUnverifiedPrincipal: false,
    staticAuth: {
      headerName: "Authorization",
      valueTemplate: null,
      username: "svc@example.com",
      passwordRef: "${vault:jira-api-token}",
    },
    oauth: null,
    baseUrlAllowlist: ["https://your-domain.atlassian.net"],
    timeoutMs: 8000,
  },
  "OAUTH2_CLIENT_CREDENTIALS / SERVICE": {
    name: "atlassian-service",
    description: "OAuth service account",
    authType: "OAUTH2_CLIENT_CREDENTIALS",
    binding: "SERVICE",
    allowUnverifiedPrincipal: false,
    staticAuth: null,
    oauth: {
      authorizationUrl: null,
      tokenUrl: "https://auth.atlassian.com/oauth/token",
      clientId: "client-id",
      clientSecret: "${vault:atlassian-client-secret}",
      scopes: ["read:jira-work"],
      extraAuthParams: {},
      usePkce: true,
      clientAuthMethod: "client_secret_basic",
      discoveryUrl: null,
    },
    baseUrlAllowlist: ["https://api.atlassian.com"],
    timeoutMs: null,
  },
  "OAUTH2_AUTHORIZATION_CODE / PER_USER with allowUnverifiedPrincipal": {
    name: "google-drive",
    description: "Each person's own Drive, behind a front proxy",
    authType: "OAUTH2_AUTHORIZATION_CODE",
    binding: "PER_USER",
    allowUnverifiedPrincipal: true,
    staticAuth: null,
    oauth: {
      authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth",
      tokenUrl: "https://oauth2.googleapis.com/token",
      clientId: "1234.apps.googleusercontent.com",
      clientSecret: "${vault:google-client-secret}",
      scopes: ["https://www.googleapis.com/auth/drive.readonly"],
      extraAuthParams: { access_type: "offline", prompt: "consent" },
      usePkce: true,
      clientAuthMethod: "client_secret_post",
      discoveryUrl: "https://accounts.google.com/.well-known/openid-configuration",
    },
    baseUrlAllowlist: ["https://www.googleapis.com", "https://drive.googleapis.com"],
    timeoutMs: null,
  },
};

/** Drop null and undefined leaves, recursively — the one difference that is not drift. */
function stripAbsent(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripAbsent);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .filter(([, v]) => v !== null && v !== undefined)
        .map(([k, v]) => [k, stripAbsent(v)]),
    );
  }
  return value;
}

describe("every backend-valid shape survives toStoredConnection unchanged", () => {
  for (const [label, fixture] of Object.entries(FIXTURES)) {
    it(`${label}`, () => {
      // Sanity first: a fixture the mirror refuses is not a backend-valid one.
      expect(validateConnection(fixture)).toEqual({});
      expect(stripAbsent(toStoredConnection(fixture))).toEqual(stripAbsent(fixture));
    });
  }
});

describe("every backend-valid shape round-trips through the detail page", () => {
  for (const [label, fixture] of Object.entries(FIXTURES)) {
    it(`${label}: the PUT body equals the loaded document except for the edited field`, async () => {
      const sent: Record<string, unknown>[] = [];
      server.use(
        http.get("*/connectionstore/connections/:id", ({ request }) => {
          if (new URL(request.url).pathname.endsWith("/descriptors")) return;
          return HttpResponse.json(fixture);
        }),
        http.put("*/connectionstore/connections/:id", async ({ request, params }) => {
          sent.push((await request.json()) as Record<string, unknown>);
          return new HttpResponse(null, {
            status: 200,
            headers: { Location: `/connectionstore/connections/${params.id}?version=2` },
          });
        }),
      );
      const user = userEvent.setup();
      renderPage(
        "/manage/connections/fixture?version=1",
        <ConnectionDetailPage />,
        "/manage/connections/:id",
      );
      await screen.findByTestId("connection-name-input");

      const description = screen.getByTestId("connection-description-input");
      await user.clear(description);
      await user.type(description, "edited");
      await user.click(screen.getByTestId("save-connection-btn"));

      await waitFor(() => expect(sent).toHaveLength(1));
      expect(stripAbsent(sent[0])).toEqual(
        stripAbsent({ ...fixture, description: "edited" }),
      );
    });
  }
});
