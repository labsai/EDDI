import { useCallback, useSyncExternalStore } from "react";
import { BROWSER_NOTICES_KEY } from "@/lib/workforce-notices";

/*
 * The opt-in to browser notifications for Workforce's notices (T2.8).
 *
 * Off unless the person turns it on, and the browser's permission prompt is
 * only ever shown from that click — never on load, which browsers penalise and
 * people rightly dislike. The choice is kept in localStorage (per browser, like
 * the theme); the permission itself is the browser's.
 */

const listeners = new Set<() => void>();

function notify() {
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function browserNoticesSupported(): boolean {
  return typeof window !== "undefined" && typeof window.Notification === "function";
}

function readOptIn(): boolean {
  try {
    return localStorage.getItem(BROWSER_NOTICES_KEY) === "on";
  } catch {
    return false;
  }
}

function writeOptIn(on: boolean) {
  try {
    if (on) localStorage.setItem(BROWSER_NOTICES_KEY, "on");
    else localStorage.removeItem(BROWSER_NOTICES_KEY);
  } catch {
    // storage blocked: the choice lasts for this page only
  }
  notify();
}

export type BrowserNoticeState = "unsupported" | "off" | "on" | "blocked";

function readState(): BrowserNoticeState {
  if (!browserNoticesSupported()) return "unsupported";
  if (window.Notification.permission === "denied") return "blocked";
  return readOptIn() && window.Notification.permission === "granted" ? "on" : "off";
}

/** Whether a notice should also go to the browser right now. */
export function shouldNotifyBrowser(): boolean {
  return readState() === "on" && typeof document !== "undefined" && document.visibilityState === "hidden";
}

export function useBrowserNotices(): {
  state: BrowserNoticeState;
  /** Asks the browser for permission. Call only from a click. */
  enable: () => Promise<void>;
  disable: () => void;
} {
  const state = useSyncExternalStore(subscribe, readState, readState);
  const enable = useCallback(async () => {
    if (!browserNoticesSupported()) return;
    let permission = window.Notification.permission;
    if (permission === "default") {
      try {
        permission = await window.Notification.requestPermission();
      } catch {
        permission = window.Notification.permission;
      }
    }
    writeOptIn(permission === "granted");
  }, []);
  const disable = useCallback(() => writeOptIn(false), []);
  return { state, enable, disable };
}
