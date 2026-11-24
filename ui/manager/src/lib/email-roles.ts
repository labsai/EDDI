import type { EmailRolePolicy, EmailSenderRole } from "@/lib/api/channels";

/** The editable form of an integration's sender roles: the ordered roles and their policies. */
export interface RolesDocument {
  senderRoles: EmailSenderRole[];
  rolePolicies: Record<string, EmailRolePolicy>;
}

/** Parses the roles editor's text; `null` when it is not the expected shape. */
export function parseRolesDocument(text: string): RolesDocument | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const doc = value as Partial<RolesDocument>;
  const roles = doc.senderRoles ?? [];
  const policies = doc.rolePolicies ?? {};
  if (!Array.isArray(roles) || typeof policies !== "object" || Array.isArray(policies)) return null;
  return { senderRoles: roles, rolePolicies: policies };
}
