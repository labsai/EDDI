package ai.labs.eddi.modules.nlp.model;


import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.regex.Pattern;

/**
 * @author ginccc
 */
public class RegEx extends DictionaryEntry implements IDictionary.IRegEx {
    private final Pattern regEx;

    public RegEx(String value, Expressions expressions) {
        super(value, expressions);
        this.regEx = Pattern.compile(value);
    }

    @Override
    public boolean isPartOfPhrase() {
        return false;
    }

    @Override
    public boolean match(String lookup) {
        return regEx.matcher(lookup).matches();
    }
}
