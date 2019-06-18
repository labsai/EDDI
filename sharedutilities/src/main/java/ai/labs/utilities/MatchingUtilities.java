package ai.labs.utilities;

import ognl.Ognl;
import ognl.OgnlException;

import java.util.List;
import java.util.Map;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

public class MatchingUtilities {
    public static boolean executeValuePath(Map<String, Object> conversationValues,
                                           String valuePath, String equals, String contains) {

        boolean success = false;
        try {
            Object value = Ognl.getValue(valuePath, conversationValues);
            if (value != null) {
                if (!isNullOrEmpty(equals) && equals.equals(value.toString())) {
                    success = true;
                } else if (!isNullOrEmpty(contains)) {
                    if (value instanceof String && ((String) value).contains(contains)) {
                        success = true;
                    } else if (value instanceof List && ((List) value).contains(contains)) {
                        success = true;
                    }
                } else if (isNullOrEmpty(equals) && isNullOrEmpty(contains)) {
                    success = true;
                }
            }
        } catch (OgnlException ignored) {
            //ignored
        }
        return success;
    }
}
