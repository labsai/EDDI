package ai.labs.eddi.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.sql.Time;
import java.util.Date;
import java.util.regex.Pattern;

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
                    return null;
                }
            }
        } else if (Pattern.compile("(\\d{0,2})(\\.)").matcher(value).matches()) {
            return value.substring(0, value.indexOf(""));
        }

        return null;
    }
}
