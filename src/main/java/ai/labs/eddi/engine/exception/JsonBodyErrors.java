/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Explains why a JSON request body could not be read, in words for whoever
 * wrote the JSON.
 * <p>
 * The framework answers every body it cannot read — malformed syntax, a value
 * of the wrong shape, a document nested past the parser's depth limit — with a
 * bare 400 and an empty body in production. The sender is left guessing which
 * of the three it was and where. These messages name the JSON path (or line and
 * column) and what belongs there, and never a Java type, a package or Jackson's
 * own wording, which spells out internal class names.
 */
final class JsonBodyErrors {

    private JsonBodyErrors() {
    }

    static String describe(JsonProcessingException e) {
        // The databind layer wraps a parser failure (a syntax error, a parser limit) in
        // a plain DatabindException; the cause is the one that says what went wrong.
        if (e instanceof DatabindException && !(e instanceof MismatchedInputException)
                && e.getCause() instanceof JsonProcessingException cause && cause != e) {
            return describe(cause);
        }
        if (e instanceof StreamConstraintsException) {
            // Its message names StreamReadConstraints' getter; the limits themselves are
            // what the sender needs to know about.
            return "The request body exceeds the JSON parser's limits (nesting depth, document size, "
                    + "or the length of a single number or string).";
        }
        if (e instanceof UnrecognizedPropertyException unknown) {
            String path = StrictConfigurationParser.jsonPath(unknown);
            return "Unknown field '" + unknown.getPropertyName() + "'" + (path.isEmpty() ? "" : " at " + path) + ".";
        }
        if (e instanceof InvalidTypeIdException typeId) {
            String path = StrictConfigurationParser.jsonPath(typeId);
            return "Cannot read the request body" + (path.isEmpty() ? "" : " at " + path) + ": "
                    + (typeId.getTypeId() == null || typeId.getTypeId().isBlank()
                            ? "expected an object with a 'type' field."
                            : "'" + typeId.getTypeId() + "' is not a known 'type'.");
        }
        if (e instanceof MismatchedInputException mismatch) {
            String path = StrictConfigurationParser.jsonPath(mismatch);
            if (path.isEmpty() && mismatch.getMessage() != null && mismatch.getMessage().startsWith("No content to map")) {
                return "The request body is empty; a JSON document is required.";
            }
            return "Cannot read the request body" + (path.isEmpty() ? "" : " at " + path) + ": "
                    + StrictConfigurationParser.expected(mismatch.getTargetType()) + ".";
        }
        if (e instanceof StreamReadException syntax) {
            JsonLocation location = syntax.getLocation();
            String where = location != null && location.getLineNr() > 0
                    ? " at line " + location.getLineNr() + ", column " + location.getColumnNr()
                    : "";
            String detail = syntax.getOriginalMessage();
            return "The request body is not valid JSON" + where
                    + (detail != null && !detail.isBlank() ? ": " + firstLine(detail) : ".");
        }
        return "The request body could not be read as JSON.";
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline >= 0 ? text.substring(0, newline) : text;
        // Jackson appends where an enclosing array or object started, as a source
        // description naming its own configuration features: noise for the sender.
        int source = line.indexOf(" (for ");
        if (source > 0) {
            line = line.substring(0, source);
        }
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
