package ai.labs.utilities;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Time;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LanguageUtilities {
    private interface TimeRecognition {
        Pattern p1 = Pattern.compile("^(([0-1]?[0-9]|[2]?2[0-3])(h)([0-5][0-9]))$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL); //e.g. 12h10
        Pattern p2 = Pattern.compile("^(([0-1]?[0-9]|[2]?2[0-3])(h))$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);             //e.g. 15h
        Pattern p3 = Pattern.compile("^([0-1]?[0-9]|[2][0-3]):([0-5][0-9])$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL); // e.g. 19:50
        Pattern p4 = Pattern.compile("(([0-1][0-9])|([2][0-3])):([0-5][0-9]):([0-5][0-9])", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);  // e.g. 13:50:12
    }

    public static Date isTimeExpression(String value) {
        value = value.toLowerCase();

        if (value.contains("h")) {
            if (TimeRecognition.p1.matcher(value).matches()) {
                value = value.replace('h', ':');
            } else if (TimeRecognition.p2.matcher(value).matches()) {
                value = value.substring(0, value.indexOf("h")) + ":00";
            }
        }

        if (value.contains("24:00")) {
            value = "00:00";
        }


        if (TimeRecognition.p3.matcher(value).matches()) {
            return Time.valueOf(value + ":00");
        }

        if (TimeRecognition.p4.matcher(value).matches()) {
            return Time.valueOf(value);
        }

        return null;
    }

    public static String isOrdinalNumber(String value) {
        if (value.length() > 2 && value.matches("[0-9]+[a-z]+")) {
            String tmp2 = value.substring(value.length() - 2, value.length());
            if (tmp2.equals("st") || tmp2.equals("nd") || tmp2.equals("rd") || tmp2.equals("th")) {
                tmp2 = value.substring(0, value.length() - 2);
                try {
                    int i = Integer.parseInt(tmp2);
                    //if(i > 0 && i < 24)
                    return String.valueOf(i);
                } catch (NumberFormatException nfe) {
                    log.error(nfe.getLocalizedMessage(), nfe);
                    return null;
                }
            }
        } else if (Pattern.compile("(\\d{0,2})(\\.)").matcher(value).matches()) {
            return value.substring(0, value.indexOf(""));
        }

        return null;
    }


    public static String buildOutputReference(String output, String expressions) {
        StringBuilder ret = new StringBuilder();

        if (output == null)
            output = "";

        if (expressions == null)
            expressions = "";

        if (!output.equals(""))
            ret.append("out:").append(output);

        if (!output.equals("") && !expressions.equals(""))
            ret.append("|");

        if (!expressions.equals(""))
            ret.append("exp:").append(expressions);

        return CharacterUtilities.wrapStringWithSquaredBrackets(ret.toString());
    }

    public static int containsArray(List lookup, List base) {
        return containsArray(lookup.toArray(), base.toArray());
    }

    public static int containsArray(Object[] lookup, Object[] base) {
        if (lookup.length <= base.length) {
            Object[] tmp = new Object[lookup.length];
            boolean isEqual;

            for (int i = 0; i < base.length && base.length - i >= lookup.length; i++) {
                System.arraycopy(base, i, tmp, 0, lookup.length);

                isEqual = true;
                for (int k = 0; k < tmp.length; k++) {
                    if (!lookup[k].equals(tmp[k])) {
                        isEqual = false;
                        break;
                    }
                }

                if (isEqual) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * Builds a String like "rot, gruen, silber oder blau"
     * out of a list with those properties and the operator "oder"
     */
    public static String buildListing(List<String> elements, String connector) {
        StringBuilder result = new StringBuilder("");

        if (elements.isEmpty())
            return "";

        if (elements.size() == 1)
            return elements.get(0);

        for (int i = 0; i < elements.size() - 1; i++)
            result.append(elements.get(i)).append(", ");

        result.delete(result.lastIndexOf(","), result.length());
//		result = new StringBuilder(result.substring(0, result.length() - 2));
        result.append(" ").append(connector).append(" ");
        result.append(elements.get(elements.size() - 1));
        return result.toString();
    }
}
