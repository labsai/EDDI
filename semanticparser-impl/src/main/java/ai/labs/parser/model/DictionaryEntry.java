package ai.labs.parser.model;

import ai.labs.expressions.Expression;

import java.util.List;

/**
 * @author ginccc
 */
public class DictionaryEntry implements IDictionary.IDictionaryEntry {

    protected String value;

    protected List<Expression> expressions;
    protected String identifier;
    protected int rating;

    protected boolean isWord = false;
    protected boolean isPhrase = false;

    protected int frequency;

    public DictionaryEntry(String value, List<Expression> expressions) {
        this(value, expressions, "", 0);
    }

    public DictionaryEntry(String value, List<Expression> expressions, String identifier, int rating) {
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

    public List<Expression> getExpressions() {
        return expressions;
    }

    public String getIdentifier() {
        return identifier;
    }

    public int getRating() {
        return rating;
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
            return frequency < dictionaryEntry.getFrequency() ? -1 : frequency == dictionaryEntry.getFrequency() ? 0 : 1;
        }
    }
}
