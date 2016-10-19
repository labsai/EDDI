package io.sls.persistence.impl.scriptimport;

import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.core.service.restinterfaces.RestInterfaceFactory;
import io.sls.expressions.Expression;
import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.expressions.value.AnyValue;
import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.behavior.IRestBehaviorStore;
import io.sls.resources.rest.behavior.model.BehaviorConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorGroupConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorRuleConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorRuleElementConfiguration;
import io.sls.resources.rest.bots.IRestBotStore;
import io.sls.resources.rest.bots.model.BotConfiguration;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.output.IRestOutputStore;
import io.sls.resources.rest.output.model.OutputConfiguration;
import io.sls.resources.rest.output.model.OutputConfigurationSet;
import io.sls.resources.rest.packages.IRestPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import io.sls.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import io.sls.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import io.sls.resources.rest.scriptimport.IRestScriptImport;
import io.sls.runtime.ThreadContext;
import io.sls.user.IUserStore;
import io.sls.user.impl.utilities.UserUtilities;
import io.sls.utilities.CharacterUtilities;
import io.sls.utilities.RuntimeUtilities;
import io.sls.utilities.SecurityUtilities;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.security.Principal;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * User: jarisch
 * Date: 22.06.13
 * Time: 20:18
 */
public class RestScriptImport implements IRestScriptImport {
    public static final String LINE_SEPARTOR_UNIX = "\n";
    public static final String LINE_SEPARATOR_WINDOWS = "\r\n";
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IUserStore userStore;
    private final IExpressionUtilities expressionUtilities;
    private final String currentDateFormatted;

    @Inject
    public RestScriptImport(IRestInterfaceFactory restInterfaceFactory,
                            IDocumentDescriptorStore documentDescriptorStore,
                            IUserStore userStore,
                            IExpressionUtilities expressionUtilities) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.documentDescriptorStore = documentDescriptorStore;
        this.userStore = userStore;
        this.expressionUtilities = expressionUtilities;
        currentDateFormatted = new SimpleDateFormat("yy-MM-dd").format(new Date(System.currentTimeMillis()));
    }

    public static class ScriptGroup {
        private String name;
        private List<ScriptEntity> scriptEntities;

        public ScriptGroup(String groupName, List<ScriptEntity> scriptEntities) {
            this.name = groupName;
            this.scriptEntities = scriptEntities;
        }

        public List<ScriptEntity> getScriptEntities() {
            return scriptEntities;
        }

        public void setScriptEntities(List<ScriptEntity> scriptEntities) {
            this.scriptEntities = scriptEntities;
        }

        public String getName() {
            return name;
        }
    }

    public static class ScriptEntity {
        private List<String> questions;
        private List<String> keywords;
        private List<String> answers;
        private String semantic;

        public ScriptEntity(String[] questions, String[] answers, String[] keywords, String semantic) {
            this.questions = new LinkedList<>(Arrays.asList(questions));
            this.answers = new LinkedList<>(Arrays.asList(answers));
            this.keywords = new LinkedList<>(Arrays.asList(keywords));
            this.semantic = semantic;
        }

        public List<String> getQuestions() {
            return questions;
        }

        public void setQuestions(List<String> questions) {
            this.questions = questions;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public List<String> getAnswers() {
            return answers;
        }

        public void setAnswers(List<String> answers) {
            this.answers = answers;
        }

        public String getSemantic() {
            return semantic;
        }

        public void setSemantic(String semantic) {
            this.semantic = semantic;
        }
    }

    @Override
    public Response createBot(String language, String botId, Integer botVersion, String script) {
        RuntimeUtilities.checkNotNull(language, "language");
        RuntimeUtilities.checkNotNull(script, "script");

        try {
            List<ScriptGroup> scriptGroups = parseScript(script);

            List<PackageConfiguration.PackageExtension> packageExtensions = new LinkedList<PackageConfiguration.PackageExtension>();

            //parser
            Map<String, Object> parserExtensions = createParserExtension(language, scriptGroups);
            Map<String, Object> parserConfig = Collections.emptyMap();
            packageExtensions.add(createPackageExtension("core://io.sls.parser", parserExtensions, parserConfig));

            //behavior
            Map<String, Object> behaviourExtensions = Collections.emptyMap();
            Map<String, Object> behaviourConfig = createBehaviourConfig(scriptGroups);
            packageExtensions.add(createPackageExtension("core://io.sls.behavior", behaviourExtensions, behaviourConfig));

            //behavior
            Map<String, Object> outputExtensions = Collections.emptyMap();
            Map<String, Object> outputConfig = createOutputConfig(scriptGroups);
            packageExtensions.add(createPackageExtension("core://io.sls.output", outputExtensions, outputConfig));

            //package
            PackageConfiguration packageConfiguration = new PackageConfiguration();
            packageConfiguration.setPackageExtensions(packageExtensions);
            URI packageUri = createRemotePackage(URI.create(""), packageConfiguration);

            //TODO get automated tests

            //bot
            if (botId == null) {
                BotConfiguration botConfiguration = new BotConfiguration();
                botConfiguration.setAuthenticationRequired(true);
                botConfiguration.setPackages(Arrays.asList(packageUri));
                URI botUri = createRemoteBot(URI.create(""), botConfiguration);
                return Response.created(botUri).build();
            } else {
                BotConfiguration botConfiguration = fetchBotConfiguration(URI.create(""), botId, botVersion);
                botConfiguration.getPackages().add(packageUri);
                URI botUri = saveBotConfiguration(URI.create(""), botId, botVersion, botConfiguration);
                return Response.ok().location(botUri).build();
            }
        } catch (Exception e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private URI saveBotConfiguration(URI baseUri, String id, Integer version, BotConfiguration botConfiguration) throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestBotStore restBotStore = restInterfaceFactory.get(IRestBotStore.class, baseUri.toString());
        URI uri = restBotStore.updateBot(id, version, botConfiguration);
        return uri;
    }

    private BotConfiguration fetchBotConfiguration(URI baseUri, String botId, Integer botVersion) throws Exception {
        IRestBotStore restBotStore = restInterfaceFactory.get(IRestBotStore.class, baseUri.toString());
        BotConfiguration botConfiguration = restBotStore.readBot(botId, botVersion);
        return botConfiguration;
    }

    private URI createRemoteBot(URI baseUri, BotConfiguration botConfiguration) throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestBotStore restBotStore = restInterfaceFactory.get(IRestBotStore.class, baseUri.toString());
        Response response = restBotStore.createBot(botConfiguration);
        return response.getLocation();
    }

    private URI createRemotePackage(URI baseUri, PackageConfiguration packageConfiguration) throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestPackageStore restPackageStore = restInterfaceFactory.get(IRestPackageStore.class, baseUri.toString());
        Response response = restPackageStore.createPackage(packageConfiguration);
        return response.getLocation();
    }

    private List<ScriptGroup> parseScript(String script) {
        IScriptParser scriptParser = new ScriptParser(script, "Gruppe:", "Frage:");
        List<ScriptGroup> scriptGroups = new LinkedList<>();
        List<ScriptEntity> scriptEntities;
        while (scriptParser.hasMoreGroups()) {
            IScriptParser.Group group = scriptParser.nextGroup();
            scriptEntities = new LinkedList<>();
            while (group.hasMoreInteractions()) {
                IScriptParser.Interaction interaction = group.nextInteraction();
                ScriptEntity scriptEntity = parseInteraction(interaction);
                scriptEntities.add(scriptEntity);
            }
            scriptGroups.add(new ScriptGroup(group.getGroupName(), scriptEntities));
        }
        return scriptGroups;
    }

    private ScriptEntity parseInteraction(IScriptParser.Interaction interaction) {
        String questionsString = interaction.getValue("", "Antwort:");
        String answersString = interaction.getValue("Antwort:", "Semantik:");
        String semantic = interaction.getValue("Semantik:", "Keywords:");
        semantic = semantic != null ? cleanString(semantic) : semantic;
        String keywordsString = interaction.getValue("Keywords:", null);
        keywordsString = keywordsString != null ? cleanString(keywordsString, false) : keywordsString;

        String[] questions = questionsString != null ? cleanString(questionsString).split("\\?") : new String[0];
        arrayToLowerCase(questions);
        String[] answers = answersString != null ? new String[]{cleanString(answersString, false)} : new String[0];
        String[] keywords = keywordsString != null ? cleanString(keywordsString, false).split(",") : new String[0];
        arrayToLowerCase(keywords);

        if ("".equals(semantic)) {
            semantic = keywords[0];
        }
        return new ScriptEntity(questions, answers, keywords, semantic);
    }

    private void arrayToLowerCase(String[] stringArray) {
        for (int i = 0; i < stringArray.length; i++) {
            stringArray[i] = stringArray[i].toLowerCase();
        }
    }

    private String cleanString(String s) {
        return cleanString(s, true);
    }

    private String cleanString(String s, boolean removePunctuation) {
        //remove leading newlines
        while (s.startsWith(LINE_SEPARTOR_UNIX) || s.startsWith(LINE_SEPARATOR_WINDOWS)) {
            int substract = s.equals(LINE_SEPARATOR_WINDOWS) ? 2 : 1;
            s = s.substring(substract);
        }

        //remove trailing new lines
        while (s.endsWith(LINE_SEPARTOR_UNIX) || s.endsWith(LINE_SEPARATOR_WINDOWS)) {
            int substract = s.equals(LINE_SEPARATOR_WINDOWS) ? 2 : 1;
            s = s.substring(0, s.length() - substract);
        }

        if (removePunctuation) {
            s = s.replaceAll(",", "").replaceAll("-", "").replaceAll("\\.", "").trim();
        }
        return s;
    }

    private Map<String, Object> createParserExtension(String language, List<ScriptGroup> scriptGroups) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        List<Map<String, Object>> dictionaryExtensions = new LinkedList<Map<String, Object>>();
        //parser.dictionary.punctuation
        Map<String, Object> punctuationExtension = createPunctuationExtension();
        dictionaryExtensions.add(punctuationExtension);
        RegularDictionaryConfiguration regularDictionaryConfiguration;
        regularDictionaryConfiguration = createRegularDictionaryConfiguration(language, scriptGroups);
        URI regularDictionaryUri = createRemoteRegularDictionary("dict-" + "-" + currentDateFormatted, URI.create(""), regularDictionaryConfiguration);
        Map<String, Object> regularDictionaryExtension = createRegularDictionaryExtension(regularDictionaryUri);
        dictionaryExtensions.add(regularDictionaryExtension);
        //parser.dictionary
        Map<String, Object> parserExtension = new HashMap<String, Object>();
        parserExtension.put("dictionaries", dictionaryExtensions.toArray());

        //parser.corrections
        List<Map<String, Object>> corrections = new LinkedList<Map<String, Object>>();
        //parser.corrections.levenshtein
        Map<String, Object> levenstheinConfiguration = createLevenshteinDistanceCorrection();
        corrections.add(levenstheinConfiguration);
        //parser.corrections.mergedTerms
        Map<String, Object> mergedTermsConfiguration = createMergedTermsCorrection();
        corrections.add(mergedTermsConfiguration);
        parserExtension.put("corrections", corrections);

        return parserExtension;
    }

    private RegularDictionaryConfiguration createRegularDictionaryConfiguration(String language, List<ScriptGroup> scriptGroups) {
        RegularDictionaryConfiguration regularDictionaryConfiguration;
        regularDictionaryConfiguration = new RegularDictionaryConfiguration();
        regularDictionaryConfiguration.setLanguage(language);
        for (ScriptGroup scriptGroup : scriptGroups) {
            //parser.dictionary.regular
            List<String> questions;
            List<String> keywords;
            String semantic;
            for (ScriptEntity scriptEntity : scriptGroup.getScriptEntities()) {
                semantic = CharacterUtilities.createSemantic(scriptEntity.getSemantic(), true);
                questions = scriptEntity.getQuestions();
                keywords = scriptEntity.getKeywords();

                //distinct between words and phrases, since they are stored separately
                List<String> keywordWords = new LinkedList<String>();
                List<String> keywordPhrases = new LinkedList<String>();
                for (String keyword : keywords) {
                    keyword = keyword.trim();
                    if (keyword.contains(" ")) {
                        keywordPhrases.add(keyword);
                    } else {
                        keywordWords.add(keyword);
                    }
                }

                List<RegularDictionaryConfiguration.WordConfiguration> wordConfigurations = createWords(keywordWords, semantic);
                regularDictionaryConfiguration.getWords().addAll(wordConfigurations);

                List<RegularDictionaryConfiguration.PhraseConfiguration> phraseConfigurations = createPhrases(questions, semantic);
                phraseConfigurations.addAll(createPhrases(keywordPhrases, semantic));
                regularDictionaryConfiguration.getPhrases().addAll(phraseConfigurations);
            }
        }
        return regularDictionaryConfiguration;
    }

    private Map<String, Object> createMergedTermsCorrection() {
        Map<String, Object> mergedTermsCorrectionExtension = new HashMap<String, Object>();
        mergedTermsCorrectionExtension.put("type", "core://io.sls.parser.corrections.mergedTerms");
        mergedTermsCorrectionExtension.put("config", new HashMap<String, Object>());
        return mergedTermsCorrectionExtension;
    }

    private Map<String, Object> createLevenshteinDistanceCorrection() {
        Map<String, Object> levenshteinCorrectionExtension = new HashMap<String, Object>();
        levenshteinCorrectionExtension.put("type", "core://io.sls.parser.corrections.levenshtein");
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("distance", "2");
        levenshteinCorrectionExtension.put("config", config);
        return levenshteinCorrectionExtension;
    }

    private URI createRemoteRegularDictionary(String dictionaryName, URI baseUri, RegularDictionaryConfiguration regularDictionaryConfiguration) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        IRestRegularDictionaryStore restRegularDictionaryStore = restInterfaceFactory.get(IRestRegularDictionaryStore.class, baseUri.toString());
        Response response = restRegularDictionaryStore.createRegularDictionary(regularDictionaryConfiguration);
        URI uri = response.getLocation();
        String uriString = uri.toString();
        String id = uriString.substring(uriString.lastIndexOf("/") + 1, uriString.indexOf("?"));
        DocumentDescriptor documentDescriptor = createDocumentDescriptor(uri, getUserUri());
        documentDescriptor.setName(dictionaryName);
        documentDescriptor.setDescription("");
        documentDescriptorStore.setDescriptor(id, 1, documentDescriptor);
        return uri;
    }

    private URI getUserUri() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
        return UserUtilities.getUserURI(userStore, userPrincipal);
    }

    /*private RegularDictionaryConfiguration createRegularDictionary(String language, String semantic, List<String> questions, List<String> keywords) {
        RegularDictionaryConfiguration regularDictionaryConfiguration;
        regularDictionaryConfiguration = new RegularDictionaryConfiguration();
        regularDictionaryConfiguration.setLanguage(language);
        List<RegularDictionaryConfiguration.PhraseConfiguration> phrases = createPhrases(questions, semantic);
        phrases.addAll(createPhrases(keywords, semantic));
        regularDictionaryConfiguration.getPhrases().addAll(phrases);

        return regularDictionaryConfiguration;
    }*/

    private Map<String, Object> createRegularDictionaryExtension(URI regularDictionaryUri) {
        Map<String, Object> regularDictionaryExtension = new HashMap<String, Object>();
        regularDictionaryExtension.put("type", "core://io.sls.parser.dictionaries.regular");
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("uri", regularDictionaryUri);
        regularDictionaryExtension.put("config", config);
        return regularDictionaryExtension;
    }

    private Map<String, Object> createPunctuationExtension() {
        Map<String, Object> punctuationExtension = new HashMap<String, Object>();
        punctuationExtension.put("type", "core://io.sls.parser.dictionaries.punctuation");
        return punctuationExtension;
    }

    private List<RegularDictionaryConfiguration.WordConfiguration> createWords(List<String> keywords, String semantic) {
        List<RegularDictionaryConfiguration.WordConfiguration> wordConfigurations = new LinkedList<RegularDictionaryConfiguration.WordConfiguration>();
        for (String keyword : keywords) {
            RegularDictionaryConfiguration.WordConfiguration wordConfiguration = new RegularDictionaryConfiguration.WordConfiguration();
            keyword = CharacterUtilities.createSemantic(keyword, true);
            wordConfiguration.setWord(keyword);
            wordConfiguration.setExp(expressionUtilities.createExpression(semantic, keyword).toString());
            wordConfigurations.add(wordConfiguration);
        }

        return wordConfigurations;
    }

    private List<RegularDictionaryConfiguration.PhraseConfiguration> createPhrases(List<String> questions, String semantic) {
        List<RegularDictionaryConfiguration.PhraseConfiguration> phraseConfigurations = new LinkedList<RegularDictionaryConfiguration.PhraseConfiguration>();
        for (String question : questions) {
            RegularDictionaryConfiguration.PhraseConfiguration phraseConfiguration = new RegularDictionaryConfiguration.PhraseConfiguration();
            question = CharacterUtilities.createSemantic(question, false);
            phraseConfiguration.setPhrase(question);
            phraseConfiguration.setExp(expressionUtilities.createExpression(semantic, CharacterUtilities.createSemantic(question, true)).toString());
            phraseConfigurations.add(phraseConfiguration);
        }

        return phraseConfigurations;
    }

    private Map<String, Object> createBehaviourConfig(List<ScriptGroup> scriptGroups) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        Map<String, Object> config = new HashMap<String, Object>();

        BehaviorConfiguration behaviorConfiguration = new BehaviorConfiguration();
        List<BehaviorGroupConfiguration> behaviorGroups = new LinkedList<BehaviorGroupConfiguration>();
        for (ScriptGroup scriptGroup : scriptGroups) {
            BehaviorGroupConfiguration behaviorGroupConfiguration = new BehaviorGroupConfiguration();
            behaviorGroupConfiguration.setName(scriptGroup.getName());
            List<BehaviorRuleConfiguration> behaviorRules = new LinkedList<BehaviorRuleConfiguration>();
            for (ScriptEntity scriptEntity : scriptGroup.getScriptEntities()) {
                BehaviorRuleConfiguration behaviorRuleConfiguration = new BehaviorRuleConfiguration();
                String semantic = scriptEntity.getSemantic();
                semantic = CharacterUtilities.createSemantic(semantic, true);
                behaviorRuleConfiguration.setName(semantic);
                behaviorRuleConfiguration.setActions(Arrays.asList(semantic));
                LinkedList<BehaviorRuleElementConfiguration> children = new LinkedList<BehaviorRuleElementConfiguration>();
                BehaviorRuleElementConfiguration behaviorRuleElementConfiguration = new BehaviorRuleElementConfiguration();
                behaviorRuleElementConfiguration.setType("inputmatcher");
                Expression expression = expressionUtilities.createExpression(semantic, new AnyValue());
                behaviorRuleElementConfiguration.getValues().put("expressions", expression.toString());
                behaviorRuleElementConfiguration.getValues().put("occurrence", "current");
                children.add(behaviorRuleElementConfiguration);
                behaviorRuleConfiguration.setChildren(children);
                behaviorRules.add(behaviorRuleConfiguration);
            }
            behaviorGroupConfiguration.setBehaviorRules(behaviorRules);
            behaviorGroups.add(behaviorGroupConfiguration);
        }
        behaviorConfiguration.setBehaviorGroups(behaviorGroups);
        URI resourceURI = createRemoteBehavior("behaviorset-" + currentDateFormatted, URI.create(""), behaviorConfiguration);
        config.put("uri", resourceURI);

        return config;
    }

    private URI createRemoteBehavior(String behaviorName, URI baseUri, BehaviorConfiguration behaviorConfiguration) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        IRestBehaviorStore restBehaviorStore = restInterfaceFactory.get(IRestBehaviorStore.class, baseUri.toString());
        Response response = restBehaviorStore.createBehaviorRuleSet(behaviorConfiguration);
        URI uri = response.getLocation();
        String uriString = uri.toString();
        String id = uriString.substring(uriString.lastIndexOf("/") + 1, uriString.indexOf("?"));
        DocumentDescriptor documentDescriptor = createDocumentDescriptor(uri, getUserUri());
        documentDescriptor.setName(behaviorName);
        documentDescriptor.setDescription("");
        documentDescriptorStore.setDescriptor(id, 1, documentDescriptor);
        return uri;
    }

    private Map<String, Object> createOutputConfig(List<ScriptGroup> scriptGroups) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        OutputConfigurationSet outputConfigurationSet = new OutputConfigurationSet();
        List<OutputConfiguration> outputs = new LinkedList<>();
        for (ScriptGroup scriptGroup : scriptGroups) {
            for (ScriptEntity scriptEntity : scriptGroup.getScriptEntities()) {
                OutputConfiguration outputConfiguration = new OutputConfiguration();
                String semantic = scriptEntity.getSemantic();
                if (semantic == null) {
                    continue;
                }
                semantic = CharacterUtilities.createSemantic(semantic, true);
                outputConfiguration.setKey(semantic);
                outputConfiguration.setOccurrence(0);
                outputConfiguration.setOutputValues(scriptEntity.getAnswers());
                outputs.add(outputConfiguration);
            }
        }
        outputConfigurationSet.setOutputs(outputs);
        URI resourceUri = createRemoteOutput("outputset-" + currentDateFormatted, URI.create(""), outputConfigurationSet);
        Map<String, Object> config = new HashMap<>();
        config.put("uri", resourceUri);
        return config;
    }

    private URI createRemoteOutput(String outputName, URI baseUri, OutputConfigurationSet outputConfigurationSet) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        IRestOutputStore restOutputStore = restInterfaceFactory.get(IRestOutputStore.class, baseUri.toString());
        Response response = restOutputStore.createOutputSet(outputConfigurationSet);
        URI uri = response.getLocation();
        String uriString = uri.toString();
        String id = uriString.substring(uriString.lastIndexOf("/") + 1, uriString.indexOf("?"));
        DocumentDescriptor documentDescriptor = createDocumentDescriptor(uri, getUserUri());
        documentDescriptor.setName(outputName);
        documentDescriptor.setDescription("");
        documentDescriptorStore.setDescriptor(id, 1, documentDescriptor);
        return uri;
    }

    private PackageConfiguration.PackageExtension createPackageExtension(String type, Map<String, Object> extensions, Map<String, Object> config) {
        PackageConfiguration.PackageExtension packageExtension = new PackageConfiguration.PackageExtension();
        packageExtension.setType(URI.create(type));
        packageExtension.setExtensions(extensions);
        packageExtension.setConfig(config);
        return packageExtension;
    }

    private DocumentDescriptor createDocumentDescriptor(URI resource, URI author) {
        Date current = new Date(System.currentTimeMillis());

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setCreatedBy(author);
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);
        descriptor.setLastModifiedBy(author);

        return descriptor;
    }
}
