/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Objects;

public class ImageOutputItem extends OutputItem {
    private String uri;
    private String alt;

    public ImageOutputItem() {
        initType();
    }

    public ImageOutputItem(String uri, String alt) {
        initType();
        this.uri = uri;
        this.alt = alt;
    }

    @Override
    protected void initType() {
        super.type = "image";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ImageOutputItem that = (ImageOutputItem) o;
        return Objects.equals(uri, that.uri) && Objects.equals(alt, that.alt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, alt);
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
}
