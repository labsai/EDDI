import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, ChevronDown, Loader2, Plug, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useConnectionDescriptors } from "@/hooks/use-connections";
import { isApiError } from "@/lib/api-client";
import { bindingLabel } from "@/lib/connection-labels";
import { isValidConnectionName } from "@/lib/connection-name";
import { toConnectionReference } from "@/lib/secret-reference";

interface ConnectionReferenceButtonProps {
  /** Called with the finished `${connection:name}` to put into the field. */
  onInsert: (reference: string) => void;
  disabled?: boolean;
  /** The trigger's classes, so a host can fit it into its own button group. */
  className?: string;
  testId?: string;
}

/**
 * A button that lists the deployment's connections and inserts a reference.
 *
 * Offered only where the backend resolves one — an httpcall header, an MCP
 * server's `apiKey`, an A2A agent's `apiKey` — and deliberately not in the
 * language-model, embedding or vector-store editors, which refuse it.
 *
 * The list is the admin descriptor list, which an `eddi-editor` may read but a
 * plain viewer may not; a 403 is therefore an answer rather than a failure,
 * and it gets a sentence rather than an error box. A 404 is a backend without
 * the feature. Both leave typing the reference by hand open.
 */
export function ConnectionReferenceButton({
  onInsert,
  disabled,
  className,
  testId = "connection-ref",
}: ConnectionReferenceButtonProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);
  const filterRef = useRef<HTMLInputElement>(null);

  // Fetched only once the popup opens: an editor with three API-key fields
  // must not spend three descriptor fan-outs on mount.
  const { data, isLoading, isError, error, refetch } = useConnectionDescriptors(
    100,
    0,
    "",
    open,
  );

  const rows = useMemo(() => {
    // A name outside the backend's grammar (a document older than the rule)
    // cannot be carried by a reference, so it is not offered for insertion.
    const all = (data ?? []).filter(
      (row) => !row.unreadable && isValidConnectionName(row.connectionName),
    );
    const q = filter.trim().toLowerCase();
    if (!q) return all;
    return all.filter(
      (row) =>
        row.connectionName.toLowerCase().includes(q) ||
        (row.description ?? "").toLowerCase().includes(q),
    );
  }, [data, filter]);

  useEffect(() => {
    if (!open) return;
    const timer = setTimeout(() => filterRef.current?.focus(), 50);
    const onMouseDown = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onMouseDown);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("mousedown", onMouseDown);
    };
  }, [open]);

  const close = () => {
    setOpen(false);
    setFilter("");
  };

  const status = isApiError(error) ? error.status : undefined;
  const label = t("secretPicker.pickConnection", "Insert a connection reference");

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        // Keeps focus in the host's input, so its blur handling does not fire
        // between mousedown and mouseup and unmount this very button.
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => (open ? close() : setOpen(true))}
        disabled={disabled}
        title={label}
        aria-label={label}
        aria-expanded={open}
        className={
          className ??
          `flex h-7 items-center gap-0.5 rounded-e-md border border-s-0 border-input px-1.5 text-xs transition-colors ${
            open
              ? "bg-primary/10 text-primary"
              : "bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground"
          }`
        }
        data-testid={testId}
      >
        <Plug className="h-3 w-3" aria-hidden="true" />
        <ChevronDown
          className={`h-2.5 w-2.5 transition-transform ${open ? "rotate-180" : ""}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div
          className="absolute end-0 top-full z-50 mt-1 w-72 overflow-hidden rounded-lg border border-border bg-popover shadow-xl animate-in fade-in-0 zoom-in-95 slide-in-from-top-2 duration-150"
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              e.preventDefault();
              close();
            }
          }}
          data-testid={`${testId}-popup`}
        >
          <div className="flex items-center gap-2 border-b border-border px-3 py-2">
            <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <input
              ref={filterRef}
              type="text"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder={t("secretPicker.connectionFilterPlaceholder", "Search connections…")}
              className="h-6 flex-1 border-none bg-transparent text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none"
              autoComplete="off"
              data-testid={`${testId}-filter`}
            />
          </div>

          <div className="max-h-48 overflow-y-auto" role="listbox">
            {isLoading ? (
              <div className="flex items-center justify-center gap-2 py-4 text-xs text-muted-foreground">
                <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                {t("secretPicker.connectionLoading", "Loading connections…")}
              </div>
            ) : isError && (status === 403 || status === 401) ? (
              <Hint testId={`${testId}-forbidden`}>
                {t(
                  "secretPicker.connectionForbidden",
                  "Your role cannot list connections. If you know its name, type ${connection:name} by hand.",
                )}
              </Hint>
            ) : isError && status === 404 ? (
              <Hint testId={`${testId}-unavailable`}>
                {t(
                  "secretPicker.connectionUnavailable",
                  "This backend does not offer connections.",
                )}
              </Hint>
            ) : isError ? (
              <Hint testId={`${testId}-failed`}>
                {t("secretPicker.connectionFailed", "Could not load connections.")}{" "}
                <Button
                  type="button"
                  variant="link"
                  size="sm"
                  onClick={() => void refetch()}
                  // Inline in a sentence: the link variant without its standalone height
                  // and padding, so it sits on the text's baseline.
                  className="h-auto p-0 text-xs"
                  data-testid={`${testId}-retry`}
                >
                  {t("common.retry", "Retry")}
                </Button>
              </Hint>
            ) : rows.length === 0 ? (
              <Hint testId={`${testId}-empty`}>
                {filter.trim()
                  ? t("secretPicker.connectionNoMatch", 'No connections matching "{{filter}}"', {
                      filter,
                    })
                  : t(
                      "secretPicker.connectionEmpty",
                      "No connections yet. An administrator creates them under Connections.",
                    )}
              </Hint>
            ) : (
              rows.map((row) => (
                <button
                  key={row.id}
                  type="button"
                  role="option"
                  aria-selected={false}
                  onClick={() => {
                    const reference = toConnectionReference(row.connectionName);
                    if (reference) onInsert(reference);
                    close();
                  }}
                  className="flex w-full items-start gap-2 px-3 py-2 text-start text-xs text-foreground transition-colors hover:bg-secondary/50"
                  data-testid={`${testId}-option-${row.connectionName}`}
                >
                  <Plug className="mt-0.5 h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-mono font-medium">
                      {row.connectionName}
                    </span>
                    <span className="block truncate text-[10px] text-muted-foreground">
                      {row.binding !== "unknown" && bindingLabel(t, row.binding)}
                      {row.binding !== "unknown" && row.description ? " — " : ""}
                      {row.description}
                    </span>
                  </span>
                </button>
              ))
            )}
          </div>

          <p className="border-t border-border px-3 py-2 text-[10px] text-muted-foreground">
            {t(
              "secretPicker.connectionHint",
              "Inserts ${connection:name} as the whole value. EDDI resolves it to the credential on every request.",
            )}
          </p>
        </div>
      )}
    </div>
  );
}

function Hint({ children, testId }: { children: React.ReactNode; testId: string }) {
  return (
    <div
      className="flex items-start gap-2 px-3 py-3 text-xs text-muted-foreground"
      data-testid={testId}
    >
      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" aria-hidden="true" />
      <span>{children}</span>
    </div>
  );
}
