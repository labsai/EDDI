package ai.labs.parser.model;

import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;

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
