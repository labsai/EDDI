package io.sls.core.parser.dictionaries;

import io.sls.core.parser.model.*;
import io.sls.expressions.Expression;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: michael
 * Date: 24.01.2012
 * Time: 17:39:45
 */
public class RegularDictionary implements IDictionary {
    private String language;

    private LinkedHashMap<String, IWord> words = new LinkedHashMap<String, IWord>();
    private List<IPhrase> phrases = new ArrayList<IPhrase>();
    private boolean lookupIfKnown;

    public RegularDictionary(String language, boolean lookupIfKnown) {
        this.language = language;
        this.lookupIfKnown = lookupIfKnown;
    }

    @Override
    public List<IWord> getWords() {
        List<IWord> allWords = new LinkedList<IWord>();
        allWords.addAll(words.values());

        for (IPhrase phrase : phrases) {
            Collections.addAll(allWords, phrase.getWords());
        }

        return Collections.unmodifiableList(allWords);
    }

    @Override
    public List<IPhrase> getPhrases() {
        return Collections.unmodifiableList(phrases);
    }

    @Override
    public IFoundWord[] lookupTerm(String lookup) {
        List<IFoundWord> ret = new ArrayList<IFoundWord>();

        for (IPhrase phrase : phrases) {
            for (IWord partOfPhrase : phrase.getWords()) {
                if (partOfPhrase.getValue().equalsIgnoreCase(lookup)) {
                    ret.add(new FoundWord(partOfPhrase, false, 1.0));
                }
            }
        }

        if (words.containsKey(lookup)) {
            ret.add(new FoundWord(words.get(lookup.toLowerCase()), false, 1.0));
        }

        return ret.toArray(new IFoundWord[ret.size()]);
    }

    @Override
    public String getLanguage() {
        return language;
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    public void addWord(final String value, final List<Expression> expressions, int rating) {
        words.put(value, new Word(value, expressions, getLanguage(), rating, false));
    }

    public void addPhrase(String value, List<Expression> expressions) {
        phrases.add(new Phrase(value, expressions, ""));
    }
}
