import { setupWorker } from "msw/browser";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers } from "./handlers";

export const worker = setupWorker(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers);
