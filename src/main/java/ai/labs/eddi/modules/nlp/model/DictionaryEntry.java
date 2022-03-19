package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.Objects;

/**
 * @author ginccc
 */
public class DictionaryEntry implements IDictionary.IDictionaryEntry {

    protected String value;

    protected Expressions expressions;
    private String languageCode;
    private int rating;

    boolean isWord = false;
    boolean isPhrase = false;

    int frequency;

    DictionaryEntry(String value, Expressions expressions) {
        this(value, expressions, "en", 0);
    }

    DictionaryEntry(String value, Expressions expressions, String languageCode, int rating) {
        this.value = value;
        this.expressions = expressions;
        this.languageCode = languageCode;
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

    public String getLanguageCode() {
        return languageCode;
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
        if (!Objects.equals(expressions, that.expressions)) return false;
        if (!Objects.equals(languageCode, that.languageCode)) return false;
        return Objects.equals(value, that.value);

    }

    @Override
    public int hashCode() {
        int result = value != null ? value.hashCode() : 0;
        result = 31 * result + (expressions != null ? expressions.hashCode() : 0);
        result = 31 * result + (languageCode != null ? languageCode.hashCode() : 0);
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
