/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The one validator for brand values, used for REST writes and for the
 * {@code eddi.branding.*} seed alike.
 * <p>
 * The values end up in three output contexts — a JavaScript string
 * ({@code __brand__.js}), a stylesheet ({@code brand.css}) and HTML text and
 * attributes (the shells) — and two of those are escaped on the way out. The
 * stylesheet is not: no free-form CSS is ever emitted, so a colour has to match
 * a grammar strict enough that it cannot close a declaration, open a rule,
 * reference a URL or call a function other than the four colour functions with
 * plain numbers. Names reject {@code <}, {@code >} and control characters as
 * well, because the Manager's i18next runs with {@code escapeValue: false} and
 * any {@code Trans}/HTML path would not escape them (plan R11).
 */
public final class BrandValidator {

    public static final int PRODUCT_NAME_MAX = 40;
    public static final int SHORT_NAME_MAX = 12;
    public static final int TAGLINE_MAX = 120;

    /** Longer than any legitimate colour, and a bound for the regex below. */
    static final int COLOR_MAX = 64;

    /** WCAG AA for normal text. Below it a write gets a warning, not a refusal. */
    static final double MIN_CONTRAST = 4.5;

    private static final Pattern HEX = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})");

    /** A plain number with an optional unit: no calc(), var(), none, or names. */
    private static final String NUM = "-?(?:\\d{1,4}(?:\\.\\d{1,6})?|\\.\\d{1,6})(?:%|deg)?";

    /**
     * {@code rgb()}, {@code rgba()}, {@code hsl()}, {@code hsla()} and
     * {@code oklch()} with three numbers and an optional alpha, comma- or
     * space-separated. Applied only to strings already capped at
     * {@link #COLOR_MAX}, so its backtracking is bounded.
     */
    private static final Pattern FUNCTION = Pattern.compile(
            "(?:rgba?|hsla?|oklch)\\(\\s*" + NUM + "(?:\\s*,\\s*|\\s+)" + NUM + "(?:\\s*,\\s*|\\s+)" + NUM
                    + "(?:\\s*[,/]\\s*" + NUM + ")?\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RGB_ARGS = Pattern.compile(
            "rgba?\\(\\s*(" + NUM + ")(?:\\s*,\\s*|\\s+)(" + NUM + ")(?:\\s*,\\s*|\\s+)(" + NUM + ").*",
            Pattern.CASE_INSENSITIVE);

    private BrandValidator() {
    }

    /**
     * Every text and colour value of a brand.
     *
     * @param fieldName
     *            maps the JSON field name ({@code productName},
     *            {@code colors.primary}) to the name a violation reports — the
     *            identity for REST, the property name for the seed
     */
    public static List<BrandViolation> validate(String productName, String shortName, String tagline, BrandColors colors,
                                                UnaryOperator<String> fieldName) {
        List<BrandViolation> violations = new ArrayList<>();
        checkText(fieldName.apply("productName"), productName, PRODUCT_NAME_MAX, violations);
        checkText(fieldName.apply("shortName"), shortName, SHORT_NAME_MAX, violations);
        checkText(fieldName.apply("tagline"), tagline, TAGLINE_MAX, violations);
        if (colors != null) {
            checkColor(fieldName.apply("colors.primary"), colors.primary(), violations);
            checkColor(fieldName.apply("colors.primaryForeground"), colors.primaryForeground(), violations);
            checkColor(fieldName.apply("colors.darkPrimary"), colors.darkPrimary(), violations);
            checkColor(fieldName.apply("colors.darkPrimaryForeground"), colors.darkPrimaryForeground(), violations);
            checkColor(fieldName.apply("colors.chatAccent"), colors.chatAccent(), violations);
        }
        return violations;
    }

    /** A name-like field: the product name, short name or tagline. */
    public static void checkText(String field, String value, int maxLength, List<BrandViolation> violations) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            violations.add(new BrandViolation(field, "must not be blank (leave it unset for the default)"));
            return;
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            violations.add(new BrandViolation(field, "must be at most " + maxLength + " characters"));
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<' || c == '>') {
                violations.add(new BrandViolation(field, "must not contain '<' or '>'"));
                return;
            }
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || isBidiControl(c)) {
                violations.add(new BrandViolation(field, "must not contain control characters"));
                return;
            }
        }
    }

    /**
     * The bidirectional overrides and isolates. They are format characters rather
     * than controls, but they reorder the text around them, which in a product name
     * is a way to make it read as something else.
     */
    private static boolean isBidiControl(char c) {
        return (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩') || c == '‎' || c == '‏';
    }

    /** A colour field: see the class comment for the grammar. */
    public static void checkColor(String field, String value, List<BrandViolation> violations) {
        if (value == null) {
            return;
        }
        if (!isSafeColor(value)) {
            violations.add(new BrandViolation(field, "must be a colour like #0f766e, #fff, rgb(15 118 110), hsl(…) or oklch(…) "
                    + "with plain numbers only"));
        }
    }

    public static boolean isSafeColor(String value) {
        if (value == null || value.isEmpty() || value.length() > COLOR_MAX) {
            return false;
        }
        return HEX.matcher(value).matches() || FUNCTION.matcher(value).matches();
    }

    /**
     * Contrast advisories for resolved colours: the foreground on the primary, in
     * light and in dark. Only pairs whose sRGB value can be read here (hex and
     * {@code rgb()}) are checked; {@code hsl()} and {@code oklch()} pass without a
     * warning rather than with a guessed one.
     */
    public static List<String> contrastWarnings(BrandColors resolved) {
        List<String> warnings = new ArrayList<>();
        if (resolved == null) {
            return warnings;
        }
        contrastWarning("primaryForeground on primary", resolved.primaryForeground(), resolved.primary()).ifPresent(warnings::add);
        String darkForeground = resolved.darkPrimaryForeground();
        String darkPrimary = resolved.darkPrimary();
        boolean darkDiffers = darkForeground != null && !darkForeground.equals(resolved.primaryForeground())
                || darkPrimary != null && !darkPrimary.equals(resolved.primary());
        if (darkDiffers) {
            contrastWarning("darkPrimaryForeground on darkPrimary", darkForeground, darkPrimary).ifPresent(warnings::add);
        }
        return warnings;
    }

    private static Optional<String> contrastWarning(String pair, String foreground, String background) {
        Optional<double[]> fg = parseSrgb(foreground);
        Optional<double[]> bg = parseSrgb(background);
        if (fg.isEmpty() || bg.isEmpty()) {
            return Optional.empty();
        }
        double ratio = contrastRatio(fg.get(), bg.get());
        if (ratio >= MIN_CONTRAST) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "Contrast of %s is %.2f:1, below the %.1f:1 that WCAG AA asks for normal text.",
                pair, ratio, MIN_CONTRAST));
    }

    /** WCAG 2 contrast ratio of two sRGB colours (channels 0..1). */
    static double contrastRatio(double[] a, double[] b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(double[] rgb) {
        return 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2]);
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** sRGB channels (0..1) of a hex or {@code rgb()} colour. */
    static Optional<double[]> parseSrgb(String color) {
        if (color == null || !isSafeColor(color)) {
            return Optional.empty();
        }
        // isSafeColor admits only hex digits and plain decimals, so the parses
        // below cannot fail today; the catch keeps a future grammar change from
        // turning a contrast check into a 500.
        try {
            return parseValidatedSrgb(color);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<double[]> parseValidatedSrgb(String color) {
        if (color.startsWith("#")) {
            String hex = color.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            return Optional.of(new double[]{
                    Integer.parseInt(hex.substring(0, 2), 16) / 255.0,
                    Integer.parseInt(hex.substring(2, 4), 16) / 255.0,
                    Integer.parseInt(hex.substring(4, 6), 16) / 255.0});
        }
        var matcher = RGB_ARGS.matcher(color);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            String arg = matcher.group(i + 1);
            if (arg.endsWith("deg")) {
                return Optional.empty();
            }
            double value = arg.endsWith("%")
                    ? Double.parseDouble(arg.substring(0, arg.length() - 1)) / 100.0
                    : Double.parseDouble(arg) / 255.0;
            rgb[i] = Math.clamp(value, 0.0, 1.0);
        }
        return Optional.of(rgb);
    }
}
