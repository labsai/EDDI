/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class MatchingUtilities {
    public static boolean executeValuePath(Map<String, Object> conversationValues, String valuePath, String equals, String contains) {

        Object value = null;
        try {
            value = PathNavigator.getValue(valuePath, conversationValues);
        } catch (Exception _) {
            // no value was found, which is an expected case, so silent exception here
        }

        if (value == null) {
            return false;
        }

        if (!isNullOrEmpty(equals) && equals.equals(value.toString())) {
            return true;
        } else if (!isNullOrEmpty(contains)) {
            if (value instanceof String s && s.contains(contains)) {
                return true;
            } else if (value instanceof List<?> l && l.contains(contains)) {
                return true;
            }
        } else if (isNullOrEmpty(equals) && isNullOrEmpty(contains)) {
            // Pure existence check — no comparison operators specified.
            // For Booleans, return the value itself (false = condition fails).
            // For any other non-null value, existence alone means success.
            if (value instanceof Boolean b) {
                return b;
            }
            return true;
        }

        return false;
    }
}
