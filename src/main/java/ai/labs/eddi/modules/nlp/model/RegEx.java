/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.utils.RegexDeadline;
import ai.labs.eddi.utils.RegexDeadline.MatchDeadlineExceededException;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @author ginccc
 */
public class RegEx extends DictionaryEntry implements IDictionary.IRegEx {
    private static final Logger LOGGER = Logger.getLogger(RegEx.class);

    /**
     * Longest one lookup may spend matching one dictionary regex. A dictionary's
     * patterns are editor-authored and run against every token of every user input,
     * so a pattern that backtracks exponentially on some token would hold the
     * conversation thread for as long as the matcher took. Past the budget the
     * token simply does not match — a miss, not an error, since nothing a
     * dictionary legitimately matches takes anywhere near this long.
     */
    public static final long MATCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private final Pattern regEx;

    public RegEx(String value, Expressions expressions) {
        super(value, expressions);
        this.regEx = Pattern.compile(value);
    }

    @Override
    public boolean isPartOfPhrase() {
        return false;
    }

    @Override
    public boolean match(String lookup) {
        return match(lookup, System.nanoTime() + MATCH_BUDGET_NANOS);
    }

    @Override
    public boolean match(String lookup, long deadlineNanos) {
        try {
            return regEx.matcher(RegexDeadline.guardUntil(lookup, deadlineNanos)).matches();
        } catch (MatchDeadlineExceededException e) {
            LOGGER.warnf("Dictionary regEx '%s' did not finish matching a %d-character token within its time budget; treated as no match",
                    sanitize(regEx.pattern()), lookup.length());
            return false;
        }
    }
}
