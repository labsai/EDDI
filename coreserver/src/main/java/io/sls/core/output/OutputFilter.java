package io.sls.core.output;

/**
 * @author ginccc
 */
public class OutputFilter implements IOutputFilter {
    private String key;
    private int occurence;

    public OutputFilter(String key, int occurence) {
        this.key = key;
        this.occurence = occurence;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public int getOccurence() {
        return occurence;
    }
}
