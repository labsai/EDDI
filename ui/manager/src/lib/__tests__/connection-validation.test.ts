import { describe, it, expect } from "vitest";
import {
  bindingFor,
  isCredentialParamName,
  isOAuthType,
  isSecretReference,
  validateConnection,
  validateCredentialEndpoint,
  validateHeaderTemplate,
  validateOrigin,
} from "@/lib/connection-validation";

/**
 * These rules are a mirror of EDDI's `ConnectionConfiguration.validate()`.
 *
 * The tests are written against the *backend's* reasons rather than against
 * this implementation's shape, so that a drift shows up as a failing case with
 * a name that says which rule moved. Each block names the rule it mirrors.
 */

describe("isSecretReference — anchored, not merely containing", () => {
  it("accepts a bare vault reference", () => {
    expect(isSecretReference("${vault:jira-token}")).toBe(true);
  });

  it("accepts the vars and legacy eddivault schemes the backend also accepts", () => {
    expect(isSecretReference("${vars:tenant-key}")).toBe(true);
    expect(isSecretReference("${eddivault:old-key}")).toBe(true);
  });

  it("trims, because a reference pasted from a config file carries whitespace", () => {
    expect(isSecretReference("  ${vault:key}\n")).toBe(true);
  });

  it("rejects a literal that merely CONTAINS a reference", () => {
    // The failure this exists to prevent: a real key smuggled past the check by
    // appending an unused reference.
    expect(isSecretReference("sk-live-x${vault:unused}")).toBe(false);
  });

  it("rejects the unbraced spellings, which the backend's anchored pattern refuses", () => {
    expect(isSecretReference("vault:key")).toBe(false);
    expect(isSecretReference("eddivault:key")).toBe(false);
  });

  it("rejects an unknown scheme and an empty body", () => {
    expect(isSecretReference("${env:KEY}")).toBe(false);
    expect(isSecretReference("${vault:}")).toBe(false);
  });

  it("rejects a plain secret and an empty value", () => {
    expect(isSecretReference("hunter2")).toBe(false);
    expect(isSecretReference("")).toBe(false);
    expect(isSecretReference(null)).toBe(false);
    expect(isSecretReference(undefined)).toBe(false);
  });
});

describe("validateOrigin — a BARE origin, parsed rather than prefix-matched", () => {
  it("accepts scheme://host", () => {
    expect(validateOrigin("https://api.example.com")).toBeNull();
  });

  it("accepts an explicit port and a lone trailing slash", () => {
    expect(validateOrigin("https://crm.internal.example.com:8443")).toBeNull();
    expect(validateOrigin("https://api.example.com/")).toBeNull();
  });

  it("accepts http, for a development instance", () => {
    expect(validateOrigin("http://localhost:7070")).toBeNull();
  });

  it("rejects a bare host — the failure that looks like a working allowlist", () => {
    // `api.atlassian.com` with no scheme parses as a path, matches nothing at
    // resolve time, and produces an allowlist that blocks everything.
    expect(validateOrigin("api.atlassian.com")).toBe("originNotBare");
  });

  it("rejects a path, a query and a fragment", () => {
    expect(validateOrigin("https://api.example.com/v1")).toBe("originNotBare");
    expect(validateOrigin("https://api.example.com?a=b")).toBe("originNotBare");
    expect(validateOrigin("https://api.example.com#x")).toBe("originNotBare");
  });

  it("rejects userinfo, which changes where the request goes", () => {
    expect(validateOrigin("https://user:pw@api.example.com")).toBe("originNotBare");
  });

  it("rejects a non-http scheme", () => {
    expect(validateOrigin("ftp://files.example.com")).toBe("originScheme");
  });

  it("rejects an empty entry", () => {
    expect(validateOrigin("   ")).toBe("originHost");
  });
});

describe("validateCredentialEndpoint — the client secret is sent here", () => {
  it("accepts an absolute https URL with a path", () => {
    expect(
      validateCredentialEndpoint("https://auth.example.com/oauth/token", true),
    ).toBeNull();
  });

  it("requires https, because the client secret is sent to it", () => {
    expect(validateCredentialEndpoint("http://auth.example.com/token", true)).toBe(
      "endpointNotHttps",
    );
  });

  it("rejects userinfo", () => {
    expect(
      validateCredentialEndpoint("https://user:pw@auth.example.com/token", true),
    ).toBe("endpointUserInfo");
  });

  it("rejects a relative URL", () => {
    expect(validateCredentialEndpoint("/oauth/token", true)).toBe(
      "endpointNotAbsolute",
    );
  });

  it("treats blank as missing when required and as absent when not", () => {
    expect(validateCredentialEndpoint("", true)).toBe("endpointRequired");
    expect(validateCredentialEndpoint("", false)).toBeNull();
    expect(validateCredentialEndpoint(null, false)).toBeNull();
  });
});

describe("validateHeaderTemplate — literal text is fine, secrets are not", () => {
  it("accepts a prefix plus one reference", () => {
    expect(validateHeaderTemplate("Bearer ${vault:jira-token}")).toBeNull();
  });

  it("accepts a template that is nothing but a reference", () => {
    expect(validateHeaderTemplate("${vault:amplitude-key}")).toBeNull();
  });

  it("accepts more than one reference", () => {
    expect(
      validateHeaderTemplate("${vars:scheme} ${vault:token}"),
    ).toBeNull();
  });

  it("rejects a template with no interpolation — a credential in disguise", () => {
    expect(validateHeaderTemplate("Bearer sk-live-abc")).toBe("templateNoReference");
  });

  it("rejects an interpolated segment that is not a reference", () => {
    expect(validateHeaderTemplate("Bearer ${env:TOKEN}")).toBe("templateBadSegment");
  });

  it("requires a value at all", () => {
    expect(validateHeaderTemplate("")).toBe("templateRequired");
    expect(validateHeaderTemplate(null)).toBe("templateRequired");
  });
});

describe("bindingFor — the coupling that runs both ways", () => {
  it("gives an authorization-code connection PER_USER", () => {
    // The bug this prevents: `binding` defaults to SERVICE, so an author who
    // picks the authorization-code flow and leaves binding alone saves a
    // connection that resolves against a principal no flow can produce.
    expect(bindingFor("OAUTH2_AUTHORIZATION_CODE")).toBe("PER_USER");
  });

  it("gives every other type SERVICE", () => {
    expect(bindingFor("STATIC")).toBe("SERVICE");
    expect(bindingFor("BASIC")).toBe("SERVICE");
    expect(bindingFor("OAUTH2_CLIENT_CREDENTIALS")).toBe("SERVICE");
  });
});

describe("isOAuthType", () => {
  it("covers both OAuth flows and neither static one", () => {
    expect(isOAuthType("OAUTH2_AUTHORIZATION_CODE")).toBe(true);
    expect(isOAuthType("OAUTH2_CLIENT_CREDENTIALS")).toBe(true);
    expect(isOAuthType("STATIC")).toBe(false);
    expect(isOAuthType("BASIC")).toBe(false);
  });
});

describe("isCredentialParamName — extraAuthParams is not a place for keys", () => {
  it("catches the obvious names", () => {
    expect(isCredentialParamName("client_secret")).toBe(true);
    expect(isCredentialParamName("access_token")).toBe(true);
    expect(isCredentialParamName("password")).toBe(true);
  });

  it("normalises case and separators, as the backend does", () => {
    expect(isCredentialParamName("Client-Secret")).toBe(true);
    expect(isCredentialParamName("API.KEY")).toBe(true);
    expect(isCredentialParamName("refreshToken")).toBe(true);
  });

  it("catches an entry that only exists in underscored form", () => {
    // The backend strips `_` before its own lookup, so `code_verifier` — whose
    // only set entry keeps the underscore — normalises to `codeverifier` and
    // matches nothing. Filed upstream; this mirror checks the raw key too, so it
    // stays at least as strict as the backend rather than promising a save the
    // backend would (once fixed) refuse.
    expect(isCredentialParamName("code_verifier")).toBe(true);
    expect(isCredentialParamName("Code-Verifier")).toBe(true);
    expect(isCredentialParamName("CODE_VERIFIER")).toBe(true);
  });

  it("leaves real protocol parameters alone", () => {
    expect(isCredentialParamName("prompt")).toBe(false);
    expect(isCredentialParamName("audience")).toBe(false);
    expect(isCredentialParamName("access_type")).toBe(false);
  });
});

// ─── The whole document ─────────────────────────────────────────

const STATIC_OK = {
  name: "amplitude",
  authType: "STATIC",
  baseUrlAllowlist: ["https://amplitude.com"],
  staticAuth: {
    headerName: "Authorization",
    valueTemplate: "Bearer ${vault:amplitude-key}",
  },
};

const OAUTH_OK = {
  name: "jira",
  authType: "OAUTH2_AUTHORIZATION_CODE",
  baseUrlAllowlist: ["https://api.atlassian.com"],
  oauth: {
    authorizationUrl: "https://auth.atlassian.com/authorize",
    tokenUrl: "https://auth.atlassian.com/oauth/token",
    clientId: "abc",
    clientSecret: "${vault:jira-client-secret}",
    extraAuthParams: { audience: "api.atlassian.com" },
  },
};

describe("validateConnection", () => {
  it("passes a well-formed STATIC connection", () => {
    expect(validateConnection(STATIC_OK)).toEqual({});
  });

  it("passes a well-formed authorization-code connection", () => {
    expect(validateConnection(OAUTH_OK)).toEqual({});
  });

  it("requires a name", () => {
    expect(validateConnection({ ...STATIC_OK, name: "  " }).name).toBe("nameRequired");
  });

  it("warns about a name a ${connection:…} reference could not carry", () => {
    expect(validateConnection({ ...STATIC_OK, name: "my connection" }).name).toBe(
      "nameFormat",
    );
    expect(validateConnection({ ...STATIC_OK, name: "acme/jira" }).name).toBe(
      "nameFormat",
    );
  });

  it("requires a non-empty allowlist", () => {
    expect(
      validateConnection({ ...STATIC_OK, baseUrlAllowlist: [] }).baseUrlAllowlist,
    ).toBe("allowlistRequired");
  });

  it("treats a whitespace-only allowlist entry as no entry", () => {
    expect(
      validateConnection({ ...STATIC_OK, baseUrlAllowlist: ["  "] }).baseUrlAllowlist,
    ).toBe("allowlistRequired");
  });

  it("reports the first malformed origin in the allowlist", () => {
    expect(
      validateConnection({
        ...STATIC_OK,
        baseUrlAllowlist: ["https://ok.example.com", "nope.example.com"],
      }).baseUrlAllowlist,
    ).toBe("originNotBare");
  });

  it("refuses a plaintext BASIC password", () => {
    const errors = validateConnection({
      name: "legacy",
      authType: "BASIC",
      baseUrlAllowlist: ["https://crm.example.com"],
      staticAuth: {
        headerName: "Authorization",
        username: "svc",
        passwordRef: "hunter2",
      },
    });
    expect(errors["staticAuth.passwordRef"]).toBe("secretMustBeReference");
  });

  it("requires a username for BASIC", () => {
    const errors = validateConnection({
      name: "legacy",
      authType: "BASIC",
      baseUrlAllowlist: ["https://crm.example.com"],
      staticAuth: {
        headerName: "Authorization",
        username: "",
        passwordRef: "${vault:pw}",
      },
    });
    expect(errors["staticAuth.username"]).toBe("usernameRequired");
  });

  it("requires a header name for a static connection", () => {
    const errors = validateConnection({
      ...STATIC_OK,
      staticAuth: { headerName: "", valueTemplate: "${vault:k}" },
    });
    expect(errors["staticAuth.headerName"]).toBe("headerNameRequired");
  });

  it("refuses a plaintext client secret", () => {
    const errors = validateConnection({
      ...OAUTH_OK,
      oauth: { ...OAUTH_OK.oauth, clientSecret: "sk-live-abc" },
    });
    expect(errors["oauth.clientSecret"]).toBe("secretMustBeReference");
  });

  it("requires an authorization URL only for the user-login flow", () => {
    const userLogin = validateConnection({
      ...OAUTH_OK,
      oauth: { ...OAUTH_OK.oauth, authorizationUrl: "" },
    });
    expect(userLogin["oauth.authorizationUrl"]).toBe("endpointRequired");

    const serviceAccount = validateConnection({
      ...OAUTH_OK,
      authType: "OAUTH2_CLIENT_CREDENTIALS",
      oauth: { ...OAUTH_OK.oauth, authorizationUrl: null },
    });
    expect(serviceAccount["oauth.authorizationUrl"]).toBeUndefined();
  });

  it("rejects a credential-shaped extra parameter", () => {
    const errors = validateConnection({
      ...OAUTH_OK,
      oauth: { ...OAUTH_OK.oauth, extraAuthParams: { api_key: "abc" } },
    });
    expect(errors["oauth.extraAuthParams"]).toBe("paramCredentialShaped");
  });

  it("reports every broken field at once, not just the first", () => {
    // The whole reason for mirroring: the backend throws on the first problem,
    // so a form driven by it can only ever mark up one field per round trip.
    const errors = validateConnection({
      name: "",
      authType: "OAUTH2_AUTHORIZATION_CODE",
      baseUrlAllowlist: [],
      oauth: { authorizationUrl: "", tokenUrl: "", clientId: "", clientSecret: "" },
    });
    expect(Object.keys(errors).sort()).toEqual([
      "baseUrlAllowlist",
      "name",
      "oauth.authorizationUrl",
      "oauth.clientId",
      "oauth.clientSecret",
      "oauth.tokenUrl",
    ]);
  });

  it("does not look at the OAuth block of a static connection, or the reverse", () => {
    // Both blocks are kept in the editor's draft so a mis-clicked type switch is
    // reversible; only the relevant one is validated and sent.
    const staticWithStaleOAuth = validateConnection({
      ...STATIC_OK,
      oauth: { tokenUrl: "", clientId: "", clientSecret: "literal" },
    });
    expect(staticWithStaleOAuth).toEqual({});

    const oauthWithStaleStatic = validateConnection({
      ...OAUTH_OK,
      staticAuth: { headerName: "", valueTemplate: "plaintext" },
    });
    expect(oauthWithStaleStatic).toEqual({});
  });
});
