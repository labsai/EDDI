import { useEffect, useId, useMemo, useRef, useState, type InputHTMLAttributes, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { groupModelSuggestions } from "@/lib/model-suggestions";

type PassThroughInputProps = Omit<
  InputHTMLAttributes<HTMLInputElement>,
  "value" | "onChange" | "list" | "type" | "role" | "size"
>;

export interface ModelComboboxProps extends PassThroughInputProps {
  value: string;
  onChange: (value: string) => void;
  /** Suggested model ids, in the order they should be listed. */
  suggestions: readonly string[];
  /** The provider's default model, badged in the list. */
  defaultModel?: string;
  /** `data-testid` of the input; the list and its options derive theirs from it. */
  testId?: string;
  /** Text size of the list, to match the input it hangs from. */
  density?: "compact" | "comfortable";
}

/**
 * A free-text model field with a suggestion list.
 *
 * It replaces a native `<datalist>`, which filters its options by whatever the
 * input already holds. A field pre-filled with the provider's default therefore
 * showed one option — the default — and read as "this is the only model". Here
 * a click or the chevron lists every suggestion with the current one ticked;
 * typing puts the matches on top and keeps the rest below a divider, so the
 * list never pretends the catalog is smaller than it is. Any id can still be
 * typed: the suggestions are a help, not a whitelist.
 */
export function ModelCombobox({
  value,
  onChange,
  suggestions,
  defaultModel,
  testId,
  density = "comfortable",
  className,
  readOnly,
  disabled,
  onKeyDown,
  onBlur,
  ...inputProps
}: ModelComboboxProps) {
  const { t } = useTranslation();
  const baseId = useId();
  const listId = `${baseId}-listbox`;
  const optionId = (index: number) => `${baseId}-option-${index}`;

  const [open, setOpen] = useState(false);
  /* Whether the user has typed since the list opened. Until then the field's
     value is a selection to show, not a query to filter by. */
  const [filtering, setFiltering] = useState(false);
  const [highlighted, setHighlighted] = useState(-1);

  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const interactive = !readOnly && !disabled && suggestions.length > 0;

  const sections = useMemo(
    () => groupModelSuggestions(suggestions, filtering ? value : ""),
    [suggestions, filtering, value],
  );
  const flat = useMemo(() => sections.flatMap((s) => s.models), [sections]);

  const openList = (fromTyping: boolean) => {
    if (!interactive) return;
    setFiltering(fromTyping);
    setOpen(true);
    if (fromTyping) {
      setHighlighted(-1);
    } else {
      // Opening shows everything with the current value highlighted, so the
      // user sees where they are in the list rather than its top.
      setHighlighted(suggestions.indexOf(value));
    }
  };

  const close = () => {
    setOpen(false);
    setFiltering(false);
    setHighlighted(-1);
  };

  const select = (model: string) => {
    onChange(model);
    close();
    inputRef.current?.focus();
  };

  // A click anywhere outside closes the list.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent | MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) close();
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  // Keep the highlighted option in view. Feature-detected: jsdom has no
  // `scrollIntoView`.
  useEffect(() => {
    if (!open || highlighted < 0) return;
    const el = listRef.current?.querySelector<HTMLElement>(`[data-index="${highlighted}"]`);
    if (typeof el?.scrollIntoView === "function") el.scrollIntoView({ block: "nearest" });
  }, [open, highlighted]);

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    onKeyDown?.(e);
    if (e.defaultPrevented || !interactive) return;
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        if (!open) {
          openList(false);
        } else if (flat.length > 0) {
          setHighlighted((h) => (h + 1) % flat.length);
        }
        break;
      case "ArrowUp":
        e.preventDefault();
        if (!open) {
          openList(false);
        } else if (flat.length > 0) {
          setHighlighted((h) => (h <= 0 ? flat.length - 1 : h - 1));
        }
        break;
      case "Home":
      case "End":
        if (open && flat.length > 0 && !e.shiftKey) {
          e.preventDefault();
          setHighlighted(e.key === "Home" ? 0 : flat.length - 1);
        }
        break;
      case "Enter":
        if (open && highlighted >= 0 && highlighted < flat.length) {
          e.preventDefault();
          select(flat[highlighted]!);
        }
        break;
      case "Escape":
        if (open) {
          // Close the list, not the dialog or panel the field sits in.
          e.preventDefault();
          e.stopPropagation();
          close();
        }
        break;
      case "Tab":
        if (open) close();
        break;
    }
  };

  const textSize = density === "compact" ? "text-xs" : "text-sm";
  let runningIndex = 0;

  return (
    <div ref={rootRef} className="relative">
      <input
        {...inputProps}
        ref={inputRef}
        type="text"
        value={value}
        readOnly={readOnly}
        disabled={disabled}
        autoComplete="off"
        spellCheck={false}
        onChange={(e) => {
          onChange(e.target.value);
          if (interactive) {
            setFiltering(true);
            setOpen(true);
            setHighlighted(-1);
          }
        }}
        onClick={() => {
          if (!open) openList(false);
        }}
        onKeyDown={handleKeyDown}
        onBlur={(e) => {
          onBlur?.(e);
          // Focus moving into the list (an option click) is not leaving.
          if (!rootRef.current?.contains(e.relatedTarget as Node)) close();
        }}
        className={cn(className, interactive && "pe-8")}
        data-testid={testId}
        {...(interactive
          ? {
              role: "combobox",
              "aria-autocomplete": "list" as const,
              "aria-expanded": open,
              "aria-controls": listId,
              "aria-activedescendant":
                open && highlighted >= 0 && highlighted < flat.length ? optionId(highlighted) : undefined,
            }
          : {})}
      />
      {interactive && (
        <button
          type="button"
          tabIndex={-1}
          aria-label={t("modelCombobox.toggle", "Show model suggestions")}
          // Keeps focus in the input; the click alone toggles the list.
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => {
            if (open) {
              close();
            } else {
              openList(false);
              inputRef.current?.focus();
            }
          }}
          className="absolute inset-y-0 inset-e-0 flex w-8 items-center justify-center text-muted-foreground transition-colors hover:text-foreground"
          data-testid={testId ? `${testId}-toggle` : undefined}
        >
          <ChevronDown className={cn("h-4 w-4 transition-transform", open && "rotate-180")} />
        </button>
      )}

      {interactive && open && (
        <div
          ref={listRef}
          id={listId}
          role="listbox"
          aria-label={t("modelCombobox.listLabel", "Model suggestions")}
          // Clicks inside must not blur the input first and close the list.
          onMouseDown={(e) => e.preventDefault()}
          className={cn(
            "absolute inset-x-0 top-full z-50 mt-1 max-h-72 overflow-y-auto rounded-lg border border-border bg-popover py-1 shadow-xl",
            textSize,
          )}
          data-testid={testId ? `${testId}-listbox` : undefined}
        >
          {filtering && value.trim() !== "" && sections.every((s) => s.kind !== "matching") && (
            <div
              role="presentation"
              className="px-3 pb-1 pt-1.5 text-[11px] text-muted-foreground"
              data-testid={testId ? `${testId}-no-match` : undefined}
            >
              {t("modelCombobox.noMatch", "No suggestion matches “{{query}}” — it will be used as typed.", {
                query: value.trim(),
              })}
            </div>
          )}
          {sections.map((section, sectionIndex) => {
            const headingId = `${baseId}-group-${section.kind}`;
            const heading =
              section.kind === "matching"
                ? t("modelCombobox.matching", "Matching “{{query}}”", { query: value.trim() })
                : section.kind === "others"
                  ? t("modelCombobox.otherModels", "Other models")
                  : null;
            return (
              <div
                key={section.kind}
                role="group"
                aria-labelledby={heading ? headingId : undefined}
                className={cn(sectionIndex > 0 && "mt-1 border-t border-border pt-1")}
                data-testid={testId ? `${testId}-group-${section.kind}` : undefined}
              >
                {heading && (
                  <div
                    id={headingId}
                    role="presentation"
                    className="px-3 pb-1 pt-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground"
                  >
                    {heading}
                  </div>
                )}
                {section.models.map((model) => {
                  const index = runningIndex++;
                  const selected = model === value;
                  const active = index === highlighted;
                  return (
                    <div
                      key={model}
                      id={optionId(index)}
                      role="option"
                      aria-selected={selected}
                      data-index={index}
                      data-testid={testId ? `${testId}-option` : undefined}
                      onMouseEnter={() => setHighlighted(index)}
                      onClick={() => select(model)}
                      className={cn(
                        "flex cursor-pointer items-center gap-2 px-3 py-1.5 font-mono",
                        active ? "bg-accent text-accent-foreground" : "text-foreground",
                      )}
                    >
                      <Check className={cn("h-3.5 w-3.5 shrink-0", selected ? "opacity-100" : "opacity-0")} />
                      <span className="min-w-0 flex-1 truncate">{model}</span>
                      {model === defaultModel && (
                        <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 font-sans text-[10px] font-medium text-muted-foreground">
                          {t("modelCombobox.default", "default")}
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            );
          })}
          <div
            role="presentation"
            className="mt-1 border-t border-border px-3 pb-1 pt-1.5 font-sans text-[11px] text-muted-foreground"
          >
            {t("modelCombobox.customHint", "Not listed? Type any model id your provider supports.")}
          </div>
        </div>
      )}
    </div>
  );
}
