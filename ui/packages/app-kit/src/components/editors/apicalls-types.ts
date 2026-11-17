// ─── Types matching HttpCallsConfiguration backend model ─────────────────────

export interface HttpCodeValidator {
  runOnHttpCode?: number[];
  skipOnHttpCode?: number[];
}

export interface PropertyInstruction {
  name?: string;
  valueString?: string;
  valueObject?: Record<string, unknown>;
  valueList?: unknown[];
  valueInt?: number;
  /** A whole number beyond the `int` range (EDDI 6.6+). */
  valueLong?: number;
  valueFloat?: number;
  /** A decimal at full precision (EDDI 6.6+). */
  valueDouble?: number;
  valueBoolean?: boolean;
  scope?: "step" | "conversation" | "longTerm" | "secret";
  fromObjectPath?: string;
  toObjectPath?: string;
  convertToObject?: boolean;
  override?: boolean;
  runOnValidationError?: boolean;
  httpCodeValidator?: HttpCodeValidator;
}

export interface OutputBuildingInstruction {
  pathToTargetArray?: string;
  iterationObjectName?: string;
  templateFilterExpression?: string;
  outputType?: string;
  outputValue?: string;
  httpCodeValidator?: HttpCodeValidator;
}

export interface QuickRepliesBuildingInstruction {
  pathToTargetArray?: string;
  iterationObjectName?: string;
  templateFilterExpression?: string;
  quickReplyValue?: string;
  quickReplyExpressions?: string;
  httpCodeValidator?: HttpCodeValidator;
}

export interface HttpPreRequest {
  propertyInstructions?: PropertyInstruction[];
  batchRequests?: unknown;
  delayBeforeExecutingInMillis?: number;
}

export interface RetryMatchingInfo {
  valuePath?: string;
  contains?: string;
  equals?: string;
  trueIfNoMatch?: boolean;
}

export interface RetryApiCallInstruction {
  maxRetries?: number;
  exponentialBackoffDelayInMillis?: number;
  retryOnHttpCodes?: number[];
  responseValuePathMatchers?: RetryMatchingInfo[];
}

export interface HttpPostResponse {
  propertyInstructions?: PropertyInstruction[];
  outputBuildInstructions?: OutputBuildingInstruction[];
  qrBuildInstructions?: QuickRepliesBuildingInstruction[];
  retryApiCallInstruction?: RetryApiCallInstruction;
}

export interface HttpRequest {
  path: string;
  method: string;
  headers: Record<string, string>;
  queryParams: Record<string, string>;
  contentType: string;
  body: string;
}

export interface HttpCall {
  name: string;
  description?: string;
  parameters?: Record<string, string>;
  actions: string[];
  saveResponse?: boolean;
  responseObjectName?: string;
  responseHeaderObjectName?: string;
  fireAndForget?: boolean;
  /**
   * Declared on the backend model but read by nothing in the engine, so the
   * form no longer offers them (batching is `preRequest.batchRequests`). Kept
   * in the type so a stored value survives a round trip untouched.
   */
  isBatchCalls?: boolean;
  iterationObjectName?: string;
  preRequest?: HttpPreRequest;
  /**
   * Always written by this editor, but a hand-written or imported config can
   * carry `null` here — the call editor guards for it rather than trusting the
   * type.
   */
  request: HttpRequest;
  postResponse?: HttpPostResponse;
}

export interface HttpCallsConfig {
  targetServerUrl?: string;
  httpCalls: HttpCall[];
}
