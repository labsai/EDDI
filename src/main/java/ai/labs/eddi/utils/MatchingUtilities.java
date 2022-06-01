package ai.labs.eddi.utils;

import ognl.Ognl;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class MatchingUtilities {
    public static boolean executeValuePath(Map<String, Object> conversationValues,
                                           String valuePath, String equals, String contains) {

        boolean success = false;

        Object value = null;
        try {
            value = Ognl.getValue(valuePath, conversationValues);
        } catch (Exception e) {
            //no value was found, which is an expected case, so silent exception here
        }
        if (value != null) {
            if (!isNullOrEmpty(equals) && equals.equals(value.toString())) {
                success = true;
            } else if (!isNullOrEmpty(contains)) {
                if (value instanceof String && ((String) value).contains(contains)) {
                    success = true;
                } else if (value instanceof List && ((List<?>) value).contains(contains)) {
                    success = true;
                }
            } else if (value instanceof Boolean) {
                success = (Boolean) value;
            } else if (isNullOrEmpty(equals) && isNullOrEmpty(contains)) {
                success = true;
            }
        }

        return success;
    }
}
