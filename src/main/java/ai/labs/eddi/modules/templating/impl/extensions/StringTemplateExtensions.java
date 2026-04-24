/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import io.quarkus.qute.TemplateExtension;

/**
 * Qute template extensions for String methods. Replaces Thymeleaf's
 * {@code #strings} utility object.
 * <p>
 * In native image mode, Qute cannot use reflection to call arbitrary String
 * methods, so we expose them explicitly.
 * <p>
 * Usage in templates:
 * <ul>
 * <li>{str.replace('old', 'new')}</li>
 * <li>{str.toLowerCase()}</li>
 * <li>{str.toUpperCase()}</li>
 * <li>{str.substring(5)}</li>
 * <li>{str.substring(0, 5)}</li>
 * <li>{str.indexOf('x')}</li>
 * <li>{str.lastIndexOf('x')}</li>
 * <li>{str.contains('sub')}</li>
 * <li>{str.startsWith('pre')}</li>
 * <li>{str.endsWith('suf')}</li>
 * <li>{str.trim()}</li>
 * <li>{str.length()}</li>
 * <li>{str.isEmpty()}</li>
 * <li>{str.charAt(0)}</li>
 * <li>{str.concat('suffix')}</li>
 * <li>{str.strip()}</li>
 * </ul>
 */
public class StringTemplateExtensions {

    // --- Case conversion ---

    @TemplateExtension(matchName = "toLowerCase")
    static String toLowerCase(String str) {
        return str == null ? null : str.toLowerCase();
    }

    @TemplateExtension(matchName = "toUpperCase")
    static String toUpperCase(String str) {
        return str == null ? null : str.toUpperCase();
    }

    // --- Search & replace ---

    @TemplateExtension(matchName = "replace")
    static String replace(String str, String target, String replacement) {
        return str == null ? null : str.replace(target, replacement);
    }

    @TemplateExtension(matchName = "contains")
    static boolean contains(String str, String sub) {
        return str != null && str.contains(sub);
    }

    @TemplateExtension(matchName = "indexOf")
    static int indexOf(String str, String sub) {
        return str == null ? -1 : str.indexOf(sub);
    }

    @TemplateExtension(matchName = "lastIndexOf")
    static int lastIndexOf(String str, String sub) {
        return str == null ? -1 : str.lastIndexOf(sub);
    }

    @TemplateExtension(matchName = "startsWith")
    static boolean startsWith(String str, String prefix) {
        return str != null && str.startsWith(prefix);
    }

    @TemplateExtension(matchName = "endsWith")
    static boolean endsWith(String str, String suffix) {
        return str != null && str.endsWith(suffix);
    }

    // --- Substring ---

    @TemplateExtension(matchName = "substring")
    static String substring(String str, int beginIndex) {
        return str == null ? null : str.substring(beginIndex);
    }

    @TemplateExtension(matchName = "substring")
    static String substringRange(String str, int beginIndex, int endIndex) {
        return str == null ? null : str.substring(beginIndex, endIndex);
    }

    // --- Trimming ---

    @TemplateExtension(matchName = "trim")
    static String trim(String str) {
        return str == null ? null : str.trim();
    }

    @TemplateExtension(matchName = "strip")
    static String strip(String str) {
        return str == null ? null : str.strip();
    }

    // --- Length & char access ---

    @TemplateExtension(matchName = "length")
    static int length(String str) {
        return str == null ? 0 : str.length();
    }

    @TemplateExtension(matchName = "isEmpty")
    static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    @TemplateExtension(matchName = "charAt")
    static char charAt(String str, int index) {
        return str.charAt(index);
    }

    // --- Concatenation ---

    @TemplateExtension(matchName = "concat")
    static String concat(String str, String suffix) {
        return str == null ? suffix : str.concat(suffix);
    }
}
