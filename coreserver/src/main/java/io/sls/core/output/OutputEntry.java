package io.sls.core.output;

import lombok.NoArgsConstructor;

/**
 * @author ginccc
 */
@NoArgsConstructor
public class OutputEntry implements Comparable<OutputEntry> {
    private String key;
    private String text;
    private int occurrence;

    public OutputEntry(String key, String text, int occurrence) {
        this.key = key;
        this.text = text;
        this.occurrence = occurrence;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
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

        OutputEntry that = (OutputEntry) o;

        if (occurrence != that.occurrence) return false;
        if (!key.equals(that.key)) return false;
        return text.equals(that.text);

    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + text.hashCode();
        result = 31 * result + occurrence;
        return result;
    }

    @Override
    public int compareTo(OutputEntry o) {
        return occurrence < o.getOccurrence() ? -1 : (occurrence == o.getOccurrence() ? 0 : 1);
    }

    @Override
    public String toString() {
        return "{" +
                "\"key\"=\"" + key + "\"" +
                ", \"text\"=\"" + text + "\"" +
                ", \"occurrence\"=\"" + occurrence + "\"" +
                "}";
    }
}
