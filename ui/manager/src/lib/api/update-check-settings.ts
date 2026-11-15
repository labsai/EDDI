import { api } from "@/lib/api-client";

/**
 * Whether this deployment checks for new EDDI releases — the backend's switch,
 * not the browser's.
 *
 * It lives on the server so that `EDDI_UPDATE_CHECK_ENABLED=false` can stop the
 * very first check, before anyone has opened the Manager, and so that one
 * administrator's choice holds for every browser and node. Unlike `updates.ts`
 * this is a same-origin call and goes through `ApiClient` like any other.
 */
export interface UpdateCheckSettings {
  /** Checks run now: the operator's property AND the stored switch. */
  enabled: boolean;
  /** `eddi.update-check.enabled=false` — the switch below cannot turn it on. */
  lockedByConfig: boolean;
  /** The stored switch alone. */
  uiEnabled: boolean;
}

export function getUpdateCheckSettings(): Promise<UpdateCheckSettings> {
  return api.get<UpdateCheckSettings>("/administration/update-check");
}

/** Administrators only; the server answers 403 to anyone else. */
export function setUpdateCheckEnabled(enabled: boolean): Promise<UpdateCheckSettings> {
  return api.put<UpdateCheckSettings>("/administration/update-check", { enabled });
}
