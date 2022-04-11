package ai.labs.eddi.backup.impl;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
public class CallbackMatcher {
    public interface Callback {
        String foundMatch(MatchResult matchResult) throws CallbackMatcherException;
    }

    private final Pattern pattern;

    public CallbackMatcher(Pattern regex) {
        this.pattern = regex;
    }

    public String replaceMatches(CharSequence charSequence, Callback callback) throws CallbackMatcherException {
        StringBuilder result = new StringBuilder(charSequence);
        final Matcher matcher = this.pattern.matcher(charSequence);
        int offset = 0;

        while (matcher.find()) {
            final MatchResult matchResult = matcher.toMatchResult();
            final String replacement = callback.foundMatch(matchResult);
            if (replacement == null) {
                continue;
            }

            int matchStart = offset + matchResult.start();
            int matchEnd = offset + matchResult.end();

            result.replace(matchStart, matchEnd, replacement);

            int matchLength = matchResult.end() - matchResult.start();
            int lengthChange = replacement.length() - matchLength;

            offset += lengthChange;
        }

        return result.toString();
    }

    public static class CallbackMatcherException extends Exception {
        public CallbackMatcherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
