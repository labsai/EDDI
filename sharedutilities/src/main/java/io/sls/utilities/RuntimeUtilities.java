package io.sls.utilities;

import java.util.Collection;
import java.util.Map;

/**
 * @author ginccc
 */
public class RuntimeUtilities {
    public static void checkNotNull(Object object, String name) {
        if (object == null) {
            String message = "Argument must not be null (%s)";
            message = String.format(message, name);
            throw new IllegalArgumentException(message);
        }
    }

    public static void checkCollectionNoNullElements(Collection collection, String name) {
        checkNotNull(collection, name);

        for (Object obj : collection) {
            if (obj == null) {
                String message = "Collection (name=%s) must not contain any null elements!";
                message = String.format(message, name);
                throw new IllegalArgumentException(message);
            }
        }
    }

    public static void checkNotNegative(Integer integer, String name) {
        checkNotNull(integer, name);

        if (integer < 0) {
            String message = "Argument (%s) must be a non-negative integer.";
            message = String.format(message, name);
            throw new IllegalArgumentException(message);
        }
    }

    public static boolean isNullOrEmpty(Object obj) {
        return obj == null ||
                (obj instanceof String && obj.equals("")) ||
                (obj instanceof Collection && ((Collection) obj).isEmpty()) ||
                (obj instanceof Map && ((Map) obj).isEmpty());
    }
}
