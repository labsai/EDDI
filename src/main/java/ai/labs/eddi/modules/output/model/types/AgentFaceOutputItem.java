/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Objects;
import java.util.function.UnaryOperator;

public class AgentFaceOutputItem extends OutputItem {

    /** The canonical v6 type id — the only id ever written. */
    public static final String TYPE_ID = "agentFace";

    /**
     * The v5 type id this item shipped under. Registered on {@link OutputItem} as a
     * read-only {@code @JsonSubTypes} alias so stored 5.x output sets stay
     * loadable, and canonicalized away in {@link #setType(String)} so it is never
     * written back out or sent to a client.
     * <p>
     * <strong>The alias is permanent — do not retire it as "already
     * migrated".</strong> {@code LegacyDocumentMigrations.output()} does rewrite
     * the id, but its only stored-document caller is the MongoDB startup sweep, and
     * {@code MigrationManager.startMigrationIfFirstTimeRun} runs that sweep only
     * while the {@code MIGRATION_CONFIRMATION} log row is absent. Every deployment
     * that has already started once has that row, so it never sweeps again;
     * {@code PostgresMigrationManager} has no sweep at all. The rewrite therefore
     * reaches only never-migrated MongoDB databases and freshly imported ZIPs — not
     * the installed base that holds the {@code botFace} documents. A stored
     * document is normalized when something happens to re-save it, and until then
     * this alias is the only thing that keeps it readable.
     */
    public static final String LEGACY_TYPE_ID = "botFace";

    private String uri;
    private String alt;
    private int delay;

    public AgentFaceOutputItem() {
        initType();
    }

    public AgentFaceOutputItem(String uri, String alt, int delay) {
        initType();
        this.uri = uri;
        this.alt = alt;
        this.delay = delay;
    }

    @Override
    protected void initType() {
        super.type = TYPE_ID;
    }

    /**
     * Canonicalizes the retired {@link #LEGACY_TYPE_ID} discriminator.
     * {@code @JsonTypeInfo(visible = true, include = EXISTING_PROPERTY)} feeds the
     * raw type id from the document straight into this setter, and with
     * {@code EXISTING_PROPERTY} Jackson writes back whatever the property holds
     * rather than the registered id — so without this, reading a v5 document would
     * push {@code botFace} back into storage and out to clients.
     */
    @Override
    public void setType(String type) {
        super.setType(LEGACY_TYPE_ID.equals(type) ? TYPE_ID : type);
    }

    @Override
    protected OutputItem templatedCopy(UnaryOperator<String> templating) {
        return new AgentFaceOutputItem(templating.apply(uri), templating.apply(alt), delay);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AgentFaceOutputItem that = (AgentFaceOutputItem) o;
        return delay == that.delay && Objects.equals(uri, that.uri) && Objects.equals(alt, that.alt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, alt, delay);
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getAlt() {
        return alt;
    }

    public void setAlt(String alt) {
        this.alt = alt;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }
}
