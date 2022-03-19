package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import lombok.NoArgsConstructor;

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
        phrases.stream().map(IPhrase::getWords).forEach(words::addAll);
        return words;
    }

    @Override
    public List<IPhrase> getPhrases() {
        return phrases;
    }

    @Override
    public List<IFoundWord> lookupTerm(String value) {
        List<IFoundWord> foundWords = new LinkedList<>();

        for (IWord word : getWords()) {
            if (word.getValue().equals(value)) {
                foundWords.add(new FoundWord(word, false, 1.0));
            }
        }

        return foundWords;
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
