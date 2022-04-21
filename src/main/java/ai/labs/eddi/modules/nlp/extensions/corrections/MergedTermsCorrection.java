package ai.labs.eddi.modules.nlp.extensions.corrections;


import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class MergedTermsCorrection implements ICorrection {
    private List<IDictionary> dictionaries;

    @Override
    public void init(List<IDictionary> dictionaries) {
        this.dictionaries = dictionaries;
    }


    @Override
    public List<IDictionary.IFoundWord> correctWord(String word, String userLanguage, List<IDictionary> temporaryDictionaries) {
        List<IDictionary.IFoundWord> possibleTerms = new ArrayList<>();
        String part;
        String tmpWord = word;
        for (int i = tmpWord.length(); i > 0; i--) {
            part = tmpWord.substring(0, i);
            List<IDictionary.IFoundWord> match = matchWord(part, temporaryDictionaries);
            if (match.size() > 0) {
                possibleTerms.addAll(match);
                tmpWord = tmpWord.substring(i);
                i = tmpWord.length() + 1;
            }
        }

        if (!tmpWord.isEmpty()) {
            possibleTerms.clear();
            for (int i = 0; i < tmpWord.length(); i++) {
                part = tmpWord.substring(i);
                List<IDictionary.IFoundWord> match = matchWord(part, temporaryDictionaries);
                if (match.size() > 0) {
                    possibleTerms.addAll(match);
                    tmpWord = tmpWord.substring(0, i);
                    i = tmpWord.length();
                }
            }
        }

        if (tmpWord.isEmpty() &&   // all terms are known
                !possibleTerms.isEmpty()) {
            return possibleTerms;
        } else {
            return IDictionary.NO_WORDS_FOUND;
        }
    }

    private List<IDictionary.IFoundWord> matchWord(String part, List<IDictionary> temporaryDictionaries) {
        List<IDictionary> allDictionaries = new LinkedList<>();
        allDictionaries.addAll(temporaryDictionaries);
        allDictionaries.addAll(dictionaries);

        return allDictionaries.stream().
                map(dictionary -> dictionary.lookupTerm(part)).
                filter(result -> result.size() > 0).
                findFirst().orElse(IDictionary.NO_WORDS_FOUND);

    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
