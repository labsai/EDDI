import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  Plus,
  Trash2,
  BookOpen,
} from "lucide-react";

// ─── Types matching RegularDictionaryConfiguration backend model ─────────────

export interface WordConfig {
  word: string;
  expressions: string;
  frequency: number;
}

export interface RegExConfig {
  regEx: string;
  expressions: string;
}

export interface PhraseConfig {
  phrase: string;
  expressions: string;
}

export interface DictionaryConfig {
  lang?: string;
  words: WordConfig[];
  regExs: RegExConfig[];
  phrases: PhraseConfig[];
}

// ─── Sub-components ──────────────────────────────────────────────────────────

function WordRow({
  word, onChange, onRemove, readOnly,
}: {
  word: WordConfig; onChange: (w: WordConfig) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1.5" data-testid="word-row">
      <input type="text" value={word.word} onChange={(e) => onChange({ ...word, word: e.target.value })}
        readOnly={readOnly} placeholder={t("dictionaryEditor.wordPlaceholder", "e.g. hello")}
        className="h-7 w-32 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      <span className="text-xs text-muted-foreground">→</span>
      <input type="text" value={word.expressions} onChange={(e) => onChange({ ...word, expressions: e.target.value })}
        readOnly={readOnly} placeholder={t("dictionaryEditor.expressionPlaceholder", "e.g. greeting(hello)")}
        className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      <input type="number" value={word.frequency} onChange={(e) => onChange({ ...word, frequency: parseInt(e.target.value, 10) || 0 })}
        readOnly={readOnly} title={t("dictionaryEditor.frequency", "Frequency")}
        className="h-7 w-14 rounded border border-input bg-background px-2 text-xs text-center text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      {!readOnly && (
        <button type="button" onClick={onRemove} className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors">
          <Trash2 className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

function PhraseRow({
  phrase, onChange, onRemove, readOnly,
}: {
  phrase: PhraseConfig; onChange: (p: PhraseConfig) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1.5" data-testid="phrase-row">
      <input type="text" value={phrase.phrase} onChange={(e) => onChange({ ...phrase, phrase: e.target.value })}
        readOnly={readOnly} placeholder={t("dictionaryEditor.phrasePlaceholder", "e.g. good morning")}
        className="h-7 w-40 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      <span className="text-xs text-muted-foreground">→</span>
      <input type="text" value={phrase.expressions} onChange={(e) => onChange({ ...phrase, expressions: e.target.value })}
        readOnly={readOnly} placeholder={t("dictionaryEditor.expressionPlaceholder", "e.g. greeting(hello)")}
        className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      {!readOnly && (
        <button type="button" onClick={onRemove} className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors">
          <Trash2 className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

function RegExRow({
  regex, onChange, onRemove, readOnly,
}: {
  regex: RegExConfig; onChange: (r: RegExConfig) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1.5" data-testid="regex-row">
      <input type="text" value={regex.regEx} onChange={(e) => onChange({ ...regex, regEx: e.target.value })}
        readOnly={readOnly} placeholder={t("dictionaryEditor.regexPlaceholder", "Regular expression")}
        className="h-7 w-48 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      <span className="text-xs text-muted-foreground">→</span>
      <input type="text" value={regex.expressions} onChange={(e) => onChange({ ...regex, expressions: e.target.value })}
        readOnly={readOnly} placeholder={t("dictionaryEditor.expressionPlaceholder", "e.g. greeting(hello)")}
        className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      {!readOnly && (
        <button type="button" onClick={onRemove} className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors">
          <Trash2 className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

// ─── Section container ───────────────────────────────────────────────────────

function DictSection({ title, count, children, onAdd, addLabel, readOnly }: {
  title: string; count: number; children: React.ReactNode;
  onAdd: () => void; addLabel: string; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {title}
          <span className="ms-1.5 rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium">{count}</span>
        </h4>
        {!readOnly && (
          <button type="button" onClick={onAdd}
            className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            data-testid={`add-${title.toLowerCase()}-btn`}>
            <Plus className="h-3 w-3" />{addLabel}
          </button>
        )}
      </div>
      {count === 0 ? (
        <p className="text-xs italic text-muted-foreground py-2">
          {t("dictionaryEditor.none", "None defined")}
        </p>
      ) : (
        <div className="space-y-1.5">{children}</div>
      )}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export interface DictionaryEditorProps {
  data: DictionaryConfig;
  onChange: (data: DictionaryConfig) => void;
  readOnly?: boolean;
}

export function DictionaryEditor({ data, onChange, readOnly }: DictionaryEditorProps) {
  const { t } = useTranslation();

  const addWord = useCallback(() => {
    onChange({ ...data, words: [...(data.words ?? []), { word: "", expressions: "", frequency: 0 }] });
  }, [data, onChange]);

  const addPhrase = useCallback(() => {
    onChange({ ...data, phrases: [...(data.phrases ?? []), { phrase: "", expressions: "" }] });
  }, [data, onChange]);

  const addRegEx = useCallback(() => {
    onChange({ ...data, regExs: [...(data.regExs ?? []), { regEx: "", expressions: "" }] });
  }, [data, onChange]);

  return (
    <div className="space-y-6" data-testid="dictionary-editor">
      {/* Language */}
      <div className="flex items-center gap-2">
        <label className="text-sm font-medium text-foreground">
          <BookOpen className="me-1.5 inline h-4 w-4 text-primary" />
          {t("dictionaryEditor.language", "Language")}
        </label>
        <input type="text" value={data.lang ?? ""} onChange={(e) => onChange({ ...data, lang: e.target.value })}
          readOnly={readOnly} placeholder={t("dictionaryEditor.langPlaceholder", "e.g. en, de")}
          className="h-8 w-24 rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      </div>

      {/* Words */}
      <DictSection title={t("dictionaryEditor.words", "Words")} count={(data.words ?? []).length}
        onAdd={addWord} addLabel={t("dictionaryEditor.addWord", "Add Word")} readOnly={readOnly}>
        {(data.words ?? []).map((w, wi) => (
          <WordRow key={wi} word={w}
            onChange={(updated) => { const words = [...(data.words ?? [])]; words[wi] = updated; onChange({ ...data, words }); }}
            onRemove={() => onChange({ ...data, words: (data.words ?? []).filter((_, j) => j !== wi) })}
            readOnly={readOnly} />
        ))}
      </DictSection>

      {/* Phrases */}
      <DictSection title={t("dictionaryEditor.phrases", "Phrases")} count={(data.phrases ?? []).length}
        onAdd={addPhrase} addLabel={t("dictionaryEditor.addPhrase", "Add Phrase")} readOnly={readOnly}>
        {(data.phrases ?? []).map((p, pi) => (
          <PhraseRow key={pi} phrase={p}
            onChange={(updated) => { const phrases = [...(data.phrases ?? [])]; phrases[pi] = updated; onChange({ ...data, phrases }); }}
            onRemove={() => onChange({ ...data, phrases: (data.phrases ?? []).filter((_, j) => j !== pi) })}
            readOnly={readOnly} />
        ))}
      </DictSection>

      {/* RegEx */}
      <DictSection title={t("dictionaryEditor.regExs", "Regular Expressions")} count={(data.regExs ?? []).length}
        onAdd={addRegEx} addLabel={t("dictionaryEditor.addRegEx", "Add RegEx")} readOnly={readOnly}>
        {(data.regExs ?? []).map((r, ri) => (
          <RegExRow key={ri} regex={r}
            onChange={(updated) => { const regExs = [...(data.regExs ?? [])]; regExs[ri] = updated; onChange({ ...data, regExs }); }}
            onRemove={() => onChange({ ...data, regExs: (data.regExs ?? []).filter((_, j) => j !== ri) })}
            readOnly={readOnly} />
        ))}
      </DictSection>
    </div>
  );
}
