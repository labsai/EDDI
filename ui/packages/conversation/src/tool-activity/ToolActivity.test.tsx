import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { createInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import type { ReactNode } from "react";
import { ToolActivityCards, ToolTurnNotice, formatToolList, useToolTurns } from "./ToolActivity";
import { ToolActivityLog, type ToolTurnActivity } from "./tool-activity-log";
import { TOOL_ACTIVITY_ENGLISH, TOOL_ACTIVITY_LOCALE_LOADERS, withToolActivityLocales } from "./i18n";

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  lng: "en",
  resources: { en: { translation: TOOL_ACTIVITY_ENGLISH } },
  interpolation: { escapeValue: false },
});

const wrap = (ui: ReactNode) => render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);

const activity = (...results: ToolTurnActivity["results"]): ToolTurnActivity => ({ results, dropped: 0 });

describe("ToolActivityCards", () => {
  it("is collapsed by default and says how many tools ran and how many failed", () => {
    wrap(
      <ToolActivityCards
        activity={activity(
          { tool: "search", ok: true, summary: "3 hits" },
          { tool: "send_email", ok: false, summary: "HTTP 500" },
        )}
      />,
    );
    const toggle = screen.getByTestId("tool-activity-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(toggle).toHaveTextContent("Tools used: 2");
    expect(screen.getByTestId("tool-activity-failed")).toHaveTextContent("1 failed");
    expect(screen.queryByTestId("tool-card")).toBeNull();
  });

  it("opens to one card per call: name, outcome, summary", () => {
    wrap(
      <ToolActivityCards
        activity={activity(
          { tool: "search", ok: true, summary: "3 hits" },
          { tool: "send_email", ok: false, summary: "" },
        )}
      />,
    );
    fireEvent.click(screen.getByTestId("tool-activity-toggle"));
    expect(screen.getByTestId("tool-activity-toggle")).toHaveAttribute("aria-expanded", "true");
    const cards = screen.getAllByTestId("tool-card");
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent("search");
    expect(cards[0]).toHaveTextContent("Succeeded");
    expect(cards[0]).toHaveTextContent("3 hits");
    expect(cards[1]).toHaveTextContent("send_email");
    expect(cards[1]).toHaveTextContent("Failed");
    expect(cards[1]).toHaveTextContent("No details");
    expect(screen.queryByTestId("tool-activity-failed")).not.toBeNull();
  });

  it("shows a summary as plain text: HTML and markdown in it are never rendered", () => {
    const summary = '<img src=x onerror="alert(1)"><b>bold</b> **not bold** [link](https://evil.example)';
    const { container } = wrap(<ToolActivityCards activity={activity({ tool: "fetch", ok: true, summary })} />);
    fireEvent.click(screen.getByTestId("tool-activity-toggle"));
    expect(screen.getByTestId("tool-card-summary").textContent).toBe(summary);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("b")).toBeNull();
    expect(container.querySelector("strong")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
  });

  it("counts the calls past the cap", () => {
    wrap(<ToolActivityCards activity={{ results: [{ tool: "a", ok: true, summary: "" }], dropped: 4 }} />);
    expect(screen.getByTestId("tool-activity-toggle")).toHaveTextContent("Tools used: 5");
    fireEvent.click(screen.getByTestId("tool-activity-toggle"));
    expect(screen.getByTestId("tool-activity-dropped")).toHaveTextContent("+4 more");
  });

  it("follows the log: the cards of a turn appear as its calls finish", () => {
    const log = new ToolActivityLog();
    function Host() {
      const turns = useToolTurns(log, "c1");
      const turn = turns.get(1);
      return turn ? <ToolActivityCards activity={turn} /> : <p>none</p>;
    }
    wrap(<Host />);
    expect(screen.getByText("none")).toBeInTheDocument();
    act(() => log.record("c1", 1, { tool: "search", ok: true, summary: "" }));
    expect(screen.getByTestId("tool-activity-toggle")).toHaveTextContent("Tools used: 1");
    act(() => log.record("c1", 1, { tool: "lookup", ok: true, summary: "" }));
    expect(screen.getByTestId("tool-activity-toggle")).toHaveTextContent("Tools used: 2");
  });
});

describe("ToolTurnNotice", () => {
  it("names the tools a turn ran, each once, before Edit and Regenerate", () => {
    const ran = activity(
      { tool: "send_email", ok: true, summary: "" },
      { tool: "create_ticket", ok: true, summary: "" },
      { tool: "send_email", ok: true, summary: "" },
    );
    const { rerender } = wrap(<ToolTurnNotice activity={ran} action="edit" id="n" />);
    const notice = screen.getByTestId("tool-turn-notice");
    expect(notice).toHaveAttribute("id", "n");
    expect(notice).toHaveTextContent("Ran send_email and create_ticket. Editing takes back the reply, not what the tools did.");
    rerender(
      <I18nextProvider i18n={i18n}>
        <ToolTurnNotice activity={ran} action="regenerate" />
      </I18nextProvider>,
    );
    expect(screen.getByTestId("tool-turn-notice")).toHaveTextContent("a new reply may run them again");
  });

  it("says nothing for a turn that ran no tools", () => {
    const { container } = wrap(<ToolTurnNotice activity={undefined} action="edit" />);
    expect(container).toBeEmptyDOMElement();
    wrap(<ToolTurnNotice activity={activity()} action="regenerate" />);
    expect(screen.queryByTestId("tool-turn-notice")).toBeNull();
  });
});

describe("formatToolList", () => {
  const more = (n: number) => `${n} more`;
  it("lists up to three tools in the reader's language and counts the rest", () => {
    expect(formatToolList(["a"], "en", more)).toBe("a");
    expect(formatToolList(["a", "b", "c"], "en", more)).toBe("a, b, and c");
    expect(formatToolList(["a", "b", "c", "d", "e"], "en", more)).toBe("a, b, c, and 2 more");
    expect(formatToolList(["a", "b"], "de", more)).toBe("a und b");
  });
  it("falls back to commas for a language tag Intl refuses", () => {
    expect(formatToolList(["a", "b"], "not a tag!", more)).toBe("a, b");
  });
});

describe("tool activity strings", () => {
  const dir = join(__dirname, "i18n", "locales");
  const flatten = (obj: Record<string, unknown>, prefix = ""): Record<string, string> =>
    Object.entries(obj).reduce<Record<string, string>>((out, [k, v]) => {
      const key = prefix ? `${prefix}.${k}` : k;
      return v && typeof v === "object"
        ? { ...out, ...flatten(v as Record<string, unknown>, key) }
        : { ...out, [key]: String(v) };
    }, {});
  const read = (file: string) => flatten(JSON.parse(readFileSync(join(dir, file), "utf8")) as Record<string, unknown>);
  const placeholders = (s: string) => [...s.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]).sort();
  const en = read("en.json");
  const others = readdirSync(dir).filter((f) => f.endsWith(".json") && f !== "en.json");

  it("ship in the eleven locales, with a loader for each", () => {
    expect(others.sort()).toEqual(Object.keys(TOOL_ACTIVITY_LOCALE_LOADERS).map((l) => `${l}.json`).sort());
  });

  it.each(others)("%s has every English key, translated, with the same placeholders", (file) => {
    const table = read(file);
    expect(Object.keys(table).sort()).toEqual(Object.keys(en).sort());
    for (const [key, value] of Object.entries(table)) {
      expect([key, placeholders(value)]).toEqual([key, placeholders(en[key]!)]);
      // A real translation, not English copied over (a bare "+{{n}}" may match).
      if (en[key]!.replace(/\{\{\s*\w+\s*\}\}/g, "").replace(/[^A-Za-z]/g, "").length > 3) {
        expect([key, value]).not.toEqual([key, en[key]]);
      }
    }
  });

  it("merge under an app's bundle, the app winning a clash", async () => {
    const { english, loaders } = withToolActivityLocales(
      { toolActivity: { ok: "App OK" }, other: "x" },
      { de: async () => ({ default: { toolActivity: { ok: "App-OK" } } }) },
    );
    const tools = english.toolActivity as Record<string, unknown>;
    expect(tools.ok).toBe("App OK");
    expect(tools.failed).toBe("Failed");
    expect(english.other).toBe("x");
    const de = (await loaders.de()).default.toolActivity as Record<string, unknown>;
    expect(de.ok).toBe("App-OK");
    expect(de.failed).toBe("Fehlgeschlagen");
  });
});

describe("the Chat UI widget", () => {
  // It shows no tool activity and is at its bundle budget: nothing of this
  // entry, or of the tool_result parser, may reach it.
  const chatSrc = join(__dirname, "..", "..", "..", "..", "chat", "src");
  const files = (dir: string): string[] =>
    readdirSync(dir).flatMap((name) => {
      const full = join(dir, name);
      return statSync(full).isDirectory() ? files(full) : /\.(ts|tsx)$/.test(name) ? [full] : [];
    });

  it("imports neither @eddi/conversation/tool-activity nor @eddi/core/tool-events", () => {
    const offenders = files(chatSrc).filter((f) =>
      /@eddi\/conversation\/tool-activity|@eddi\/core\/tool-events/.test(readFileSync(f, "utf8")),
    );
    expect(files(chatSrc).length).toBeGreaterThan(10);
    expect(offenders).toEqual([]);
  });
});
