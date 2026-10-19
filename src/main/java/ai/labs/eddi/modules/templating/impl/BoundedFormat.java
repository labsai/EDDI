/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.TemplateLimitExceededException;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.Expression;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.Results;
import io.quarkus.qute.ValueResolver;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code str:fmt}/{@code str:format} and the {@code String.fmt}/{@code format}
 * instance forms with their output size checked <em>before</em>
 * {@link String#format} allocates it.
 * <p>
 * Quarkus's {@code StringTemplateExtensions} expose them as a direct
 * {@code String.format}, and the resolver bound only sees the result:
 * {@code {str:fmt('%2000000000d', 1)}} built a 2 GB string (an
 * {@code OutOfMemoryError}) before the {@code eddi.templating.max-output-chars}
 * check ever ran. These resolvers take the same arguments, estimate an upper
 * bound for the output from the format specifiers (literal text plus, per
 * specifier, the larger of its width and its content — the argument's length
 * for {@code %s}, a numeric bound plus precision for numbers), refuse when that
 * is over the limit, and only then format. They are registered ahead of the
 * generated ones, so the unchecked implementation is never reached. Security
 * review 2026-10-17, M10.
 */
final class BoundedFormat {

    /** The specifier grammar of {@link java.util.Formatter}. */
    private static final Pattern SPECIFIER = Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

    static final Set<String> NAMES = Set.of("fmt", "format");

    /** Above the generated resolvers, whatever priority Quarkus gives them. */
    static final int PRIORITY = 10_000;

    /** Upper bound for the digits of any primitive or boxed number. */
    private static final long NUMBER_CHARS = 40;

    /**
     * Upper bound for a {@code double}'s integer digits in {@code %f} plus sign and
     * point.
     */
    private static final long FLOATING_CHARS = 330;

    private BoundedFormat() {
    }

    /**
     * Formats after the size check; a {@code Locale} first argument selects the
     * locale, as in the Quarkus extensions.
     */
    static String format(String format, List<Object> arguments, int maxChars) {
        Locale locale = null;
        List<Object> args = arguments;
        if (!args.isEmpty() && args.getFirst() instanceof Locale first) {
            locale = first;
            args = args.subList(1, args.size());
        }
        Object[] array = args.toArray();
        requireWithin(format, array, maxChars);
        return locale != null ? String.format(locale, format, array) : String.format(format, array);
    }

    /**
     * @throws TemplateLimitExceededException
     *             when the formatted output could exceed {@code maxChars}
     *             ({@code <= 0}: unbounded)
     */
    static void requireWithin(String format, Object[] args, int maxChars) {
        if (maxChars <= 0 || format == null) {
            return;
        }
        long estimate = estimate(format, args, maxChars);
        if (estimate > maxChars) {
            throw new TemplateLimitExceededException("str:fmt would produce up to " + estimate + " characters, more than the limit of "
                    + maxChars + " (eddi.templating.max-output-chars)");
        }
    }

    /**
     * An upper bound for the length of {@code String.format(format, args)}; stops
     * counting once past {@code limit}.
     */
    static long estimate(String format, Object[] args, long limit) {
        Matcher m = SPECIFIER.matcher(format);
        long total = 0;
        int last = 0;
        int ordinary = 0;
        int previous = -1;
        while (m.find()) {
            total += m.start() - last;
            last = m.end();
            String flags = m.group(2) == null ? "" : m.group(2);
            long width = number(m.group(3));
            long precision = m.group(4) == null ? -1 : number(m.group(4).substring(1));
            char conversion = m.group(6).charAt(0);
            long content;
            if (conversion == '%') {
                content = 1;
            } else if (conversion == 'n') {
                content = System.lineSeparator().length();
            } else {
                int index;
                if (m.group(1) != null) {
                    index = (int) Math.min(Integer.MAX_VALUE, number(m.group(1).substring(0, m.group(1).length() - 1))) - 1;
                } else if (flags.indexOf('<') >= 0) {
                    index = previous;
                } else {
                    index = ordinary++;
                }
                previous = index;
                Object arg = index >= 0 && args != null && index < args.length ? args[index] : null;
                content = contentChars(m.group(5) != null, conversion, arg, precision, flags.indexOf(',') >= 0);
            }
            total = saturatingAdd(total, Math.max(width, content));
            if (total > limit) {
                return total;
            }
        }
        return saturatingAdd(total, format.length() - last);
    }

    private static long contentChars(boolean dateTime, char conversion, Object arg, long precision, boolean grouping) {
        if (dateTime) {
            return 100;
        }
        long chars = switch (Character.toLowerCase(conversion)) {
            case 's' -> {
                long length = arg == null ? 4 : arg instanceof CharSequence text ? text.length() : String.valueOf(arg).length();
                yield precision >= 0 ? Math.min(length, precision) : length;
            }
            case 'b' -> precision >= 0 ? Math.min(5, precision) : 5;
            case 'c' -> 2;
            case 'h' -> 8;
            case 'd', 'o', 'x' -> integerChars(arg);
            case 'e', 'f', 'g', 'a' -> saturatingAdd(floatingChars(arg), Math.max(precision, 0));
            default -> saturatingAdd(NUMBER_CHARS, Math.max(precision, 0));
        };
        return grouping ? saturatingAdd(chars, chars / 2) : chars;
    }

    private static long integerChars(Object arg) {
        if (arg instanceof BigInteger big) {
            // bitLength bits need at most bitLength octal digits
            return big.bitLength() + 2L;
        }
        return NUMBER_CHARS;
    }

    private static long floatingChars(Object arg) {
        if (arg instanceof BigDecimal decimal) {
            return saturatingAdd(saturatingAdd(decimal.precision(), Math.abs((long) decimal.scale())), NUMBER_CHARS);
        }
        if (arg instanceof BigInteger big) {
            return big.bitLength() + NUMBER_CHARS;
        }
        return FLOATING_CHARS;
    }

    /** A digit string as a long, saturating instead of overflowing. */
    private static long number(String digits) {
        if (digits == null || digits.isEmpty()) {
            return 0;
        }
        if (digits.length() > 18) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(digits);
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** Evaluates every parameter, a missing value as {@code null}. */
    private static CompletionStage<List<Object>> evaluateAll(EvalContext context, List<Expression> params) {
        CompletableFuture<List<Object>> result = CompletableFuture.completedFuture(new ArrayList<>(params.size()));
        for (Expression param : params) {
            result = result.thenCompose(list -> context.evaluate(param).thenApply(value -> {
                list.add(Results.isNotFound(value) ? null : value);
                return list;
            }));
        }
        return result;
    }

    /**
     * {@code str:fmt(format, args...)} and
     * {@code str:fmt(locale, format, args...)}.
     */
    record Namespace(int maxChars) implements NamespaceResolver {
        @Override
        public String getNamespace() {
            return "str";
        }

        @Override
        public int getPriority() {
            return PRIORITY;
        }

        @Override
        public CompletionStage<Object> resolve(EvalContext context) {
            if (!NAMES.contains(context.getName()) || context.getParams().isEmpty()) {
                return Results.notFound(context);
            }
            return evaluateAll(context, context.getParams()).thenApply(values -> {
                int formatIndex = values.getFirst() instanceof Locale ? 1 : 0;
                if (values.size() <= formatIndex || !(values.get(formatIndex) instanceof String format)) {
                    return Results.NotFound.from(context);
                }
                List<Object> args = new ArrayList<>(values.subList(formatIndex + 1, values.size()));
                if (formatIndex == 1) {
                    args.addFirst(values.getFirst());
                }
                return format(format, args, maxChars);
            });
        }
    }

    /** {@code {myFormat.fmt(args...)}} and {@code .fmt(locale, args...)}. */
    record Instance(int maxChars) implements ValueResolver {
        @Override
        public int getPriority() {
            return PRIORITY;
        }

        @Override
        public boolean appliesTo(EvalContext context) {
            return context.getBase() instanceof String && NAMES.contains(context.getName());
        }

        @Override
        public CompletionStage<Object> resolve(EvalContext context) {
            String format = (String) context.getBase();
            return evaluateAll(context, context.getParams()).thenApply(values -> format(format, values, maxChars));
        }
    }
}
