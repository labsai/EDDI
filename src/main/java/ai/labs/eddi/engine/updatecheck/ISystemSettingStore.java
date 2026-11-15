/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import java.util.Optional;

/**
 * Persistence for small deployment-wide switches an administrator can change at
 * runtime: one string value per key.
 * <p>
 * Deliberately not a place for security posture or secrets — a key here is one
 * API call away from being changed, so anything an operator must be able to pin
 * stays a startup property (see {@link UpdateCheckService}).
 */
public interface ISystemSettingStore {

    Optional<String> read(String key);

    void write(String key, String value, String updatedBy);
}
