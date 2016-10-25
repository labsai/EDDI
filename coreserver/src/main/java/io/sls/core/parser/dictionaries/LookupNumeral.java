package io.sls.core.parser.dictionaries;

import io.sls.core.parser.internal.InputHolder;

/**
 * @author ginccc
 */
public class LookupNumeral {
    private String[] numbers = new String[]{"null", "ein", "zwei", "drei", "vier", "fuenf",
            "sechs", "sieb", "acht", "neun", "zehn", "elf", "zwoelf",
            "zwanzig", "hundert", "tausend", "million"};

    public boolean processAnalysis(InputHolder holder) {
        /*String current = holder.input[holder.index].toLowerCase();

        boolean hasNumber = false;
        for (String number : numbers)
            if (current.contains(number)) {
                hasNumber = true;
                break;
            }

        if (hasNumber) {
            NumeralConverter converter = NumeralConverter.getInstance();
            int number;
            if ((number = converter.convertNumeralToInteger(current)) != -1) {
                String foundValue = String.valueOf(number);
                List<Expression> expressions = Arrays.asList(ExpressionUtilities.createExpression("integer", number));
                DictionaryEntry foundInteger = new DictionaryEntry(foundValue, foundValue, expressions);
                holder.addMeaning(foundInteger);

                return true;
            }
        }
*/
        return false;
    }
}
