/**
 * Types for vite-plugin-brand.mjs, so vite.config.ts and the TypeScript suite
 * can import it. The plugin stays plain JS for the same reason check-i18n.mjs
 * does.
 */
import type { Plugin } from "vite";

/** What EDDI's `/branding/__brand__.js` answers when nothing is branded. */
export const DEFAULT_BRAND_SCRIPT: string;

/** Replaces the `%BRAND_*%` placeholders with an unbranded EDDI's values. */
export function substituteBrandDefaults(html: string, fileName: string): string;

/** The dev-server-only plugin; see the .mjs for what it serves. */
export function brandDefaults(options: { assets: Record<string, string> }): Plugin;
