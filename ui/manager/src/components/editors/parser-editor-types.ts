// ─── Parser Editor Types & Constants ─────────────────────────────────────────
// Separated from parser-editor.tsx to satisfy react-refresh/only-export-components

export interface ParserConfig {
  appendExpressions?: boolean;
  includeUnused?: boolean;
  includeUnknown?: boolean;
}

export interface ParserExtensionItem {
  type: string;
  config?: Record<string, unknown>;
}

export interface ParserExtensions {
  dictionaries?: ParserExtensionItem[];
  corrections?: ParserExtensionItem[];
  normalizer?: ParserExtensionItem[];
}

export interface ParserData {
  config?: ParserConfig;
  extensions?: ParserExtensions;
}

// ─── Constants ───────────────────────────────────────────────────────────────

export const BUILTIN_DICTIONARIES = [
  { type: "eddi://ai.labs.parser.dictionaries.integer", labelKey: "parserEditor.dict.integer", label: "Integer Dictionary" },
  { type: "eddi://ai.labs.parser.dictionaries.decimal", labelKey: "parserEditor.dict.decimal", label: "Decimal Dictionary" },
  { type: "eddi://ai.labs.parser.dictionaries.punctuation", labelKey: "parserEditor.dict.punctuation", label: "Punctuation Dictionary" },
  { type: "eddi://ai.labs.parser.dictionaries.email", labelKey: "parserEditor.dict.email", label: "Email Dictionary" },
  { type: "eddi://ai.labs.parser.dictionaries.time", labelKey: "parserEditor.dict.time", label: "Time Expression Dictionary" },
  { type: "eddi://ai.labs.parser.dictionaries.ordinalNumber", labelKey: "parserEditor.dict.ordinalNumber", label: "Ordinal Number Dictionary" },
] as const;

export const REGULAR_DICT_TYPE = "eddi://ai.labs.parser.dictionaries.regular";

export const CORRECTION_TYPES = [
  { type: "eddi://ai.labs.parser.corrections.levenshtein", labelKey: "parserEditor.corr.levenshtein", label: "Levenshtein Correction", hasDistance: true },
  { type: "eddi://ai.labs.parser.corrections.phonetic", labelKey: "parserEditor.corr.phonetic", label: "Phonetic Correction", hasDistance: false },
  { type: "eddi://ai.labs.parser.corrections.mergedTerms", labelKey: "parserEditor.corr.mergedTerms", label: "Merged Terms Correction", hasDistance: false },
] as const;

export const NORMALIZER_TYPES = [
  { type: "eddi://ai.labs.parser.normalizers.punctuation", labelKey: "parserEditor.norm.punctuation", label: "Punctuation Normalizer", hasConfig: true },
  { type: "eddi://ai.labs.parser.normalizers.specialCharacter", labelKey: "parserEditor.norm.specialCharacter", label: "Special Character Normalizer", hasConfig: false },
  { type: "eddi://ai.labs.parser.normalizers.contractedWords", labelKey: "parserEditor.norm.contractedWords", label: "Contracted Word Normalizer", hasConfig: false },
  { type: "eddi://ai.labs.parser.normalizers.allowedCharacter", labelKey: "parserEditor.norm.allowedCharacter", label: "Allowed Character Normalizer", hasConfig: false },
] as const;

/** Default parser config when creating a new parser step */
export function createDefaultParserData(): ParserData {
  return {
    config: {
      appendExpressions: true,
      includeUnused: true,
      includeUnknown: true,
    },
    extensions: {
      dictionaries: [
        { type: "eddi://ai.labs.parser.dictionaries.integer" },
        { type: "eddi://ai.labs.parser.dictionaries.decimal" },
        { type: "eddi://ai.labs.parser.dictionaries.punctuation" },
        { type: "eddi://ai.labs.parser.dictionaries.email" },
        { type: "eddi://ai.labs.parser.dictionaries.time" },
        { type: "eddi://ai.labs.parser.dictionaries.ordinalNumber" },
      ],
      corrections: [
        { type: "eddi://ai.labs.parser.corrections.levenshtein", config: { distance: "2" } },
        { type: "eddi://ai.labs.parser.corrections.mergedTerms" },
      ],
      normalizer: [],
    },
  };
}
