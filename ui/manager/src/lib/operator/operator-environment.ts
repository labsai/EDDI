import { toEnvironment, type Environment } from "@/lib/constants";

/**
 * The operator's deployment environment, normalized ONCE per flow.
 *
 * `OperatorConfig.environment` is a plain string read back from a backend global
 * variable, so it is data rather than a literal. Normalizing it at some call
 * sites and passing the raw value at others is the failure this exists to
 * prevent: creating the conversation in a normalized environment while
 * addressing the stream with an unvalidated one means the two can disagree
 * about which deployment a turn belongs to.
 *
 * Falling back to production matches the backend's own
 * `@DefaultValue("production")` rather than inventing a third behaviour.
 */
export function operatorEnvironment(config: { environment: string }): Environment {
  return toEnvironment(config.environment);
}
