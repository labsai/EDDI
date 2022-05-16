package ai.labs.eddi.backup.impl;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
public class LifecycleTaskUriExtractor {
    public static final String BOT_EXT = "bot";
    public static final String PACKAGE_EXT = "package";
    public static final String DICTIONARY_EXT = "regulardictionary";
    public static final String BEHAVIOR_EXT = "behavior";
    public static final String HTTPCALLS_EXT = "httpcalls";
    public static final String PROPERTY_EXT = "property";
    public static final String OUTPUT_EXT = "output";
    public static final String GITCALLS_EXT = "gitcalls";
    public static final Pattern DICTIONARY_URI_PATTERN =
            Pattern.compile("\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/.*?\"");
    public static final Pattern BEHAVIOR_URI_PATTERN =
            Pattern.compile("\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/.*?\"");
    public static final Pattern HTTPCALLS_URI_PATTERN =
            Pattern.compile("\"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/.*?\"");
    public static final Pattern PROPERTY_URI_PATTERN =
            Pattern.compile("\"eddi://ai.labs.property/propertysetterstore/propertysetters/.*?\"");
    public static final Pattern OUTPUT_URI_PATTERN =
            Pattern.compile("\"eddi://ai.labs.output/outputstore/outputsets/.*?\"");
    public static final Pattern GITCALLS_URI_PATTERN =
            Pattern.compile("\"eddi://ai.labs.gitcalls/gitcallsstore/gitcalls/.*?\"");


    public static List<URI> extractResourcesUris(String resourceConfigString, Pattern uriPattern)
            throws CallbackMatcher.CallbackMatcherException {

        List<URI> ret = new LinkedList<>();

        CallbackMatcher callbackMatcher = new CallbackMatcher(uriPattern);
        callbackMatcher.replaceMatches(resourceConfigString, matchResult -> {
            String match = matchResult.group();
            String uri = match.substring(1, match.length() - 1);
            ret.add(URI.create(uri));
            return null;
        });
        return ret;
    }
}
