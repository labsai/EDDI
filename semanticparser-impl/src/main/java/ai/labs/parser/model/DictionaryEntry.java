package ai.labs.parser.model;

import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;

/**
 * @author ginccc
 */
public class DictionaryEntry implements IDictionary.IDictionaryEntry {

    protected String value;

    protected Expressions expressions;
    private String identifier;
    private int rating;

    boolean isWord = false;
    boolean isPhrase = false;

    int frequency;

    DictionaryEntry(String value, Expressions expressions) {
        this(value, expressions, "", 0);
    }

    DictionaryEntry(String value, Expressions expressions, String identifier, int rating) {
        this.value = value;
        this.expressions = expressions;
        this.identifier = identifier;
        this.rating = rating;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Expressions getExpressions() {
        return expressions;
    }

    public String getIdentifier() {
        return identifier;
    }

    public boolean isWord() {
        return isWord;
    }

    public boolean isPhrase() {
        return isPhrase;
    }

    @Override
    public int getFrequency() {
        return frequency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DictionaryEntry that = (DictionaryEntry) o;

        if (frequency != that.frequency) return false;
        if (rating != that.rating) return false;
        if (expressions != null ? !expressions.equals(that.expressions) : that.expressions != null) return false;
        if (identifier != null ? !identifier.equals(that.identifier) : that.identifier != null) return false;
        return value != null ? value.equals(that.value) : that.value == null;

    }

    @Override
    public int hashCode() {
        int result = value != null ? value.hashCode() : 0;
        result = 31 * result + (expressions != null ? expressions.hashCode() : 0);
        result = 31 * result + (identifier != null ? identifier.hashCode() : 0);
        result = 31 * result + rating;
        result = 31 * result + frequency;
        return result;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int compareTo(IDictionary.IDictionaryEntry dictionaryEntry) {
        if (frequency == dictionaryEntry.getFrequency()) {
            return value.compareTo(dictionaryEntry.getValue());
        } else {
            return Integer.compare(frequency, dictionaryEntry.getFrequency());
        }
    }
}
