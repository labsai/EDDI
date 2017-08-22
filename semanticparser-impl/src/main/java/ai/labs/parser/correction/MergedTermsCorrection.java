package ai.labs.parser.correction;

import ai.labs.parser.model.IDictionary;

import java.util.ArrayList;
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
    public List<IDictionary.IFoundWord> correctWord(String word) {
        List<IDictionary.IFoundWord> possibleTerms = new ArrayList<>();
        String part;
        for (int i = word.length(); i > 0; i--) {
            part = word.substring(0, i);
            List<IDictionary.IFoundWord> match = matchWord(part);
            if (match.size() > 0) {
                possibleTerms.addAll(match);
                word = word.substring(i, word.length());
                i = word.length() + 1;
            }
        }

        if (!word.isEmpty()) {
            possibleTerms.clear();
            for (int i = 0; i < word.length(); i++) {
                part = word.substring(i, word.length());
                List<IDictionary.IFoundWord> match = matchWord(part);
                if (match.size() > 0) {
                    possibleTerms.addAll(match);
                    word = word.substring(0, i);
                    i = word.length();
                }
            }
        }

        if (word.isEmpty() &&   // all terms are known
                !possibleTerms.isEmpty()) {
            return possibleTerms;
        } else {
            return IDictionary.NO_WORDS_FOUND;
        }
    }

    private List<IDictionary.IFoundWord> matchWord(String part) {
        return dictionaries.stream().
                map(dictionary -> dictionary.lookupTerm(part)).
                filter(result -> result.size() > 0).
                findFirst().orElse(IDictionary.NO_WORDS_FOUND);

    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
