package ai.labs.eddi.configs.properties.model;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;

public class PropertyInstruction extends Property {
    private String fromObjectPath = "";
    private String toObjectPath = "";
    private Boolean convertToObject = false;
    private Boolean override = true;
    private Boolean runOnValidationError = false;
    private HttpCodeValidator httpCodeValidator;

    public PropertyInstruction() {
    }

    public PropertyInstruction(String fromObjectPath, String toObjectPath, Boolean convertToObject, Boolean override, Boolean runOnValidationError, HttpCodeValidator httpCodeValidator) {
        this.fromObjectPath = fromObjectPath;
        this.toObjectPath = toObjectPath;
        this.convertToObject = convertToObject;
        this.override = override;
        this.runOnValidationError = runOnValidationError;
        this.httpCodeValidator = httpCodeValidator;
    }

    public String getFromObjectPath() {
        return fromObjectPath;
    }

    public void setFromObjectPath(String fromObjectPath) {
        this.fromObjectPath = fromObjectPath;
    }

    public String getToObjectPath() {
        return toObjectPath;
    }

    public void setToObjectPath(String toObjectPath) {
        this.toObjectPath = toObjectPath;
    }

    public Boolean getConvertToObject() {
        return convertToObject;
    }

    public void setConvertToObject(Boolean convertToObject) {
        this.convertToObject = convertToObject;
    }

    public Boolean getOverride() {
        return override;
    }

    public void setOverride(Boolean override) {
        this.override = override;
    }

    public Boolean getRunOnValidationError() {
        return runOnValidationError;
    }

    public void setRunOnValidationError(Boolean runOnValidationError) {
        this.runOnValidationError = runOnValidationError;
    }

    public HttpCodeValidator getHttpCodeValidator() {
        return httpCodeValidator;
    }

    public void setHttpCodeValidator(HttpCodeValidator httpCodeValidator) {
        this.httpCodeValidator = httpCodeValidator;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        PropertyInstruction that = (PropertyInstruction) o;
        return java.util.Objects.equals(fromObjectPath, that.fromObjectPath) && java.util.Objects.equals(toObjectPath, that.toObjectPath) && java.util.Objects.equals(convertToObject, that.convertToObject) && java.util.Objects.equals(override, that.override) && java.util.Objects.equals(runOnValidationError, that.runOnValidationError) && java.util.Objects.equals(httpCodeValidator, that.httpCodeValidator);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(super.hashCode(), fromObjectPath, toObjectPath, convertToObject, override, runOnValidationError, httpCodeValidator);
    }
}
