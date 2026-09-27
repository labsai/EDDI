import { StrictMode } from "react";
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../auth-provider";
import { useAuth } from "@/hooks/use-auth";
import { api } from "@/lib/api-client";
import { userEvent } from "@/test/test-utils";

/**
 * The Keycloak half of AuthProvider, against a controllable fake.
 *
 * Findings covered: StrictMode could never authenticate (double init), a failed
 * init rendered the app unauthenticated with no way to sign in, roles were read
 * once and never refreshed, and nothing user-scoped was cleared at logout.
 */

const kc = vi.hoisted(() => ({
  instances: [] as unknown[],
  initImpl: null as null | (() => Promise<boolean>),
}));

vi.mock("keycloak-js", () => ({
  default: class FakeKeycloak {
    didInitialize = false;
    token: string | undefined = undefined;
    refreshToken: string | undefined = undefined;
    idToken: string | undefined = undefined;
    tokenParsed: Record<string, unknown> = {};
    onTokenExpired: (() => void) | undefined = undefined;
    initCalls = 0;
    logout = vi.fn(async () => {});
    login = vi.fn(async () => {});
    updateToken = vi.fn(async () => false);
    constructor() {
      kc.instances.push(this);
    }
    async init() {
      this.initCalls++;
      // Mirrors keycloak-js: a second init on one instance throws.
      if (this.didInitialize) {
        throw new Error("A 'Keycloak' instance can only be initialized once.");
      }
      this.didInitialize = true;
      if (kc.initImpl) return kc.initImpl();
      this.token = "token-1";
      this.refreshToken = "refresh-1";
      this.tokenParsed = {
        preferred_username: "alice",
        realm_access: { roles: ["eddi-viewer"] },
      };
      return true;
    }
    async loadUserInfo() {
      return { preferred_username: "alice" };
    }
  },
}));

type FakeInstance = {
  initCalls: number;
  token?: string;
  tokenParsed: Record<string, unknown>;
  onTokenExpired?: () => void;
  updateToken: ReturnType<typeof vi.fn>;
  logout: ReturnType<typeof vi.fn>;
};

function lastInstance(): FakeInstance {
  // StrictMode runs the useState initializer twice and keeps one result; the
  // kept instance is the one that was initialised.
  return (kc.instances as FakeInstance[]).find((i) => i.initCalls > 0)!;
}

function Probe() {
  const { authenticated, roles, user, logout } = useAuth();
  return (
    <div>
      <span data-testid="status">{authenticated ? "in" : "out"}</span>
      <span data-testid="user">{user?.username ?? ""}</span>
      <span data-testid="roles">{roles.join(",")}</span>
      <button data-testid="logout" onClick={logout}>
        logout
      </button>
    </div>
  );
}

const CONFIG = {
  method: "keycloak" as const,
  url: "http://localhost:8180",
  realm: "eddi",
  clientId: "eddi-manager",
};

function renderStrict() {
  return render(
    <StrictMode>
      <AuthProvider configOverride={CONFIG}>
        <Probe />
      </AuthProvider>
    </StrictMode>,
  );
}

describe("AuthProvider (Keycloak)", () => {
  afterEach(() => {
    kc.instances.length = 0;
    kc.initImpl = null;
    api.setTokenRefresher(null);
    api.clearAuthToken();
  });

  it("signs in under React StrictMode — init runs once, not twice", async () => {
    renderStrict();
    expect(await screen.findByTestId("status")).toHaveTextContent("in");
    expect(screen.getByTestId("user")).toHaveTextContent("alice");
    // Every instance StrictMode created was initialised at most once.
    for (const instance of kc.instances as FakeInstance[]) {
      expect(instance.initCalls).toBeLessThanOrEqual(1);
    }
    expect(api.getAuthHeader()).toEqual({ Authorization: "Bearer token-1" });
  });

  it("shows a sign-in error instead of an unauthenticated app when init fails", async () => {
    kc.initImpl = () => Promise.reject(new Error("Failed to fetch"));
    vi.spyOn(console, "error").mockImplementation(() => {});
    renderStrict();
    expect(await screen.findByTestId("auth-init-failed")).toBeInTheDocument();
    expect(screen.getByTestId("auth-retry")).toBeInTheDocument();
    expect(screen.queryByTestId("status")).not.toBeInTheDocument();
  });

  it("says the sign-in did not complete — not that Keycloak is unreachable — on an OAuth error", async () => {
    // A user who cancels on the Keycloak page comes back with
    // error=access_denied; keycloak-js rejects init() with a plain object.
    kc.initImpl = () => Promise.reject({ error: "access_denied", error_description: "" });
    vi.spyOn(console, "error").mockImplementation(() => {});
    renderStrict();
    expect(await screen.findByTestId("auth-incomplete")).toHaveTextContent(
      "Sign-in did not complete",
    );
    expect(screen.getByTestId("auth-sign-in")).toBeInTheDocument();
    expect(screen.queryByTestId("auth-init-failed")).not.toBeInTheDocument();
  });

  it("on a lost session stops sending the dead token and leaves the redirect to keycloak-js", async () => {
    renderStrict();
    await screen.findByTestId("status");
    const instance = lastInstance() as FakeInstance & {
      refreshToken?: string;
      login: ReturnType<typeof vi.fn>;
    };
    instance.updateToken.mockImplementation(async () => {
      // keycloak-js on a 400 from the token endpoint: clears its tokens (and
      // itself calls login(), because init ran with login-required).
      instance.refreshToken = undefined;
      instance.token = undefined;
      throw new Error("Server responded with an invalid status.");
    });
    await act(async () => {
      instance.onTokenExpired?.();
    });
    expect(api.getAuthHeader()).toEqual({});
    // No second login() on top of keycloak-js's own.
    expect(instance.login).not.toHaveBeenCalled();
  });

  it("on a lost session stops presenting the app as signed in", async () => {
    // The gate used to stay "ready": `authenticated` stayed true and the app
    // stayed mounted, every call 401ing, on a session that was gone.
    const refresherSet = vi.spyOn(api, "setTokenRefresher");
    renderStrict();
    expect(await screen.findByTestId("status")).toHaveTextContent("in");
    const instance = lastInstance() as FakeInstance & {
      refreshToken?: string;
      login: ReturnType<typeof vi.fn>;
    };
    instance.updateToken.mockImplementation(async () => {
      instance.refreshToken = undefined;
      instance.token = undefined;
      throw new Error("Server responded with an invalid status.");
    });
    await act(async () => {
      instance.onTokenExpired?.();
    });

    expect(await screen.findByTestId("auth-signed-out")).toBeInTheDocument();
    expect(screen.queryByTestId("status")).not.toBeInTheDocument();
    // Leaving "ready" detached the refresher and the expiry hook.
    expect(refresherSet).toHaveBeenLastCalledWith(null);
    expect(instance.onTokenExpired).toBeUndefined();

    // The screen's way out is a sign-in on the (still initialised) adapter.
    await userEvent.setup().click(screen.getByTestId("auth-sign-in"));
    expect(instance.login).toHaveBeenCalledTimes(1);
    refresherSet.mockRestore();
  });

  it("a refresh that lands after logout does not republish its token", async () => {
    // Detaching the refresher does not cancel a refresh already awaiting
    // Keycloak; its late success used to put a bearer token back into the API
    // client that logout had just cleared.
    const user = userEvent.setup();
    renderStrict();
    await screen.findByTestId("status");
    const instance = lastInstance();
    let finish: () => void = () => {};
    instance.updateToken.mockImplementation(
      () =>
        new Promise<boolean>((resolve) => {
          finish = () => {
            instance.token = "token-2";
            resolve(true);
          };
        }),
    );
    await act(async () => {
      instance.onTokenExpired?.(); // refresh now pending
    });

    await user.click(screen.getByTestId("logout"));
    expect(api.getAuthHeader()).toEqual({});
    await act(async () => {
      finish();
    });

    expect(api.getAuthHeader()).toEqual({});
  });

  it("a refresh that settles after the provider unmounts publishes nothing", async () => {
    const view = renderStrict();
    await screen.findByTestId("status");
    const instance = lastInstance();
    let finish: () => void = () => {};
    instance.updateToken.mockImplementation(
      () =>
        new Promise<boolean>((resolve) => {
          finish = () => {
            instance.token = "token-2";
            resolve(true);
          };
        }),
    );
    await act(async () => {
      instance.onTokenExpired?.();
    });
    view.unmount();
    const published = vi.spyOn(api, "setAuthToken");
    await act(async () => {
      finish();
    });

    expect(published).not.toHaveBeenCalled();
    published.mockRestore();
  });

  it("re-reads realm roles when the token is refreshed", async () => {
    renderStrict();
    expect(await screen.findByTestId("roles")).toHaveTextContent("eddi-viewer");

    const instance = lastInstance();
    instance.updateToken.mockImplementation(async () => {
      instance.token = "token-2";
      instance.tokenParsed = {
        ...instance.tokenParsed,
        realm_access: { roles: ["eddi-viewer", "eddi-admin"] },
      };
      return true;
    });
    await act(async () => {
      instance.onTokenExpired?.();
    });

    expect(await screen.findByText("eddi-viewer,eddi-admin")).toBeInTheDocument();
    expect(api.getAuthHeader()).toEqual({ Authorization: "Bearer token-2" });
  });

  it("clears this user's browser-held Workforce threads at logout", async () => {
    localStorage.setItem("workforce-threads:alice", "[]");
    localStorage.setItem("workforce-threads", "[]");
    localStorage.setItem("workforce-threads:bob", '["bob"]');
    localStorage.setItem("workforce-templates:alice", "[]");
    const user = userEvent.setup();
    renderStrict();
    await screen.findByTestId("status");

    await user.click(screen.getByTestId("logout"));

    expect(localStorage.getItem("workforce-threads:alice")).toBeNull();
    expect(localStorage.getItem("workforce-threads")).toBeNull();
    // Another user of this browser keeps their threads.
    expect(localStorage.getItem("workforce-threads:bob")).toBe('["bob"]');
    // Templates exist only in this browser; they are per-user, not wiped.
    expect(localStorage.getItem("workforce-templates:alice")).toBe("[]");
    expect(lastInstance().logout).toHaveBeenCalled();
    expect(api.getAuthHeader()).toEqual({});
  });
});
