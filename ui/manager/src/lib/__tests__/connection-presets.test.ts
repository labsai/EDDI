import { describe, expect, it } from "vitest";
import { CONNECTION_PRESETS, applyPreset } from "../connection-presets";
import { emptyConnection, toStoredConnection } from "../api/connections";
import { isThumbprint, validateConnection } from "../connection-validation";

const preset = (id: string) => {
  const found = CONNECTION_PRESETS.find((p) => p.id === id);
  if (!found) throw new Error(`preset ${id} missing`);
  return found;
};

describe("connection presets", () => {
  it("loads the JSON presets, each with a known auth type", () => {
    const ids = CONNECTION_PRESETS.map((p) => p.id);
    expect(ids).toContain("google-service-account");
    expect(ids).toContain("entra-app-certificate");
  });

  it("never carries a literal secret — every key is a vault reference", () => {
    for (const p of CONNECTION_PRESETS) {
      const key = p.connection.oauth?.privateKey;
      if (key) expect(key).toMatch(/^\$\{vault:[^}]+\}$/);
      expect(p.connection.oauth?.clientSecret ?? null).toBeNull();
    }
  });

  it("Google: a JWT bearer service account that validates as soon as it has a name", () => {
    const draft = applyPreset(preset("google-service-account"), {
      name: "google",
      description: "",
    });
    expect(draft.authType).toBe("OAUTH2_JWT_BEARER");
    expect(draft.binding).toBe("SERVICE");
    const stored = toStoredConnection(draft);
    // A blank client id is sent as null — the key file names the account.
    expect(stored.oauth?.clientId).toBeNull();
    expect(stored.oauth?.clientSecret).toBeNull();
    expect(stored.oauth?.authorizationUrl).toBeNull();
    expect(validateConnection(stored)).toEqual({});
  });

  it("Entra: private_key_jwt on client credentials, still asking for the client id", () => {
    const draft = applyPreset(preset("entra-app-certificate"), {
      name: "entra",
      description: "kept",
    });
    expect(draft.description).toBe("kept");
    expect(draft.authType).toBe("OAUTH2_CLIENT_CREDENTIALS");
    const stored = toStoredConnection(draft);
    expect(stored.oauth?.clientAuthMethod).toBe("private_key_jwt");
    expect(stored.oauth?.clientSecret).toBeNull();
    expect(stored.oauth?.privateKey).toBe("${vault:entra-app-private-key}");
    expect(validateConnection(stored)["oauth.clientId"]).toBe("clientIdRequired");
  });
});

describe("signed-JWT conversion and validation", () => {
  it("a blank JWT bearer connection asks for its private key, not a client secret or id", () => {
    const errors = validateConnection(
      toStoredConnection({ ...emptyConnection("OAUTH2_JWT_BEARER"), name: "g" }),
    );
    expect(errors["oauth.privateKey"]).toBe("secretMustBeReference");
    expect(errors["oauth.clientSecret"]).toBeUndefined();
    expect(errors["oauth.clientId"]).toBeUndefined();
  });

  it("switching back to client_secret_basic drops the private key and thumbprint from what is sent", () => {
    const draft = applyPreset(preset("entra-app-certificate"), { name: "e", description: "" });
    const stored = toStoredConnection({
      ...draft,
      oauth: { ...draft.oauth, clientAuthMethod: "client_secret_basic", clientSecret: "${vault:s}" },
    });
    expect(stored.oauth?.privateKey).toBeNull();
    expect(stored.oauth?.certificateThumbprint).toBeNull();
    expect(stored.oauth?.clientSecret).toBe("${vault:s}");
  });

  it("checks the thumbprint length against its header", () => {
    const sha256Hex = "AB".repeat(32);
    const sha1Hex = "0123456789abcdef0123456789abcdef01234567";
    expect(isThumbprint(sha256Hex, "x5t#S256")).toBe(true);
    expect(isThumbprint(sha256Hex.match(/../g)!.join(":"), "x5t#S256")).toBe(true);
    expect(isThumbprint(sha1Hex, "x5t#S256")).toBe(false);
    expect(isThumbprint(sha1Hex, "x5t")).toBe(true);
    // 43 base64url characters are 32 bytes.
    expect(isThumbprint("q83vASNFZ4mrze8BI0VniavN7wEjRWeJq83vASNFZ4k", "x5t#S256")).toBe(true);
    expect(isThumbprint("not a thumbprint", "x5t#S256")).toBe(false);
  });
});
