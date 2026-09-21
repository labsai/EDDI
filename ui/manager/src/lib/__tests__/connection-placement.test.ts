import { describe, it, expect } from "vitest";
import {
  connectionHeaderName,
  expectedHeaderFor,
  headerNameMismatch,
} from "@/lib/connection-placement";

/**
 * The header rule: a header referencing a connection must be named what the
 * connection names. Mirrored from EDDI's build-time check so the editor can
 * say so beside the header instead of at deploy time.
 */

const STATIC_X_API_KEY = {
  authType: "STATIC" as const,
  staticAuth: { headerName: "x-api-key", valueTemplate: "${vault:k}" },
};
const BASIC_CUSTOM = {
  authType: "BASIC" as const,
  staticAuth: { headerName: "X-Legacy-Auth", username: "u", passwordRef: "${vault:p}" },
};
const OAUTH = { authType: "OAUTH2_AUTHORIZATION_CODE" as const, staticAuth: null };

describe("connectionHeaderName", () => {
  it("reads staticAuth.headerName for STATIC and BASIC", () => {
    expect(connectionHeaderName(STATIC_X_API_KEY)).toBe("x-api-key");
    expect(connectionHeaderName(BASIC_CUSTOM)).toBe("X-Legacy-Auth");
  });

  it("falls back to Authorization when a static header name is blank", () => {
    expect(
      connectionHeaderName({ authType: "STATIC", staticAuth: { headerName: " " } }),
    ).toBe("Authorization");
  });

  it("is always Authorization for an OAuth connection", () => {
    expect(connectionHeaderName(OAUTH)).toBe("Authorization");
    expect(connectionHeaderName({ ...OAUTH, authType: "OAUTH2_CLIENT_CREDENTIALS" })).toBe(
      "Authorization",
    );
  });
});

describe("headerNameMismatch", () => {
  it("compares case-insensitively, as HTTP does", () => {
    expect(headerNameMismatch("authorization", "Authorization")).toBe(false);
    expect(headerNameMismatch("X-API-KEY", "x-api-key")).toBe(false);
    expect(headerNameMismatch("X-Auth", "Authorization")).toBe(true);
  });
});

describe("expectedHeaderFor", () => {
  const lookup = (name: string) =>
    ({ gnowbe: STATIC_X_API_KEY, jira: OAUTH })[name];

  it("names the header the connection requires when the header disagrees", () => {
    expect(expectedHeaderFor("Authorization", "${connection:gnowbe}", lookup)).toBe(
      "x-api-key",
    );
    expect(expectedHeaderFor("X-Auth", "${connection:jira}", lookup)).toBe("Authorization");
  });

  it("stays silent when the names agree, in any case", () => {
    expect(expectedHeaderFor("X-Api-Key", "${connection:gnowbe}", lookup)).toBeNull();
    expect(expectedHeaderFor("authorization", "${connection:jira}", lookup)).toBeNull();
  });

  it("stays silent on an unknown connection — no evidence, no accusation", () => {
    // The lookup is fed from a list only an editor or admin can fetch.
    expect(expectedHeaderFor("X-Auth", "${connection:missing}", lookup)).toBeNull();
  });

  it("stays silent on a value that is not a bare reference", () => {
    // The wrapped shape is a different rule with its own message.
    expect(expectedHeaderFor("X-Auth", "Bearer ${connection:jira}", lookup)).toBeNull();
    expect(expectedHeaderFor("X-Auth", "${vault:jira}", lookup)).toBeNull();
  });
});
