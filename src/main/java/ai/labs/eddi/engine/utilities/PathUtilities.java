package ai.labs.eddi.engine.utilities;

public class PathUtilities {

    public static String buildPath(char separator, boolean trailingSeparator, String... directories) {
        StringBuilder ret = new StringBuilder();
        for (String directory : directories) {
            ret.append(directory).append(separator);
        }

        if (!trailingSeparator && directories.length > 0) {
            ret.deleteCharAt(ret.length() - 1);
        }

        return ret.toString();
    }
}
