import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ExternalLink, FileText, Library, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useDocumentChunks, useSourceDocuments } from "@/hooks/use-ingestion-sources";
import type { IngestedDocument, KnowledgeChunk } from "@/lib/api/ingestion-sources";

const PAGE_SIZE = 25;

/** How long the filter waits after the last keystroke before asking the server. */
const FILTER_DEBOUNCE_MS = 300;

/**
 * What a source has put into the knowledge base: every document it ingested,
 * when, whether it is still there — and, opened, the chunks it was stored as.
 *
 * Collapsed until asked for: the list is a request, and each opened document an
 * embedding call on the server.
 */
export function SourceContents({
  kbId,
  sourceId,
  version,
  testId,
}: {
  kbId: string;
  sourceId: string;
  version: number;
  testId: string;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [openDocument, setOpenDocument] = useState<string | null>(null);

  useEffect(() => {
    const next = filter.trim();
    // Nothing to apply — on mount, or an edit that only added spaces. Resetting the
    // page here anyway undid a "Next" clicked within the debounce window.
    if (next === query) return;
    const id = window.setTimeout(() => {
      setQuery(next);
      setPage(0);
    }, FILTER_DEBOUNCE_MS);
    return () => window.clearTimeout(id);
  }, [filter, query]);

  const documents = useSourceDocuments(
    kbId,
    sourceId,
    version,
    { query: query || undefined, offset: page * PAGE_SIZE, limit: PAGE_SIZE },
    open,
  );
  const total = documents.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  // A run can remove documents while a later page is open; the refetch then
  // reports fewer pages, and the page asked for is past the end.
  const hasData = Boolean(documents.data);
  useEffect(() => {
    if (hasData && page > pages - 1) setPage(pages - 1);
  }, [hasData, page, pages]);

  return (
    <div className="rounded-lg border border-border/60" data-testid={`${testId}-contents`}>
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-start text-xs hover:bg-secondary/60"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        data-testid={`${testId}-contents-toggle`}
      >
        <Library className="h-3.5 w-3.5 shrink-0 text-sky-500" aria-hidden="true" />
        <span className="flex-1 font-semibold uppercase tracking-wider text-muted-foreground">
          {t("ragEditor.contents.title", "Ingested documents")}
        </span>
        {open && documents.data && (
          <Badge variant="secondary" data-testid={`${testId}-contents-total`}>
            {documents.data.truncated ? `${total}+` : total}
          </Badge>
        )}
        <ChevronDown
          className={cn("h-3 w-3 text-muted-foreground transition-transform", open && "rotate-180")}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div className="space-y-2 border-t border-border/60 p-3">
          <div className="relative">
            <Search
              className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              className="ps-9"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder={t("ragEditor.contents.filter", "Filter by URL or file name")}
              aria-label={t("ragEditor.contents.filter", "Filter by URL or file name")}
              data-testid={`${testId}-contents-filter`}
            />
          </div>

          {documents.isError ? (
            <p className="text-[11px] text-destructive" role="alert" data-testid={`${testId}-contents-error`}>
              {t("ragEditor.contents.loadFailed", "Could not load the documents: {{error}}", {
                error: documents.error instanceof Error ? documents.error.message : String(documents.error),
              })}
            </p>
          ) : documents.isLoading ? (
            <p className="text-xs text-muted-foreground">{t("common.loading", "Loading…")}</p>
          ) : total === 0 ? (
            <p className="text-xs italic text-muted-foreground" data-testid={`${testId}-contents-empty`}>
              {query
                ? t("ragEditor.contents.noMatch", "No document matches this filter.")
                : t(
                    "ragEditor.contents.empty",
                    "Nothing ingested yet. Run the source, and its documents appear here.",
                  )}
            </p>
          ) : (
            <>
              <ul className="space-y-1">
                {documents.data?.documents.map((document) => (
                  <DocumentRow
                    key={document.documentId}
                    document={document}
                    isOpen={openDocument === document.documentId}
                    onToggle={() =>
                      setOpenDocument(openDocument === document.documentId ? null : document.documentId)
                    }
                    kbId={kbId}
                    sourceId={sourceId}
                    version={version}
                    testId={`${testId}-doc`}
                  />
                ))}
              </ul>
              {documents.data?.truncated && (
                <p className="text-[11px] text-muted-foreground" data-testid={`${testId}-contents-truncated`}>
                  {t(
                    "ragEditor.contents.truncated",
                    "This source holds more documents than are listed. Filter to find a specific one.",
                  )}
                </p>
              )}
              {pages > 1 && (
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPage(page - 1)}
                    disabled={page === 0}
                    data-testid={`${testId}-contents-prev`}
                  >
                    {t("common.previous", "Previous")}
                  </Button>
                  <span>
                    {t("ragEditor.contents.page", "Page {{page}} of {{pages}}", { page: page + 1, pages })}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPage(page + 1)}
                    disabled={page + 1 >= pages}
                    data-testid={`${testId}-contents-next`}
                  >
                    {t("common.next", "Next")}
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function DocumentRow({
  document,
  isOpen,
  onToggle,
  kbId,
  sourceId,
  version,
  testId,
}: {
  document: IngestedDocument;
  isOpen: boolean;
  onToggle: () => void;
  kbId: string;
  sourceId: string;
  version: number;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <li className="rounded-md border border-border/60 text-xs" data-testid={`${testId}-${document.documentId}`}>
      <div className="flex items-center gap-2 px-2 py-1.5">
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-2 text-start"
          aria-expanded={isOpen}
          onClick={onToggle}
          data-testid={`${testId}-${document.documentId}-toggle`}
        >
          <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span
            className={cn("truncate", document.tombstoned ? "text-muted-foreground line-through" : "text-foreground")}
            title={document.label}
          >
            {document.label}
          </span>
        </button>
        <DocumentState document={document} />
        {document.lastIngestedAt && (
          <span className="hidden shrink-0 text-muted-foreground sm:inline">
            {new Date(document.lastIngestedAt).toLocaleString()}
          </span>
        )}
        {document.url && (
          <a
            href={document.url}
            target="_blank"
            rel="noopener noreferrer"
            className="shrink-0 text-muted-foreground hover:text-foreground"
            aria-label={t("ragEditor.contents.openUrl", "Open the page")}
          >
            <ExternalLink className="h-3.5 w-3.5" />
          </a>
        )}
      </div>
      {isOpen && (
        <DocumentChunks
          kbId={kbId}
          sourceId={sourceId}
          version={version}
          document={document}
          testId={`${testId}-${document.documentId}`}
        />
      )}
    </li>
  );
}

function DocumentState({ document }: { document: IngestedDocument }) {
  const { t } = useTranslation();
  if (document.tombstoned) {
    return (
      <Badge variant="secondary" className="shrink-0 py-0 text-[10px]">
        {t("ragEditor.contents.removed", "Removed")}
      </Badge>
    );
  }
  if (document.missedRuns > 0) {
    return (
      <Badge variant="warning" className="shrink-0 py-0 text-[10px]">
        {t("ragEditor.contents.missed", "Missed by the last {{runs}} run(s)", { runs: document.missedRuns })}
      </Badge>
    );
  }
  return (
    <Badge variant="success" className="shrink-0 py-0 text-[10px]">
      {t("ragEditor.contents.live", "Live")}
    </Badge>
  );
}

function DocumentChunks({
  kbId,
  sourceId,
  version,
  document,
  testId,
}: {
  kbId: string;
  sourceId: string;
  version: number;
  document: IngestedDocument;
  testId: string;
}) {
  const { t } = useTranslation();
  const chunks = useDocumentChunks(kbId, sourceId, version, document.documentId);

  return (
    <div className="space-y-2 border-t border-border/60 p-2" data-testid={`${testId}-chunks`}>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-0.5 text-[11px] text-muted-foreground sm:grid-cols-2">
        {document.firstIngestedAt && (
          <div className="flex gap-1">
            <dt>{t("ragEditor.contents.firstIngested", "First ingested")}:</dt>
            <dd className="text-foreground">{new Date(document.firstIngestedAt).toLocaleString()}</dd>
          </div>
        )}
        {document.lastIngestedAt && (
          <div className="flex gap-1">
            <dt>{t("ragEditor.contents.lastIngested", "Last embedded")}:</dt>
            <dd className="text-foreground">{new Date(document.lastIngestedAt).toLocaleString()}</dd>
          </div>
        )}
      </dl>
      {document.tombstoned ? (
        <p className="text-[11px] italic text-muted-foreground">
          {t(
            "ragEditor.contents.removedHint",
            "This document is gone from the source, and its chunks were removed from the knowledge base.",
          )}
        </p>
      ) : chunks.isLoading ? (
        <p className="text-[11px] text-muted-foreground">{t("common.loading", "Loading…")}</p>
      ) : chunks.isError ? (
        <p className="text-[11px] text-destructive" role="alert" data-testid={`${testId}-chunks-error`}>
          {t("ragEditor.contents.chunksFailed", "Could not read the chunks: {{error}}", {
            error: chunks.error instanceof Error ? chunks.error.message : String(chunks.error),
          })}
        </p>
      ) : chunks.data && chunks.data.chunks.length === 0 ? (
        <p className="text-[11px] italic text-muted-foreground" data-testid={`${testId}-chunks-empty`}>
          {t(
            "ragEditor.contents.noChunks",
            "The vector store holds no chunks for this document. If the store is in-memory, a restart empties it — run the source again.",
          )}
        </p>
      ) : (
        <ChunkList chunks={chunks.data?.chunks ?? []} />
      )}
    </div>
  );
}

/** Chunks as stored, each with its position and size. Shared with the retrieval tester. */
export function ChunkList({ chunks, showScore }: { chunks: KnowledgeChunk[]; showScore?: boolean }) {
  const { t } = useTranslation();
  return (
    <ol className="space-y-1.5" data-testid="chunk-list">
      {chunks.map((chunk, i) => (
        <li key={`${chunk.index ?? "x"}-${i}`} className="rounded-md bg-muted/40 p-2 text-[11px]">
          <div className="mb-1 flex flex-wrap items-center gap-2 text-muted-foreground">
            <span className="font-semibold">
              {chunk.index != null
                ? t("ragEditor.contents.chunkNumber", "Chunk {{number}}", { number: chunk.index + 1 })
                : t("ragEditor.contents.chunk", "Chunk")}
            </span>
            <span>{t("ragEditor.contents.characters", "{{length}} characters", { length: chunk.text.length })}</span>
            {showScore && typeof chunk.score === "number" && <ScoreBar score={chunk.score} />}
            {showScore && <ChunkOrigin chunk={chunk} />}
          </div>
          <p className="whitespace-pre-wrap break-words text-foreground">{chunk.text}</p>
        </li>
      ))}
    </ol>
  );
}

function ScoreBar({ score }: { score: number }) {
  const { t } = useTranslation();
  const percent = Math.max(0, Math.min(1, score)) * 100;
  return (
    <span className="flex items-center gap-1.5" data-testid="chunk-score">
      <span className="h-1.5 w-16 overflow-hidden rounded-full bg-muted" aria-hidden="true">
        <span className="block h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
      </span>
      {t("ragEditor.retrieval.score", "score {{score}}", { score: score.toFixed(3) })}
    </span>
  );
}

/** Where a retrieved chunk came from — the metadata ingestion tagged it with. */
function ChunkOrigin({ chunk }: { chunk: KnowledgeChunk }) {
  const title = stringField(chunk.metadata, "title");
  const url = stringField(chunk.metadata, "url");
  const source = stringField(chunk.metadata, "sourceName") ?? stringField(chunk.metadata, "source");
  const label = title || url || source;
  if (!label) return null;
  return url ? (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="truncate underline-offset-2 hover:text-foreground hover:underline"
      title={url}
    >
      {label}
    </a>
  ) : (
    <span className="truncate" title={label}>
      {label}
    </span>
  );
}

function stringField(metadata: Record<string, unknown>, key: string): string | undefined {
  const value = metadata[key];
  return typeof value === "string" && value.length > 0 ? value : undefined;
}
