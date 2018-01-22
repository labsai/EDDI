package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Phrase;
import ai.labs.parser.model.Word;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@Getter
@Setter
public class RegularDictionary implements IDictionary {
    private static final String ID = "regular";
    private Map<String, IWord> words = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<IPhrase> phrases = new LinkedList<>();

    private boolean lookupIfKnown;

    @Override
    public List<IWord> getWords() {
        List<IWord> allWords = new LinkedList<>();
        allWords.addAll(words.values());

        phrases.stream().map(IPhrase::getWords).forEach(allWords::addAll);

        return Collections.unmodifiableList(allWords);
    }

    @Override
    public List<IPhrase> getPhrases() {
        return Collections.unmodifiableList(phrases);
    }

    @Override
    public List<IFoundWord> lookupTerm(String lookup) {
        List<IFoundWord> ret = phrases.stream().flatMap(phrase -> phrase.getWords().stream()).
                filter(partOfPhrase -> partOfPhrase.getValue().equalsIgnoreCase(lookup)).
                map(partOfPhrase -> new FoundWord(partOfPhrase, false, 1.0)).
                collect(Collectors.toList());

        IWord word;
        if ((word = words.get(lookup)) != null) {
            boolean isCaseSensitiveMatch = words.keySet().stream().parallel().anyMatch(key -> key.equals(lookup));
            ret.add(new FoundWord(word, !isCaseSensitiveMatch, isCaseSensitiveMatch ? 1.0 : 0.9));
        }

        return ret;
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    public void addWord(final String value, final List<Expression> expressions, int rating) {
        words.put(value, new Word(value, expressions, ID, rating, false));
    }

    public void addPhrase(String value, List<Expression> expressions) {
        phrases.add(new Phrase(value, expressions, ID));
    }
}
