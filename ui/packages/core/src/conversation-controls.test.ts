/* `parseConversationControls`: the profile's `conversationControls` (B4),
   with every default applied — and `rawHtml` off unless explicitly on (D9). */

import { describe, it, expect } from "vitest";
import { parseConversationControls } from "./conversation";

describe("parseConversationControls", () => {
  it("offers every control and renders no HTML when the server says nothing", () => {
    const expected = {
      undo: true,
      redo: true,
      attachments: true,
      rating: true,
      quickReplies: true,
      streaming: true,
      rawHtml: false,
    };
    expect(parseConversationControls(undefined)).toEqual(expected);
    expect(parseConversationControls(null)).toEqual(expected);
    expect(parseConversationControls("rawHtml")).toEqual(expected);
    expect(parseConversationControls({})).toEqual(expected);
  });

  it("switches a control off only on an explicit false", () => {
    const controls = parseConversationControls({
      undo: false,
      redo: "false",
      attachments: false,
      rating: 0,
      quickReplies: false,
      streaming: false,
    });
    expect(controls.undo).toBe(false);
    expect(controls.redo).toBe(true);
    expect(controls.attachments).toBe(false);
    expect(controls.rating).toBe(true);
    expect(controls.quickReplies).toBe(false);
    expect(controls.streaming).toBe(false);
  });

  it("renders raw HTML only on an explicit true", () => {
    expect(parseConversationControls({ rawHtml: true }).rawHtml).toBe(true);
    expect(parseConversationControls({ rawHtml: "true" }).rawHtml).toBe(false);
    expect(parseConversationControls({ rawHtml: 1 }).rawHtml).toBe(false);
    expect(parseConversationControls({ rawHtml: false }).rawHtml).toBe(false);
  });
});
