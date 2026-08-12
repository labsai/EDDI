import { setupWorker } from "msw/browser";
import { browserHandlers } from "./browser-handlers";

export const worker = setupWorker(...browserHandlers);
