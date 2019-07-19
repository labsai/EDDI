package ai.labs.channels.differ.utilities;

import java.util.Date;
import java.util.UUID;

public class DifferUtilities {
    public static Date getCurrentTime() {
        return new Date(System.currentTimeMillis());
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString();
    }
}
