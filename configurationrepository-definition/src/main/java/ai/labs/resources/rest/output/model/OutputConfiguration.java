package ai.labs.resources.rest.output.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class OutputConfiguration {
    private String key;
    private List<String> outputValues;
    private int occurrence;

    public OutputConfiguration(String key, int occurrence, String... values) {
        this.key = key;
        this.occurrence = occurrence;
        this.outputValues = new ArrayList<>();
        outputValues.addAll(Arrays.asList(values));
    }

    public OutputConfiguration() {
        this.outputValues = new ArrayList<>();
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
