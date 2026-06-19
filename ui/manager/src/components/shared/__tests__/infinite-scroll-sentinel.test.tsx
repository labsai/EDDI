import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { InfiniteScrollSentinel } from "@/components/shared/infinite-scroll-sentinel";

// Mock IntersectionObserver
const observe = vi.fn();
const disconnect = vi.fn();
vi.stubGlobal(
  "IntersectionObserver",
  vi.fn((cb: IntersectionObserverCallback) => {
    // immediately call with isIntersecting=true
    setTimeout(() => cb([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver), 0);
    return { observe, disconnect, unobserve: vi.fn() };
  })
);

describe("InfiniteScrollSentinel", () => {
  it("renders nothing when hasMore is false", () => {
    const { container } = renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={false}
        hasMore={false}
      />
    );
    expect(container.innerHTML).toBe("");
  });

  it("renders sentinel div when hasMore is true", () => {
    const { container } = renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={false}
        hasMore={true}
      />
    );
    const sentinel = container.querySelector("[aria-live='polite']");
    expect(sentinel).not.toBeNull();
  });

  it("shows spinner when isFetchingMore is true", () => {
    const { container } = renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={true}
        hasMore={true}
      />
    );
    const spinner = container.querySelector(".animate-spin");
    expect(spinner).not.toBeNull();
  });

  it("shows sr-only loading text when fetching", () => {
    renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={true}
        hasMore={true}
      />
    );
    expect(screen.getByText("Loading more items…")).toBeInTheDocument();
  });

  it("does not show spinner when not fetching", () => {
    const { container } = renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={false}
        hasMore={true}
      />
    );
    const spinner = container.querySelector(".animate-spin");
    expect(spinner).toBeNull();
  });

  it("sets up IntersectionObserver when hasMore and not fetching", () => {
    observe.mockClear();
    renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={false}
        hasMore={true}
      />
    );
    expect(observe).toHaveBeenCalled();
  });

  it("does not set up observer when isFetchingMore", () => {
    observe.mockClear();
    renderWithProviders(
      <InfiniteScrollSentinel
        onLoadMore={vi.fn()}
        isFetchingMore={true}
        hasMore={true}
      />
    );
    expect(observe).not.toHaveBeenCalled();
  });
});
