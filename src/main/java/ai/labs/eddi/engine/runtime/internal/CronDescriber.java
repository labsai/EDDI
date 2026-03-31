package ai.labs.eddi.engine.runtime.internal;

import java.util.Map;
import java.util.Set;

/**
 * Converts 5-field cron expressions to human-readable descriptions.
 * <p>
 * Examples:
 * <ul>
 * <li>{@code * * * * *} -&gt; "Every minute"</li>
 * <li>{@code 0 9 * * MON-FRI} -&gt; "At 09:00 on every weekday"</li>
 * <li>{@code 0 0 1 * *} -&gt; "At midnight on the 1st of every month"</li>
 * <li>{@code *}{@code /15 * * * *} -&gt; "Every 15 minutes"</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class CronDescriber {

    private static final String[] MONTH_LABELS = {"", "January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};

    private static final String[] DOW_LABELS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

    private CronDescriber() {
    }

    /**
     * Convert a 5-field cron expression to a human-readable string.
     *
     * @param cronExpression
     *            5-field cron (min hour dom month dow)
     * @return human-readable description
     */
    public static String describe(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return "Invalid cron expression";
        }

        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 5) {
            return "Invalid cron expression (expected 5 fields)";
        }

        String minute = parts[0];
        String hour = parts[1];
        String dom = parts[2];
        // Fix #11: reuse maps from CronParser instead of duplicating
        String month = substituteNames(parts[3], CronParser.MONTH_NAMES);
        String dow = substituteNames(parts[4], CronParser.DOW_NAMES);

        StringBuilder sb = new StringBuilder();

        // Special case: * * * * * = every minute
        if (minute.equals("*") && hour.equals("*") && dom.equals("*") && month.equals("*") && dow.equals("*")) {
            return "Every minute";
        }

        // Step pattern in minutes: */N
        if (minute.startsWith("*/") && hour.equals("*") && dom.equals("*") && month.equals("*") && dow.equals("*")) {
            return "Every " + minute.substring(2) + " minutes";
        }

        // Step pattern in hours: 0 */N * * *
        if (minute.equals("0") && hour.startsWith("*/") && dom.equals("*") && month.equals("*") && dow.equals("*")) {
            return "Every " + hour.substring(2) + " hours";
        }

        // Time description
        if (!minute.equals("*") && !hour.equals("*")) {
            String timeStr = formatTime(hour, minute);
            if (hour.equals("0") && minute.equals("0")) {
                sb.append("At midnight");
            } else {
                sb.append("At ").append(timeStr);
            }
        } else if (!minute.equals("*")) {
            sb.append("At minute ").append(minute);
        } else {
            sb.append("Every minute");
        }

        // Day of month
        if (!dom.equals("*")) {
            sb.append(" on the ").append(ordinal(dom)).append(" of");
        }

        // Month
        if (!month.equals("*")) {
            Set<Integer> monthValues = CronParser.parseField(month, 1, 12);
            if (dom.equals("*"))
                sb.append(" in");
            sb.append(" ").append(formatSet(monthValues, MONTH_LABELS));
        } else if (!dom.equals("*")) {
            sb.append(" every month");
        }

        // Day of week
        if (!dow.equals("*")) {
            Set<Integer> dowValues = CronParser.parseField(dow, 0, 6);
            if (isWeekdays(dowValues)) {
                sb.append(" on every weekday");
            } else if (isWeekends(dowValues)) {
                sb.append(" on weekends");
            } else {
                sb.append(" on ").append(formatSet(dowValues, DOW_LABELS));
            }
        }

        return sb.toString();
    }

    private static String formatTime(String hour, String minute) {
        try {
            int h = Integer.parseInt(hour);
            int m = Integer.parseInt(minute);
            return String.format("%02d:%02d", h, m);
        } catch (NumberFormatException e) {
            return hour + ":" + minute;
        }
    }

    private static String ordinal(String num) {
        try {
            int n = Integer.parseInt(num);
            if (n >= 11 && n <= 13)
                return n + "th";
            return switch (n % 10) {
                case 1 -> n + "st";
                case 2 -> n + "nd";
                case 3 -> n + "rd";
                default -> n + "th";
            };
        } catch (NumberFormatException e) {
            return num;
        }
    }

    private static boolean isWeekdays(Set<Integer> dows) {
        return dows.size() == 5 && dows.contains(1) && dows.contains(2) && dows.contains(3) && dows.contains(4) && dows.contains(5);
    }

    private static boolean isWeekends(Set<Integer> dows) {
        return dows.size() == 2 && dows.contains(0) && dows.contains(6);
    }

    private static String formatSet(Set<Integer> values, String[] labels) {
        if (values.size() == 1) {
            int v = values.iterator().next();
            return v < labels.length ? labels[v] : String.valueOf(v);
        }
        return values.stream().map(v -> v < labels.length ? labels[v] : String.valueOf(v)).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String substituteNames(String field, Map<String, String> names) {
        String result = field.toUpperCase();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
