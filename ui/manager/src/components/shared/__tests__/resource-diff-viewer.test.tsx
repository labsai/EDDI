import { describe, it, expect } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";

function renderDiff(source: string | null, target: string | null) {
  return render(
    <ResourceDiffViewer sourceContent={source} targetContent={target} />
  );
}

/**
 * Rendered diff rows, optionally narrowed to one kind.
 *
 * Asserting through the row selector rather than on loose text is what lets a
 * test say "this line is an ADDITION" — the thing that actually distinguishes a
 * working diff from a whole-document rewrite, and the one thing a colour class
 * cannot be queried for.
 */
function rowTexts(kind?: "added" | "removed" | "context"): string[] {
  return screen
    .getAllByTestId("diff-line")
    .filter((row) => !kind || row.getAttribute("data-diff-kind") === kind)
    // The marker gutter is a sibling span; drop it so assertions read as content.
    .map((row) => row.lastElementChild?.textContent ?? "");
}

describe("ResourceDiffViewer", () => {
  it("renders nothing when both contents are null", () => {
    const { container } = renderDiff(null, null);
    expect(container.innerHTML).toBe("");
  });

  it("shows 'Content identical' when source equals target", () => {
    renderDiff('{"key": "value"}', '{"key": "value"}');
    expect(screen.getByText("Content identical")).toBeInTheDocument();
  });

  it("shows 'Content identical' even with different key order (deep-sort)", () => {
    renderDiff(
      '{"b": 1, "a": 2}',
      '{"a": 2, "b": 1}'
    );
    expect(screen.getByText("Content identical")).toBeInTheDocument();
  });

  it("shows 'Content identical' with nested key reorder", () => {
    renderDiff(
      '{"outer": {"z": 1, "a": 2}}',
      '{"outer": {"a": 2, "z": 1}}'
    );
    expect(screen.getByText("Content identical")).toBeInTheDocument();
  });

  it("shows diff lines when content differs", () => {
    renderDiff(
      '{"key": "new-value"}',
      '{"key": "old-value"}'
    );
    // Should show + and − markers
    expect(screen.getByText("+")).toBeInTheDocument();
    expect(screen.getByText("−")).toBeInTheDocument();
  });

  it("handles malformed JSON gracefully (raw text diff)", () => {
    renderDiff("not { json", "also not json");
    // Should still render a diff without crashing
    expect(screen.getByText("+")).toBeInTheDocument();
  });

  it("handles one side being null (entirely new content)", () => {
    renderDiff('{"new": true}', null);
    // All lines should be additions
    const additions = screen.getAllByText("+");
    expect(additions.length).toBeGreaterThan(0);
  });

  it("renders Target → Source header", () => {
    renderDiff('{"a": 1}', '{"a": 2}');
    expect(screen.getByText(/Target/)).toBeInTheDocument();
    expect(screen.getByText(/Source/)).toBeInTheDocument();
  });

  describe("normalisation", () => {
    it("treats a compact body and a pretty-printed one as identical", () => {
      // The operator approval diff compares a raw request body against a stored
      // document. Without normalising both, the entire document reads as
      // rewritten when only the whitespace differs.
      renderDiff('{"a":1,"b":{"c":2}}', '{\n  "a": 1,\n  "b": {\n    "c": 2\n  }\n}');
      expect(screen.getByText("Content identical")).toBeInTheDocument();
    });

    it("formats a body the redaction filter left unparseable, rather than dropping to raw text", () => {
      // `"apiKey":"sk-…"` comes back from SecretRedactionFilter as
      // `"apiKey=<REDACTED>"`. The full shape matrix lives in
      // `lib/__tests__/redacted-json.test.ts`; this pins that the viewer routes
      // through it, because the visible cost of not doing so is the whole diff.
      renderDiff(
        '{"modelName":"claude-sonnet-5","apiKey=<REDACTED>","threshold":9}',
        '{"modelName":"claude-sonnet-5","apiKey":"stored","threshold":5}',
      );

      expect(screen.queryByTestId("diff-raw-comparison")).not.toBeInTheDocument();
      expect(rowTexts("added")).toContain('  "threshold": 9');
      expect(rowTexts("removed")).toContain('  "threshold": 5');
      // Unchanged, so one shared context row — not a line on each side, which
      // is what a whole-document rewrite would have produced.
      expect(rowTexts("context")).toContain('  "modelName": "claude-sonnet-5",');
      expect(rowTexts().filter((line) => line.includes("modelName"))).toHaveLength(1);
    });

    it("says so when a side genuinely isn't JSON, rather than implying a rewrite", () => {
      renderDiff("not { json", '{"a": 1}');
      expect(screen.getByTestId("diff-raw-comparison")).toBeInTheDocument();
    });

    it("shows no caveat when both sides parse", () => {
      renderDiff('{"a": 1}', '{"a": 2}');
      expect(screen.queryByTestId("diff-raw-comparison")).not.toBeInTheDocument();
    });
  });

  describe("unchanged context", () => {
    const many = Object.fromEntries(
      Array.from({ length: 20 }, (_, i) => [`k${String(i).padStart(2, "0")}`, i]),
    );

    it("folds away long runs of unchanged lines", () => {
      renderDiff(JSON.stringify({ ...many, k00: 999 }), JSON.stringify(many));

      const gap = screen.getByTestId("diff-context-gap");
      expect(gap).toHaveTextContent(/unchanged lines/);
      expect(rowTexts().some((line) => line.includes("k19"))).toBe(false);
      // The change itself and its immediate context stay visible.
      expect(rowTexts("added")).toContain('  "k00": 999,');
      expect(rowTexts("context")).toContain('  "k01": 1,');
    });

    it("expands a fold on click — nothing is hidden that can't be got back", async () => {
      renderDiff(JSON.stringify({ ...many, k00: 999 }), JSON.stringify(many));

      await userEvent.click(screen.getByTestId("diff-context-gap"));
      expect(rowTexts("context")).toContain('  "k19": 19');
      expect(screen.queryByTestId("diff-context-gap")).not.toBeInTheDocument();
    });

    it("gives the fold row the app's focus treatment, since it is keyboard-reachable", async () => {
      renderDiff(JSON.stringify({ ...many, k00: 999 }), JSON.stringify(many));

      const gap = screen.getByTestId("diff-context-gap");
      await userEvent.tab();
      expect(gap).toHaveFocus();
      expect(gap.className).toMatch(/focus-visible:ring-2/);
    });

    it("does not fold a short run — the fold row would cost as much as it saves", () => {
      renderDiff('{"a": 1, "b": 2, "c": 3}', '{"a": 9, "b": 2, "c": 3}');
      expect(screen.queryByTestId("diff-context-gap")).not.toBeInTheDocument();
    });

    it("expands the fold that was clicked when several are on screen", () => {
      // Gap ids are handed out to every dropped run, folded or not, so a short
      // unfolded run above a long one cannot renumber it out from under a click.
      const wide = Object.fromEntries(
        Array.from({ length: 40 }, (_, i) => [`k${String(i).padStart(2, "0")}`, i]),
      );
      renderDiff(
        JSON.stringify({ ...wide, k00: 999, k20: 999 }),
        JSON.stringify(wide),
      );

      const gaps = screen.getAllByTestId("diff-context-gap");
      expect(gaps.length).toBeGreaterThan(1);
      fireEvent.click(gaps[gaps.length - 1]!);

      // The last fold opened; the first is still folded.
      expect(rowTexts("context")).toContain('  "k39": 39');
      expect(screen.getAllByTestId("diff-context-gap")).toHaveLength(gaps.length - 1);
    });

    it("forgets expanded folds when the content changes — gap ids are positional", async () => {
      // The sync page re-previews into the same viewer instance. A fold opened
      // in the old diff must not pre-open whatever happens to be gap 0 in the new one.
      const { rerender } = renderDiff(JSON.stringify({ ...many, k00: 999 }), JSON.stringify(many));
      await userEvent.click(screen.getByTestId("diff-context-gap"));
      expect(screen.queryByTestId("diff-context-gap")).not.toBeInTheDocument();

      rerender(
        <ResourceDiffViewer
          sourceContent={JSON.stringify({ ...many, k19: 999 })}
          targetContent={JSON.stringify(many)}
        />,
      );
      expect(screen.getByTestId("diff-context-gap")).toBeInTheDocument();
    });
  });

  describe("legend", () => {
    it("names the sides by the caller's vocabulary when given", () => {
      render(
        <ResourceDiffViewer
          sourceContent='{"a": 1}'
          targetContent='{"a": 2}'
          labels={{ target: "Stored v3", source: "Proposed" }}
        />,
      );
      expect(screen.getByText("Stored v3")).toBeInTheDocument();
      expect(screen.getByText("Proposed")).toBeInTheDocument();
      expect(screen.queryByText(/Target/)).not.toBeInTheDocument();
    });
  });
});
