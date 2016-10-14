package io.sls.core.output;

/**
 * Spoken Language System. Core.
 * User: jarisch
 * Date: 12.03.12
 * Time: 16:35
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
