package ai.labs.parser;

import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
public class TestDictionary implements IDictionary {
    private List<IWord> words = new LinkedList<>();
    private List<IPhrase> phrases = new LinkedList<>();
    private boolean lookupIfKnow = false;

    TestDictionary(boolean lookupIfKnow) {
        this.lookupIfKnow = lookupIfKnow;
    }

    @Override
    public List<IWord> getWords() {
        LinkedList<IWord> words = new LinkedList<>(this.words);
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
        List<IFoundWord> foundWords = new LinkedList<>();

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
        return lookupIfKnow;
    }

    void addWord(IWord word) {
        this.words.add(word);
    }

    void addPhrase(IPhrase phrase) {
        this.phrases.add(phrase);
    }
}
