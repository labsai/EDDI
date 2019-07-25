package ai.labs.channels.differ.utilities;

import java.util.Date;
import java.util.UUID;

public class DifferUtilities {
    private static final int DELAY_PER_WORD = 110;

    public static Date getCurrentTime() {
        return new Date(System.currentTimeMillis());
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString();
    }

    /**
     * @param text words or sentences articulated by the bot
     * @return delay in Millis
     */
    public static long calculateTypingDelay(String text) {
        var delay = text.split(" ").length * DELAY_PER_WORD;
        delay = (delay < DELAY_PER_WORD * 3) ? 300 : delay;

        if (delay > 1000) {
            delay = 1000;
        }

        return delay;
    }

    public static long calculateSentAt(long receivedEventCreatedAt) {
        long currentSystemTime = System.currentTimeMillis();
        return receivedEventCreatedAt > currentSystemTime ? receivedEventCreatedAt + 1 : currentSystemTime;
    }
}
