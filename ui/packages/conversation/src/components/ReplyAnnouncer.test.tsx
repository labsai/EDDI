import { describe, expect, it } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
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

describe("ReplyAnnouncer — rebuilt transcripts and repeated words", () => {
  const user = (id: string, content: string): AnnouncedMessage => ({ id, role: "user", content });

  it("announces only the new reply of a transcript rebuilt with fresh ids (a rerun)", () => {
    const seen = new Set<string>();
    const before = [user("u1", "Q1"), agent("a1", "A1"), user("u2", "Q2"), agent("a2", "old answer")];
    nextAnnouncement(before, seen, speaker);
    const rebuilt = [user("x1", "Q1"), agent("x2", "A1"), user("x3", "Q2"), agent("x4", "new answer")];
    expect(nextAnnouncement(rebuilt, seen, speaker, before)).toBe("Bot said: new answer");
    const undone = [user("y1", "Q1"), agent("y2", "A1")];
    expect(nextAnnouncement(undone, seen, speaker, rebuilt)).toBeNull();
  });

  it("treats a short transcript of another conversation as history", () => {
    const { rerender } = render(<ReplyAnnouncer messages={[agent("a", "One")]} speaker={speaker} historyKey="c1" />);
    act(() => rerender(<ReplyAnnouncer messages={[user("u", "Hi"), agent("b", "Old reply")]} speaker={speaker} historyKey="c2" />));
    expect(screen.getByTestId("reply-announcer")).toHaveTextContent("");
  });

  it("re-announces a second reply with the same words", async () => {
    const { rerender } = render(<ReplyAnnouncer messages={[]} speaker={speaker} />);
    const region = screen.getByTestId("reply-announcer");
    act(() => rerender(<ReplyAnnouncer messages={[agent("a", "OK")]} speaker={speaker} />));
    expect(region).toHaveTextContent("Bot said: OK");
    act(() => rerender(<ReplyAnnouncer messages={[agent("a", "OK"), agent("b", "OK")]} speaker={speaker} />));
    expect(region).toHaveTextContent("");
    await waitFor(() => expect(region).toHaveTextContent("Bot said: OK"));
  });
});
