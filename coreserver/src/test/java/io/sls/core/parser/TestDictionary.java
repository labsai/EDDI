package io.sls.core.parser;

import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 02.11.12
 * Time: 11:50
 */
public class TestDictionary implements IDictionary {
    private List<IWord> words = new LinkedList<IWord>();
    private List<IPhrase> phrases = new LinkedList<IPhrase>();

    @Override
    public List<IWord> getWords() {
        LinkedList<IWord> words = new LinkedList<IWord>(this.words);
        for (IPhrase phrase : phrases) {
            words.addAll(Arrays.asList(phrase.getWords()));
        }
        return words;
    }

    @Override
    public List<IPhrase> getPhrases() {
        return phrases;
    }

    @Override
    public IFoundWord[] lookupTerm(String value) {
        List<IFoundWord> foundWords = new LinkedList<IFoundWord>();

        for (IWord word : getWords()) {
            if (word.getValue().equals(value))  {
                foundWords.add(new FoundWord(word, false, 1.0));
            }
        }

        return foundWords.toArray(new IFoundWord[foundWords.size()]);
    }

    @Override
    public String getLanguage() {
        return "testDictionary";
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }

    public void addWord(IWord word) {
        this.words.add(word);
    }

    public void addPhrase(IPhrase phrase) {
        this.phrases.add(phrase);
    }
}
