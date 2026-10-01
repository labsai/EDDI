import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { User, Users } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { searchDirectory, type DirectoryMatch } from "@/lib/api/workspaces";

interface ShareSubjectInputProps {
  value: string;
  onChange: (value: string) => void;
  /** Called with a suggestion's subject when one is chosen. */
  onPick: (match: DirectoryMatch) => void;
  /** Enter with no suggestion highlighted. */
  onSubmit: () => void;
  disabled?: boolean;
}

/** How long to wait after the last keystroke before asking the directory. */
const DEBOUNCE_MS = 200;

/**
 * The "who" box of the share dialog, with suggestions from the user directory.
 *
 * <h3>Why suggestions and not just a text box</h3> A share is stored against a
 * principal, which is rarely what people type. Suggestions resolve a name or an
 * email to the right account before anything is sent, and make it obvious when
 * a colleague has never signed in — they simply do not appear. The server still
 * resolves whatever is typed and refuses what matches nobody, so a person who
 * ignores the list gets the same answer, only later.
 *
 * Built on the WAI-ARIA combobox pattern: the input owns focus, arrow keys move
 * through the list, Enter picks, Escape closes.
 */
export function ShareSubjectInput({ value, onChange, onPick, onSubmit, disabled }: ShareSubjectInputProps) {
  const { t } = useTranslation();
  const listId = useId();
  const [debounced, setDebounced] = useState(value);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(-1);
  const blurTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value.trim()), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [value]);

  useEffect(() => () => {
    if (blurTimer.current) clearTimeout(blurTimer.current);
  }, []);

  const { data } = useQuery({
    queryKey: ["workspaces", "directory", debounced],
    queryFn: () => searchDirectory(debounced),
    enabled: open && debounced.length > 0,
    staleTime: 30_000,
  });
  const matches = useMemo(() => (open && debounced.length > 0 ? (data ?? []) : []), [data, open, debounced]);

  const pick = (match: DirectoryMatch) => {
    onPick(match);
    setOpen(false);
    setHighlight(-1);
  };

  return (
    <div className="relative flex-1">
      <Input
        value={value}
        disabled={disabled}
        onChange={(e) => {
          onChange(e.target.value);
          setOpen(true);
          setHighlight(-1);
        }}
        onFocus={() => setOpen(true)}
        // Delayed so a click on a suggestion lands before the list disappears.
        onBlur={() => {
          blurTimer.current = setTimeout(() => setOpen(false), 150);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" && matches.length > 0) {
            e.preventDefault();
            setOpen(true);
            setHighlight((h) => (h + 1) % matches.length);
          } else if (e.key === "ArrowUp" && matches.length > 0) {
            e.preventDefault();
            setHighlight((h) => (h <= 0 ? matches.length - 1 : h - 1));
          } else if (e.key === "Escape") {
            setOpen(false);
          } else if (e.key === "Enter") {
            e.preventDefault();
            const chosen = highlight >= 0 ? matches[highlight] : undefined;
            if (chosen) pick(chosen);
            else onSubmit();
          }
        }}
        placeholder={t("workspaces.share.subjectPlaceholder", "Name, email or team")}
        aria-label={t("workspaces.share.subjectLabel", "Person or team")}
        role="combobox"
        aria-expanded={matches.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={highlight >= 0 ? `${listId}-${highlight}` : undefined}
        autoComplete="off"
        data-testid="share-subject-input"
      />
      {matches.length > 0 && (
        <ul
          id={listId}
          role="listbox"
          className="absolute inset-x-0 top-full z-50 mt-1 max-h-64 overflow-auto rounded-lg border border-border bg-card py-1 shadow-lg"
          data-testid="share-suggestions"
        >
          {matches.map((match, index) => {
            const Icon = match.kind === "team" ? Users : User;
            return (
              <li
                key={match.subject}
                id={`${listId}-${index}`}
                role="option"
                aria-selected={index === highlight}
                // mousedown, not click: it fires before the input's blur.
                onMouseDown={(e) => {
                  e.preventDefault();
                  pick(match);
                }}
                onMouseEnter={() => setHighlight(index)}
                className={cn(
                  "flex cursor-pointer items-center gap-2 px-3 py-2 text-sm",
                  index === highlight ? "bg-accent/40" : "hover:bg-accent/20"
                )}
                data-testid={`share-suggestion-${match.subject}`}
              >
                <Icon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="flex min-w-0 flex-col">
                  <span className="truncate">{match.label}</span>
                  {match.detail && <span className="truncate text-xs text-muted-foreground">{match.detail}</span>}
                </span>
                <span className="ms-auto text-xs text-muted-foreground">
                  {match.kind === "team"
                    ? t("workspaces.share.subjectIsTeam", "Team")
                    : t("workspaces.share.subjectIsPerson", "Person")}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
