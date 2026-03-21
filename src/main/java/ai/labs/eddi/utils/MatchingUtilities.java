package ai.labs.eddi.utils;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class MatchingUtilities {
    public static boolean executeValuePath(Map<String, Object> conversationValues,
            String valuePath, String equals, String contains) {

        boolean success = false;

        Object value = null;
        try {
            value = PathNavigator.getValue(valuePath, conversationValues);
        } catch (Exception _) {
            // no value was found, which is an expected case, so silent exception here
        }
        if (value != null) {
            if (!isNullOrEmpty(equals) && equals.equals(value.toString())) {
                success = true;
            } else if (!isNullOrEmpty(contains)) {
                if (value instanceof String s && s.contains(contains)) {
                    success = true;
                } else if (value instanceof List<?> l && l.contains(contains)) {
                    success = true;
                }
            } else if (value instanceof Boolean b) {
                success = b;
            } else if (isNullOrEmpty(equals) && isNullOrEmpty(contains)) {
                success = true;
            }
        }

        return success;
    }
}
