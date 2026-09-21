import { useTranslation } from "react-i18next";
import type { Environment } from "@/lib/constants";

const ENV_LABEL_KEYS: Record<Environment, { key: string; fallback: string }> = {
  production: { key: "environments.production", fallback: "Production" },
  test: { key: "environments.test", fallback: "Test" },
};

/**
 * Human label for a deployment environment.
 *
 * Its own module because it is a hook, not a component — keeping it beside the
 * badge broke fast refresh for that file.
 */
export function useEnvironmentLabel() {
  const { t } = useTranslation();
  return (env: Environment) => {
    const entry = ENV_LABEL_KEYS[env];
    return entry ? t(entry.key, entry.fallback) : env;
  };
}
