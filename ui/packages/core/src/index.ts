/* ──────────────────────────────────────────────
   @eddi/core — framework-free TypeScript for EDDI's conversation contract.
   No React, no DOM beyond `fetch`. Consumed as source through the npm
   workspace link; there is no build step. See README.md for the backend
   contract each module encodes.
   ────────────────────────────────────────────── */

export * from "./types";
export * from "./http";
export * from "./sse";
export * from "./stream-events";
export * from "./conversation";
export * from "./hitl";
export * from "./attachments";
export * from "./ratings";
export * from "./rating-steps";
export * from "./snapshot";
