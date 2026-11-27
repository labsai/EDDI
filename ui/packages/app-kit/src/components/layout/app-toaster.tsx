import { useTranslation } from "react-i18next";
import { isRtlLanguage, ThemedToaster } from "@eddi/ui";

/**
 * The app's toaster: @eddi/ui's ThemedToaster in the reading direction of the
 * language on screen (the package has no i18n of its own to ask).
 */
export function AppToaster() {
  const { i18n } = useTranslation();
  return <ThemedToaster rtl={isRtlLanguage(i18n.resolvedLanguage ?? i18n.language ?? "en")} />;
}
