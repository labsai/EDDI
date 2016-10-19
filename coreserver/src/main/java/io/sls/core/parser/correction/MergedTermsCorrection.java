package io.sls.core.parser.correction;

import io.sls.core.parser.model.IDictionary;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 26.11.2010
 * Time: 15:51:13
 */
public class MergedTermsCorrection implements ICorrection {
    private List<IDictionary> dictionaries;

    @Override
    public void init(List<IDictionary> dictionaries) {
        this.dictionaries = dictionaries;
    }

    @Override
    public IDictionary.IFoundWord[] correctWord(String word) {
        LinkedList<IDictionary.IFoundWord> possibleTerms = new LinkedList<IDictionary.IFoundWord>();
        String part;
        for (int i = word.length(); i > 0; i--) {
            part = word.substring(0, i);
            IDictionary.IFoundWord[] match = matchWord(part);
            if (match.length > 0) {
                Collections.addAll(possibleTerms, match);
                word = word.substring(i, word.length());
                i = word.length() + 1;
            }
        }

        if (!word.isEmpty()) {
            possibleTerms.clear();
            for (int i = 0; i < word.length(); i++) {
                part = word.substring(i, word.length());
                IDictionary.IFoundWord[] match = matchWord(part);
                if (match.length > 0) {
                    Collections.addAll(possibleTerms, match);
                    word = word.substring(0, i);
                    i = word.length();
                }
            }
        }

        if (word.isEmpty() &&   // all terms are known
                !possibleTerms.isEmpty()) {
           return possibleTerms.toArray(new IDictionary.IFoundWord[possibleTerms.size()]);
        }else {
            return new IDictionary.IFoundWord[0];
        }
    }

    private IDictionary.IFoundWord[] matchWord(String part) {
        for (IDictionary dictionary : dictionaries) {
            IDictionary.IFoundWord[] result = dictionary.lookupTerm(part);
            if(result.length > 0) {
                return result;
            }
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
