import { describe, it, expect, afterEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { useDocumentTitle } from "@/hooks/use-document-title";
import { type ReactNode } from "react";

function createWrapper(path: string) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={[path]}>
        {children}
      </MemoryRouter>
    );
  };
}

describe("useDocumentTitle", () => {
  afterEach(() => {
    document.title = "";
  });

  it("sets dashboard title for root /manage/ path", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/"),
    });
    expect(document.title).toContain("EDDI Manager");
  });

  it("sets dashboard title for /manage path", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage"),
    });
    expect(document.title).toContain("EDDI Manager");
  });

  it("sets title based on first path segment (agents)", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/agents"),
    });
    expect(document.title).toContain("EDDI Manager");
    expect(document.title).not.toBe("");
  });

  it("sets title based on conversations path", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/conversations"),
    });
    expect(document.title).toContain("EDDI Manager");
  });

  it("sets title for resources path", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/resources"),
    });
    expect(document.title).toContain("EDDI Manager");
  });

  it("sets title for logs path", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/logs"),
    });
    expect(document.title).toContain("EDDI Manager");
  });

  it("uses segment as fallback for unknown paths", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/unknown-page"),
    });
    expect(document.title).toContain("unknown-page");
    expect(document.title).toContain("EDDI Manager");
  });

  it("strips 'view' suffix from segment", () => {
    renderHook(() => useDocumentTitle(), {
      wrapper: createWrapper("/manage/agentsview"),
    });
    expect(document.title).toContain("EDDI Manager");
  });
});
