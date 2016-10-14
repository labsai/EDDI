package io.sls.resources.rest.output.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: jarisch
 * Date: 09.08.12
 * Time: 16:41
 */
public class OutputConfiguration {
    private String key;
    private List<String> outputValues;
    private int occurrence;

    public OutputConfiguration(String key, int occurrence, String... values) {
        this.key = key;
        this.occurrence = occurrence;
        this.outputValues = new ArrayList<String>();
        outputValues.addAll(Arrays.asList(values));
    }

    public OutputConfiguration() {
        this.outputValues = new ArrayList<String>();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<String> getOutputValues() {
        return outputValues;
    }

    public void setOutputValues(List<String> outputValues) {
        this.outputValues = outputValues;
    }

    public int getOccurrence() {
        return occurrence;
    }

    public void setOccurrence(int occurrence) {
        this.occurrence = occurrence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OutputConfiguration that = (OutputConfiguration) o;

        return key.equals(that.key) && occurrence == that.occurrence;
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + occurrence;
        return result;
    }
}
