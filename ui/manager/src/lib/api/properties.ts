import { api } from "../api-client";

// ─── Types ───

export interface Properties {
  [key: string]: Property;
}

export interface Property {
  name?: string;
  scope?: string;
  valueString?: string;
  valueInt?: number;
  valueFloat?: number;
  valueBoolean?: boolean;
  valueObject?: unknown;
  valueList?: unknown[];
}

// ─── API Functions ───

const BASE = "/propertiesstore/properties";

export async function readProperties(
  userId: string,
): Promise<Properties> {
  return api.get<Properties>(`${BASE}/${encodeURIComponent(userId)}`);
}

export async function mergeProperties(
  userId: string,
  properties: Properties,
): Promise<void> {
  return api.post(`${BASE}/${encodeURIComponent(userId)}`, properties);
}

export async function deleteProperties(userId: string): Promise<void> {
  return api.delete(`${BASE}/${encodeURIComponent(userId)}`);
}
