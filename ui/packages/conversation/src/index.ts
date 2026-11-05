/* ──────────────────────────────────────────────
   @eddi/conversation — controllers and components for every kind of EDDI
   conversation (frontend unification plan §3.2). Router-free: hosts pass ids
   and callbacks. Never reads window.__EDDI_*__. Consumed as source through the
   npm workspace link; there is no build step. See README.md.
   ────────────────────────────────────────────── */

export * from "./controller/state";
export * from "./controller/transport";
export * from "./controller/agent-conversation";
export * from "./controller/notices";
export * from "./i18n/text";
export * from "./react/hooks";
