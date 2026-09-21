import { describe, it, expect } from "vitest";
import {
  bindingFor,
  isCredentialParamName,
  isLegalBinding,
  isOAuthType,
  isReservedOAuthParamName,
  isSecretReference,
  legalBindings,
  validateConnection,
  validateCredentialEndpoint,
  validateHeaderTemplate,
  validateOrigin,
  validateParamValue,
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

  it("rejects a literal key with a reference stapled on", () => {
    // The backend's rule on the literal halves: a run of twelve or more from
    // the alphabet keys are written in is a key, whatever surrounds it.
    expect(validateHeaderTemplate("sk-live-abcdef${vault:x}")).toBe(
      "templateLiteralCredential",
    );
    expect(validateHeaderTemplate("${vault:x}.eyJhbGciOiJIUzI1NiJ9")).toBe(
      "templateLiteralCredential",
    );
  });

  it("rejects a literal half longer than 32 characters even without a run", () => {
    expect(
      validateHeaderTemplate("this is a very long prefix with spaces ${vault:x}"),
    ).toBe("templateLiteralCredential");
  });

  it("still accepts the scheme prefixes people actually write", () => {
    expect(validateHeaderTemplate("Bearer ${vault:k}")).toBeNull();
    expect(validateHeaderTemplate("Basic ${vault:k}")).toBeNull();
    expect(validateHeaderTemplate("Token token=${vault:k}")).toBeNull();
    // A long vault key NAME is inside the braces, not a literal half.
    expect(validateHeaderTemplate("Bearer ${vault:acme/prod/amplitude-api-key-2026}")).toBeNull();
  });
});

describe("bindingFor — the coupling that runs both ways", () => {
  it("gives an authorization-code connection PER_USER", () => {
    // The bug this prevents: `binding` defaults to SERVICE, so an author who
    // picks the authorization-code flow and leaves binding alone saves a
    // connection that resolves against a principal no flow can produce.
    expect(bindingFor("OAUTH2_AUTHORIZATION_CODE")).toBe("PER_USER");
  });

  it("gives every other type SERVICE by default", () => {
    expect(bindingFor("STATIC")).toBe("SERVICE");
    expect(bindingFor("BASIC")).toBe("SERVICE");
    expect(bindingFor("OAUTH2_CLIENT_CREDENTIALS")).toBe("SERVICE");
  });

  it("honours CALLER_SUPPLIED on STATIC, the one type with a real choice", () => {
    // A connection loaded from the API must keep the binding it was stored
    // with; deriving it blindly rewrote every caller-supplied document to
    // SERVICE on the first unrelated edit.
    expect(bindingFor("STATIC", "CALLER_SUPPLIED")).toBe("CALLER_SUPPLIED");
    expect(bindingFor("STATIC", "SERVICE")).toBe("SERVICE");
  });

  it("corrects a requested binding the type cannot carry", () => {
    expect(bindingFor("BASIC", "CALLER_SUPPLIED")).toBe("SERVICE");
    expect(bindingFor("STATIC", "PER_USER")).toBe("SERVICE");
    expect(bindingFor("OAUTH2_AUTHORIZATION_CODE", "SERVICE")).toBe("PER_USER");
    expect(bindingFor("OAUTH2_CLIENT_CREDENTIALS", "PER_USER")).toBe("SERVICE");
  });

  it("lists exactly the pairs the backend saves", () => {
    expect(legalBindings("STATIC")).toEqual(["SERVICE", "CALLER_SUPPLIED"]);
    expect(legalBindings("BASIC")).toEqual(["SERVICE"]);
    expect(legalBindings("OAUTH2_CLIENT_CREDENTIALS")).toEqual(["SERVICE"]);
    expect(legalBindings("OAUTH2_AUTHORIZATION_CODE")).toEqual(["PER_USER"]);
    expect(isLegalBinding("STATIC", "CALLER_SUPPLIED")).toBe(true);
    expect(isLegalBinding("BASIC", "CALLER_SUPPLIED")).toBe(false);
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

  it("catches code_verifier in every spelling — the PKCE secret must never reach a URL", () => {
    // The backend normalises the key and consults a set written in the same
    // stripped form, so all three spellings are one name there and here.
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

describe("isReservedOAuthParamName — parameters EDDI writes itself", () => {
  it("refuses the ones the flow depends on, whatever the case or separator", () => {
    expect(isReservedOAuthParamName("redirect_uri")).toBe(true);
    expect(isReservedOAuthParamName("Redirect-URI")).toBe(true);
    expect(isReservedOAuthParamName("STATE")).toBe(true);
    expect(isReservedOAuthParamName("codeChallenge")).toBe(true);
    expect(isReservedOAuthParamName("response.type")).toBe(true);
    expect(isReservedOAuthParamName("client_secret")).toBe(true);
    expect(isReservedOAuthParamName("code_verifier")).toBe(true);
  });

  it("leaves provider-specific parameters alone", () => {
    expect(isReservedOAuthParamName("prompt")).toBe(false);
    expect(isReservedOAuthParamName("audience")).toBe(false);
    expect(isReservedOAuthParamName("login_hint")).toBe(false);
  });

  it("is no wider than the backend's list — scope, code and grant_type are accepted there", () => {
    // The mirror used to refuse these three, so a document the backend saves
    // was blocked before it left the browser. A mirror may under-refuse (the
    // backend's 400 still arrives); it must never over-refuse.
    expect(isReservedOAuthParamName("scope")).toBe(false);
    expect(isReservedOAuthParamName("code")).toBe(false);
    expect(isReservedOAuthParamName("grant_type")).toBe(false);
  });
});

describe("validateParamValue — the map is plain text in a URL", () => {
  it("accepts the values providers actually document", () => {
    expect(validateParamValue("consent")).toBeNull();
    expect(validateParamValue("offline")).toBeNull();
    expect(validateParamValue("api.atlassian.com")).toBeNull();
    expect(validateParamValue("https://graph.microsoft.com")).toBeNull();
    expect(validateParamValue("")).toBeNull();
  });

  it("refuses a reference — nothing resolves it there", () => {
    expect(validateParamValue("${vault:client-secret}")).toBe("paramValueReference");
    expect(validateParamValue("x${vars:y}")).toBe("paramValueReference");
  });

  it("refuses a value past 512 characters", () => {
    expect(validateParamValue("a b ".repeat(200))).toBe("paramValueTooLong");
    expect(validateParamValue("a b ".repeat(128))).toBeNull();
  });

  it("refuses a value that starts like a credential — the backend's prefix list, exactly", () => {
    // OpenAI/Stripe keys, Slack tokens, GitHub tokens, AWS key ids, JWTs, and
    // a pasted Authorization header. A prefix test, like the backend's: the
    // length of what follows is irrelevant.
    for (const value of [
      "sk-live-x",
      "xoxb-1234",
      "xoxp-1234",
      "ghp_abcdef",
      "gho_abcdef",
      "github_pat_abc",
      "AKIAIOSFODNN7EXAMPLE",
      "eyJhbGciOiJIUzI1NiJ9",
      "Bearer abc",
      "bearer abc",
      "Basic dXNlcjpwYXNz",
    ]) {
      expect(validateParamValue(value), value).toBe("paramValueCredentialShaped");
    }
  });

  it("accepts a long opaque value the backend accepts — no run-length heuristic", () => {
    // The mirror used to refuse any 32+ run of the token alphabet, which the
    // backend never did: a UUID audience or a 40-character opaque tenant id
    // was blocked here and saved there. And the prefixes are exact — `sk_`
    // is not `sk-`, and `Bearer` without a following space is a word.
    expect(validateParamValue("A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6")).toBeNull();
    expect(validateParamValue("6ba7b810-9dad-11d1-80b4-00c04fd430c8")).toBeNull();
    expect(validateParamValue("sk_live_" + "a".repeat(40))).toBeNull();
    expect(validateParamValue("Bearer")).toBeNull();
    expect(validateParamValue("basicauth")).toBeNull();
    // Not trimmed first: the backend anchors at the raw start, so a leading
    // space hides the prefix from both sides equally.
    expect(validateParamValue(" sk-live-x")).toBeNull();
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

  it("refuses a name a ${connection:…} reference could not carry", () => {
    expect(validateConnection({ ...STATIC_OK, name: "my connection" }).name).toBe(
      "nameFormat",
    );
    expect(validateConnection({ ...STATIC_OK, name: "acme/jira" }).name).toBe(
      "nameFormat",
    );
    // The backend's grammar starts with a letter or digit.
    expect(validateConnection({ ...STATIC_OK, name: "-jira" }).name).toBe("nameFormat");
    expect(validateConnection({ ...STATIC_OK, name: "jira_v2.prod-eu" }).name).toBeUndefined();
  });

  it("refuses surrounding whitespace, as the backend does — it is not trimmed away", () => {
    // The backend matches its grammar against the raw name and says so in its
    // own source ("Not trimmed"). The mirror used to trim first, so " jira"
    // passed here and was refused there.
    expect(validateConnection({ ...STATIC_OK, name: " jira" }).name).toBe("nameFormat");
    expect(validateConnection({ ...STATIC_OK, name: "jira " }).name).toBe("nameFormat");
    expect(validateConnection({ ...STATIC_OK, name: "jira" }).name).toBeUndefined();
  });

  it("refuses a name past 64 characters", () => {
    expect(validateConnection({ ...STATIC_OK, name: "a".repeat(64) }).name).toBeUndefined();
    expect(validateConnection({ ...STATIC_OK, name: "a".repeat(65) }).name).toBe(
      "nameTooLong",
    );
  });

  it("bounds the token-endpoint timeout, and lets null mean the default", () => {
    expect(validateConnection({ ...STATIC_OK, timeoutMs: null }).timeoutMs).toBeUndefined();
    expect(validateConnection({ ...STATIC_OK, timeoutMs: 8000 }).timeoutMs).toBeUndefined();
    expect(validateConnection({ ...STATIC_OK, timeoutMs: 60000 }).timeoutMs).toBeUndefined();
    expect(validateConnection({ ...STATIC_OK, timeoutMs: 0 }).timeoutMs).toBe("timeoutRange");
    expect(validateConnection({ ...STATIC_OK, timeoutMs: 60001 }).timeoutMs).toBe(
      "timeoutRange",
    );
    expect(validateConnection({ ...STATIC_OK, timeoutMs: 1.5 }).timeoutMs).toBe(
      "timeoutRange",
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

  it("rejects an extra parameter EDDI sets itself, case-insensitively", () => {
    const errors = validateConnection({
      ...OAUTH_OK,
      oauth: { ...OAUTH_OK.oauth, extraAuthParams: { "Redirect-Uri": "https://evil.example" } },
    });
    expect(errors["oauth.extraAuthParams"]).toBe("paramReserved");
  });

  it("rejects an extra parameter VALUE that is a reference, too long, or a key", () => {
    const withValue = (value: string) =>
      validateConnection({
        ...OAUTH_OK,
        oauth: { ...OAUTH_OK.oauth, extraAuthParams: { audience: value } },
      })["oauth.extraAuthParams"];
    expect(withValue("${vault:secret}")).toBe("paramValueReference");
    expect(withValue("x".repeat(513))).toBe("paramValueTooLong");
    expect(withValue("sk-live-abcdef")).toBe("paramValueCredentialShaped");
    expect(withValue("api.atlassian.com")).toBeUndefined();
    expect(withValue("A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6")).toBeUndefined();
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

  it("refuses a binding the auth type cannot carry", () => {
    // Both directions of the backend's coupling, on the field that broke it.
    expect(
      validateConnection({ ...STATIC_OK, authType: "BASIC", binding: "CALLER_SUPPLIED" })
        .binding,
    ).toBe("bindingMismatch");
    expect(validateConnection({ ...STATIC_OK, binding: "PER_USER" }).binding).toBe(
      "bindingMismatch",
    );
    expect(validateConnection({ ...OAUTH_OK, binding: "SERVICE" }).binding).toBe(
      "bindingMismatch",
    );
    expect(validateConnection({ ...STATIC_OK, binding: "SERVICE" }).binding).toBeUndefined();
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

// ─── CALLER_SUPPLIED — EDDI stores nothing ──────────────────────

const CALLER_SUPPLIED_OK = {
  name: "gnowbe",
  authType: "STATIC",
  binding: "CALLER_SUPPLIED",
  baseUrlAllowlist: ["https://api.gnowbe.com"],
  staticAuth: { headerName: "x-api-key" },
};

describe("validateConnection — a caller-supplied connection", () => {
  it("passes with only a header name — no template is required", () => {
    // The whole point of the binding: the value arrives with each request, so
    // the STATIC "template required" rule must not fire.
    expect(validateConnection(CALLER_SUPPLIED_OK)).toEqual({});
  });

  it("still requires the header name — the connection owns it whoever supplies the value", () => {
    expect(
      validateConnection({ ...CALLER_SUPPLIED_OK, staticAuth: { headerName: " " } })[
        "staticAuth.headerName"
      ],
    ).toBe("headerNameRequired");
  });

  it("refuses a stored template, which would race the caller's value", () => {
    const errors = validateConnection({
      ...CALLER_SUPPLIED_OK,
      staticAuth: { headerName: "x-api-key", valueTemplate: "Bearer ${vault:k}" },
    });
    expect(errors["staticAuth.valueTemplate"]).toBe("callerSuppliedRefused");
  });

  it("refuses a username and a password reference for the same reason", () => {
    const errors = validateConnection({
      ...CALLER_SUPPLIED_OK,
      staticAuth: {
        headerName: "x-api-key",
        username: "svc",
        passwordRef: "${vault:pw}",
      },
    });
    expect(errors["staticAuth.username"]).toBe("callerSuppliedRefused");
    expect(errors["staticAuth.passwordRef"]).toBe("callerSuppliedRefused");
  });

  it("treats a blank template as absent, as the backend does", () => {
    expect(
      validateConnection({
        ...CALLER_SUPPLIED_OK,
        staticAuth: { headerName: "x-api-key", valueTemplate: "  " },
      }),
    ).toEqual({});
  });
});
