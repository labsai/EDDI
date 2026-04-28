import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Database,
  Server,
  Layers,
  Scissors,
  Search,
  Upload,
  FileText,
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

// ─── Types ───────────────────────────────────────────────────────────────────

export interface RagConfig {
  name?: string;
  embeddingProvider?: string;
  embeddingParameters?: Record<string, string>;
  storeType?: string;
  storeParameters?: Record<string, string>;
  chunkStrategy?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  maxResults?: number;
  minScore?: number;
}

// ─── Constants ───────────────────────────────────────────────────────────────

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

/** Provider-specific parameter hints — shown as suggested keys in the embedding params editor */
const EMBEDDING_PARAM_HINTS: Record<string, { key: string; placeholder: string }[]> = {
  openai: [
    { key: "model", placeholder: "text-embedding-3-small" },
    { key: "apiKey", placeholder: "${eddivault:openai-key}" },
  ],
  "azure-openai": [
    { key: "endpoint", placeholder: "https://my.openai.azure.com/" },
    { key: "apiKey", placeholder: "${eddivault:azure-key}" },
    { key: "deploymentName", placeholder: "text-embedding-3-small" },
  ],
  ollama: [
    { key: "model", placeholder: "nomic-embed-text" },
    { key: "baseUrl", placeholder: "http://localhost:11434" },
  ],
  mistral: [
    { key: "model", placeholder: "mistral-embed" },
    { key: "apiKey", placeholder: "${eddivault:mistral-key}" },
  ],
  bedrock: [
    { key: "model", placeholder: "amazon.titan-embed-text-v2:0" },
    { key: "region", placeholder: "us-east-1" },
  ],
  cohere: [
    { key: "model", placeholder: "embed-english-v3.0" },
    { key: "apiKey", placeholder: "${eddivault:cohere-key}" },
  ],
  vertex: [
    { key: "project", placeholder: "my-gcp-project" },
    { key: "location", placeholder: "us-central1" },
    { key: "model", placeholder: "text-embedding-005" },
  ],
  gemini: [
    { key: "apiKey", placeholder: "${eddivault:gemini-key}" },
    { key: "model", placeholder: "gemini-embedding-2"},
    { key: "taskType", placeholder: "RETRIEVAL_DOCUMENT"},
    { key: "outputDimensionality", placeholder: "3072"},
  ]
};

const STORE_TYPES = [
  { value: "in-memory", label: "In-Memory", hint: "Dev/test only — data lost on restart" },
  { value: "pgvector", label: "PostgreSQL (pgvector)", hint: "Production — persistent vector store" },
  { value: "mongodb-atlas", label: "MongoDB Atlas", hint: "Atlas vector search" },
  { value: "elasticsearch", label: "Elasticsearch", hint: "Elasticsearch vector search" },
  { value: "qdrant", label: "Qdrant", hint: "High-performance vector DB" },
  { value: "chroma", label: "Chroma", hint: "Fast, serverless, and scalable infrastructure supporting vector, full-text, regex, and metadata search." },
] as const;

const STORE_PARAM_HINTS: Record<string, { key: string; placeholder: string }[]> = {
  "in-memory": [],
  pgvector: [
    { key: "host", placeholder: "localhost" },
    { key: "port", placeholder: "5432" },
    { key: "database", placeholder: "eddi" },
    { key: "table", placeholder: "embeddings" },
    { key: "dimension", placeholder: "1536" },
    { key: "user", placeholder: "${eddivault:pg-user}" },
    { key: "password", placeholder: "${eddivault:pg-password}" },
  ],
  "mongodb-atlas": [
    { key: "connectionString", placeholder: "${eddivault:mongo-uri}" },
    { key: "databaseName", placeholder: "eddi" },
    { key: "collectionName", placeholder: "eddi_kb_product-docs" },
    { key: "indexName", placeholder: "vector_index" },
  ],
  elasticsearch: [
    { key: "serverUrl", placeholder: "http://localhost:9200" },
    { key: "indexName", placeholder: "eddi_kb_product-docs" },
    { key: "apiKey", placeholder: "${eddivault:es-key}" },
    { key: "userName", placeholder: "elastic" },
    { key: "password", placeholder: "${eddivault:es-password}" },
  ],
  qdrant: [
    { key: "host", placeholder: "localhost" },
    { key: "port", placeholder: "6334" },
    { key: "collectionName", placeholder: "kb-product-docs" },
    { key: "apiKey", placeholder: "${eddivault:qdrant-key}" },
    { key: "useTls", placeholder: "false" },
  ],
  chroma: [
    { key: "baseUrl", placeholder: "http://localhost:8000" },
    { key: "collectionName", placeholder: "kb-product-docs" },
    { key: "tenantName", placeholder: "default_tenant" },
    { key: "databaseName", placeholder: "default_database" },
  ],
};

const CHUNK_STRATEGIES = [
  { value: "recursive", label: "Recursive (recommended)" },
  { value: "paragraph", label: "Paragraph" },
  { value: "sentence", label: "Sentence" },
] as const;

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
const SENSITIVE_KEYS = new Set(["apikey", "password", "secret", "token"]);

function isSensitiveKey(key: string): boolean {
  return SENSITIVE_KEYS.has(key.toLowerCase());
}

function KeyValueEditor({
  entries,
  onChange,
  readOnly,
  hints,
  label,
  testIdPrefix = "kv",
}: {
  entries: Record<string, string>;
  onChange: (v: Record<string, string>) => void;
  readOnly?: boolean;
  hints?: { key: string; placeholder: string }[];
  label?: string;
  /** Prefix for data-testid attributes, avoids collisions when multiple editors share key names */
  testIdPrefix?: string;
}) {
  const { t } = useTranslation();
  const pairs = Object.entries(entries);

  const addEntry = () => {
    // Use next hint key, or generate unique key
    const existingKeys = new Set(Object.keys(entries));
    const nextHint = hints?.find((h) => !existingKeys.has(h.key));
    const key = nextHint?.key ?? `param${pairs.length + 1}`;
    onChange({ ...entries, [key]: "" });
  };

  const removeEntry = (key: string) => {
    const copy = { ...entries };
    delete copy[key];
    onChange(copy);
  };

  const updateKey = (newKey: string, idx: number) => {
    const arr = Object.entries(entries);
    // Prevent duplicate keys (except our own position)
    if (arr.some(([k], i) => i !== idx && k === newKey)) return;
    arr[idx] = [newKey, arr[idx]?.[1] ?? ""];
    onChange(Object.fromEntries(arr));
  };

  const updateValue = (key: string, value: string) => {
    onChange({ ...entries, [key]: value });
  };

  const isVaultRef = (value: string) => value.startsWith("${eddivault:");

  return (
    <div className="space-y-1.5">
      {label && (
        <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {label}
        </span>
      )}
      {pairs.map(([key, value], idx) => {
        const hint = hints?.find((h) => h.key === key);
        return (
          <div key={idx} className="flex items-center gap-1.5">
            <input
              type="text"
              value={key}
              onChange={(e) => updateKey(e.target.value, idx)}
              readOnly={readOnly}
              placeholder="key"
              className="h-7 w-32 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
            <span className="text-muted-foreground/40">=</span>
            {isSensitiveKey(key) ? (
              <div className="flex-1">
                <SecretKeyPicker
                  value={value}
                  onChange={(v) => updateValue(key, v)}
                  readOnly={readOnly}
                  placeholder={hint?.placeholder || "${eddivault:...}"}
                  testId={`${testIdPrefix}-${key}`}
                />
              </div>
            ) : (
              <div className="relative flex-1">
                <input
                  type="text"
                  value={value}
                  onChange={(e) => updateValue(key, e.target.value)}
                  readOnly={readOnly}
                  placeholder={hint?.placeholder || "value"}
                  className={cn(
                    "h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring",
                    isVaultRef(value) && "pe-7",
                  )}
                />
                {isVaultRef(value) && (
                  <Lock className="absolute inset-e-2 top-1.5 h-3.5 w-3.5 text-amber-500" />
                )}
              </div>
            )}
            {!readOnly && (
              <button
                type="button"
                onClick={() => removeEntry(key)}
                className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        );
      })}
      {/* Auto-suggest missing hint keys */}
      {!readOnly && hints && hints.some((h) => !(h.key in entries)) && (
        <div className="flex flex-wrap gap-1 pt-1">
          {hints
            .filter((h) => !(h.key in entries))
            .map((h) => (
              <button
                key={h.key}
                type="button"
                onClick={() => onChange({ ...entries, [h.key]: h.placeholder.startsWith("${eddivault:") ? h.placeholder : "" })}
                className="rounded-full border border-dashed border-muted-foreground/30 px-2 py-0.5 text-[10px] text-muted-foreground hover:border-primary hover:text-primary transition-colors"
              >
                + {h.key}
              </button>
            ))}
        </div>
      )}
      {!readOnly && (
        <button
          type="button"
          onClick={addEntry}
          className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <Plus className="h-3 w-3" />
          {t("ragEditor.addParam", "Add Parameter")}
        </button>
      )}
    </div>
  );
}

// ─── Ingestion Panel ────────────────────────────────────────────────────────

interface IngestionStatus {
  ingestionId: string;
  status: string;
  documentName: string;
}

function IngestionPanel({
  kbId,
  version,
  readOnly,
}: {
  kbId: string;
  version: number;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [ingestions, setIngestions] = useState<IngestionStatus[]>([]);
  const [textContent, setTextContent] = useState("");
  const [dragOver, setDragOver] = useState(false);
  const mountedRef = useRef(true);

  // Cleanup: mark unmounted so polling stops updating state
  useEffect(() => {
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
          const result = await api.get<{ status: string }>(
            `/ragstore/rags/${kbId}/ingestion/${ingestionId}/status`,
          );
          if (!mountedRef.current) return;
          setIngestions((prev) =>
            prev.map((ing) =>
              ing.ingestionId === ingestionId ? { ...ing, status: result.status } : ing,
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
    async (content: string, name: string) => {
      const tempId = `local-${Date.now()}`;
      setIngestions((prev) => [...prev, { ingestionId: tempId, status: "uploading", documentName: name }]);

      try {
        // Backend expects: POST /ragstore/rags/{id}/ingest?version=N&documentName=X
        // with Content-Type: text/plain body
        const params = new URLSearchParams({
          version: String(version),
          documentName: name,
        });
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

  const handleFiles = useCallback(
    (files: FileList) => {
      Array.from(files).forEach((file) => {
        const reader = new FileReader();
        reader.onload = () => {
          const content = reader.result as string;
          startIngestion(content, file.name);
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
    [startIngestion],
  );

  const handleTextIngest = useCallback(() => {
    if (!textContent.trim()) return;
    startIngestion(textContent, `text-${Date.now()}.txt`);
    setTextContent("");
  }, [textContent, startIngestion]);

  if (readOnly) return null;

  return (
    <div className="space-y-3" data-testid="ingestion-panel">
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
          dragOver
            ? "border-primary bg-primary/5"
            : "border-muted-foreground/20 hover:border-muted-foreground/40",
        )}
      >
        <Upload className="mx-auto h-6 w-6 text-muted-foreground/50 mb-1.5" />
        <p className="text-xs text-muted-foreground">
          {t("ragEditor.dropFiles", "Drop documents here to ingest")}
        </p>
        <p className="text-[10px] text-muted-foreground/60 mt-0.5">
          {t("ragEditor.dropHint", "Text, Markdown, or any text-based file")}
        </p>
        <label className="mt-2 inline-flex cursor-pointer items-center gap-1 rounded-md border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted/50 transition-colors">
          <FileText className="h-3 w-3" />
          {t("ragEditor.browse", "Browse")}
          <input
            type="file"
            multiple
            accept=".txt,.md,.csv,.json,.xml,.html"
            className="hidden"
            onChange={(e) => e.target.files && handleFiles(e.target.files)}
          />
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
            className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
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
              className="flex items-center gap-2 rounded-md border border-border bg-card px-3 py-2"
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
}

export function RagEditor({ data, onChange, readOnly, resourceId, version = 1 }: RagEditorProps) {
  const { t } = useTranslation();

  // Cache per-store-type params so switching back preserves values
  const storeParamCache = useRef<Record<string, Record<string, string>>>({});
  // Cache per-provider embedding params so switching back preserves values
  const embeddingParamCache = useRef<Record<string, Record<string, string>>>({});

  const selectedProvider = EMBEDDING_PROVIDERS.find((p) => p.value === data.embeddingProvider);
  const selectedStore = STORE_TYPES.find((s) => s.value === data.storeType);
  const storeHints = STORE_PARAM_HINTS[data.storeType ?? "in-memory"] ?? [];
  const embeddingHints = EMBEDDING_PARAM_HINTS[data.embeddingProvider ?? "openai"] ?? [];

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
                // Restore cached params or auto-populate from hints
                const cached = embeddingParamCache.current[newProvider];
                const hints = EMBEDDING_PARAM_HINTS[newProvider] ?? [];
                const newParams = cached ?? Object.fromEntries(
                  hints.map((h) => [
                    h.key,
                    h.placeholder.startsWith("${eddivault:") ? h.placeholder : "",
                  ]),
                );
                onChange({
                  ...data,
                  embeddingProvider: newProvider,
                  embeddingParameters: Object.keys(newParams).length > 0 ? newParams : undefined,
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
              {t("ragEditor.embeddingParamsHint", "Model name, API key (use ${eddivault:...} for secrets), base URL, etc.")}
            </p>
            <KeyValueEditor
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
                    // Restore cached params or auto-populate from hints
                    const cached = storeParamCache.current[st.value];
                    const newParams = cached ?? Object.fromEntries(
                      (STORE_PARAM_HINTS[st.value] ?? []).map((h) => [
                        h.key,
                        h.placeholder.startsWith("${eddivault:") ? h.placeholder : "",
                      ]),
                    );
                    onChange({
                      ...data,
                      storeType: st.value,
                      storeParameters: Object.keys(newParams).length > 0 ? newParams : undefined,
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
            </select>
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
          <div className="rounded-md border border-dashed border-muted-foreground/20 p-2.5">
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

      {/* ══════ Document Ingestion ══════ */}
      <Section
        label={t("ragEditor.ingestion", "Document Ingestion")}
        icon={Upload}
        accent="text-rose-500"
        defaultOpen={false}
      >
        {resourceId ? (
          <IngestionPanel kbId={resourceId} version={version} readOnly={readOnly} />
        ) : (
          <p className="text-xs text-muted-foreground italic">
            {t("ragEditor.saveFirstIngestion", "Save this knowledge base first to enable document ingestion.")}
          </p>
        )}
      </Section>
    </div>
  );
}
