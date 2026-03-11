import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";
import { coordinatorHandlers } from "./handlers";

export const worker = setupWorker(...handlers, ...coordinatorHandlers);
