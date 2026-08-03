/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api.model;

/**
 * Result of one client-run gate verification, reported for {@code /q/metrics}
 * visibility.
 * <p>
 * {@code verifyGateInstalled} (Manager-side) reads every provisioned version of
 * the operator agent back and checks the approval gate is installed and sane on
 * each. That fact has no backend-side equivalent to observe directly — the
 * operator is not a distinct concept in this codebase, just an agent document
 * like any other — so the Manager reports the outcome after checking it.
 *
 * @param verified
 *            true only when every version read back with a sound gate.
 */
public record OperatorGateStatusReport(boolean verified) {
}
