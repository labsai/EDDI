import type React from "react";
import { useTranslation } from "react-i18next";
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  GripVertical,
  Trash2,
  ExternalLink,
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
  Puzzle,
} from "lucide-react";
import { Link } from "react-router-dom";
import type { WorkflowExtension } from "@/lib/api/packages";
import { EXTENSION_TYPE_INFO } from "@/lib/api/extensions";

/* ─── Icon map ─── */
const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
};

function getExtensionIcon(type: string): React.ComponentType<{ className?: string }> {
  const info = EXTENSION_TYPE_INFO[type];
  if (info && iconMap[info.icon]) return iconMap[info.icon]!;
  return Puzzle;
}

function getExtensionLabel(type: string) {
  return EXTENSION_TYPE_INFO[type]?.label ?? type;
}

/** Parse an eddi:// URI to extract the resource type slug and ID */
function parseExtensionUri(uri: string): { slug: string; id: string } | null {
  try {
    const url = new URL(uri.replace("eddi://", "http://"));
    const segments = url.pathname.split("/").filter(Boolean);
    // e.g. /rulestore/rulesets/abc123 → slug=rules, id=abc123
    if (segments.length >= 3) {
      // Map store name to resource slug
      const storeMap: Record<string, string> = {
        rulestore: "rules",
        apicallstore: "apicalls",
        llmstore: "llm",
        outputstore: "output",
        dictionarystore: "dictionary",
        propertysetterstore: "propertysetter",
        parserstore: "parser",
      };
      const storeName = segments[0];
      const resourceId = segments[2];
      if (storeName && resourceId) {
        const slug = storeMap[storeName] ?? storeName;
        return { slug, id: resourceId };
      }
    }
  } catch {
    // ignore
  }
  return null;
}

/* ─── Types ─── */
export interface PipelineItem {
  /** Stable ID for dnd-kit (index-based) */
  id: string;
  /** Original index in workflowSteps */
  index: number;
  extension: WorkflowExtension;
}

export interface PipelineBuilderProps {
  items: PipelineItem[];
  onChange: (items: PipelineItem[]) => void;
  onRemove: (index: number) => void;
  disabled?: boolean;
}

/* ─── Main component ─── */
export function PipelineBuilder({
  items,
  onChange,
  onRemove,
  disabled = false,
}: PipelineBuilderProps) {
  const { t } = useTranslation();
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      const oldIndex = items.findIndex((i) => i.id === active.id);
      const newIndex = items.findIndex((i) => i.id === over.id);
      if (oldIndex !== -1 && newIndex !== -1) {
        onChange(arrayMove(items, oldIndex, newIndex));
      }
    }
  }

  if (items.length === 0) {
    return (
      <div
        className="flex flex-col items-center justify-center py-12 text-muted-foreground"
        data-testid="pipeline-empty"
      >
        <Puzzle className="h-10 w-10 opacity-50" />
        <p className="mt-3 text-sm">
          {t("packageEditor.noExtensions", "No extensions in this package")}
        </p>
        <p className="mt-1 text-xs text-muted-foreground/70">
          {t(
            "packageEditor.addHint",
            "Add extensions to build your processing pipeline"
          )}
        </p>
      </div>
    );
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={handleDragEnd}
    >
      <SortableContext
        items={items.map((i) => i.id)}
        strategy={verticalListSortingStrategy}
      >
        <div
          className="divide-y divide-border"
          data-testid="pipeline-list"
          role="list"
          aria-label={t("packageEditor.pipeline", "Extension pipeline")}
        >
          {items.map((item, idx) => (
            <SortableExtensionItem
              key={item.id}
              item={item}
              position={idx + 1}
              total={items.length}
              onRemove={() => onRemove(item.index)}
              disabled={disabled}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}

/* ─── Sortable item ─── */
function SortableExtensionItem({
  item,
  position,
  total,
  onRemove,
  disabled,
}: {
  item: PipelineItem;
  position: number;
  total: number;
  onRemove: () => void;
  disabled: boolean;
}) {
  const { t } = useTranslation();
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: item.id, disabled });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    zIndex: isDragging ? 50 : undefined,
  };

  const ext = item.extension;
  const Icon = getExtensionIcon(ext.type);
  const label = getExtensionLabel(ext.type);
  const configUri = ext.config?.["uri"] as string | undefined;
  const parsed = configUri ? parseExtensionUri(configUri) : null;

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`flex items-center gap-3 px-5 py-3 transition-colors ${
        isDragging
          ? "bg-primary/5 shadow-lg rounded-lg"
          : "hover:bg-secondary/50"
      }`}
      role="listitem"
      data-testid={`pipeline-item-${item.index}`}
    >
      {/* Drag handle */}
      <button
        {...attributes}
        {...listeners}
        className="cursor-grab rounded-md p-1 text-muted-foreground hover:bg-secondary active:cursor-grabbing disabled:cursor-not-allowed disabled:opacity-50"
        tabIndex={0}
        aria-label={t("packageEditor.reorder", "Reorder extension")}
        disabled={disabled}
      >
        <GripVertical className="h-4 w-4" />
      </button>

      {/* Step number */}
      <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
        {position}
      </span>

      {/* Type icon + label */}
      <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-foreground">{label}</p>
        {parsed && (
          <Link
            to={`/manage/resources/${parsed.slug}/${parsed.id}`}
            className="text-xs text-muted-foreground hover:text-primary truncate block transition-colors"
          >
            {parsed.id}
            <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
          </Link>
        )}
      </div>

      {/* Pipeline connector arrow (except last) */}
      {position < total && (
        <span className="text-xs text-muted-foreground/40 hidden sm:block">
          →
        </span>
      )}

      {/* Remove */}
      <button
        onClick={onRemove}
        disabled={disabled}
        className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors disabled:opacity-50"
        title={t("common.delete")}
        data-testid={`remove-ext-${item.index}`}
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </div>
  );
}
