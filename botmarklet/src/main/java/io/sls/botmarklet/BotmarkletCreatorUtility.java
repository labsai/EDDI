package io.sls.botmarklet;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

public class BotmarkletCreatorUtility {

    private BotmarkletCreatorUtility() {
        //utility class
    }

    public static String removeLineBreaks(String input) {
        return input.replaceAll("((\\n)|(\\r)|(\\r\\n))\\s*", "");
    }

    public static String replaceBlanks(String input) {
        return input.replace(" ", "%20");
    }

    public static String createBotMarklet(String baseUrl, String environment, String botId, List<URL> jsResources, List<URL> cssResources) {
        String botMarklet = internalCreateBotMarklet(baseUrl, environment, botId, jsResources, cssResources);
        String removedLineBreaks = removeLineBreaks(botMarklet);
        return replaceBlanks(removedLineBreaks);
    }

    private static String internalCreateBotMarklet(String baseUrl, String environment, String botId, List<URL> jsResources, List<URL> cssResources) {
        InputStream stream = BotmarkletCreatorUtility.class.getResourceAsStream("/botmarklet.js");
        String botMarklet = convertStreamToString(stream);
        String commaSeparatedJsList = createCommaSeparatedList(jsResources);
        String commaSeparatedCssList = createCommaSeparatedList(cssResources);
        return String.format(botMarklet, baseUrl, environment, botId, commaSeparatedJsList, commaSeparatedCssList);
    }

    public static String createCommaSeparatedList(List<URL> urls) {
        StringBuilder builder = new StringBuilder();

        for (URL url : urls) {
            builder.append("\"");
            builder.append(url.toString());
            builder.append("\"");
            builder.append(",");
        }

        if (urls.size() > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }

        return builder.toString();
    }

    private static String convertStreamToString(InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
