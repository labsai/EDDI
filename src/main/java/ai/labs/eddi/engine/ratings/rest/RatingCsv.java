/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.engine.ratings.model.ConversationRating;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV rendering of ratings for the export.
 *
 * <h3>Formula injection</h3> A comment is text a user typed, and the export is
 * opened by administrators in a spreadsheet. A cell beginning with {@code =},
 * {@code +}, {@code -}, {@code @}, a tab or a carriage return would be read as
 * a formula there ({@code =HYPERLINK(…)}, DDE), so every such text cell is
 * prefixed with an apostrophe. Numbers EDDI itself writes are left alone. Every
 * text cell is then quoted per RFC 4180, so commas, quotes and line breaks in a
 * comment cannot shift columns.
 */
public final class RatingCsv {

    private static final List<String> BASE_COLUMNS = List.of("id", "conversationId", "stepIndex", "kind", "agentId", "agentVersion",
            "environment", "channel", "scale", "score", "normalizedScore", "tags", "comment", "createdAt", "updatedAt", "reviewStatus", "reviewedBy",
            "reviewedAt", "reviewNote");

    private RatingCsv() {
    }

    /** The header row, with {@code userId} only when it is included. */
    public static String header(boolean includeUserId) {
        List<String> columns = new ArrayList<>(BASE_COLUMNS);
        if (includeUserId) {
            columns.add("userId");
        }
        return String.join(",", columns) + "\r\n";
    }

    /** One rating as a CSV row, terminated by CRLF. */
    public static String row(ConversationRating rating, boolean includeUserId) {
        List<String> cells = new ArrayList<>();
        cells.add(text(rating.id()));
        cells.add(text(rating.conversationId()));
        cells.add(Integer.toString(rating.stepIndex()));
        cells.add(text(rating.kind().value()));
        cells.add(text(rating.agentId()));
        cells.add(rating.agentVersion() == null ? "" : rating.agentVersion().toString());
        cells.add(text(rating.environment()));
        cells.add(text(rating.channel()));
        cells.add(text(rating.scale() == null ? null : rating.scale().name()));
        cells.add(Integer.toString(rating.score()));
        cells.add(Double.toString(rating.normalizedScore()));
        cells.add(text(String.join("|", rating.tags())));
        cells.add(text(rating.comment()));
        cells.add(text(rating.createdAt() == null ? null : rating.createdAt().toString()));
        cells.add(text(rating.updatedAt() == null ? null : rating.updatedAt().toString()));
        cells.add(text(rating.reviewStatus() == null ? null : rating.reviewStatus().name()));
        cells.add(text(rating.reviewedBy()));
        cells.add(text(rating.reviewedAt() == null ? null : rating.reviewedAt().toString()));
        cells.add(text(rating.reviewNote()));
        if (includeUserId) {
            cells.add(text(rating.userId()));
        }
        return String.join(",", cells) + "\r\n";
    }

    /**
     * A text cell: neutralised against formula injection, then quoted. Null is an
     * empty cell.
     */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = startsLikeFormula(value) ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    static boolean startsLikeFormula(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
