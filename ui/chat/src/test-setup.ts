import "@testing-library/jest-dom/vitest";

// jsdom does not implement window.matchMedia
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

// jsdom does not implement Element.scrollIntoView (used for auto-scroll)
Element.prototype.scrollIntoView = () => {};
