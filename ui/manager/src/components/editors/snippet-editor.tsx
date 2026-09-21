import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Plus,
  X,
  Puzzle,
  Tag,
  FileText,
  Code2,
} from "lucide-react";
import { ContentEditor } from "./content-editor";
import { EditorSection } from "./editor-section";

// ─── Types ──────────────────────────────────────────────────────────────────

export interface PromptSnippetConfig {
  name?: string;
  category?: string;
  description?: string;
  content?: string;
  tags?: string[];
  templateEnabled?: boolean;
}

const CATEGORIES = ["governance", "persona", "compliance", "custom"] as const;

// ─── Sub-components ─────────────────────────────────────────────────────────


function TagsInput({
  tags,
  onChange,
  readOnly,
}: {
  tags: string[];
  onChange: (t: string[]) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");

  const addTag = () => {
    const trimmed = input.trim().toLowerCase();
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed]);
      setInput("");
    }
  };

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap gap-1.5">
        {tags.map((tag) => (
          <span
            key={tag}
            className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
          >
            {tag}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(tags.filter((x) => x !== tag))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors"
                aria-label={`Remove ${tag}`}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
        {tags.length === 0 && (
          <span className="text-xs text-muted-foreground italic">
            {t("snippetEditor.noTags", "No tags")}
          </span>
        )}
      </div>
      {!readOnly && (
        <div className="flex gap-1.5">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addTag();
              }
            }}
            placeholder={t(
              "snippetEditor.tagPlaceholder",
              "e.g. safety, production"
            )}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={addTag}
            className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary"
          >
            <Plus className="h-3 w-3" />
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Main Component ─────────────────────────────────────────────────────────

export interface SnippetEditorProps {
  data: PromptSnippetConfig;
  onChange: (data: PromptSnippetConfig) => void;
  readOnly?: boolean;
}

export function SnippetEditor({
  data,
  onChange,
  readOnly,
}: SnippetEditorProps) {
  const { t } = useTranslation();

  return (
    <div className="space-y-6" data-testid="snippet-editor">
      {/* Identity */}
      <EditorSection
        label={t("snippetEditor.identity", "Identity")}
        icon={Puzzle}
        accent="text-violet-500"
      >
        <div className="space-y-3">
          {/* Name */}
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("snippetEditor.name", "Snippet Name")}
            </label>
            <input
              type="text"
              value={data.name ?? ""}
              onChange={(e) =>
                onChange({
                  ...data,
                  name: e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, "_"),
                })
              }
              readOnly={readOnly}
              placeholder={t(
                "snippetEditor.namePlaceholder",
                "e.g. cautious_mode"
              )}
              pattern="[a-z0-9_]+"
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="snippet-name"
            />
            {data.name && (
              <p className="mt-1 text-[10px] text-muted-foreground font-mono">
                {t("snippetEditor.usageHint", "Usage:")}{" "}
                <code className="rounded bg-primary/10 px-1 py-0.5 text-primary">
                  {"{{"}snippets.{data.name}{"}}"}
                </code>
              </p>
            )}
          </div>

          {/* Category */}
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("snippetEditor.category", "Category")}
            </label>
            <select
              value={data.category ?? "custom"}
              onChange={(e) =>
                onChange({ ...data, category: e.target.value })
              }
              disabled={readOnly}
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
              data-testid="snippet-category"
            >
              {CATEGORIES.map((cat) => (
                <option key={cat} value={cat}>
                  {t(`snippetEditor.category_${cat}`, cat.charAt(0).toUpperCase() + cat.slice(1))}
                </option>
              ))}
            </select>
          </div>

          {/* Description */}
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("snippetEditor.description", "Description")}
            </label>
            <input
              type="text"
              value={data.description ?? ""}
              onChange={(e) =>
                onChange({ ...data, description: e.target.value })
              }
              readOnly={readOnly}
              placeholder={t(
                "snippetEditor.descriptionPlaceholder",
                "What this snippet does"
              )}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="snippet-description"
            />
          </div>
        </div>
      </EditorSection>

      {/* Content */}
      <EditorSection
        label={t("snippetEditor.content", "Prompt Content")}
        icon={FileText}
        accent="text-amber-500"
      >
        <div className="space-y-3">
          <ContentEditor
            value={data.content ?? ""}
            onChange={(v) => onChange({ ...data, content: v })}
            readOnly={readOnly}
            language="prompt"
            label={t("snippetEditor.contentLabel", "Snippet Content")}
            placeholder={t(
              "snippetEditor.contentPlaceholder",
              "You should be cautious and careful in your responses..."
            )}
            testId="snippet-content"
          />

          {/* Template enabled */}
          <label className="inline-flex items-center gap-2 text-xs text-foreground">
            <input
              type="checkbox"
              checked={data.templateEnabled ?? true}
              onChange={(e) =>
                onChange({ ...data, templateEnabled: e.target.checked })
              }
              disabled={readOnly}
              className="h-3.5 w-3.5 rounded border-input accent-primary"
              data-testid="snippet-template-enabled"
            />
            <Code2 className="h-3.5 w-3.5 text-muted-foreground" />
            {t("snippetEditor.templateEnabled", "Enable template resolution")}
          </label>
          <p className="text-[10px] text-muted-foreground ps-5 -mt-1">
            {t(
              "snippetEditor.templateEnabledHint",
              "When disabled, {{}} markers in the content are treated as literal text (useful for code examples)."
            )}
          </p>
        </div>
      </EditorSection>

      {/* Tags */}
      <EditorSection
        label={t("snippetEditor.tags", "Tags")}
        icon={Tag}
        accent="text-sky-500"
        defaultOpen={!!(data.tags && data.tags.length > 0)}
      >
        <TagsInput
          tags={data.tags ?? []}
          onChange={(tags) => onChange({ ...data, tags })}
          readOnly={readOnly}
        />
      </EditorSection>
    </div>
  );
}
