import { Toaster } from "sonner";
import { useTranslation } from "react-i18next";
import { isRtlLanguage } from "@/i18n/config";
import { useTheme } from "./theme-provider";

/**
 * Toasts follow the resolved theme — sonner defaults to light and ignores `.dark`.
 * They also sit in the reading direction's trailing corner: sonner takes a
 * physical `position`, so `bottom-right` stayed on the right in Arabic, where
 * it covers the start of the layout instead of its end.
 */
export function ThemedToaster() {
  const { resolvedTheme } = useTheme();
  const { i18n } = useTranslation();
  const rtl = isRtlLanguage(i18n.resolvedLanguage ?? i18n.language ?? "en");
  return (
    <Toaster
      position={rtl ? "bottom-left" : "bottom-right"}
      dir={rtl ? "rtl" : "ltr"}
      richColors
      closeButton
      theme={resolvedTheme}
    />
  );
}
