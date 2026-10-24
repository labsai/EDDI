/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.ws.rs.QueryParam;

/**
 * The filter every {@code /ratings} endpoint accepts, as one bean so the list,
 * summary, export, bulk review and attention count cannot drift apart.
 * <p>
 * {@code from} / {@code to} take an ISO-8601 instant or a date (read as UTC);
 * {@code to} is exclusive, a date there includes that whole day.
 * {@code minScore} / {@code maxScore} are normalized scores (0..1).
 * {@code reviewStatus} is {@code NEW} or {@code REVIEWED};
 * {@code needsAttention=true} keeps the {@code NEW} ratings that are low
 * (normalized at most 0.5) or carry a comment.
 */
public class RatingQueryParams {

    @QueryParam("agentId")
    public String agentId;

    @QueryParam("agentVersion")
    public Integer agentVersion;

    @QueryParam("environment")
    public String environment;

    @QueryParam("from")
    public String from;

    @QueryParam("to")
    public String to;

    @QueryParam("minScore")
    public Double minScore;

    @QueryParam("maxScore")
    public Double maxScore;

    /** {@code conversation} or {@code message}. */
    @QueryParam("kind")
    public String kind;

    @QueryParam("tag")
    public String tag;

    @QueryParam("hasComment")
    public Boolean hasComment;

    @QueryParam("reviewStatus")
    public String reviewStatus;

    @QueryParam("needsAttention")
    public Boolean needsAttention;

    /**
     * The ratings of one conversation only. Scoping still applies: an editor names
     * its agent too.
     */
    @QueryParam("conversationId")
    public String conversationId;

    /** Ratings of one agent; the other fields stay unset. */
    public static RatingQueryParams ofAgent(String agentId) {
        RatingQueryParams params = new RatingQueryParams();
        params.agentId = agentId;
        return params;
    }
}
