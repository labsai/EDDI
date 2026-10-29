/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers.providers;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.PunctuationNormalizer;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.BOOLEAN;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.STRING;

@ApplicationScoped
public class PunctuationNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.punctuation";

    private static final String KEY_REMOVE_PUNCTUATION = "removePunctuation";
    private static final String KEY_PUNCTUATION_REGEX_PATTERN = "punctuationRegexPattern";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Punctuation Normalizer";
    }

    @Override
    public INormalizer provide(Map<String, Object> config) {
        boolean removePunctuation = false;

        if (config.containsKey(KEY_REMOVE_PUNCTUATION)) {
            removePunctuation = Boolean.parseBoolean(config.get(KEY_REMOVE_PUNCTUATION).toString());
        }

        // A local, not a field: this provider is a singleton shared by every
        // parser, and a field let one parser's custom punctuation become the
        // default of every parser configured after it.
        String punctuationRegexPattern = PunctuationNormalizer.PUNCTUATION;
        if (config.containsKey(KEY_PUNCTUATION_REGEX_PATTERN)) {
            punctuationRegexPattern = config.get(KEY_PUNCTUATION_REGEX_PATTERN).toString();
        }

        return new PunctuationNormalizer(toRegexPattern(punctuationRegexPattern), removePunctuation);
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_REMOVE_PUNCTUATION, new ConfigValue("Remove Punctuation", BOOLEAN, true, false));
        ret.put(KEY_PUNCTUATION_REGEX_PATTERN, new ConfigValue("Punctuation RegEx Pattern", STRING, true, PunctuationNormalizer.PUNCTUATION));

        return ret;
    }

    /**
     * The punctuation setting as a pattern that is always one character class.
     * <p>
     * The setting is spliced between {@code [} and {@code ]}, so it used to be able
     * to close that class itself: {@code ]|(a+)+$|[} compiled to an alternation
     * with a catastrophically backtracking branch, run against every token of every
     * user message. Now the setting is used as a class body only when it is one —
     * its brackets balance, so none of them can close the outer class, and it
     * carries no {@code \Q…\E} quote, which could hide one. That keeps the forms
     * the setting has always accepted ({@code !?:.,;}, {@code [.!?]},
     * {@code \p{Punct}}, {@code a-z}). Anything else is taken literally, each
     * character meaning itself, and a character class matches in time linear in the
     * token whatever it contains.
     */
    public Pattern toRegexPattern(String punctuation) {
        if (punctuation.isEmpty()) {
            // "[]" does not compile; no punctuation configured matches nothing
            return Pattern.compile("(?!)");
        }
        String body = isSingleClassBody(punctuation) ? punctuation : literalClassBody(punctuation);
        try {
            return Pattern.compile("[" + body + "]");
        } catch (PatternSyntaxException e) {
            // a balanced body that is still not valid class syntax, such as the
            // reversed range "z-a": its characters, literally
            return Pattern.compile("[" + literalClassBody(punctuation) + "]");
        }
    }

    /**
     * Whether {@code body}, wrapped in {@code [} {@code ]}, is a single character
     * class: every unescaped {@code ]} closes a {@code [} opened inside it.
     * Conservative — a body it is unsure of (a trailing backslash, say) is refused
     * and then read literally.
     */
    static boolean isSingleClassBody(String body) {
        if (body.isEmpty()) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                if (i + 1 < body.length() && (body.charAt(i + 1) == 'Q' || body.charAt(i + 1) == 'E')) {
                    return false;
                }
                i++;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth < 0) {
                return false;
            }
        }
        return depth == 0 && body.charAt(body.length() - 1) != '\\';
    }

    /** {@code text}'s characters as class members, each one literal. */
    static String literalClassBody(String text) {
        StringBuilder body = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // a backslash before any ASCII character that is not a letter or digit
            // makes it literal; letters and digits are literal already, and an
            // escaped letter would be a (reserved) escape sequence
            if (c < 0x80 && !Character.isLetterOrDigit(c)) {
                body.append('\\');
            }
            body.append(c);
        }
        return body.toString();
    }
}
