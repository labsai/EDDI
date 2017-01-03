package ai.labs.parser.dictionaries;

import ai.labs.parser.model.IDictionary;

import java.util.Collections;
import java.util.List;


/**
 * @author ginccc
 */
public class MathOperatorDictionary implements IDictionary {
    private static final String ID = "mathOperator";
    private boolean lookupIfKnown;

    public MathOperatorDictionary(boolean lookupIfKnown) {
        this.lookupIfKnown = lookupIfKnown;
    }

    @Override
    public List<IWord> getWords() {
        return Collections.emptyList();
    }

    @Override
    public List<IPhrase> getPhrases() {
        return Collections.emptyList();
    }

    @Override
    public IFoundWord[] lookupTerm(String value) {
        /*Object[][] mathOperators = new Object[][]{{"<=", new ComparisonOperator(ComparisonOperator.LESS_EQUALS)},
                {">=", new ComparisonOperator(ComparisonOperator.GREATER_EQUALS)},
                {"<", new ComparisonOperator(ComparisonOperator.LESS)},
                {">", new ComparisonOperator(ComparisonOperator.GREATER)},
                {"=", new Expression(ConfigProperties.EXPRESSION_NAME_EXACT_VALUE)}};

        for (Object[] s : mathOperators) {
            if (value.equals(s[0])) {
                return new IWord[]{DictionaryUtils.createWord(ID, value, value, s[1].toString())};
            }
        }*/

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public String getLanguage() {
        return ID;
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }
}
