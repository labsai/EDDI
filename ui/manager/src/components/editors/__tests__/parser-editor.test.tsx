import { describe, it, expect, vi } from "vitest";
import { screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
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
    // Providers, not a bare render: the dictionary section resolves the linked
    // dictionaries' names through react-query, and the picker dialog it opens
    // reads the same descriptor list.
    ...renderWithProviders(
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

  it("adds a regular dictionary by picking an existing one", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    expect(screen.getByTestId("dictionary-picker-dialog")).toBeInTheDocument();

    // The list is the point of the change: a dictionary is picked by its name,
    // never by typing out the resource URI it resolves to.
    const option = await screen.findByTestId("dict-option-res1");
    expect(option).toHaveTextContent("Mock res1");
    await userEvent.click(option);

    expect(onChange).toHaveBeenCalled();
    const call = onChange.mock.calls[0]![0] as ParserData;
    const regularDicts = call.extensions?.dictionaries?.filter((d) => d.type === REGULAR_DICT_TYPE);
    expect(regularDicts?.length).toBe(1);
    expect(regularDicts?.[0]?.config?.uri).toContain("res1");
  });

  it("shows an already-linked dictionary as added and blocks a second link", async () => {
    const data: ParserData = {
      config: {},
      extensions: {
        dictionaries: [
          {
            type: REGULAR_DICT_TYPE,
            config: { uri: "eddi://ai.labs.dictionary/dictionarystore/dictionaries/res1?version=1" },
          },
        ],
        corrections: [],
        normalizer: [],
      },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    const option = await screen.findByTestId("dict-option-res1");
    expect(option).toBeDisabled();
    await userEvent.click(option);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("still accepts a hand-written URI through the manual field", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    await userEvent.click(await screen.findByTestId("dict-manual-open"));

    const input = screen.getByTestId("dict-uri-input");
    expect(screen.getByTestId("confirm-add-dict")).toBeDisabled();
    await userEvent.type(input, "eddi://ai.labs.dictionary/dictionarystore/dictionaries/mydict?version=2");
    await userEvent.click(screen.getByTestId("confirm-add-dict"));

    const call = onChange.mock.calls[0]![0] as ParserData;
    const regularDicts = call.extensions?.dictionaries?.filter((d) => d.type === REGULAR_DICT_TYPE);
    expect(regularDicts?.[0]?.config?.uri).toContain("mydict");
  });

  it("creates a dictionary from the picker and links it", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    await userEvent.click(await screen.findByTestId("dict-create-open"));

    const nameInput = screen.getByTestId("dict-create-name");
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, "Support Terms");
    await userEvent.click(screen.getByTestId("dict-create-submit"));

    await waitFor(() => expect(onChange).toHaveBeenCalled());
    const call = onChange.mock.calls[0]![0] as ParserData;
    const regularDicts = call.extensions?.dictionaries?.filter((d) => d.type === REGULAR_DICT_TYPE);
    expect(regularDicts?.length).toBe(1);
    expect(regularDicts?.[0]?.config?.uri).toMatch(
      /^eddi:\/\/ai\.labs\.dictionary\/dictionarystore\/dictionaries\/.+\?version=\d+$/,
    );
  });

  it("backs out of the manual field to the list, without adding anything", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    await userEvent.click(await screen.findByTestId("dict-manual-open"));
    await userEvent.click(screen.getByTestId("cancel-add-dict"));

    // Cancel here backs out of the URI field, it does not close the dialog —
    // the list is still there to pick from.
    expect(screen.queryByTestId("dict-uri-picker")).not.toBeInTheDocument();
    expect(screen.getByTestId("dictionary-picker-dialog")).toBeInTheDocument();
    expect(screen.getByTestId("dict-search")).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("closes the picker on Escape without adding anything", async () => {
    const data: ParserData = {
      config: {},
      extensions: { dictionaries: [], corrections: [], normalizer: [] },
    };
    const { onChange } = renderEditor(data);

    await userEvent.click(screen.getByTestId("add-regular-dict-btn"));
    expect(await screen.findByTestId("dict-option-res1")).toBeInTheDocument();

    await userEvent.keyboard("{Escape}");

    expect(screen.queryByTestId("dictionary-picker-dialog")).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId("no-regular-dicts")).toBeInTheDocument();
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

  // ── Linked dictionaries resolve to their name ──

  it("labels a linked dictionary with its resource name, not its id", async () => {
    renderEditor({
      config: {},
      extensions: {
        dictionaries: [
          {
            type: REGULAR_DICT_TYPE,
            config: { uri: "eddi://ai.labs.dictionary/dictionarystore/dictionaries/res1?version=3" },
          },
        ],
        corrections: [],
        normalizer: [],
      },
    });

    const row = screen.getByTestId("regular-dict-0");
    // The descriptor's name can only appear once the per-id descriptor read
    // resolved; the id alone would render as "res1".
    await waitFor(() => expect(row).toHaveTextContent("Mock descriptor res1"));
    // The id and pinned version stay visible underneath — that is what the
    // URI actually carries.
    expect(row).toHaveTextContent("res1 · v3");
    expect(screen.getByTestId("open-regular-dict-0")).toHaveAttribute(
      "href",
      "/manage/resources/dictionary/res1",
    );
  });

  it("shows a non-dictionary URI as itself, with no link to a resource that isn't there", () => {
    renderEditor({
      config: {},
      extensions: {
        dictionaries: [
          // What the manual field accepts but the dictionary store cannot
          // resolve — linking to /manage/resources/dictionary/beh1 would point
          // at a page for a ruleset.
          {
            type: REGULAR_DICT_TYPE,
            config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
          },
        ],
        corrections: [],
        normalizer: [],
      },
    });

    const row = screen.getByTestId("regular-dict-0");
    expect(row).toHaveTextContent("eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1");
    expect(screen.queryByTestId("open-regular-dict-0")).not.toBeInTheDocument();
  });
});
