package ai.labs.eddi.modules.output.model;

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
        return java.util.Objects.equals(value, that.value) && java.util.Objects.equals(expressions, that.expressions)
                && java.util.Objects.equals(isDefault, that.isDefault);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(value, expressions, isDefault);
    }
}
