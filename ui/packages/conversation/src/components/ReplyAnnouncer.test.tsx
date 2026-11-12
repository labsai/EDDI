import { describe, expect, it } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { nextAnnouncement, ReplyAnnouncer, speakable, type AnnouncedMessage } from "./ReplyAnnouncer";
import { indicatorStatusText, ThinkingIndicator } from "./ActivityIndicator";

const speaker = () => "Bot said:";
const agent = (id: string, content: string, extra: Partial<AnnouncedMessage> = {}): AnnouncedMessage => ({
  id,
  role: "agent",
  content,
  ...extra,
});

describe("nextAnnouncement", () => {
  it("says a completed reply once, with its speaker, and never a streaming one", () => {
    const seen = new Set<string>();
    expect(nextAnnouncement([agent("a", "Hel", { isStreaming: true })], seen, speaker)).toBeNull();
    expect(nextAnnouncement([agent("a", "Hello")], seen, speaker)).toBe("Bot said: Hello");
    expect(nextAnnouncement([agent("a", "Hello")], seen, speaker)).toBeNull();
  });

  it("reads a notice without a speaker, and never the person's own message", () => {
    const seen = new Set<string>();
    const out = nextAnnouncement(
      [{ id: "u", role: "user", content: "Hi" }, agent("n", "Stop came too late.", { kind: "notice" })],
      seen,
      speaker,
    );
    expect(out).toBe("Stop came too late.");
  });

  it("stays silent when a whole transcript arrives at once (a conversation was opened)", () => {
    const seen = new Set<string>();
    const loaded = ["1", "2", "3", "4"].map((id) => agent(id, `reply ${id}`));
    expect(nextAnnouncement(loaded, seen, speaker)).toBeNull();
    expect(nextAnnouncement([...loaded, agent("5", "new")], seen, speaker)).toBe("Bot said: new");
  });

  it("reads markdown as words and cuts a long reply", () => {
    expect(speakable("**Bold** and [a link](https://x.test) `code`\n```js\nlet x\n```")).toBe("Bold and a link code");
    expect(speakable("x".repeat(700)).length).toBeLessThanOrEqual(601);
  });
});

describe("ReplyAnnouncer", () => {
  it("is a polite status region that ignores what was on screen at mount", () => {
    const { rerender } = render(<ReplyAnnouncer messages={[agent("old", "Earlier")]} speaker={speaker} />);
    const region = screen.getByTestId("reply-announcer");
    expect(region).toHaveAttribute("role", "status");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toHaveTextContent("");
    act(() => rerender(<ReplyAnnouncer messages={[agent("old", "Earlier"), agent("new", "Fresh")]} speaker={speaker} />));
    expect(region).toHaveTextContent("Bot said: Fresh");
  });
});

describe("reconnecting", () => {
  it("wins over every other activity state, in the indicator and its announcement", () => {
    expect(indicatorStatusText(true, "calculator", undefined, true, true)).toBe("Connection lost. Reconnecting…");
    render(<ThinkingIndicator tool="calculator" reconnecting />);
    expect(screen.getByTestId("reconnecting-indicator")).toHaveTextContent("Connection lost. Reconnecting…");
  });
});
