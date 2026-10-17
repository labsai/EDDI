/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.IExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * Parses the {@code name(sub, sub(deeper))} expression notation.
 * <p>
 * The parser works on index ranges of the one input string. It used to cut a
 * fresh {@code substring} at every nesting level and recurse without any bound,
 * so a payload of nested parentheses — which any chatting user can send as a
 * {@code context} entry of type {@code expressions} — cost quadratic CPU and,
 * deep enough, overflowed the stack. {@link ExpressionLimits} now refuses an
 * over-long or over-deep input in one linear scan before parsing starts, and
 * the recursion checks the depth again at every level it enters. The grammar
 * itself is unchanged, quirks included: the only text copied is each
 * expression's name.
 */
@ApplicationScoped
public class ExpressionProvider implements IExpressionProvider {
    private final IExpressionFactory expressionFactory;
    private final ExpressionLimits limits;

    private static final Logger log = Logger.getLogger(ExpressionProvider.class);

    @Inject
    public ExpressionProvider(IExpressionFactory expressionFactory, ExpressionLimits limits) {
        this.expressionFactory = expressionFactory;
        this.limits = limits;
    }

    /**
     * A parser with the shipped {@link ExpressionLimits#defaults() default bounds}.
     */
    public ExpressionProvider(IExpressionFactory expressionFactory) {
        this(expressionFactory, ExpressionLimits.defaults());
    }

    @Override
    public Expression createExpression(String predicate, Object... values) {
        StringBuilder sb = new StringBuilder(predicate);
        if (values != null && values.length > 0) {
            sb.append("(");

            Arrays.stream(values).forEach(value -> sb.append(value).append(","));

            sb.deleteCharAt(sb.length() - 1);
            sb.append(")");
        }

        return parseExpression(sb.toString());
    }

    /**
     * @throws ExpressionLimitExceededException
     *             when the text is over one of the {@link ExpressionLimits} bounds
     *             — never swallowed by the parse-error logging below
     */
    @Override
    public Expressions parseExpressions(String expressions) {
        if (isNullOrEmpty(expressions)) {
            return new Expressions();
        }

        limits.check(expressions);
        return parseExpressions(expressions, 0, expressions.length(), 0);
    }

    /**
     * Splits {@code source[start, end)} into its top-level, comma-separated
     * expressions and parses each one at nesting level {@code depth}.
     */
    private Expressions parseExpressions(String source, int start, int end, int depth) {
        start = trimStart(source, start, end);
        end = trimEnd(source, start, end);

        Expressions ret = new Expressions();
        if (start >= end) {
            return ret;
        }

        int lastPos = start;
        int parenthesis = -1;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (i != start && parenthesis < 1 && c == ',' && source.charAt(i - 1) != ')') {
                addIfNotEmpty(ret, source, lastPos, i, depth);
                lastPos = i + 1;
            }

            if (c == '(') {
                if (parenthesis == -1) {
                    parenthesis = 0;
                }
                parenthesis++;
            }

            if (c == ')') {
                parenthesis--;
            }

            if (parenthesis == 0) {
                addIfNotEmpty(ret, source, lastPos, i + 1, depth);
                parenthesis = -1;
                lastPos = i + 2;
            }
        }

        if (lastPos < end) {
            addIfNotEmpty(ret, source, lastPos, end, depth);
        }

        return ret;
    }

    private void addIfNotEmpty(Expressions target, String source, int start, int end, int depth) {
        if (start < end) {
            target.add(parseExpression(source, start, end, depth));
        }
    }

    /**
     * @throws ExpressionLimitExceededException
     *             when the text is over one of the {@link ExpressionLimits} bounds
     */
    @Override
    public Expression parseExpression(String expression) {
        limits.check(expression);
        return parseExpression(expression, 0, expression.length(), 0);
    }

    /**
     * Parses the one expression in {@code source[start, end)}: the name before the
     * first {@code (}, and — when there is also a {@code )} — the sub-expressions
     * between that {@code (} and the last {@code )}, parsed one level deeper.
     */
    private Expression parseExpression(String source, int start, int end, int depth) {
        start = trimStart(source, start, end);
        end = trimEnd(source, start, end);

        Expression exp;
        int indexOfOpening = indexOf(source, '(', start, end);
        int indexOfClosing = lastIndexOf(source, ')', start, end);
        if (indexOfOpening > -1 && indexOfClosing > -1) {
            exp = new Expression(source.substring(start, indexOfOpening).trim());
            // The bound is enforced where the descent happens, not only in the entry
            // scan, so a caller cannot reach this level around the scan.
            limits.requireDepth(depth + 1);
            try {
                if (indexOfClosing < indexOfOpening + 1) {
                    // ")name(" — the closing bracket precedes the opening one; there is no
                    // sub-expression range to parse.
                    throw new IllegalArgumentException("closing parenthesis precedes the opening one");
                }
                Expressions expressions = parseExpressions(source, indexOfOpening + 1, indexOfClosing, depth + 1);
                exp.setSubExpressions(expressions);
            } catch (ExpressionLimitExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error(format("Error while parsing Expression: %s, indexOfOpening: %s, indexOfClosing: %s, message: %s",
                        sanitize(source.substring(start, end)), indexOfOpening - start, indexOfClosing - start, e.getLocalizedMessage()));
            }
        } else {
            exp = new Expression(source.substring(start, end));
        }

        return expressionFactory.getExpression(exp);
    }

    private static int trimStart(String source, int start, int end) {
        while (start < end && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        return start;
    }

    private static int trimEnd(String source, int start, int end) {
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static int indexOf(String source, char c, int start, int end) {
        for (int i = start; i < end; i++) {
            if (source.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(String source, char c, int start, int end) {
        for (int i = end - 1; i >= start; i--) {
            if (source.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void extractAllValues(Expression exp, List<Value> ret) {
        if (exp instanceof Value) {
            ret.add((Value) exp);
        } else {
            Expression[] values = exp.getSubExpressions();

            Arrays.stream(values).forEach(value -> {
                if (!(value instanceof Value)) {
                    extractAllValues(value, ret);
                } else {
                    ret.add((Value) value);
                }
            });
        }

    }
}
