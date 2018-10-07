package ai.labs.utilities;

import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class NumeralConverter {
    public static NumeralConverter instance = null;

    private Hashtable<Integer, CompareNumeralStrings> numbersGerman = new Hashtable<Integer, CompareNumeralStrings>();       // max 9999

    public static NumeralConverter getInstance() {
        if (instance == null)
            instance = new NumeralConverter();

        return instance;
    }

    private NumeralConverter() {
        init();
    }

    private void init() {
        String[] namen = {"", "eins", "zwei", "drei", "vier", "fuenf",
                "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwoelf"};

        for (int i = 0; i < 13; i++) {
            numbersGerman.put(i, new CompareNumeralStrings(i, namen[i]));
        }

        numbersGerman.put(7, new CompareNumeralStrings(7, "sieb")); //wird später rückgänig gemacht

        for (int i = 13; i < 20; i++)
            numbersGerman.put(i, new CompareNumeralStrings(i, numbersGerman.get(i - 10) + "zehn"));

        numbersGerman.put(20, new CompareNumeralStrings(20, "zwanzig"));
        numbersGerman.put(30, new CompareNumeralStrings(30, "dreissig"));

        for (int i = 40; i < 100; i += 10)
            numbersGerman.put(i, new CompareNumeralStrings(i, numbersGerman.get(i / 10) + "zig"));

        numbersGerman.put(7, new CompareNumeralStrings(7, "sieben"));
        numbersGerman.put(1, new CompareNumeralStrings(1, "ein")); //wird später rückgängig gemacht

        for (int i = 21; i < 100; i++) {
            if (i % 10 == 0)
                continue;

            numbersGerman.put(i, new CompareNumeralStrings(i, numbersGerman.get(i % 10) + "und" + numbersGerman.get(i - (i % 10))));
        }

        for (int i = 100; i < 1000; i++)
            numbersGerman.put(i, new CompareNumeralStrings(i, numbersGerman.get(i / 100) + "hundert" + numbersGerman.get(i % 100)));

        for (int i = 1000; i < 10000; i++)
            numbersGerman.put(i, new CompareNumeralStrings(i, numbersGerman.get(i / 1000) + "tausend" + numbersGerman.get(i % 1000)));

        numbersGerman.put(1, new CompareNumeralStrings(1, "eins"));
    }

    public String convertIntegerToNumeral(int number) {
        if (!numbersGerman.contains(number))
            return null;

        return numbersGerman.get(number).toString();
    }

    public int convertNumeralToInteger(String numeral) {
        if (numeral.equals("null"))
            return 0;
        else if (numeral.equals("million"))
            return 1000000;

        List<CompareNumeralStrings> values = new LinkedList<CompareNumeralStrings>(numbersGerman.values());

        Collections.sort(values);

        for (CompareNumeralStrings compare : values)
            if (compare.equals(numeral))
                return compare.number;

        return -1;
    }

    public static void main(String[] args) {
        NumeralConverter converter = NumeralConverter.getInstance();

        System.out.println(converter.convertIntegerToNumeral(95));
        System.out.println(converter.convertNumeralToInteger("fünfundneunzig"));
    }
}

class CompareNumeralStrings implements Comparable<CompareNumeralStrings> {
    Integer number;
    String value;

    CompareNumeralStrings(int number, String value) {
        this.number = number;
        this.value = value;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Integer)
            return this.number.equals(obj);

        if (obj instanceof String)
            return this.value.equals(obj);

        return false;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int compareTo(CompareNumeralStrings o) {
        return Integer.valueOf(number).compareTo(o.number);
    }
}
