/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a query, path or matrix parameter that cannot be converted into its
 * declared type into a 400 that says so, instead of a bare 404.
 * <p>
 * The JAX-RS specification (§3.2) makes the framework answer such a parameter —
 * {@code ?conversationState=BOGUS}, {@code ?version=abc}, {@code ?limit=x} —
 * with a {@link NotFoundException} wrapping the conversion failure. The client
 * then reads "HTTP 404 Not Found" for a resource that exists and has no way to
 * tell a typo in a parameter from a missing document. Those 404s are recognised
 * by their origin (the framework's parameter handler, with the conversion
 * failure as cause) and answered with the shared JSON error body naming the
 * rejected value and, for an enum, the legal ones.
 * <p>
 * Every other {@code NotFoundException} — an unmatched path, a missing resource
 * — is left exactly as {@link ClientErrorExceptionMapper} answers it.
 */
@Provider
public class ParameterConversionExceptionMapper implements ExceptionMapper<NotFoundException> {

    /** Class the framework raises parameter-conversion 404s from. */
    private static final String PARAMETER_HANDLER = "org.jboss.resteasy.reactive.server.handlers.ParameterHandler";

    /** {@code Enum.valueOf}: "No enum constant fully.qualified.Type.VALUE". */
    private static final Pattern ENUM_CONSTANT = Pattern.compile("No enum constant ([\\w.$]+)\\.([^.]*)$");

    /** {@code NumberFormatException}: "For input string: \"abc\"". */
    private static final Pattern NUMBER_INPUT = Pattern.compile("For input string: \"(.*)\"");

    private static final int MAX_VALUE_LENGTH = 100;

    private final ClientErrorExceptionMapper fallback = new ClientErrorExceptionMapper();

    @Override
    public Response toResponse(NotFoundException exception) {
        if (!isParameterConversionFailure(exception)) {
            return fallback.toResponse(exception);
        }
        return ErrorResponses.badRequest(describe(exception.getCause()));
    }

    static boolean isParameterConversionFailure(NotFoundException exception) {
        Throwable cause = exception.getCause();
        if (cause == null || cause instanceof WebApplicationException) {
            return false;
        }
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (PARAMETER_HANDLER.equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Explains the rejected value without Java type names: the conversion failure's
     * own message names the enum's fully qualified class.
     */
    static String describe(Throwable cause) {
        String raw = cause == null ? null : cause.getMessage();
        if (raw != null) {
            Matcher enumMatch = ENUM_CONSTANT.matcher(raw);
            if (enumMatch.find()) {
                String allowed = allowedValues(enumMatch.group(1));
                return "Invalid parameter value '" + clip(enumMatch.group(2)) + "'"
                        + (allowed != null ? "; expected one of " + allowed : "") + ".";
            }
            Matcher numberMatch = NUMBER_INPUT.matcher(raw);
            if (numberMatch.find()) {
                return "Invalid parameter value '" + clip(numberMatch.group(1)) + "'; expected a number.";
            }
        }
        return "A query or path parameter has a value of the wrong type.";
    }

    private static String allowedValues(String enumClassName) {
        try {
            Class<?> type = Class.forName(enumClassName, false, ParameterConversionExceptionMapper.class.getClassLoader());
            if (!type.isEnum()) {
                return null;
            }
            return Arrays.stream(type.getEnumConstants()).map(String::valueOf)
                    .collect(Collectors.joining(", ", "[", "]"));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static String clip(String value) {
        return value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) + "…" : value;
    }
}
