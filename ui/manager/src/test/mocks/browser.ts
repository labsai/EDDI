import { setupWorker } from "msw/browser";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, quotaHandlers } from "./handlers";

export const worker = setupWorker(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...quotaHandlers);
