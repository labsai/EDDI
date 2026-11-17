/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections.similarities;

public interface IDistanceCalculator {
    /** The edit distance between the two strings; 0 means they are identical. */
    int calculate(String stringOne, String stringTwo);
}
