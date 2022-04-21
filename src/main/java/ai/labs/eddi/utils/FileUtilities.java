package ai.labs.eddi.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public final class FileUtilities {
    private static final String lineSeparator = System.getProperty("line.separator");

    private FileUtilities() {
        //utility class
    }

    public static String readTextFromFile(File file) throws IOException {
        BufferedReader rd = null;
        StringBuilder ret = new StringBuilder();
        try {
            rd = new BufferedReader(new FileReader(file));
            while (rd.ready()) {
                ret.append(rd.readLine());
                ret.append(lineSeparator);
            }

            return ret.toString();

        } finally {
            if (rd != null) {
                try {
                    rd.close();
                } catch (IOException e) {
                    //do nothing
                }
            }
        }
    }

    public static String buildPath(String... directories) {
        StringBuilder ret = new StringBuilder();
        for (String directory : directories) {
            ret.append(directory);
            if (!endsWith(ret, File.separator)) {
                ret.append(File.separator);
            }
        }

        if (directories.length > 0 && endsWith(ret, File.separator)) {
            ret.deleteCharAt(ret.length() - 1);
        }

        if (!ret.substring(ret.lastIndexOf(File.separator)).contains(".")) {
            ret.append(File.separatorChar);
        }

        return ret.toString();
    }

    private static boolean endsWith(StringBuilder sb, String lookup) {
        return sb.substring(sb.length() - lookup.length()).equals(lookup);
    }
}