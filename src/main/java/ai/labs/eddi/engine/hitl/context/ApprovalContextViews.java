/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a persisted {@link RenderedApprovalContext} is served: unchanged text,
 * <b>re-redacted through the current filter at serve time</b> (the context was
 * redacted once, when the pause was committed, with whatever filter existed
 * then — the same discipline as the redacted tool arguments), as one shared
 * shape for REST and MCP so the two surfaces cannot drift.
 */
public final class ApprovalContextViews {

    private ApprovalContextViews() {
    }

    /**
     * The view served under {@code approvalContext}: {@code {items:[{label, text,
     * truncated, source, callId, error}], truncated, digest}}, or {@code null} when
     * there is nothing to show.
     */
    public static Map<String, Object> toView(RenderedApprovalContext context) {
        RenderedApprovalContext served = reRedacted(context);
        if (served == null) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item item : served.getItems()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("label", item.getLabel());
            view.put("text", item.getText());
            view.put("truncated", item.isTruncated());
            view.put("source", item.getSource());
            view.put("callId", item.getCallId());
            view.put("error", item.getError());
            items.add(view);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("items", items);
        view.put("truncated", served.isTruncated());
        view.put("digest", served.getDigest());
        return view;
    }

    /**
     * A copy of {@code context} with every label and text passed through reference
     * masking and the current {@code SecretRedactionFilter}; the digest is kept as
     * stored (it identifies what was rendered, not what is served). {@code null}
     * for a null or empty context.
     */
    public static RenderedApprovalContext reRedacted(RenderedApprovalContext context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        RenderedApprovalContext copy = new RenderedApprovalContext();
        List<Item> items = new ArrayList<>();
        for (Item item : context.getItems()) {
            items.add(new Item(ApprovalContextRenderer.clean(item.getLabel()), ApprovalContextRenderer.clean(item.getText()), item.isTruncated(),
                    item.getSource(), item.getCallId(), item.getError()));
        }
        copy.setItems(items);
        copy.setTruncated(context.isTruncated());
        copy.setDigest(context.getDigest());
        return copy;
    }
}
