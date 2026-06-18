import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  ParserEditor,
} from "@/components/editors/parser-editor";
import {
  createDefaultParserData,
  BUILTIN_DICTIONARIES,
  CORRECTION_TYPES,
  NORMALIZER_TYPES,
  REGULAR_DICT_TYPE,
  type ParserData,
} from "@/components/editors/parser-editor-types";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function renderEditor(
  data: ParserData = createDefaultParserData(),
  onChange = vi.fn(),
  readOnly = false,
) {
  return {
    onChange,
    ...render(
      <ParserEditor data={data} onChange={onChange} readOnly={readOnly} />,
    ),
  };
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe("ParserEditor", () => {
  // ── Rendering ──

  it("renders the editor with all four sections", () => {
    renderEditor();
    expect(screen.getByTestId("parser-editor")).toBeInTheDocument();
    expect(screen.getByTestId("parser-config-section")).toBeInTheDocument();
    expect(screen.getByTestId("parser-dictionaries-section")).toBeInTheDocument();
    expect(screen.getByTestId("parser-corrections-section")).toBeInTheDocument();
    expect(screen.getByTestId("parser-normalizers-section")).toBeInTheDocument();
  });

  // ── Config toggles ──

  it("renders config toggles with correct initial state", () => {
    renderEditor();
    const appendExpr = screen.getByTestId("toggle-appendExpressions");
    const includeUnused = screen.getByTestId("toggle-includeUnused");
    const includeUnknown = screen.getByTestId("toggle-includeUnknown");

    expect(appendExpr).toBeInTheDocument();
    expect(includeUnused).toBeInTheDocument();
    expect(includeUnknown).toBeInTheDocument();

    // All checkboxes should be checked (default data has all true)
    expect(within(appendExpr).getByRole("checkbox")).toBeChecked();
    expect(within(includeUnused).getByRole("checkbox")).toBeChecked();
    expect(within(includeUnknown).getByRole("checkbox")).toBeChecked();
  });

  it("toggles appendExpressions config", async () => {
    const { onChange } = renderEditor();
    const checkbox = within(screen.getByTestId("toggle-appendExpressions")).getByRole("checkbox");
    await userEvent.click(checkbox);

    expect(onChange).toHaveBeenCalledTimes(1);
    const call = onChange.mock.calls[0]![0] as ParserData;
    expect(call.config?.appendExpressions).toBe(false);
  });

  it("toggles includeUnused config", async () => {
    const { onChange } = renderEditor();
    const checkbox = within(screen.getByTestId("toggle-includeUnused")).getByRole("checkbox");
    await userEvent.click(checkbox);

    const call = onChange.mock.calls[0]![0] as ParserData;
    expect(call.config?.includeUnused).toBe(false);
  });

  it("toggles includeUnknown config", async () => {
    const { onChange } = renderEditor();
    const checkbox = within(screen.getByTestId("toggle-includeUnknown")).getByRole("checkbox");
    await userEvent.click(checkbox);

    const call = onChange.mock.calls[0]![0] as ParserData;
    expect(call.config?.includeUnknown).toBe(false);
  });

  // ── Built-in dictionaries ──

  it("renders all built-in dictionary toggles", () => {
    renderEditor();
    for (const bd of BUILTIN_DICTIONARIES) {
      const lastSegment = bd.type.split(".").pop()!;
      expect(screen.getByTestId(`dict-${lastSegment}`)).toBeInTheDocument();
    }
  });

  it("built-in dictionaries reflect default state", () => {
    const data = createDefaultParserData();
    renderEditor(data);
    // Default includes 6 built-in dicts
    for (const bd of BUILTIN_DICTIONARIES) {
      const lastSegment = bd.type.split(".").pop()!;
      const toggle = screen.getByTestId(`dict-${lastSegment}`);
      expect(within(toggle).getByRole("checkbox")).toBeChecked();
    }
  });

  it("toggles a built-in dictionary off", async () => {
    const data = createDefaultParserData();
    const { onChange } = renderEditor(data);

    const integerToggle = within(screen.getByTestId("dict-integer")).getByRole("checkbox");
    await userEvent.click(integerToggle);

    const call = onChange.mock.calls[0]![0] as ParserData;
    const integerPresent = call.extensions?.dictionaries?.some(
      (d) => d.type === "eddi://ai.labs.parser.dictionaries.integer",
    );
    expect(integerPresent).toBe(false);
  });

  it("toggles a built-in dictionary on", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    const integerToggle = within(screen.getByTestId("dict-integer")).getByRole("checkbox");
    await userEvent.click(integerToggle);

    const call = onChange.mock.calls[0]![0] as ParserData;
    const integerPresent = call.extensions?.dictionaries?.some(
      (d) => d.type === "eddi://ai.labs.parser.dictionaries.integer",
    );
    expect(integerPresent).toBe(true);
  });

  // ── Regular dictionaries ──

  it("shows 'no regular dicts' when none configured", () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    renderEditor(data);
    expect(screen.getByTestId("no-regular-dicts")).toBeInTheDocument();
  });

  it("renders regular dictionary entries", () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [
          {
            type: REGULAR_DICT_TYPE,
            config: { uri: "eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1" },
          },
        ],
        corrections: [],
        normalizer: [],
      },
    };
    renderEditor(data);
    expect(screen.getByTestId("regular-dict-0")).toBeInTheDocument();
  });

  it("adds a regular dictionary via URI picker", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    // Click Add button
    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    expect(screen.getByTestId("dict-uri-picker")).toBeInTheDocument();

    // Type URI
    const input = screen.getByTestId("dict-uri-input");
    await userEvent.type(input, "eddi://ai.labs.dictionary/dictionarystore/dictionaries/mydict?version=1");

    // Confirm
    await userEvent.click(screen.getByTestId("confirm-add-dict"));

    expect(onChange).toHaveBeenCalled();
    const call = onChange.mock.calls[0]![0] as ParserData;
    const regularDicts = call.extensions?.dictionaries?.filter((d) => d.type === REGULAR_DICT_TYPE);
    expect(regularDicts?.length).toBe(1);
    expect(regularDicts?.[0]?.config?.uri).toContain("mydict");
  });

  it("cancels dictionary URI picker", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    expect(screen.getByTestId("dict-uri-picker")).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("cancel-add-dict"));
    expect(screen.queryByTestId("dict-uri-picker")).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("removes a regular dictionary", async () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [
          { type: REGULAR_DICT_TYPE, config: { uri: "eddi://test/dict1" } },
          { type: REGULAR_DICT_TYPE, config: { uri: "eddi://test/dict2" } },
        ],
        corrections: [],
        normalizer: [],
      },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("remove-regular-dict-0"));

    const call = onChange.mock.calls[0]![0] as ParserData;
    const regularDicts = call.extensions?.dictionaries?.filter((d) => d.type === REGULAR_DICT_TYPE);
    expect(regularDicts?.length).toBe(1);
    expect((regularDicts?.[0]?.config?.uri as string)).toContain("dict2");
  });

  it("does not show add button in read-only mode", () => {
    renderEditor(createDefaultParserData(), vi.fn(), true);
    expect(screen.queryByTestId("add-regular-dict-btn")).not.toBeInTheDocument();
  });

  // ── Corrections ──

  it("renders correction toggles", () => {
    renderEditor();
    for (const ct of CORRECTION_TYPES) {
      const lastSegment = ct.type.split(".").pop()!;
      expect(screen.getByTestId(`corr-${lastSegment}`)).toBeInTheDocument();
    }
  });

  it("shows levenshtein distance input when enabled", () => {
    renderEditor();
    // Default data has levenshtein enabled
    expect(screen.getByTestId("levenshtein-distance")).toBeInTheDocument();
  });

  it("hides levenshtein distance when disabled", async () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [],
        corrections: [],
        normalizer: [],
      },
    };
    renderEditor(data);
    expect(screen.queryByTestId("levenshtein-distance")).not.toBeInTheDocument();
  });

  it("toggles correction on", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    const phonetic = within(screen.getByTestId("corr-phonetic")).getByRole("checkbox");
    await userEvent.click(phonetic);

    const call = onChange.mock.calls[0]![0] as ParserData;
    const phoneticEntry = call.extensions?.corrections?.find(
      (c) => c.type === "eddi://ai.labs.parser.corrections.phonetic",
    );
    expect(phoneticEntry).toBeDefined();
  });

  it("toggles correction off", async () => {
    const data = createDefaultParserData();
    const { onChange } = renderEditor(data);

    const levenshtein = within(screen.getByTestId("corr-levenshtein")).getByRole("checkbox");
    await userEvent.click(levenshtein);

    const call = onChange.mock.calls[0]![0] as ParserData;
    const levenshteinEntry = call.extensions?.corrections?.find(
      (c) => c.type === "eddi://ai.labs.parser.corrections.levenshtein",
    );
    expect(levenshteinEntry).toBeUndefined();
  });

  it("updates levenshtein distance", async () => {
    const data = createDefaultParserData();
    const { onChange } = renderEditor(data);

    const distanceInput = screen.getByTestId("levenshtein-distance") as HTMLInputElement;
    fireEvent.change(distanceInput, { target: { value: "3" } });

    // Should have been called with updated distance
    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1]![0] as ParserData;
    const levenshteinEntry = lastCall.extensions?.corrections?.find(
      (c) => c.type === "eddi://ai.labs.parser.corrections.levenshtein",
    );
    expect(levenshteinEntry?.config?.distance).toBe("3");
  });

  // ── Normalizers ──

  it("renders normalizer toggles", () => {
    renderEditor();
    for (const nt of NORMALIZER_TYPES) {
      const lastSegment = nt.type.split(".").pop()!;
      expect(screen.getByTestId(`norm-${lastSegment}`)).toBeInTheDocument();
    }
  });

  it("toggles normalizer on", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    const punctuation = within(screen.getByTestId("norm-punctuation")).getByRole("checkbox");
    await userEvent.click(punctuation);

    const call = onChange.mock.calls[0]![0] as ParserData;
    const entry = call.extensions?.normalizer?.find(
      (n) => n.type === "eddi://ai.labs.parser.normalizers.punctuation",
    );
    expect(entry).toBeDefined();
  });

  it("toggles normalizer off", async () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [],
        corrections: [],
        normalizer: [{ type: "eddi://ai.labs.parser.normalizers.punctuation" }],
      },
    };
    const { onChange } = renderEditor(data);

    const punctuation = within(screen.getByTestId("norm-punctuation")).getByRole("checkbox");
    await userEvent.click(punctuation);

    const call = onChange.mock.calls[0]![0] as ParserData;
    expect(call.extensions?.normalizer?.length).toBe(0);
  });

  it("shows punctuation config when punctuation normalizer is enabled", () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [],
        corrections: [],
        normalizer: [
          { type: "eddi://ai.labs.parser.normalizers.punctuation", config: { removePunctuation: "true" } },
        ],
      },
    };
    renderEditor(data);
    expect(screen.getByTestId("norm-removePunctuation")).toBeInTheDocument();
    expect(screen.getByTestId("norm-punctuationRegexPattern")).toBeInTheDocument();
  });

  it("updates punctuation normalizer config", async () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [],
        corrections: [],
        normalizer: [
          { type: "eddi://ai.labs.parser.normalizers.punctuation", config: { removePunctuation: "false" } },
        ],
      },
    };
    const { onChange } = renderEditor(data);

    const patternInput = screen.getByTestId("norm-punctuationRegexPattern") as HTMLInputElement;
    fireEvent.change(patternInput, { target: { value: "[.!?]" } });

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1]![0] as ParserData;
    const entry = lastCall.extensions?.normalizer?.find(
      (n) => n.type === "eddi://ai.labs.parser.normalizers.punctuation",
    );
    expect(entry?.config?.punctuationRegexPattern).toBe("[.!?]");
  });

  // ── Read-only mode ──

  it("disables all checkboxes in read-only mode", () => {
    renderEditor(createDefaultParserData(), vi.fn(), true);

    const checkboxes = screen.getAllByRole("checkbox");
    for (const cb of checkboxes) {
      expect(cb).toBeDisabled();
    }
  });

  it("hides remove buttons in read-only mode for regular dicts", () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [
          { type: REGULAR_DICT_TYPE, config: { uri: "eddi://test/dict1" } },
        ],
        corrections: [],
        normalizer: [],
      },
    };
    renderEditor(data, vi.fn(), true);
    expect(screen.queryByTestId("remove-regular-dict-0")).not.toBeInTheDocument();
  });

  // ── Default data factory ──

  it("createDefaultParserData returns correct defaults", () => {
    const defaults = createDefaultParserData();

    expect(defaults.config?.appendExpressions).toBe(true);
    expect(defaults.config?.includeUnused).toBe(true);
    expect(defaults.config?.includeUnknown).toBe(true);

    // 6 built-in dicts
    expect(defaults.extensions?.dictionaries?.length).toBe(6);

    // 2 corrections (levenshtein + mergedTerms)
    expect(defaults.extensions?.corrections?.length).toBe(2);
    expect(
      defaults.extensions?.corrections?.find(
        (c) => c.type === "eddi://ai.labs.parser.corrections.levenshtein",
      )?.config?.distance,
    ).toBe("2");

    // 0 normalizers
    expect(defaults.extensions?.normalizer?.length).toBe(0);
  });

  // ── Empty/null data handling ──

  it("handles empty data gracefully", () => {
    renderEditor({});
    expect(screen.getByTestId("parser-editor")).toBeInTheDocument();
  });

  it("handles undefined extensions gracefully", () => {
    renderEditor({ config: { appendExpressions: true } });
    expect(screen.getByTestId("parser-editor")).toBeInTheDocument();
    expect(screen.getByTestId("no-regular-dicts")).toBeInTheDocument();
  });

  // ── Section collapsing ──

  it("collapses and expands sections", async () => {
    renderEditor();

    // Find config section header button and click to collapse
    const configSection = screen.getByTestId("parser-config-section");
    const headerButton = within(configSection).getByRole("button", { expanded: true });
    await userEvent.click(headerButton);

    // Toggles should no longer be visible
    expect(screen.queryByTestId("toggle-appendExpressions")).not.toBeInTheDocument();

    // Click again to expand
    const collapsedButton = within(configSection).getByRole("button", { expanded: false });
    await userEvent.click(collapsedButton);

    expect(screen.getByTestId("toggle-appendExpressions")).toBeInTheDocument();
  });

  // ── Badge counts ──

  it("displays correct badge counts", () => {
    const data = createDefaultParserData();
    renderEditor(data);

    // Dictionaries badge should show count of dictionaries
    const dictSection = screen.getByTestId("parser-dictionaries-section");
    expect(dictSection.textContent).toContain("6");

    // Corrections badge should show count
    const corrSection = screen.getByTestId("parser-corrections-section");
    expect(corrSection.textContent).toContain("2");
  });

  it("dictionary badge updates when toggling", async () => {
    const data = createDefaultParserData();
    const onChange = vi.fn();
    const { rerender } = renderEditor(data, onChange);

    // Toggle one off
    const integerToggle = within(screen.getByTestId("dict-integer")).getByRole("checkbox");
    await userEvent.click(integerToggle);

    // Re-render with updated data
    const newData = onChange.mock.calls[0]![0] as ParserData;
    rerender(
      <ParserEditor data={newData} onChange={onChange} readOnly={false} />,
    );

    const dictSection = screen.getByTestId("parser-dictionaries-section");
    expect(dictSection.textContent).toContain("5");
  });

  // ── Add dict button disabled when URI empty ──

  it("disables confirm button when URI is empty", async () => {
    renderEditor({
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    });

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    const confirmBtn = screen.getByTestId("confirm-add-dict");
    expect(confirmBtn).toBeDisabled();
  });
});
