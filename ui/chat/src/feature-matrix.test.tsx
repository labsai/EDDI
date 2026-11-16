/* ──────────────────────────────────────────────
   Conversation feature matrix — the widget's column
   (frontend unification plan, §1.3 and phase 0)

   The same rows as ui/manager/e2e/feature-matrix.spec.ts, which holds the
   Manager's surfaces. This app has no Playwright tier, so its column renders
   the components that implement each capability. A `no` cell is `it.todo`
   with the reason; when a phase brings the capability here, it becomes an
   assertion.
   ────────────────────────────────────────────── */

import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

import { ChatProvider } from "@/store/chat-store";
import { MessageBubble } from "@/components/MessageBubble";
import { QuickReplies } from "@/components/QuickReplies";
import { SecretInput } from "@/components/SecretInput";
import { SUPPORTED_LANGUAGES } from "@eddi/ui";
import { LOCALE_LOADERS, initLocale, t } from "@/i18n";
import type { ChatMessage } from "@/types";

const agent = (content: string, extra: Partial<ChatMessage> = {}): ChatMessage => ({
  id: "m1",
  role: "agent",
  content,
  timestamp: 0,
  ...extra,
});

type Cell = { status: "yes"; check: () => Promise<void> | void } | { status: "no"; reason: string };

const WIDGET: Record<string, Cell> = {
  katex: {
    status: "yes",
    check: async () => {
      const { container } = render(<MessageBubble message={agent("$$x^2$$")} />);
      // The KaTeX chunk is large; its first import under jsdom takes seconds.
      await waitFor(() => expect(container.querySelector(".katex")).not.toBeNull(), {
        timeout: 10_000,
      });
    },
  },
  highlight: {
    status: "yes",
    check: async () => {
      const { container } = render(<MessageBubble message={agent("```js\nconst a = 1;\n```")} />);
      await waitFor(() => expect(container.querySelector("code.hljs")).not.toBeNull(), {
        timeout: 10_000,
      });
    },
  },
  "output-images": {
    status: "yes",
    check: () => {
      const { container } = render(
        <MessageBubble message={agent("", { images: [{ uri: "https://example.com/a.png", alt: "a" }] })} />,
      );
      expect(container.querySelector('img[src="https://example.com/a.png"]')).not.toBeNull();
    },
  },
  "quick-replies": {
    status: "yes",
    check: () => {
      render(<QuickReplies replies={[{ value: "Yes" }, { value: "No" }]} onSelect={() => {}} />);
      expect(screen.getByTestId("quick-replies").querySelectorAll("button")).toHaveLength(2);
    },
  },
  "input-field": {
    status: "yes",
    check: () => {
      render(
        <ChatProvider>
          <SecretInput subType="email" label="Email" onSend={() => {}} />
        </ChatProvider>,
      );
      expect(screen.getByTestId("secret-input-field")).toHaveAttribute("type", "email");
    },
  },
  "secret-input": {
    status: "yes",
    check: () => {
      render(
        <ChatProvider>
          <SecretInput label="Password" onSend={() => {}} />
        </ChatProvider>,
      );
      expect(screen.getByTestId("secret-input-field")).toHaveAttribute("type", "password");
    },
  },
  history: { status: "no", reason: "one conversation per URL; no list of past conversations" },
  "inline-hitl": {
    status: "no",
    reason: "the paused card is read-only (cancel only); deciding happens in the Manager's approvals page",
  },
  i18n: {
    status: "yes",
    check: async () => {
      // The Manager's eleven locales, each loaded on demand (phase 3a).
      expect(["en", ...Object.keys(LOCALE_LOADERS)].sort()).toEqual([...SUPPORTED_LANGUAGES].sort());
      await initLocale("de");
      try {
        expect(t("speaker.you")).toBe((await LOCALE_LOADERS.de()).default["speaker.you"]);
        await initLocale("ar");
        expect(document.documentElement.dir).toBe("rtl");
      } finally {
        await initLocale("en");
      }
    },
  },
};

describe("feature matrix: widget", () => {
  for (const [capability, cell] of Object.entries(WIDGET)) {
    const title = `${capability} @surface:widget @capability:${capability}`;
    if (cell.status === "yes") it(title, cell.check, 15_000);
    else it.todo(`${title} — not supported: ${cell.reason}`);
  }
});
