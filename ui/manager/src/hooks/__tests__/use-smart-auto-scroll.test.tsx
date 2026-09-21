import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, act } from "@testing-library/react";
import { useSmartAutoScroll } from "../use-smart-auto-scroll";

/**
 * The reported bug: while a reply is streaming, the user cannot scroll up.
 *
 * The hook always had a "pause when the user scrolls away" branch, and it was
 * unreachable during a stream. Two reasons, both covered here:
 *
 *  1. Intent was inferred from the `scroll` event, which our own `scrollTo`
 *     also fires — so every token overwrote "the user scrolled up" with the
 *     position the auto-scroll had just produced.
 *  2. Each token restarted a *smooth* scroll, leaving the container in
 *     continuous programmatic motion, so there was no still moment in which a
 *     gesture could take effect.
 */
describe("useSmartAutoScroll", () => {
  /**
   * A scroll container jsdom can measure. jsdom reports 0 for every layout
   * property and implements no scrolling, so the geometry is stubbed and
   * `scrollTo` is recorded rather than performed.
   */
  function Harness({ tokens }: { tokens: number }) {
    const { scrollRef, handleScroll, showScrollFab } =
      useSmartAutoScroll<HTMLDivElement>({ deps: [tokens] });

    return (
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        data-testid="scroller"
        data-fab={showScrollFab ? "shown" : "hidden"}
      >
        {tokens}
      </div>
    );
  }

  /**
   * @param overflows
   *   false models a conversation short enough to fit the viewport — nothing to
   *   scroll, so `scrollTop` is pinned at 0 and no gesture produces a `scroll`
   *   event.
   */
  function mountScroller({ overflows = true }: { overflows?: boolean } = {}) {
    const calls: ScrollToOptions[] = [];
    const view = render(<Harness tokens={0} />);
    const el = view.getByTestId("scroller") as HTMLDivElement;

    Object.defineProperty(el, "scrollHeight", {
      value: overflows ? 1000 : 200,
      configurable: true,
    });
    Object.defineProperty(el, "clientHeight", { value: 200, configurable: true });
    let scrollTop = overflows ? 800 : 0;
    Object.defineProperty(el, "scrollTop", {
      configurable: true,
      get: () => scrollTop,
      set: (v: number) => {
        scrollTop = v;
      },
    });
    el.scrollTo = ((opts: ScrollToOptions) => {
      calls.push(opts);
      scrollTop = overflows ? 800 : 0;
    }) as HTMLElement["scrollTo"];

    return { view, el, calls, setScrollTop: (v: number) => (scrollTop = v) };
  }

  beforeEach(() => {
    vi.useRealTimers();
  });

  it("keeps following the bottom while tokens arrive and the user has not moved", () => {
    const { view, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBeGreaterThanOrEqual(2);
    expect(calls[calls.length - 1]?.top).toBe(1000);
  });

  it("uses instant scrolling for rapid updates, so the container is still between tokens", () => {
    const { view, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    // A smooth scroll restarted every few ms never settles, and a wheel gesture
    // is cancelled by the next one before it can travel a line.
    expect(calls[calls.length - 1]?.behavior).toBe("auto");
  });

  it("stops following as soon as the user wheels upward mid-stream", () => {
    const { view, el, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
    });

    act(() => {
      view.rerender(<Harness tokens={2} />);
    });
    act(() => {
      view.rerender(<Harness tokens={3} />);
    });

    expect(calls.length).toBe(before);
    expect(el.getAttribute("data-fab")).toBe("shown");
  });

  it("ignores a downward wheel — that is following along, not reading history", () => {
    const { view, el, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: 120, bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before + 1);
  });

  it("stops following on PageUp", () => {
    const { view, el, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new KeyboardEvent("keydown", { key: "PageUp", bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before);
  });

  it("ignores an upward gesture the container cannot act on", () => {
    // A short conversation that fits the viewport. Wheeling up scrolls nothing,
    // so no `scroll` event follows to correct the state — pausing here would
    // strand the user with a "Scroll to bottom" control while they are already
    // at the bottom, and stop the reply following.
    const { view, el, calls } = mountScroller({ overflows: false });

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before + 1);
    expect(el.getAttribute("data-fab")).toBe("hidden");
  });

  it("ignores PageUp at the very top, where there is nowhere further to go", () => {
    const { view, el, calls } = mountScroller({ overflows: false });

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new KeyboardEvent("keydown", { key: "PageUp", bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before + 1);
  });

  it("resumes following once the user scrolls back to the bottom", () => {
    const { view, el, calls, setScrollTop } = mountScroller();

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const paused = calls.length;

    // Back within the bottom threshold, reported through the container's own
    // scroll event exactly as the browser would.
    act(() => {
      setScrollTop(800);
      el.dispatchEvent(new Event("scroll", { bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(paused + 1);
  });
});
