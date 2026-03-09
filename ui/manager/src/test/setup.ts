import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeAll, afterAll, vi } from "vitest";
import { server } from "./mocks/server";

// Mock keycloak-js globally so auth-provider doesn't try to connect
vi.mock("keycloak-js", () => ({
  default: class MockKeycloak {
    authenticated = false;
    token = "";
    tokenParsed = {};
    onTokenExpired: (() => void) | null = null;
    init() {
      return Promise.resolve(false);
    }
    login() {
      return Promise.resolve();
    }
    logout() {
      return Promise.resolve();
    }
    updateToken() {
      return Promise.resolve(false);
    }
    loadUserProfile() {
      return Promise.resolve({});
    }
  },
}));

// Mock window.matchMedia for theme-provider (JSDOM doesn't implement it)
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: query === "(prefers-color-scheme: dark)",
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

import "@/i18n/config";

// Start MSW server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));

// Reset handlers after each test
afterEach(() => {
  cleanup();
  server.resetHandlers();
});

// Clean up after all tests
afterAll(() => server.close());
