/**
 * Types for vite-backend-proxy.mjs, so the apps' vite.config.ts can import it.
 */
import type { ProxyOptions } from "vite";

/** Every backend path the signed-in apps call, proxied to `target`. */
export function backendProxy(target: string): Record<string, ProxyOptions>;
