import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "@eddi/app-kit/components/auth/auth-provider";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import { clearWorkforceThreads, threadsStorageKey } from "@eddi/app-kit/lib/workforce-threads-storage";

const kc = vi.hoisted(() => ({
  init: vi.fn(),
  logout: vi.fn(),
  login: vi.fn(),
  updateToken: vi.fn(),
}));

vi.mock("keycloak-js", () => ({
  default: class MockKeycloak {
    token = "tok";
    idToken = "id";
    tokenParsed = {};
    onTokenExpired: (() => void) | null = null;
    init = kc.init;
    login = kc.login;
    logout = kc.logout;
    updateToken = kc.updateToken;
    loadUserInfo() {
      return Promise.resolve({});
    }
  },
}));

const config = { method: "keycloak" as const, url: "http://localhost:8180", realm: "eddi", clientId: "eddi-manager" };

function SignOut() {
  const { logout } = useAuth();
  return <button onClick={logout}>sign out</button>;
}

describe("AuthProvider — sign-out is the app's own", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    kc.init.mockResolvedValue(true);
    kc.updateToken.mockResolvedValue(true);
  });

  it.each([
    ["manager", "/manage"],
    ["workforce", "/workforce"],
  ] as const)("%s signs out back to %s, not to the other app", async (app, path) => {
    render(
      <AuthProvider configOverride={config} app={app}>
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(kc.logout).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}${path}` });
  });

  it("defaults to the Manager", async () => {
    render(
      <AuthProvider configOverride={config}>
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(kc.logout).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}/manage` });
  });

  it("sign-out removes every thread pointer, this person's and the unscoped legacy key, and only those", async () => {
    localStorage.setItem(threadsStorageKey("alice"), "[]");
    localStorage.setItem("workforce-threads", "[]");
    localStorage.setItem("eddi-theme", "dark");
    render(
      <AuthProvider configOverride={config} app="workforce">
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(localStorage.getItem(threadsStorageKey("alice"))).toBeNull();
    expect(localStorage.getItem("workforce-threads")).toBeNull();
    expect(localStorage.getItem("eddi-theme")).toBe("dark");
  });

  it("a failed Workforce sign-in does not call itself the Manager", async () => {
    kc.init.mockResolvedValue(false);
    render(
      <AuthProvider configOverride={config} app="workforce">
        <div />
      </AuthProvider>,
    );
    const error = await screen.findByTestId("auth-error");
    expect(error).toHaveTextContent(/Workforce could not complete sign-in/);
    expect(error).not.toHaveTextContent(/The Manager/);
  });
});

describe("thread pointers are per principal", () => {
  beforeEach(() => localStorage.clear());

  it("two people on one browser get two keys; no one gets the unscoped key", () => {
    expect(threadsStorageKey("alice")).not.toBe(threadsStorageKey("bob"));
    expect(threadsStorageKey(null)).toBe(threadsStorageKey("  "));
    expect(threadsStorageKey("alice")).not.toBe("workforce-threads");
  });

  it("clearing leaves other keys alone", () => {
    localStorage.setItem(threadsStorageKey("bob"), "[]");
    localStorage.setItem("workforce-pins-bob:b1", "[]");
    clearWorkforceThreads();
    expect(localStorage.getItem(threadsStorageKey("bob"))).toBeNull();
    expect(localStorage.getItem("workforce-pins-bob:b1")).toBe("[]");
  });
});
