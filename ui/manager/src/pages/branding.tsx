import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AlertTriangle, Lock, Palette, RefreshCw, RotateCcw, Save } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ErrorState } from "@/components/shared/error-state";
import { BrandPreview } from "@/components/branding/brand-preview";
import { BrandAssetSlotCard } from "@/components/branding/brand-asset-slot";
import {
  useBranding,
  useDeleteBrandAsset,
  useResetBranding,
  useUpdateBranding,
  useUploadBrandAsset,
} from "@/hooks/use-branding";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import {
  brandingErrorInfo,
  type BrandDocument,
  type BrandingErrorInfo,
  type BrandingView,
  type BrandUpdate,
} from "@/lib/api/branding";
import { DEFAULT_BRAND, BRAND_ASSET_SLOTS, type BrandAssetFlags, type BrandAssetSlot } from "@/lib/brand";
import { resolveBrandColors, type BrandColorValues } from "@/lib/brand-css";
import {
  checkBrandText,
  contrastRatio,
  isSafeBrandColor,
  MIN_CONTRAST,
  PRODUCT_NAME_MAX,
  SHORT_NAME_MAX,
  TAGLINE_MAX,
  toColorInputValue,
  type BrandTextProblem,
} from "@/lib/brand-validation";
import { isForbidden } from "@/lib/access";
import { cn } from "@/lib/utils";

type ColorKey = keyof BrandColorValues;
const COLOR_KEYS: readonly ColorKey[] = ["primary", "primaryForeground", "darkPrimary", "darkPrimaryForeground", "chatAccent"];

/** The editable fields as the form holds them: `""` is unset. */
interface BrandForm {
  productName: string;
  shortName: string;
  tagline: string;
  colors: Record<ColorKey, string>;
  /** `null` leaves the toggle unset, so the seed or the default applies. */
  poweredBy: boolean | null;
  showUpdateCheck: boolean | null;
}

function formFromDocument(doc: BrandDocument | null | undefined): BrandForm {
  const colors = doc?.colors ?? {};
  return {
    productName: doc?.productName ?? "",
    shortName: doc?.shortName ?? "",
    tagline: doc?.tagline ?? "",
    colors: Object.fromEntries(COLOR_KEYS.map((k) => [k, colors[k] ?? ""])) as Record<ColorKey, string>,
    poweredBy: doc?.poweredBy ?? null,
    showUpdateCheck: doc?.showUpdateCheck ?? null,
  };
}

function orNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

/** The full-replacement `PUT` body. */
function toUpdate(form: BrandForm, revision: number): BrandUpdate {
  return {
    revision,
    productName: orNull(form.productName),
    shortName: orNull(form.shortName),
    tagline: orNull(form.tagline),
    colors: Object.fromEntries(COLOR_KEYS.map((k) => [k, orNull(form.colors[k])])) as unknown as BrandColorValues,
    poweredBy: form.poweredBy,
    showUpdateCheck: form.showUpdateCheck,
  };
}

function sameForm(a: BrandForm, b: BrandForm): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** Per slot: the URL under /branding/ and the types the picker offers. */
const SLOT_META: Record<BrandAssetSlot, { publicPath: string; accept: string }> = {
  logo: { publicPath: "logo", accept: "image/png,image/webp,image/svg+xml" },
  logoDark: { publicPath: "logo-dark", accept: "image/png,image/webp,image/svg+xml" },
  icon: { publicPath: "icon", accept: "image/png,image/webp,image/svg+xml" },
  favicon: { publicPath: "favicon.ico", accept: "image/png,image/webp,image/svg+xml,image/x-icon,.ico" },
  ogImage: { publicPath: "og-image", accept: "image/png,image/webp,image/svg+xml" },
};

function assetFlags(hashes: Partial<Record<BrandAssetSlot, string | null>> | null | undefined): BrandAssetFlags {
  return Object.fromEntries(BRAND_ASSET_SLOTS.map((s) => [s, !!hashes?.[s]])) as BrandAssetFlags;
}

/** A switch in the house style (`role="switch"`, as in the Workforce settings). */
function Toggle({
  checked,
  onChange,
  disabled,
  label,
  testId,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled: boolean;
  label: string;
  testId: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      data-testid={testId}
      className={cn(
        "relative mt-0.5 inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "bg-primary" : "bg-muted",
      )}
    >
      <span
        className={cn(
          "pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform",
          checked ? "translate-x-5 rtl:-translate-x-5" : "translate-x-0",
        )}
      />
    </button>
  );
}

function FieldError({ id, children }: { id: string; children: ReactNode }) {
  return (
    <p id={id} className="text-xs text-destructive" role="alert" data-testid={id}>
      {children}
    </p>
  );
}

export function BrandingPage() {
  const { t } = useTranslation();
  const { data: view, isLoading, isError, error, refetch, isFetching } = useBranding();

  const onApplyError = useCallback(
    () =>
      toast.warning(
        t("branding.toast.applyFailed", "Saved, but this tab could not load the new brand. Reload the page to see it."),
      ),
    [t],
  );
  const updateMutation = useUpdateBranding(onApplyError);
  const resetMutation = useResetBranding(onApplyError);
  const uploadMutation = useUploadBrandAsset(onApplyError);
  const deleteAssetMutation = useDeleteBrandAsset(onApplyError);

  // The document the form is based on: its revision goes into the next PUT.
  const [baseRevision, setBaseRevision] = useState<number | null>(null);
  const [baseForm, setBaseForm] = useState<BrandForm | null>(null);
  const [form, setForm] = useState<BrandForm | null>(null);
  const [serverViolations, setServerViolations] = useState<Record<string, string>>({});
  const [saveError, setSaveError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<number | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [assetErrors, setAssetErrors] = useState<Partial<Record<BrandAssetSlot, string>>>({});
  const [busySlot, setBusySlot] = useState<BrandAssetSlot | null>(null);
  const [resetOpen, setResetOpen] = useState(false);

  /** Rebase the whole form on a document (load, save, reset, reload latest). */
  const adopt = useCallback((next: BrandingView) => {
    const f = formFromDocument(next.document);
    setBaseForm(f);
    setForm(f);
    setBaseRevision(next.document?.revision ?? next.effective.revision);
    setServerViolations({});
    setSaveError(null);
    setConflict(null);
  }, []);

  useEffect(() => {
    if (view && baseRevision === null) adopt(view);
  }, [view, baseRevision, adopt]);

  const locked = view?.locked ?? false;
  const readOnly = locked;
  const dirty = !!form && !!baseForm && !sameForm(form, baseForm);
  useUnsavedChangesGuard(dirty && !readOnly);

  // ── Client-side validation, mirroring BrandValidator ──
  const textProblem = (problem: BrandTextProblem | null, max: number): string | null => {
    switch (problem) {
      case "blank":
        return t("branding.validation.blank", "Must not be only spaces. Leave it empty for the default.");
      case "tooLong":
        return t("branding.validation.tooLong", "At most {{max}} characters.", { max });
      case "angleBrackets":
        return t("branding.validation.angleBrackets", "Must not contain < or >.");
      case "control":
        return t("branding.validation.control", "Must not contain control characters.");
      default:
        return null;
    }
  };

  const clientErrors = useMemo(() => {
    const out: Record<string, string> = {};
    if (!form) return out;
    const text = (field: "productName" | "shortName" | "tagline", max: number) => {
      const value = form[field];
      const msg = textProblem(checkBrandText(value === "" ? null : value, max), max);
      if (msg) out[field] = msg;
    };
    text("productName", PRODUCT_NAME_MAX);
    text("shortName", SHORT_NAME_MAX);
    text("tagline", TAGLINE_MAX);
    for (const key of COLOR_KEYS) {
      const value = form.colors[key].trim();
      if (value && !isSafeBrandColor(value)) {
        out[`colors.${key}`] = t(
          "branding.validation.color",
          "Use a colour like #0f766e, #fff, rgb(15 118 110), hsl(…) or oklch(…), with plain numbers only.",
        );
      }
    }
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- textProblem only reads t
  }, [form, t]);

  const fieldError = (field: string): string | null => clientErrors[field] ?? serverViolations[field] ?? null;

  // ── Derived preview values ──
  const formColors = useMemo<BrandColorValues>(() => {
    const raw = Object.fromEntries(
      COLOR_KEYS.map((k) => {
        const v = form?.colors[k].trim() ?? "";
        return [k, v && isSafeBrandColor(v) ? v : null];
      }),
    ) as unknown as BrandColorValues;
    return resolveBrandColors(raw);
  }, [form]);

  const effective = view?.effective;
  const assets = assetFlags(locked ? effective?.assets : (view?.document?.assets ?? effective?.assets));
  const revision = effective?.revision ?? 0;
  const previewName =
    orNull(form?.productName ?? "") ?? (view?.document?.productName ? DEFAULT_BRAND.productName : effective?.productName) ?? DEFAULT_BRAND.productName;
  const previewBranded =
    previewName !== DEFAULT_BRAND.productName || Object.values(assets).some(Boolean) || !!formColors.primary;

  const lightRatio = contrastRatio(formColors.primaryForeground, formColors.primary);
  const darkRatio = contrastRatio(formColors.darkPrimaryForeground, formColors.darkPrimary);

  // ── Writes ──
  const hasClientErrors = Object.keys(clientErrors).length > 0;

  const handleSave = async () => {
    if (!form || baseRevision === null || hasClientErrors || readOnly) return;
    setSaveError(null);
    setServerViolations({});
    setConflict(null);
    try {
      const next = await updateMutation.mutateAsync(toUpdate(form, baseRevision));
      adopt(next);
      setWarnings(next.warnings ?? []);
      toast.success(t("branding.toast.saved", "Brand saved"));
    } catch (e) {
      handleWriteError(e);
    }
  };

  const handleWriteError = (e: unknown) => {
    const info: BrandingErrorInfo | null = brandingErrorInfo(e);
    if (info?.status === 409 && info.currentRevision !== null) {
      setConflict(info.currentRevision);
      return;
    }
    if (info && info.violations.length > 0) {
      setServerViolations(Object.fromEntries(info.violations.map((v) => [v.field, v.message])));
    }
    setSaveError(info?.message ?? (e instanceof Error ? e.message : String(e)));
  };

  const reloadLatest = async () => {
    const result = await refetch();
    if (result.data) adopt(result.data);
  };

  const handleReset = async () => {
    try {
      const next = await resetMutation.mutateAsync();
      adopt(next);
      setWarnings([]);
      setResetOpen(false);
      toast.success(t("branding.toast.reset", "Brand reset to the defaults"));
    } catch (e) {
      setResetOpen(false);
      handleWriteError(e);
    }
  };

  const assetErrorMessage = (e: unknown): string => {
    const info = brandingErrorInfo(e);
    switch (info?.status) {
      case 400:
        return t("branding.assets.errorInvalid", "The image was refused: {{reason}}", { reason: info.message });
      case 413:
        return t("branding.assets.errorTooLarge", "The image is larger than 512 KB.");
      case 415:
        return t("branding.assets.errorType", "This file type is not accepted here. Use PNG, WebP or SVG (ICO for the favicon only).");
      case 409:
        return t("branding.assets.errorLocked", "The brand is locked, so images cannot be changed here.");
      default:
        return info?.message ?? (e instanceof Error ? e.message : String(e));
    }
  };

  /**
   * An image write bumps the revision without touching the text fields, so the
   * form keeps its unsaved edits and only takes the new revision — otherwise the
   * next save would conflict with the upload it just made.
   */
  const afterAssetWrite = (next: BrandingView) => {
    setBaseRevision(next.document?.revision ?? next.effective.revision);
  };

  const handleUpload = async (slot: BrandAssetSlot, file: File) => {
    setBusySlot(slot);
    setAssetErrors((prev) => ({ ...prev, [slot]: undefined }));
    try {
      afterAssetWrite(await uploadMutation.mutateAsync({ slot, file }));
    } catch (e) {
      setAssetErrors((prev) => ({ ...prev, [slot]: assetErrorMessage(e) }));
    } finally {
      setBusySlot(null);
    }
  };

  const handleRemove = async (slot: BrandAssetSlot) => {
    setBusySlot(slot);
    setAssetErrors((prev) => ({ ...prev, [slot]: undefined }));
    try {
      afterAssetWrite(await deleteAssetMutation.mutateAsync(slot));
    } catch (e) {
      setAssetErrors((prev) => ({ ...prev, [slot]: assetErrorMessage(e) }));
    } finally {
      setBusySlot(null);
    }
  };

  const setField = <K extends keyof BrandForm>(key: K, value: BrandForm[K]) => {
    setForm((prev) => (prev ? { ...prev, [key]: value } : prev));
    setServerViolations((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key as string];
      return next;
    });
  };

  const setColor = (key: ColorKey, value: string) => {
    setForm((prev) => (prev ? { ...prev, colors: { ...prev.colors, [key]: value } } : prev));
    setServerViolations((prev) => {
      const field = `colors.${key}`;
      if (!(field in prev)) return prev;
      const next = { ...prev };
      delete next[field];
      return next;
    });
  };

  // ── Render ──
  const loadFailed = isError && !view;

  const sourceLabel = (source: string | undefined) => {
    switch (source?.toLowerCase()) {
      case "database":
        return t("branding.source.database", "Saved on this page");
      case "properties":
        return t("branding.source.properties", "Configuration properties");
      default:
        return t("branding.source.default", "Built-in defaults");
    }
  };

  const textField = (
    field: "productName" | "shortName" | "tagline",
    label: string,
    hint: string,
    max: number,
    placeholder: string,
  ) => {
    const err = fieldError(field);
    const id = `branding-${field}`;
    return (
      <div className="space-y-1.5">
        <label htmlFor={id} className="text-sm font-medium text-foreground">
          {label}
        </label>
        <Input
          id={id}
          value={form?.[field] ?? ""}
          onChange={(e) => setField(field, e.target.value)}
          placeholder={placeholder}
          disabled={readOnly}
          aria-invalid={!!err}
          aria-describedby={err ? `${id}-error` : `${id}-hint`}
          data-testid={id}
        />
        <div className="flex justify-between gap-2 text-xs text-muted-foreground">
          <span id={`${id}-hint`}>{hint}</span>
          <span className="tabular-nums" data-testid={`${id}-count`}>
            {Array.from(form?.[field] ?? "").length}/{max}
          </span>
        </div>
        {err && <FieldError id={`${id}-error`}>{err}</FieldError>}
      </div>
    );
  };

  const colorField = (key: ColorKey, label: string, placeholder: string) => {
    const field = `colors.${key}`;
    const err = fieldError(field);
    const id = `branding-color-${key}`;
    const value = form?.colors[key] ?? "";
    // An empty field shows what it falls back to (dark follows light, the
    // accent follows the primary), dimmed; the native picker needs some hex.
    const own = toColorInputValue(value.trim());
    const swatch = own ?? toColorInputValue(formColors[key]) ?? "#000000";
    return (
      <div className="space-y-1.5">
        <label htmlFor={id} className="text-sm font-medium text-foreground">
          {label}
        </label>
        <div className="flex items-center gap-2">
          <input
            type="color"
            value={swatch}
            onChange={(e) => setColor(key, e.target.value)}
            disabled={readOnly}
            aria-label={t("branding.colors.picker", "Pick {{name}}", { name: label })}
            className={cn(
              "h-10 w-12 shrink-0 cursor-pointer rounded-lg border border-input bg-background p-1 disabled:cursor-not-allowed disabled:opacity-50",
              !own && "opacity-40",
            )}
            data-testid={`${id}-picker`}
          />
          <Input
            id={id}
            value={value}
            onChange={(e) => setColor(key, e.target.value)}
            placeholder={placeholder}
            disabled={readOnly}
            spellCheck={false}
            className="font-mono"
            aria-invalid={!!err}
            aria-describedby={err ? `${id}-error` : undefined}
            data-testid={id}
          />
        </div>
        {err && <FieldError id={`${id}-error`}>{err}</FieldError>}
      </div>
    );
  };

  const contrastBadge = (ratio: number | null, testId: string) => {
    if (ratio === null) {
      return (
        <Badge variant="secondary" data-testid={testId}>
          {t("branding.contrast.unknown", "Contrast not checked")}
        </Badge>
      );
    }
    const ok = ratio >= MIN_CONTRAST;
    return (
      <Badge variant={ok ? "success" : "warning"} data-testid={testId} data-ok={ok}>
        {ok
          ? t("branding.contrast.pass", "{{ratio}}:1 — passes WCAG AA", { ratio: ratio.toFixed(2) })
          : t("branding.contrast.fail", "{{ratio}}:1 — below WCAG AA (4.5:1)", { ratio: ratio.toFixed(2) })}
      </Badge>
    );
  };

  const slotText: Record<BrandAssetSlot, { label: string; hint: string }> = {
    logo: {
      label: t("branding.assets.logo", "Logo"),
      hint: t("branding.assets.logoHint", "The sidebar wordmark in light mode. Should read on a light background."),
    },
    logoDark: {
      label: t("branding.assets.logoDark", "Dark-mode logo"),
      hint: t("branding.assets.logoDarkHint", "Optional. Used in dark mode; the logo is used when empty."),
    },
    icon: {
      label: t("branding.assets.icon", "Icon"),
      hint: t("branding.assets.iconHint", "Square. The collapsed sidebar, the landing page and About."),
    },
    favicon: {
      label: t("branding.assets.favicon", "Favicon"),
      hint: t("branding.assets.faviconHint", "The browser tab icon. The icon is used when empty."),
    },
    ogImage: {
      label: t("branding.assets.ogImage", "Link preview image"),
      hint: t("branding.assets.ogImageHint", "Shown when a chat link is shared. The logo is used when empty."),
    },
  };

  const slotNote = (slot: BrandAssetSlot): string | null => {
    if (!previewBranded) return null;
    if (slot === "logo" && !assets.logo) {
      return t(
        "branding.assets.noLogoNote",
        "No logo uploaded: the sidebar shows “{{name}}” as text. The pages shown before sign-in still show the default logo.",
        { name: previewName },
      );
    }
    if (slot === "icon" && !assets.icon) {
      return t(
        "branding.assets.noIconNote",
        "No icon uploaded: the app shows the first letter of the short name, and the browser tab keeps the default icon.",
      );
    }
    return null;
  };

  return (
    <div className="space-y-6" data-testid="branding-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Palette className="h-8 w-8 text-primary" aria-hidden="true" />
            {t("branding.title", "Branding")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("branding.subtitle", "Run {{productName}} under your own name, logo and colours.")}
          </p>
        </div>
        {view && !readOnly && (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              onClick={() => setResetOpen(true)}
              disabled={resetMutation.isPending}
              data-testid="branding-reset-btn"
            >
              <RotateCcw />
              {t("branding.reset", "Reset to defaults")}
            </Button>
            <Button
              onClick={handleSave}
              disabled={!dirty || hasClientErrors || updateMutation.isPending || conflict !== null}
              data-testid="branding-save-btn"
            >
              <Save />
              {updateMutation.isPending ? t("common.saving", "Saving...") : t("common.save", "Save")}
            </Button>
          </div>
        )}
      </div>

      {isLoading && (
        <div className="space-y-4" data-testid="branding-loading">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="space-y-3 rounded-xl border border-border bg-card p-5">
              <Skeleton className="h-5 w-1/3" />
              <Skeleton className="h-10 w-full" />
            </div>
          ))}
        </div>
      )}

      {loadFailed && (
        <ErrorState
          message={
            isForbidden(error)
              ? t("branding.forbidden", "Only an administrator (eddi-admin) can change the brand.")
              : t("common.error", "Something went wrong")
          }
          onRetry={() => refetch()}
          retryLabel={t("common.retry", "Retry")}
        />
      )}

      {view && form && (
        <>
          {/* Source, lock and conflict notices */}
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>{t("branding.sourceLabel", "Brand in effect comes from:")}</span>
            <Badge variant="outline" data-testid="branding-source">
              {sourceLabel(view.source)}
            </Badge>
            {view.document?.updatedAt && !locked && (
              <span className="text-xs" data-testid="branding-updated">
                {t("branding.updated", "Last saved {{when}} by {{who}}, revision {{revision}}", {
                  when: new Date(view.document.updatedAt).toLocaleString(),
                  who: view.document.updatedBy ?? "—",
                  revision: view.document.revision,
                })}
              </span>
            )}
          </div>

          {locked && (
            <div
              className="flex items-start gap-3 rounded-xl border border-warn-300 bg-warn-50 p-4 text-sm dark:border-warn-800 dark:bg-warn-950/30"
              data-testid="branding-locked"
            >
              <Lock className="mt-0.5 h-5 w-5 shrink-0 text-warn-700 dark:text-warn-400" aria-hidden="true" />
              <div className="space-y-1">
                <p className="font-medium text-foreground">{t("branding.locked.title", "The brand is locked")}</p>
                <p className="text-muted-foreground">
                  {t(
                    "branding.locked.body",
                    "This deployment sets eddi.branding.locked=true, so the brand comes from the eddi.branding.* properties ({{source}}) and cannot be changed here. Change those properties and restart to rebrand.",
                    { source: sourceLabel(view.source) },
                  )}
                </p>
              </div>
            </div>
          )}

          {conflict !== null && (
            <div
              className="flex flex-col gap-3 rounded-xl border border-warn-300 bg-warn-50 p-4 text-sm sm:flex-row sm:items-center sm:justify-between dark:border-warn-800 dark:bg-warn-950/30"
              role="alert"
              data-testid="branding-conflict"
            >
              <div className="flex items-start gap-3">
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warn-700 dark:text-warn-400" aria-hidden="true" />
                <p className="text-foreground">
                  {t(
                    "branding.conflict.body",
                    "Someone else saved the brand in the meantime (revision {{revision}}). Your changes were not saved. Reload the latest version and apply them again.",
                    { revision: conflict },
                  )}
                </p>
              </div>
              <Button variant="warning" size="sm" onClick={reloadLatest} disabled={isFetching} data-testid="branding-reload-latest">
                <RefreshCw />
                {t("branding.conflict.reload", "Reload latest")}
              </Button>
            </div>
          )}

          {saveError && (
            <div
              className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"
              role="alert"
              data-testid="branding-save-error"
            >
              {t("branding.saveFailed", "The brand was not saved: {{reason}}", { reason: saveError })}
            </div>
          )}

          {warnings.length > 0 && (
            <div
              className="space-y-1 rounded-xl border border-warn-300 bg-warn-50 p-4 text-sm dark:border-warn-800 dark:bg-warn-950/30"
              data-testid="branding-warnings"
            >
              <p className="font-medium text-foreground">
                {t("branding.warningsTitle", "Saved, with a warning from the server:")}
              </p>
              <ul className="list-disc ps-5 text-muted-foreground">
                {warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            </div>
          )}

          <div className="grid gap-6 @5xl/main:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <div className="space-y-6">
              {/* Names */}
              <Card>
                <CardHeader>
                  <CardTitle>{t("branding.names.title", "Name")}</CardTitle>
                  <CardDescription>
                    {t("branding.names.description", "Empty fields fall back to the configuration properties, then to the defaults.")}
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  {textField(
                    "productName",
                    t("branding.names.productName", "Product name"),
                    t("branding.names.productNameHint", "Replaces the product name in every screen and title."),
                    PRODUCT_NAME_MAX,
                    view.document?.productName ? DEFAULT_BRAND.productName : view.effective.productName,
                  )}
                  {textField(
                    "shortName",
                    t("branding.names.shortName", "Short name"),
                    t("branding.names.shortNameHint", "For tight spaces. The product name is used when empty."),
                    SHORT_NAME_MAX,
                    t("branding.names.shortNamePlaceholder", "Same as the product name"),
                  )}
                  {textField(
                    "tagline",
                    t("branding.names.tagline", "Tagline"),
                    t("branding.names.taglineHint", "Shown in About and in page descriptions."),
                    TAGLINE_MAX,
                    "",
                  )}
                </CardContent>
              </Card>

              {/* Colours */}
              <Card>
                <CardHeader>
                  <CardTitle>{t("branding.colors.title", "Colours")}</CardTitle>
                  <CardDescription>
                    {t(
                      "branding.colors.description",
                      "The primary colour drives buttons, accents and the whole brand scale. Warnings keep their own colour.",
                    )}
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-5">
                  <div className="grid gap-4 sm:grid-cols-2">
                    {colorField("primary", t("branding.colors.primary", "Primary"), t("branding.colors.defaultPlaceholder", "Default"))}
                    {colorField(
                      "primaryForeground",
                      t("branding.colors.primaryForeground", "Text on primary"),
                      t("branding.colors.defaultPlaceholder", "Default"),
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span>{t("branding.contrast.light", "Light mode contrast")}</span>
                    {contrastBadge(lightRatio, "branding-contrast-light")}
                  </div>
                  <div className="grid gap-4 sm:grid-cols-2">
                    {colorField(
                      "darkPrimary",
                      t("branding.colors.darkPrimary", "Primary in dark mode"),
                      t("branding.colors.followsLight", "Same as light"),
                    )}
                    {colorField(
                      "darkPrimaryForeground",
                      t("branding.colors.darkPrimaryForeground", "Text on primary in dark mode"),
                      t("branding.colors.followsLight", "Same as light"),
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span>{t("branding.contrast.dark", "Dark mode contrast")}</span>
                    {contrastBadge(darkRatio, "branding-contrast-dark")}
                  </div>
                  <div className="grid gap-4 sm:grid-cols-2">
                    {colorField(
                      "chatAccent",
                      t("branding.colors.chatAccent", "Chat widget accent"),
                      t("branding.colors.followsPrimary", "Same as primary"),
                    )}
                  </div>
                </CardContent>
              </Card>

              {/* Toggles */}
              <Card>
                <CardHeader>
                  <CardTitle>{t("branding.options.title", "Options")}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="flex items-start justify-between gap-4">
                    <div>
                      <p className="text-sm font-medium text-foreground">{t("branding.options.poweredBy", "Show the “Powered by” notice")}</p>
                      <p className="text-xs text-muted-foreground">
                        {t("branding.options.poweredByHint", "The About dialog names the open-source engine and its licence.")}
                      </p>
                    </div>
                    <Toggle
                      checked={form.poweredBy ?? view.effective.poweredBy}
                      onChange={(v) => setField("poweredBy", v)}
                      disabled={readOnly}
                      label={t("branding.options.poweredBy", "Show the “Powered by” notice")}
                      testId="branding-powered-by"
                    />
                  </div>
                  <div className="flex items-start justify-between gap-4 border-t border-border pt-4">
                    <div>
                      <p className="text-sm font-medium text-foreground">{t("branding.options.updateCheck", "Show the update check")}</p>
                      <p className="text-xs text-muted-foreground">
                        {t(
                          "branding.options.updateCheckHint",
                          "The Updates page and banner link to the upstream project. Hidden for a branded deployment unless this is on.",
                        )}
                      </p>
                    </div>
                    <Toggle
                      checked={form.showUpdateCheck ?? view.effective.showUpdateCheck}
                      onChange={(v) => setField("showUpdateCheck", v)}
                      disabled={readOnly}
                      label={t("branding.options.updateCheck", "Show the update check")}
                      testId="branding-update-check"
                    />
                  </div>
                </CardContent>
              </Card>
            </div>

            <div className="space-y-6">
              {/* Preview */}
              <Card>
                <CardHeader>
                  <CardTitle>{t("branding.preview.title", "Preview")}</CardTitle>
                  <CardDescription>
                    {dirty
                      ? t("branding.preview.unsaved", "Unsaved changes — Save to apply them everywhere.")
                      : t("branding.preview.saved", "What every user sees now.")}
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <BrandPreview
                    colors={formColors}
                    productName={previewName}
                    branded={previewBranded}
                    assets={assets}
                    revision={revision}
                  />
                </CardContent>
              </Card>

              {/* Images */}
              <Card>
                <CardHeader>
                  <CardTitle>{t("branding.assets.title", "Images")}</CardTitle>
                  <CardDescription>
                    {t(
                      "branding.assets.description",
                      "PNG, WebP or SVG, up to 512 KB; ICO for the favicon only. An image applies as soon as it is uploaded.",
                    )}
                  </CardDescription>
                </CardHeader>
                <CardContent className="grid gap-3 @3xl/main:grid-cols-2">
                  {BRAND_ASSET_SLOTS.map((slot) => (
                    <BrandAssetSlotCard
                      key={slot}
                      slot={slot}
                      publicPath={SLOT_META[slot].publicPath}
                      accept={SLOT_META[slot].accept}
                      label={slotText[slot].label}
                      hint={slotText[slot].hint}
                      uploaded={assets[slot]}
                      revision={revision}
                      readOnly={readOnly}
                      busy={busySlot === slot}
                      error={assetErrors[slot] ?? null}
                      note={slotNote(slot)}
                      onUpload={(file) => handleUpload(slot, file)}
                      onRemove={() => handleRemove(slot)}
                      onTooLarge={() =>
                        setAssetErrors((prev) => ({
                          ...prev,
                          [slot]: t("branding.assets.errorTooLarge", "The image is larger than 512 KB."),
                        }))
                      }
                    />
                  ))}
                </CardContent>
              </Card>
            </div>
          </div>
        </>
      )}

      <AlertDialog
        open={resetOpen}
        onOpenChange={setResetOpen}
        title={t("branding.resetDialog.title", "Reset the brand?")}
        description={t(
          "branding.resetDialog.description",
          "This clears every saved name, colour, option and image. The configuration properties, or the defaults, apply again for every user.",
        )}
        confirmLabel={t("branding.reset", "Reset to defaults")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={handleReset}
        variant="destructive"
        isPending={resetMutation.isPending}
      />
    </div>
  );
}
