/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;
import java.util.Objects;

/**
 * @author ginccc
 */
public class QuickReply {
    private String value;
    private String expressions;
    private Boolean isDefault;

    @Override
    public String toString() {
        return "QuickReply{" + "value='" + value + '\'' + ", expressions='" + expressions + '\'' + ", isDefault=" + isDefault + '}';
    }

    public QuickReply() {
    }

    public QuickReply(String value, String expressions, Boolean isDefault) {
        this.value = value;
        this.expressions = expressions;
        this.isDefault = isDefault;
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

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        QuickReply that = (QuickReply) o;
        return Objects.equals(value, that.value) && Objects.equals(expressions, that.expressions)
                && Objects.equals(isDefault, that.isDefault);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, expressions, isDefault);
    }
}
