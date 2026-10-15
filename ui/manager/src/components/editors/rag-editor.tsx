import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Database,
  Server,
  Layers,
  Scissors,
  Search,
  Upload,
  FileText,
  Globe,
  Plus,
  X,
  Loader2,
  CheckCircle2,
  AlertCircle,
  Lock,
  ChevronDown,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api-client";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { ConnectionReferenceWarning } from "@/components/shared/connection-reference-warning";
import { IngestionSourcesPanel } from "@/components/editors/ingestion-sources-panel";
import { RagStoragePanel } from "@/components/editors/rag-storage-panel";
import type { IngestionSource } from "@/lib/api/ingestion-sources";

// ─── Types ───────────────────────────────────────────────────────────────────

export interface RagConfig {
  name?: string;
  embeddingProvider?: string;
  embeddingParameters?: Record<string, string>;
  storeType?: string;
  storeParameters?: Record<string, string>;
  /**
   * How the vector store is addressed (EDDI 6.6+): `"id"` — a store of its own,
   * stamped by the server on every knowledge base created since — or absent /
   * `"name"`, the 6.5 layout addressed by the name. Never written by the editor
   * except through the explicit "move to its own store" action.
   */
  storeNamespace?: string;
  chunkStrategy?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  maxResults?: number;
  minScore?: number;
  /** Where this knowledge base pulls its own documents from. */
  sources?: IngestionSource[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

/** Check if a placeholder or value looks like a vault reference (canonical or legacy) */
const isVaultPlaceholder = (v: string) =>
  v.startsWith("${vault:") || v.startsWith("${eddivault:");

const EMBEDDING_PROVIDERS = [
  { value: "openai", label: "OpenAI", hint: "text-embedding-3-small" },
  { value: "azure-openai", label: "Azure OpenAI", hint: "text-embedding-3-small" },
  { value: "ollama", label: "Ollama", hint: "nomic-embed-text" },
  { value: "mistral", label: "Mistral AI", hint: "mistral-embed" },
  { value: "bedrock", label: "Amazon Bedrock", hint: "amazon.titan-embed-text-v2:0" },
  { value: "cohere", label: "Cohere", hint: "embed-english-v3.0" },
  { value: "vertex", label: "Google Vertex AI", hint: "text-embedding-005" },
  { value: "gemini", label: "Google AI", hint: "gemini-embedding-2" },
] as const;

/**
 * A parameter the backend knows, and what it does when the parameter is absent.
 *
 * The distinction is the point. `EmbeddingModelFactory` and
 * `EmbeddingStoreFactory` apply a default only to an ABSENT key
 * (`params.getOrDefault`), so a hint that merely shows a sample value must not
 * look like one that names the real default. These used to be one
 * `placeholder` field: `nomic-embed-text` (the real default) and
 * `${vault:openai-key}` (a name nobody created) rendered identically, and the
 * second was written into the config as its value on a provider switch.
 *
 * Keep `defaultValue` in step with the two factories — it is a copy.
 */
interface ParamHint {
  key: string;
  /** What the backend uses when the key is absent. Shown as "Default: …". */
  defaultValue?: string;
  /** A sample only; leaving the field empty does not use it. Shown as "e.g. …". */
  example?: string;
  /** The backend refuses to build without it. */
  required?: boolean;
}

/** Provider-specific parameters, mirroring `EmbeddingModelFactory`. */
const EMBEDDING_PARAM_HINTS: Record<string, ParamHint[]> = {
  openai: [
    { key: "model", defaultValue: "text-embedding-3-small" },
    { key: "apiKey", required: true },
    // Any OpenAI-compatible server. Honoured since 6.6 — before, it was ignored
    // and documents went to api.openai.com.
    { key: "baseUrl", defaultValue: "https://api.openai.com/v1" },
  ],
  "azure-openai": [
    { key: "endpoint", example: "https://my-resource.openai.azure.com/", required: true },
    { key: "apiKey", required: true },
    { key: "deploymentName", defaultValue: "text-embedding-3-small" },
  ],
  ollama: [
    { key: "model", defaultValue: "nomic-embed-text" },
    { key: "baseUrl", defaultValue: "http://localhost:11434" },
  ],
  mistral: [
    { key: "model", defaultValue: "mistral-embed" },
    { key: "apiKey", required: true },
  ],
  bedrock: [
    { key: "model", defaultValue: "amazon.titan-embed-text-v2:0" },
    { key: "region", defaultValue: "us-east-1" },
  ],
  cohere: [
    { key: "model", defaultValue: "embed-english-v3.0" },
    { key: "apiKey", required: true },
  ],
  vertex: [
    { key: "project", example: "my-gcp-project", required: true },
    { key: "location", defaultValue: "us-central1" },
    { key: "model", defaultValue: "text-embedding-005" },
  ],
  gemini: [
    { key: "apiKey", required: true },
    { key: "model", defaultValue: "gemini-embedding-2" },
    // Unset, each call is tagged with its role — documents and queries embed
    // differently (EmbeddingModelFactory.pinsNonRetrievalTaskType).
    { key: "taskType", defaultValue: "RETRIEVAL_DOCUMENT / RETRIEVAL_QUERY" },
    { key: "outputDimensionality", defaultValue: "3072" },
  ],
};

const STORE_TYPES = [
  { value: "in-memory", label: "In-Memory", hint: "Dev/test only — data lost on restart" },
  { value: "pgvector", label: "PostgreSQL (pgvector)", hint: "Production — persistent vector store" },
  { value: "mongodb-atlas", label: "MongoDB Atlas", hint: "Atlas vector search" },
  { value: "elasticsearch", label: "Elasticsearch", hint: "Elasticsearch vector search" },
  { value: "qdrant", label: "Qdrant", hint: "High-performance vector DB" },
  { value: "chroma", label: "Chroma", hint: "Fast, serverless, and scalable infrastructure supporting vector, full-text, regex, and metadata search." },
] as const;

/**
 * Store-specific parameters, mirroring `EmbeddingStoreFactory`.
 *
 * `perKb` is the table/collection/index name the factory derives from the
 * knowledge base id when none is set — one per KB. The old hint showed a fixed
 * sample (`embeddings`) instead, and a user who typed it into several KBs made
 * them share one table.
 */
function storeParamHints(storeType: string, perKb: string): ParamHint[] {
  switch (storeType) {
    case "pgvector":
      return [
        { key: "host", defaultValue: "localhost" },
        { key: "port", defaultValue: "5432" },
        { key: "database", defaultValue: "eddi" },
        { key: "table", defaultValue: perKb },
        { key: "dimension", defaultValue: "1536" },
        { key: "user", defaultValue: "eddi" },
        { key: "password", required: true },
      ];
    case "mongodb-atlas":
      return [
        { key: "connectionString", required: true },
        { key: "databaseName", defaultValue: "eddi" },
        { key: "collectionName", defaultValue: perKb },
        { key: "indexName", defaultValue: "vector_index" },
      ];
    case "elasticsearch":
      return [
        { key: "serverUrl", defaultValue: "http://localhost:9200" },
        { key: "indexName", defaultValue: perKb },
        { key: "apiKey" },
        { key: "userName", example: "elastic" },
        { key: "password" },
      ];
    case "qdrant":
      return [
        { key: "host", defaultValue: "localhost" },
        { key: "port", defaultValue: "6334" },
        { key: "collectionName", defaultValue: perKb },
        { key: "apiKey" },
        { key: "useTls", defaultValue: "false" },
      ];
    case "chroma":
      return [
        { key: "baseUrl", defaultValue: "http://localhost:8000" },
        { key: "collectionName", defaultValue: perKb },
        { key: "tenantName", defaultValue: "default_tenant" },
        { key: "databaseName", defaultValue: "default_database" },
        { key: "apiVersion", defaultValue: "V2" },
      ];
    default:
      return [];
  }
}

/**
 * Only `recursive` is implemented. `paragraph` and `sentence` were offered here
 * but always split recursively, and the backend now rewrites them to
 * `recursive` on save (`RagConfiguration.LEGACY_CHUNK_STRATEGIES`).
 */
const CHUNK_STRATEGIES = [
  { value: "recursive", label: "Recursive (recommended)" },
] as const;

/**
 * Only these two are rewritten to `recursive` on save; any other unknown
 * strategy (from an import, a hand edit) is refused by
 * `RagConfiguration.validate()` with a 400.
 */
const LEGACY_CHUNK_STRATEGIES = new Set(["paragraph", "sentence"]);
const isLegacyChunkStrategy = (value: string | undefined) =>
  LEGACY_CHUNK_STRATEGIES.has((value ?? "").trim().toLowerCase());

// ─── Section Component ──────────────────────────────────────────────────────

function Section({
  label,
  icon: Icon,
  accent,
  defaultOpen = true,
  badge,
  children,
}: {
  label: string;
  icon: React.ElementType;
  accent: string;
  defaultOpen?: boolean;
  badge?: string;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="rounded-lg border border-border bg-card/50 overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex w-full items-center gap-2.5 px-4 py-2.5 text-start transition-colors hover:bg-muted/30"
      >
        <Icon className={cn("h-4 w-4 shrink-0", accent)} />
        <span className="flex-1 text-xs font-semibold uppercase tracking-wider text-foreground/80">
          {label}
        </span>
        {badge && (
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary">
            {badge}
          </span>
        )}
        <ChevronDown
          className={cn(
            "h-3.5 w-3.5 text-muted-foreground transition-transform",
            open && "rotate-180",
          )}
        />
      </button>
      {open && <div className="border-t border-border px-4 py-3">{children}</div>}
    </div>
  );
}

// ─── Key-Value Editor ───────────────────────────────────────────────────────

/** Keys whose values should use SecretKeyPicker instead of a plain text input */
const SENSITIVE_KEYS = new Set(["apikey", "password", "secret", "token", "connectionstring"]);

function isSensitiveKey(key: string): boolean {
  return SENSITIVE_KEYS.has(key.toLowerCase());
}

/** A parameter the editor has no hint for, as edited on screen. */
interface CustomRow {
  id: number;
  key: string;
  value: string;
}

/** Only complete rows reach the config; see {@link KeyValueEditor}. */
const isComplete = (r: CustomRow) => r.key !== "" && r.value !== "";

const serializeRows = (rows: CustomRow[]) =>
  JSON.stringify(rows.filter(isComplete).map((r) => [r.key, r.value]));

/**
 * Edits a provider's or store's parameters.
 *
 * **An empty field is never saved.** Both factories default a parameter only
 * when its key is ABSENT (`params.getOrDefault`), so `{"model": ""}` is not
 * "use the default": it reaches the provider as an empty model name and fails
 * at ingest. Clearing a field removes the key.
 *
 * Every known parameter (`hints`) is always shown as its own row, and an empty
 * one says what happens: "Default: …" (the backend's value), "e.g. …" (a
 * sample only), or "Required". Nothing is pre-filled. An earlier version wrote
 * each hint's placeholder into the config on a provider switch, so knowledge
 * bases referenced vault keys such as `${vault:openai-key}` that nobody had
 * ever created.
 *
 * Other parameters (from an import, a hand edit, or "Add Parameter") are held
 * as local rows so an unfinished one stays on screen, in place and focused,
 * without its empty value reaching the config.
 */
function KeyValueEditor({
  entries,
  onChange,
  readOnly,
  hints = [],
  label,
  testIdPrefix = "kv",
}: {
  entries: Record<string, string>;
  onChange: (v: Record<string, string>) => void;
  readOnly?: boolean;
  hints?: ParamHint[];
  label?: string;
  /** Prefix for data-testid attributes, avoids collisions when multiple editors share key names */
  testIdPrefix?: string;
}) {
  const { t } = useTranslation();
  const nextId = useRef(0);
  const hintKeys = new Set(hints.map((h) => h.key));

  const toRows = (source: Record<string, string>): CustomRow[] =>
    Object.entries(source)
      .filter(([k]) => !hintKeys.has(k))
      .map(([key, value]) => ({ id: nextId.current++, key, value }));

  const [rows, setRows] = useState<CustomRow[]>(() => toRows(entries));

  // Adopt a change made elsewhere (provider switch, the JSON tab). Our own
  // emits come back equal to what the complete rows already say, so they never
  // reset the rows and never drop an unfinished one.
  const propRows = toComparable(entries, hintKeys);
  if (propRows !== serializeRows(rows)) {
    setRows(toRows(entries));
  }

  const hintEntries = (source: Record<string, string>) =>
    Object.fromEntries(Object.entries(source).filter(([k, v]) => hintKeys.has(k) && v !== ""));

  const emit = (hinted: Record<string, string>, custom: CustomRow[]) =>
    onChange({
      ...hintEntries(hinted),
      ...Object.fromEntries(custom.filter(isComplete).map((r) => [r.key, r.value])),
    });

  const setHintValue = (key: string, value: string) => emit({ ...entries, [key]: value }, rows);

  const resetHint = (key: string) => {
    const copy = { ...entries };
    delete copy[key];
    emit(copy, rows);
  };

  const keyTaken = (key: string, exceptId: number) =>
    hintKeys.has(key) || rows.some((r) => r.id !== exceptId && r.key === key);

  const updateRow = (id: number, patch: Partial<CustomRow>) => {
    if (patch.key !== undefined && patch.key !== "" && keyTaken(patch.key, id)) return;
    const next = rows.map((r) => (r.id === id ? { ...r, ...patch } : r));
    setRows(next);
    emit(entries, next);
  };

  const removeRow = (id: number) => {
    const next = rows.filter((r) => r.id !== id);
    setRows(next);
    emit(entries, next);
  };

  const addRow = () => {
    let n = rows.length + 1;
    while (keyTaken(`param${n}`, -1)) n++;
    // Not emitted: it has no value yet.
    setRows([...rows, { id: nextId.current++, key: `param${n}`, value: "" }]);
  };

  const describe = (hint: ParamHint, secret: boolean): string | undefined => {
    if (hint.defaultValue !== undefined) {
      return t("ragEditor.paramDefault", "Default: {{value}}", { value: hint.defaultValue });
    }
    if (secret) {
      return hint.required
        ? t("ragEditor.secretRequired", "Required — pick a vault secret")
        : t("ragEditor.secretOptional", "Optional — pick a vault secret");
    }
    if (hint.example !== undefined) {
      return hint.required
        ? t("ragEditor.paramRequiredExample", "Required — e.g. {{value}}", { value: hint.example })
        : t("ragEditor.paramExample", "e.g. {{value}}", { value: hint.example });
    }
    return hint.required ? t("ragEditor.paramRequired", "Required") : undefined;
  };

  const valueInput = (key: string, value: string, placeholder: string | undefined, onValue: (v: string) => void) =>
    isSensitiveKey(key) ? (
      <div className="flex-1">
        {/* No `connections` here: embedding models and vector stores
            need a bare credential and refuse ${connection:…}. */}
        <SecretKeyPicker
          value={value}
          onChange={onValue}
          readOnly={readOnly}
          placeholder={placeholder}
          testId={`${testIdPrefix}-${key}`}
        />
      </div>
    ) : (
      <div className="relative flex-1">
        <input
          type="text"
          value={value}
          onChange={(e) => onValue(e.target.value)}
          readOnly={readOnly}
          placeholder={placeholder ?? t("ragEditor.paramValuePlaceholder", "value")}
          className={cn(
            "h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs text-foreground placeholder:text-muted-foreground/70 focus:outline-none focus:ring-1 focus:ring-ring",
            isVaultPlaceholder(value) && "pe-7",
          )}
          data-testid={`${testIdPrefix}-value-${key}`}
        />
        {isVaultPlaceholder(value) && (
          <Lock className="absolute inset-e-2 top-1.5 h-3.5 w-3.5 text-amber-500" />
        )}
      </div>
    );

  const keyBoxClass =
    "h-7 w-32 shrink-0 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";

  return (
    <div className="space-y-1.5">
      {label && (
        <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {label}
        </span>
      )}

      {hints.length > 0 && (
        <p className="text-[10px] text-muted-foreground" data-testid={`${testIdPrefix}-defaults-note`}>
          {t("ragEditor.paramsDefaultNote", "Leave a field empty to use the default shown in it.")}
        </p>
      )}

      {/* Known parameters: always listed; empty means absent, so the default applies. */}
      {hints.map((hint) => {
        const stored = entries[hint.key];
        const value = stored ?? "";
        return (
          <div
            key={`hint-${hint.key}`}
            data-testid={`${testIdPrefix}-row-${hint.key}`}
            data-state={value !== "" ? "custom" : "default"}
          >
            <div className="flex items-center gap-1.5">
              <span className={cn(keyBoxClass, "flex items-center bg-muted/40")} title={hint.key}>
                <span className="truncate">{hint.key}</span>
                {hint.required && (
                  <span className="ms-0.5 text-destructive" aria-hidden="true">*</span>
                )}
              </span>
              <span className="text-muted-foreground/40">=</span>
              {valueInput(hint.key, value, describe(hint, isSensitiveKey(hint.key)), (v) => setHintValue(hint.key, v))}
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => resetHint(hint.key)}
                  disabled={stored === undefined}
                  aria-label={t("ragEditor.resetToDefault", "Reset to default")}
                  title={t("ragEditor.resetToDefault", "Reset to default")}
                  className="rounded p-1 text-muted-foreground transition-colors hover:text-destructive disabled:invisible"
                  data-testid={`${testIdPrefix}-reset-${hint.key}`}
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
            {/* Saved by an earlier version of this editor: the key is present with "",
                which the backend uses as the value instead of its default. */}
            {stored === "" && (
              <p
                className="mt-0.5 flex items-center gap-1 ps-1 text-[10px] text-amber-600 dark:text-amber-400"
                data-testid={`${testIdPrefix}-empty-${hint.key}`}
              >
                <AlertCircle className="h-3 w-3 shrink-0" />
                {t("ragEditor.emptySavedValue", "Saved as an empty value, so the default is not used.")}
                {!readOnly && (
                  <button
                    type="button"
                    onClick={() => resetHint(hint.key)}
                    className="font-medium underline underline-offset-2"
                    data-testid={`${testIdPrefix}-use-default-${hint.key}`}
                  >
                    {t("ragEditor.useDefault", "Use default")}
                  </button>
                )}
              </p>
            )}
            <ConnectionReferenceWarning
              value={value}
              refused="model"
              testId={`${testIdPrefix}-connection-warning-${hint.key}`}
            />
          </div>
        );
      })}

      {/* Everything else, in place until complete. */}
      {rows.map((row) => (
        <div key={`row-${row.id}`} data-testid={`${testIdPrefix}-custom-row`} data-key={row.key}>
          <div className="flex items-center gap-1.5">
            <input
              type="text"
              value={row.key}
              onChange={(e) => updateRow(row.id, { key: e.target.value })}
              readOnly={readOnly}
              placeholder={t("ragEditor.paramKeyPlaceholder", "key")}
              aria-label={t("ragEditor.paramKeyPlaceholder", "key")}
              className={keyBoxClass}
              data-testid={`${testIdPrefix}-custom-key`}
            />
            <span className="text-muted-foreground/40">=</span>
            {valueInput(row.key, row.value, undefined, (v) => updateRow(row.id, { value: v }))}
            {!readOnly && (
              <button
                type="button"
                onClick={() => removeRow(row.id)}
                aria-label={t("ragEditor.removeParam", "Remove parameter")}
                className="rounded p-1 text-muted-foreground transition-colors hover:text-destructive"
                data-testid={`${testIdPrefix}-custom-remove`}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
          <ConnectionReferenceWarning
            value={row.value}
            refused="model"
            testId={`${testIdPrefix}-connection-warning-${row.key}`}
          />
        </div>
      ))}

      {!readOnly && (
        <button
          type="button"
          onClick={addRow}
          className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
          data-testid={`${testIdPrefix}-add`}
        >
          <Plus className="h-3 w-3" />
          {t("ragEditor.addParam", "Add Parameter")}
        </button>
      )}
    </div>
  );
}

/** The non-hint part of `entries`, serialised the way {@link serializeRows} does. */
function toComparable(entries: Record<string, string>, hintKeys: Set<string>): string {
  return JSON.stringify(Object.entries(entries).filter(([k, v]) => !hintKeys.has(k) && v !== ""));
}

// ─── Ingestion Panel ────────────────────────────────────────────────────────

interface IngestionStatus {
  ingestionId: string;
  status: string;
  documentName: string;
  /** A caveat on a completed ingestion — e.g. a replacement the vector store could not carry out. */
  warning?: string;
}

function IngestionPanel({
  kbId,
  version,
  readOnly,
  hasUnsavedChanges,
}: {
  kbId: string;
  version: number;
  readOnly?: boolean;
  /**
   * The ingest endpoint resolves the *saved* knowledge base: its store, its
   * embedding model, its chunking. Ingesting with unsaved edits on screen put
   * the documents into the old store with the old model — and nothing told
   * the user.
   */
  hasUnsavedChanges?: boolean;
}) {
  const { t } = useTranslation();
  const [ingestions, setIngestions] = useState<IngestionStatus[]>([]);
  const [textContent, setTextContent] = useState("");
  const [dragOver, setDragOver] = useState(false);
  // Off by default: without it, ingesting a file name twice keeps both copies,
  // which is the long-standing behaviour of this endpoint.
  const [replaceSameName, setReplaceSameName] = useState(false);
  const mountedRef = useRef(true);

  // Cleanup: mark unmounted so polling stops updating state. Set on mount as well,
  // not only initialised: StrictMode mounts, unmounts and mounts again, and a ref
  // that only the cleanup writes stays false from then on — every poll returned
  // at once and an ingestion sat at "processing" for ever in development.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const pollStatus = useCallback(
    (ingestionId: string) => {
      let attempts = 0;
      const MAX_POLL_ATTEMPTS = 30; // ~60 seconds at 2s intervals
      const poll = async () => {
        if (!mountedRef.current) return;
        attempts++;
        try {
          const result = await api.get<{ status: string; warning?: string }>(
            `/ragstore/rags/${kbId}/ingestion/${ingestionId}/status`,
          );
          if (!mountedRef.current) return;
          setIngestions((prev) =>
            prev.map((ing) =>
              ing.ingestionId === ingestionId ? { ...ing, status: result.status, warning: result.warning } : ing,
            ),
          );
          if ((result.status === "processing" || result.status === "pending") && attempts < MAX_POLL_ATTEMPTS) {
            setTimeout(poll, 2000);
          } else if (attempts >= MAX_POLL_ATTEMPTS) {
            setIngestions((prev) =>
              prev.map((ing) =>
                ing.ingestionId === ingestionId ? { ...ing, status: "failed: timeout" } : ing,
              ),
            );
          }
        } catch {
          if (!mountedRef.current) return;
          setIngestions((prev) =>
            prev.map((ing) =>
              ing.ingestionId === ingestionId ? { ...ing, status: "failed: polling error" } : ing,
            ),
          );
        }
      };
      poll();
    },
    [kbId],
  );

  const startIngestion = useCallback(
    async (content: string, name: string, replace = false) => {
      const tempId = `local-${Date.now()}`;
      setIngestions((prev) => [...prev, { ingestionId: tempId, status: "uploading", documentName: name }]);

      try {
        // Backend expects: POST /ragstore/rags/{id}/ingest?version=N&documentName=X
        // with Content-Type: text/plain body
        const params = new URLSearchParams({
          version: String(version),
          documentName: name,
        });
        if (replace) params.set("replace", "true");
        const response = await fetch(
          `${api.getBaseUrl()}/ragstore/rags/${kbId}/ingest?${params.toString()}`,
          {
            method: "POST",
            headers: {
              "Content-Type": "text/plain",
              ...api.getAuthHeader(),
            },
            body: content,
          },
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const result: { ingestionId: string } = await response.json();
        const id = result.ingestionId;
        setIngestions((prev) =>
          prev.map((ing) =>
            ing.ingestionId === tempId ? { ...ing, ingestionId: id, status: "processing" } : ing,
          ),
        );
        // Poll status
        pollStatus(id);
      } catch {
        setIngestions((prev) =>
          prev.map((ing) =>
            ing.ingestionId === tempId ? { ...ing, status: "failed: upload error" } : ing,
          ),
        );
      }
    },
    [kbId, version, pollStatus],
  );

  // What the editor looks like *now*, for a file read that finished after the
  // render that started it: the check at drop time is not enough on its own.
  // Updated in a layout effect, i.e. during the commit that shows the new props:
  // a passive effect can run after the browser has already handled other tasks,
  // and a FileReader.onload among them would read the previous guard.
  const readGuard = useRef({ dirty: Boolean(hasUnsavedChanges), version });
  useLayoutEffect(() => {
    readGuard.current = { dirty: Boolean(hasUnsavedChanges), version };
  }, [hasUnsavedChanges, version]);

  const handleFiles = useCallback(
    (files: FileList) => {
      if (hasUnsavedChanges) return;
      Array.from(files).forEach((file) => {
        const reader = new FileReader();
        reader.onload = () => {
          if (!mountedRef.current) return;
          // Edited (or saved as a new version) while the file was being read:
          // the ingest would go to a knowledge base other than the one on screen.
          if (readGuard.current.dirty || readGuard.current.version !== version) {
            setIngestions((prev) => [
              ...prev,
              {
                ingestionId: `err-${Date.now()}`,
                status: `failed: ${file.name} was not ingested — the knowledge base changed while it was read`,
                documentName: file.name,
              },
            ]);
            return;
          }
          const content = reader.result as string;
          startIngestion(content, file.name, replaceSameName);
        };
        reader.onerror = () => {
          setIngestions((prev) => [
            ...prev,
            { ingestionId: `err-${Date.now()}`, status: `failed: could not read ${file.name}`, documentName: file.name },
          ]);
        };
        reader.readAsText(file);
      });
    },
    [startIngestion, hasUnsavedChanges, version, replaceSameName],
  );

  const handleTextIngest = useCallback(() => {
    if (!textContent.trim() || hasUnsavedChanges) return;
    startIngestion(textContent, `text-${Date.now()}.txt`);
    setTextContent("");
  }, [textContent, startIngestion, hasUnsavedChanges]);

  if (readOnly) return null;

  return (
    <div className="space-y-3" data-testid="ingestion-panel">
      {hasUnsavedChanges && (
        <p
          className="text-xs text-amber-700 dark:text-amber-400"
          role="alert"
          data-testid="ingestion-save-first"
        >
          {t(
            "ragEditor.saveBeforeIngest",
            "Save your changes first — documents are ingested with the saved knowledge base's store and embedding model, not the edits on screen.",
          )}
        </p>
      )}
      {/* Drop zone */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          if (e.dataTransfer.files.length) handleFiles(e.dataTransfer.files);
        }}
        className={cn(
          "rounded-lg border-2 border-dashed p-4 text-center transition-colors",
          dragOver && !hasUnsavedChanges
            ? "border-primary bg-primary/5"
            : "border-muted-foreground/20 hover:border-muted-foreground/40",
          hasUnsavedChanges && "opacity-60",
        )}
        data-testid="ingestion-dropzone"
      >
        <Upload className="mx-auto h-6 w-6 text-muted-foreground/50 mb-1.5" />
        <p className="text-xs text-muted-foreground">
          {t("ragEditor.dropFiles", "Drop documents here to ingest")}
        </p>
        <p className="text-[10px] text-muted-foreground/60 mt-0.5">
          {t("ragEditor.dropHint", "Text, Markdown, or any text-based file")}
        </p>
        <label
          className={cn(
            "mt-2 inline-flex items-center gap-1 rounded-md border border-input px-3 py-1.5 text-xs font-medium text-foreground transition-colors",
            hasUnsavedChanges ? "cursor-not-allowed" : "cursor-pointer hover:bg-muted/50",
          )}
        >
          <FileText className="h-3 w-3" />
          {t("ragEditor.browse", "Browse")}
          <input
            type="file"
            multiple
            accept=".txt,.md,.csv,.json,.xml,.html"
            className="hidden"
            disabled={hasUnsavedChanges}
            onChange={(e) => e.target.files && handleFiles(e.target.files)}
            data-testid="ingestion-file-input"
          />
        </label>
        <label className="mt-2 flex items-center justify-center gap-1.5 text-[11px] text-muted-foreground">
          <input
            type="checkbox"
            checked={replaceSameName}
            onChange={(e) => setReplaceSameName(e.target.checked)}
            data-testid="ingest-replace-same-name"
          />
          {t("ragEditor.replaceSameName", "Replace a previously ingested document with the same file name")}
        </label>
      </div>

      {/* Text paste */}
      <div className="space-y-1.5">
        <label className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("ragEditor.pasteText", "Or paste text content")}
        </label>
        <textarea
          value={textContent}
          onChange={(e) => setTextContent(e.target.value)}
          placeholder={t("ragEditor.pastePlaceholder", "Paste document text here...")}
          rows={3}
          className="w-full rounded-md border border-input bg-background px-3 py-2 text-xs text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-ring resize-y"
        />
        {textContent.trim() && (
          <button
            type="button"
            onClick={handleTextIngest}
            disabled={hasUnsavedChanges}
            className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="ingest-text-btn"
          >
            <Upload className="h-3 w-3" />
            {t("ragEditor.ingestText", "Ingest Text")}
            <span className="text-[10px] opacity-70">
              ({textContent.length.toLocaleString()} chars)
            </span>
          </button>
        )}
      </div>

      {/* Ingestion status list */}
      {ingestions.length > 0 && (
        <div className="space-y-1">
          <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            {t("ragEditor.ingestionHistory", "Ingestion Status")}
          </span>
          {ingestions.map((ing) => (
            <div
              key={ing.ingestionId}
              className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-card px-3 py-2"
            >
              {ing.status === "completed" ? (
                <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
              ) : ing.status.startsWith("failed") ? (
                <AlertCircle className="h-3.5 w-3.5 shrink-0 text-destructive" />
              ) : (
                <Loader2 className="h-3.5 w-3.5 shrink-0 text-primary animate-spin" />
              )}
              <span className="flex-1 truncate text-xs text-foreground">
                {ing.documentName}
              </span>
              <span
                className={cn(
                  "text-[10px] font-medium",
                  ing.status === "completed"
                    ? "text-emerald-500"
                    : ing.status.startsWith("failed")
                      ? "text-destructive"
                      : "text-primary",
                )}
              >
                {ing.status}
              </span>
              {ing.warning && (
                <span className="basis-full text-[10px] text-warning" role="status">
                  {ing.warning}
                </span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── Main Editor Component ──────────────────────────────────────────────────

export interface RagEditorProps {
  data: RagConfig;
  onChange: (data: RagConfig) => void;
  readOnly?: boolean;
  resourceId?: string;
  version?: number;
  /** Unsaved edits in the editor. Running a source would use the saved config. */
  isDirty?: boolean;
}

export function RagEditor({
  data,
  onChange,
  readOnly,
  resourceId,
  version = 1,
  isDirty,
}: RagEditorProps) {
  const { t } = useTranslation();

  // Cache per-store-type params so switching back preserves values
  const storeParamCache = useRef<Record<string, Record<string, string>>>({});
  // Cache per-provider embedding params so switching back preserves values
  const embeddingParamCache = useRef<Record<string, Record<string, string>>>({});

  const selectedProvider = EMBEDDING_PROVIDERS.find((p) => p.value === data.embeddingProvider);
  const selectedStore = STORE_TYPES.find((s) => s.value === data.storeType);
  // What `KnowledgeBaseStorage.defaultPhysicalName` names a KB's
  // table/collection/index when none is set. A KB with no id yet is new, and the
  // server stamps every new KB with the per-id layout. Resource ids are lower-case
  // hex, so the id sanitising leaves them as-is. The legacy (6.5) layout derives
  // the name differently per store type, so it is spelled out only for a name
  // every derivation leaves unchanged.
  const perKbName =
    !resourceId
      ? t("ragEditor.perKbDefault", "one per knowledge base (eddi_kbid_<id>)")
      : data.storeNamespace === "id"
        ? `eddi_kbid_${resourceId.toLowerCase()}`
        : data.name && data.name.length <= 50 && /^[a-z0-9_]*[a-z0-9]$/.test(data.name)
          ? `eddi_kb_${data.name}`
          : t("ragEditor.perKbLegacyDefault", "derived from the name (eddi_kb_…)");
  const storeHints = storeParamHints(data.storeType ?? "in-memory", perKbName);
  const embeddingHints = EMBEDDING_PARAM_HINTS[data.embeddingProvider ?? "openai"] ?? [];

  const unknownChunkStrategy =
    !!data.chunkStrategy?.trim() &&
    !CHUNK_STRATEGIES.some((s) => s.value === data.chunkStrategy!.trim().toLowerCase());

  // Deterministic chunk preview bar heights (stable across re-renders)
  const chunkCount = Math.min(6, Math.ceil(2048 / (data.chunkSize ?? 512)));
  const previewHeights = useMemo(
    () => Array.from({ length: 6 }, (_, i) => 16 + ((i * 7 + 3) % 13)),
    [],
  );

  return (
    <div className="space-y-4" data-testid="rag-editor">
      {/* ══════ General ══════ */}
      <Section
        label={t("ragEditor.general", "General")}
        icon={Database}
        accent="text-emerald-500"
      >
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("ragEditor.kbName", "Knowledge Base Name")}
            </label>
            <input
              type="text"
              value={data.name ?? ""}
              onChange={(e) => onChange({ ...data, name: e.target.value || undefined })}
              readOnly={readOnly}
              placeholder={t("ragEditor.kbNamePlaceholder", "e.g. product-docs, faq, internal-wiki")}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="kb-name"
            />
            <p className="mt-0.5 text-[10px] text-muted-foreground">
              {t("ragEditor.kbNameHint", "Used to reference this KB from LLM tasks")}
            </p>
          </div>
        </div>
      </Section>

      {/* ══════ Embedding Model ══════ */}
      <Section
        label={t("ragEditor.embeddingModel", "Embedding Model")}
        icon={Layers}
        accent="text-blue-500"
        badge={selectedProvider?.label}
      >
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("ragEditor.provider", "Provider")}
            </label>
            <select
              value={data.embeddingProvider ?? "openai"}
              onChange={(e) => {
                const newProvider = e.target.value;
                // Save current embedding params before switching
                if (data.embeddingProvider && data.embeddingParameters) {
                  embeddingParamCache.current[data.embeddingProvider] = { ...data.embeddingParameters };
                }
                // Restore what this provider had earlier in the session, else
                // start empty: every parameter then shows its default or says
                // it is required. Never pre-fill — see KeyValueEditor.
                const cached = embeddingParamCache.current[newProvider];
                onChange({
                  ...data,
                  embeddingProvider: newProvider,
                  embeddingParameters: cached && Object.keys(cached).length > 0 ? cached : undefined,
                });
              }}
              disabled={readOnly}
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
              data-testid="embedding-provider"
            >
              {EMBEDDING_PROVIDERS.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label} — {p.hint}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              <Server className="h-3 w-3" />
              {t("ragEditor.embeddingParams", "Provider Parameters")}
            </label>
            <p className="mb-1 text-[10px] text-muted-foreground">
              {t("ragEditor.embeddingParamsHint", "Model name, API key (use ${vault:...} for secrets), base URL, etc.")}
            </p>
            <KeyValueEditor
              key={data.embeddingProvider ?? "openai"}
              entries={data.embeddingParameters ?? {}}
              onChange={(v) => onChange({ ...data, embeddingParameters: Object.keys(v).length > 0 ? v : undefined })}
              readOnly={readOnly}
              hints={embeddingHints}
              testIdPrefix="embed"
            />
          </div>
        </div>
      </Section>

      {/* ══════ Vector Store ══════ */}
      <Section
        label={t("ragEditor.vectorStore", "Vector Store")}
        icon={Server}
        accent="text-purple-500"
        badge={selectedStore?.label}
      >
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("ragEditor.storeType", "Store Type")}
            </label>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {STORE_TYPES.map((st) => (
                <button
                  key={st.value}
                  type="button"
                  disabled={readOnly}
                  onClick={() => {
                    if (st.value === data.storeType) return; // no-op if already selected
                    // Save current params before switching
                    if (data.storeType && data.storeParameters) {
                      storeParamCache.current[data.storeType] = { ...data.storeParameters };
                    }
                    // Restore what this store had earlier in the session, else
                    // start empty. Never pre-fill — see KeyValueEditor.
                    const cached = storeParamCache.current[st.value];
                    onChange({
                      ...data,
                      storeType: st.value,
                      storeParameters: cached && Object.keys(cached).length > 0 ? cached : undefined,
                    });
                  }}
                  className={cn(
                    "rounded-lg border p-2.5 text-start transition-all",
                    data.storeType === st.value
                      ? "border-primary bg-primary/5 ring-1 ring-primary/50"
                      : "border-border hover:border-muted-foreground/40",
                    readOnly && "opacity-60 cursor-default",
                  )}
                  data-testid={`store-${st.value}`}
                  aria-pressed={data.storeType === st.value}
                >
                  <span className="text-xs font-medium text-foreground">{st.label}</span>
                  <p className="mt-0.5 text-[10px] text-muted-foreground">{st.hint}</p>
                </button>
              ))}
            </div>
          </div>

          {storeHints.length > 0 && (
            <div>
              <label className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                <Server className="h-3 w-3" />
                {t("ragEditor.storeParams", "Connection Parameters")}
              </label>
              <KeyValueEditor
                key={data.storeType}
                entries={data.storeParameters ?? {}}
                onChange={(v) =>
                  onChange({ ...data, storeParameters: Object.keys(v).length > 0 ? v : undefined })
                }
                readOnly={readOnly}
                hints={storeHints}
                testIdPrefix="store"
              />
            </div>
          )}

          <RagStoragePanel data={data} onChange={onChange} readOnly={readOnly} resourceId={resourceId} version={version} />
        </div>
      </Section>

      {/* ══════ Chunking ══════ */}
      <Section
        label={t("ragEditor.chunking", "Document Chunking")}
        icon={Scissors}
        accent="text-amber-500"
        defaultOpen={false}
      >
        <div className="space-y-3" data-testid="chunking-section">
          <div>
            <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("ragEditor.chunkStrategy", "Strategy")}
            </label>
            <select
              value={data.chunkStrategy ?? "recursive"}
              onChange={(e) => onChange({ ...data, chunkStrategy: e.target.value })}
              disabled={readOnly}
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
              data-testid="chunk-strategy"
            >
              {CHUNK_STRATEGIES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
              {unknownChunkStrategy && (
                <option value={data.chunkStrategy} disabled>
                  {isLegacyChunkStrategy(data.chunkStrategy)
                    ? t("ragEditor.chunkStrategyLegacy", "{{value}} (saved as Recursive)", {
                        value: data.chunkStrategy,
                      })
                    : t("ragEditor.chunkStrategyUnsupported", "{{value}} (not supported)", {
                        value: data.chunkStrategy,
                      })}
                </option>
              )}
            </select>
            {unknownChunkStrategy && !isLegacyChunkStrategy(data.chunkStrategy) && (
              <p className="mt-1 text-[10px] text-destructive" role="alert" data-testid="chunk-strategy-unsupported">
                {t(
                  "ragEditor.chunkStrategyUnsupportedHint",
                  "The server refuses this strategy when you save. Choose Recursive.",
                )}
              </p>
            )}
          </div>

          {/* Chunk size slider */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("ragEditor.chunkSize", "Chunk Size")}
              </label>
              <span className="text-xs font-mono font-medium text-foreground">
                {data.chunkSize ?? 512} chars
              </span>
            </div>
            <input
              type="range"
              min={64}
              max={4096}
              step={64}
              value={data.chunkSize ?? 512}
              onChange={(e) => onChange({ ...data, chunkSize: parseInt(e.target.value, 10) })}
              disabled={readOnly}
              className="w-full accent-primary"
              data-testid="chunk-size"
            />
            <div className="flex justify-between text-[9px] text-muted-foreground/50 mt-0.5">
              <span>64</span>
              <span>512</span>
              <span>1024</span>
              <span>2048</span>
              <span>4096</span>
            </div>
          </div>

          {/* Chunk overlap slider */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("ragEditor.chunkOverlap", "Chunk Overlap")}
              </label>
              <span className="text-xs font-mono font-medium text-foreground">
                {data.chunkOverlap ?? 64} chars
              </span>
            </div>
            <input
              type="range"
              min={0}
              max={Math.floor((data.chunkSize ?? 512) / 2)}
              step={16}
              value={data.chunkOverlap ?? 64}
              onChange={(e) => onChange({ ...data, chunkOverlap: parseInt(e.target.value, 10) })}
              disabled={readOnly}
              className="w-full accent-primary"
              data-testid="chunk-overlap"
            />
            <p className="mt-0.5 text-[10px] text-muted-foreground">
              {t("ragEditor.overlapHint", "Overlap helps maintain context between chunks")}
            </p>
          </div>

          {/* Visual chunk preview */}
          <div className="rounded-md border border-dashed border-muted-foreground/20 p-3">
            <p className="text-[10px] font-semibold text-muted-foreground mb-1.5">
              {t("ragEditor.chunkPreview", "Preview (how a document gets split)")}
            </p>
            <div className="flex gap-0.5 items-end">
              {Array.from({ length: chunkCount }).map(
                (_, i) => (
                  <div key={i} className="flex-1">
                    <div
                      className={cn(
                        "rounded-sm",
                        i % 2 === 0 ? "bg-emerald-500/50" : "bg-blue-500/50",
                      )}
                      style={{ height: `${previewHeights[i]}px` }}
                    />
                    {(data.chunkOverlap ?? 64) > 0 && i < 5 && (
                      <div
                        className="bg-amber-500/30 rounded-sm -mt-0.5"
                        style={{
                          height: `${Math.max(2, ((data.chunkOverlap ?? 64) / (data.chunkSize ?? 512)) * 16)}px`,
                        }}
                      />
                    )}
                  </div>
                ),
              )}
            </div>
            <p className="text-[9px] text-muted-foreground/60 mt-1">
              {t("ragEditor.chunkBlocks", "{{count}} chunks of ~{{size}} chars with {{overlap}} char overlap", {
                count: Math.ceil(2048 / (data.chunkSize ?? 512)),
                size: data.chunkSize ?? 512,
                overlap: data.chunkOverlap ?? 64,
              })}
            </p>
          </div>
        </div>
      </Section>

      {/* ══════ Retrieval Defaults ══════ */}
      <Section
        label={t("ragEditor.retrievalDefaults", "Retrieval Defaults")}
        icon={Search}
        accent="text-cyan-500"
      >
        <div className="space-y-3" data-testid="retrieval-section">
          <p className="text-[10px] text-muted-foreground">
            {t(
              "ragEditor.retrievalHint",
              "These defaults can be overridden per-task in the LLM configuration.",
            )}
          </p>

          {/* Max results */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("ragEditor.maxResults", "Max Results (top-K)")}
              </label>
              <span className="text-xs font-mono font-medium text-foreground">
                {data.maxResults ?? 5}
              </span>
            </div>
            <input
              type="range"
              min={1}
              max={20}
              value={data.maxResults ?? 5}
              onChange={(e) => onChange({ ...data, maxResults: parseInt(e.target.value, 10) })}
              disabled={readOnly}
              className="w-full accent-primary"
              data-testid="max-results"
            />
          </div>

          {/* Min score */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("ragEditor.minScore", "Min Similarity Score")}
              </label>
              <span
                className={cn(
                  "text-xs font-mono font-medium",
                  (data.minScore ?? 0.6) >= 0.8
                    ? "text-emerald-500"
                    : (data.minScore ?? 0.6) >= 0.5
                      ? "text-amber-500"
                      : "text-red-500",
                )}
              >
                {(data.minScore ?? 0.6).toFixed(2)}
              </span>
            </div>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={data.minScore ?? 0.6}
              onChange={(e) => onChange({ ...data, minScore: parseFloat(e.target.value) })}
              disabled={readOnly}
              className="w-full accent-primary"
              data-testid="min-score"
            />
            <div className="flex justify-between text-[9px] mt-0.5">
              <span className="text-red-400">0.0 (any)</span>
              <span className="text-amber-400">0.5</span>
              <span className="text-emerald-400">1.0 (exact)</span>
            </div>
          </div>
        </div>
      </Section>

      {/* ══════ Ingestion Sources ══════ */}
      <Section
        label={t("ragEditor.sources.title", "Ingestion Sources")}
        icon={Globe}
        accent="text-sky-500"
        defaultOpen={false}
        badge={data.sources?.length ? String(data.sources.length) : undefined}
      >
        <IngestionSourcesPanel
          sources={data.sources ?? []}
          onChange={(sources) => onChange({ ...data, sources })}
          kbId={resourceId}
          version={version}
          readOnly={readOnly}
          hasUnsavedChanges={isDirty}
        />
      </Section>

      {/* ══════ Document Ingestion ══════ */}
      <Section
        label={t("ragEditor.ingestion", "Document Ingestion")}
        icon={Upload}
        accent="text-rose-500"
        defaultOpen={false}
      >
        {resourceId ? (
          <IngestionPanel
            kbId={resourceId}
            version={version}
            readOnly={readOnly}
            hasUnsavedChanges={isDirty}
          />
        ) : (
          <p className="text-xs text-muted-foreground italic">
            {t("ragEditor.saveFirstIngestion", "Save this knowledge base first to enable document ingestion.")}
          </p>
        )}
      </Section>
    </div>
  );
}
