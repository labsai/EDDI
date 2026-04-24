/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Objects;

public class QuickReplyOutputItem extends OutputItem {
    private String value;
    private String expressions;
    private Boolean isDefault;

    public QuickReplyOutputItem() {
        initType();
    }

    public QuickReplyOutputItem(String value, String expressions, Boolean isDefault) {
        initType();
        this.value = value;
        this.expressions = expressions;
        this.isDefault = isDefault;
    }

    @Override
    protected void initType() {
        super.type = "quickReply";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        QuickReplyOutputItem that = (QuickReplyOutputItem) o;
        return Objects.equals(value, that.value) && Objects.equals(expressions, that.expressions) && Objects.equals(isDefault, that.isDefault);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, expressions, isDefault);
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getExpressions() {
        return expressions;
    }

    public void setExpressions(String expressions) {
        this.expressions = expressions;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }
}
