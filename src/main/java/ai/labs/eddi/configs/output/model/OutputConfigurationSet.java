package ai.labs.eddi.configs.output.model;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

public class OutputConfigurationSet {
    private String lang;
    private List<OutputConfiguration> outputSet = new ArrayList<>();

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public List<OutputConfiguration> getOutputSet() {
        return outputSet;
    }

    public void setOutputSet(List<OutputConfiguration> outputSet) {
        this.outputSet = outputSet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        OutputConfigurationSet that = (OutputConfigurationSet) o;
        return java.util.Objects.equals(lang, that.lang) && java.util.Objects.equals(outputSet, that.outputSet);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(lang, outputSet);
    }
}
