import { setupWorker } from "msw/browser";
import { handlers, coordinatorHandlers, orphanHandlers } from "./handlers";

export const worker = setupWorker(...handlers, ...coordinatorHandlers, ...orphanHandlers);
