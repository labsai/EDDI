package ai.labs.eddi.backup.impl;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
abstract class AbstractBackupService {
    static final String AGENT_EXT = "agent";
    static final String WORKFLOW_EXT = "workflow";
    static final String DICTIONARY_EXT = "regulardictionary";
    static final String BEHAVIOR_EXT = "behavior";
    static final String HTTPCALLS_EXT = "httpcalls";
    static final String LLM_EXT = "langchain";
    static final String PROPERTY_EXT = "property";
    static final String OUTPUT_EXT = "output";
    static final String MCPCALLS_EXT = "mcpcalls";
    static final String RAG_EXT = "rag";
    static final String SNIPPET_EXT = "snippet";

    // ---- V6 canonical URI patterns ----
    static final Pattern DICTIONARY_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/.*?\"");
    static final Pattern BEHAVIOR_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.rules/rulestore/rulesets/.*?\"");
    static final Pattern HTTPCALLS_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.apicalls/apicallstore/apicalls/.*?\"");
    static final Pattern LANGCHAIN_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.llm/llmstore/llms/.*?\"");
    static final Pattern PROPERTY_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.property/propertysetterstore/propertysetters/.*?\"");
    static final Pattern OUTPUT_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.output/outputstore/outputsets/.*?\"");
    static final Pattern MCPCALLS_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/.*?\"");
    static final Pattern RAG_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.rag/ragstore/rags/.*?\"");

    // ---- Legacy (v5) URI patterns for backwards-compatible ZIP import ----
    static final Pattern LEGACY_DICTIONARY_URI_PATTERN = Pattern
            .compile("\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/.*?\"");
    static final Pattern LEGACY_BEHAVIOR_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/.*?\"");
    static final Pattern LEGACY_HTTPCALLS_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/.*?\"");
    static final Pattern LEGACY_LANGCHAIN_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.langchain/langchainstore/langchains/.*?\"");
    static final Pattern LEGACY_WORKFLOW_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.package/packagestore/packages/.*?\"");
    static final Pattern LEGACY_AGENT_URI_PATTERN = Pattern.compile("\"eddi://ai.labs.bot/botstore/bots/.*?\"");

    /** Legacy → v6 URI authority + store path rewrites for import normalization. */
    static final String[][] LEGACY_URI_REWRITES = {
            {"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/",
                    "eddi://ai.labs.dictionary/dictionarystore/dictionaries/"},
            {"eddi://ai.labs.behavior/behaviorstore/behaviorsets/", "eddi://ai.labs.rules/rulestore/rulesets/"},
            {"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/", "eddi://ai.labs.apicalls/apicallstore/apicalls/"},
            {"eddi://ai.labs.langchain/langchainstore/langchains/", "eddi://ai.labs.llm/llmstore/llms/"},
            {"eddi://ai.labs.package/packagestore/packages/", "eddi://ai.labs.workflow/workflowstore/workflows/"},
            {"eddi://ai.labs.bot/botstore/bots/", "eddi://ai.labs.agent/agentstore/agents/"},};

    List<URI> extractResourcesUris(String resourceConfigString, Pattern uriPattern) throws CallbackMatcher.CallbackMatcherException {
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

    /**
     * Normalize legacy eddi:// URIs in a JSON string to their v6 canonical form.
     * Used during ZIP import to transform old-format workflow configs.
     */
    static String normalizeLegacyUris(String jsonString) {
        if (jsonString == null || !jsonString.contains("eddi://")) {
            return jsonString;
        }
        String result = jsonString;
        for (String[] mapping : LEGACY_URI_REWRITES) {
            result = result.replace(mapping[0], mapping[1]);
        }
        return result;
    }
}
