package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.model.*;
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
    private String languageCode;
    private List<IPhrase> phrases = new LinkedList<>();
    private Map<String, IWord> words = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<IRegEx> regExs = new LinkedList<>();

    private boolean lookupIfKnown;

    @Override
    public List<IWord> getWords() {
        List<IWord> allWords = new LinkedList<>(words.values());
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
                filter(partOfPhrase -> partOfPhrase.getValue().equals(lookup)).
                map(partOfPhrase -> new FoundWord(partOfPhrase, false, 1.0)).
                collect(Collectors.toList());

        phrases.stream().flatMap(phrase -> phrase.getWords().stream()).
                filter(partOfPhrase -> partOfPhrase.getValue().equalsIgnoreCase(lookup)).
                map(partOfPhrase -> new FoundWord(partOfPhrase, false, 0.9)).
                collect(Collectors.toList()).stream().filter(foundWord -> !ret.contains(foundWord)).forEach(ret::add);

        IWord word;
        if ((word = words.get(lookup)) != null) {
            boolean isCaseSensitiveMatch = words.keySet().stream().parallel().anyMatch(key -> key.equals(lookup));
            ret.add(new FoundWord(word, !isCaseSensitiveMatch, isCaseSensitiveMatch ? 1.0 : 0.9));
        }

        if (ret.isEmpty() || lookupIfKnown) {
            regExs.stream().
                    filter(regEx -> regEx.match(lookup)).
                    forEach(regEx ->
                            ret.add(new FoundRegEx(new Word(lookup, regEx.getExpressions(), regEx.getLanguageCode()), regEx))
                    );
        }

        return ret;
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    public void addWord(final String value, final Expressions expressions, int rating) {
        words.put(value, new Word(value, expressions, ID, rating, false));
    }

    public void addRegex(final String regEx, Expressions expressions) {
        regExs.add(new RegEx(regEx, expressions));
    }

    public void addPhrase(String value, Expressions expressions) {
        phrases.add(new Phrase(value, expressions, ID));
    }
}
