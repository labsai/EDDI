package ai.labs.eddi.integrationtests;

import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.restassured.response.Response;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
class BotEngineSetup extends BaseCRUDOperations {
    private static final String HEADER_LOCATION = "location";
    private final IJsonSerialization jsonSerialization;

    public BotEngineSetup(IJsonSerialization jsonSerialization) {
        this.jsonSerialization = jsonSerialization;
    }


    URI setupBot(String regularDictionaryPath, String behaviorPath, String outputPath) throws IOException, InterruptedException {
        // load test resources
        String REGULAR_DICTIONARY = load(regularDictionaryPath);
        String BEHAVIOR = load(behaviorPath);
        String OUTPUT = load(outputPath);

        //create dictionary
        String locationDictionary = createResource(REGULAR_DICTIONARY, "/regulardictionarystore/regulardictionaries");

        //create behavior
        String locationBehavior = createResource(BEHAVIOR, "/behaviorstore/behaviorsets");

        //create output
        String locationOutput = createResource(OUTPUT, "/outputstore/outputsets");

        //createPackage
        PackageConfiguration packageConfig = new PackageConfiguration();
        packageConfig.getPackageExtensions().add(createParserExtension(locationDictionary));
        packageConfig.getPackageExtensions().add(createBehaviorExtension(locationBehavior));
        packageConfig.getPackageExtensions().add(createOutputExtension(locationOutput));
        packageConfig.getPackageExtensions().add(createTemplateExtension());
        packageConfig.getPackageExtensions().add(createPropertyExtraction());
        String locationPackage = createResource(jsonSerialization.serialize(packageConfig), "/packagestore/packages");


        //createBot
        BotConfiguration botConfig = new BotConfiguration();
        botConfig.getPackages().add(URI.create(locationPackage));
        return URI.create(createResource(jsonSerialization.serialize(botConfig), "/botstore/bots"));
    }

    private PackageConfiguration.PackageExtension createPropertyExtraction() {
        return createExtension("eddi://ai.labs.property");
    }

    private PackageConfiguration.PackageExtension createExtension(String type) {
        PackageConfiguration.PackageExtension packageExtension = new PackageConfiguration.PackageExtension();
        packageExtension.setType(URI.create(type));

        return packageExtension;
    }

    private PackageConfiguration.PackageExtension createNormalizerExtension() {
        PackageConfiguration.PackageExtension packageExtension = createExtension("eddi://ai.labs.normalizer");
        packageExtension.getConfig().put("allowedChars", "0123456789abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        packageExtension.getConfig().put("convertUmlaute", "true");
        return packageExtension;
    }

    private PackageConfiguration.PackageExtension createParserExtension(String locationDictionary) {
        PackageConfiguration.PackageExtension packageExtension = createExtension("eddi://ai.labs.parser");
        List<PackageConfiguration.PackageExtension> dictionaries = new ArrayList<>();

        dictionaries.add(createExtension("eddi://ai.labs.parser.dictionaries.integer"));
        dictionaries.add(createExtension("eddi://ai.labs.parser.dictionaries.decimal"));
        dictionaries.add(createExtension("eddi://ai.labs.parser.dictionaries.punctuation"));
        dictionaries.add(createExtension("eddi://ai.labs.parser.dictionaries.email"));
        dictionaries.add(createExtension("eddi://ai.labs.parser.dictionaries.time"));
        dictionaries.add(createExtension("eddi://ai.labs.parser.dictionaries.ordinalNumber"));
        PackageConfiguration.PackageExtension regularDictionary =
                createExtension("eddi://ai.labs.parser.dictionaries.regular");
        regularDictionary.getConfig().put("uri", locationDictionary);
        dictionaries.add(regularDictionary);

        packageExtension.getExtensions().put("dictionaries", dictionaries.toArray());

        List<PackageConfiguration.PackageExtension> corrections = new ArrayList<>();
        PackageConfiguration.PackageExtension stemming = createExtension("eddi://ai.labs.parser.corrections.stemming");
        stemming.getConfig().put("language", "english");
        stemming.getConfig().put("lookupIfKnown", "false");
        corrections.add(stemming);
        PackageConfiguration.PackageExtension levenshtein = createExtension("eddi://ai.labs.parser.corrections.levenshtein");
        levenshtein.getConfig().put("distance", "2");
        corrections.add(levenshtein);
        corrections.add(createExtension("eddi://ai.labs.parser.corrections.mergedTerms"));

        packageExtension.getExtensions().put("corrections", corrections.toArray());
        return packageExtension;
    }

    private PackageConfiguration.PackageExtension createBehaviorExtension(String locationBehavior) {
        PackageConfiguration.PackageExtension extension = createExtension("eddi://ai.labs.behavior");
        extension.getConfig().put("uri", locationBehavior);
        return extension;
    }

    private PackageConfiguration.PackageExtension createOutputExtension(String locationOutput) {
        PackageConfiguration.PackageExtension extension = createExtension("eddi://ai.labs.output");
        extension.getConfig().put("uri", locationOutput);
        return extension;
    }

    private PackageConfiguration.PackageExtension createTemplateExtension() {
        return createExtension("eddi://ai.labs.templating");
    }

    private String createResource(String body, String resourceUri) {
        Response response = create(body, resourceUri);
        return response.getHeader(HEADER_LOCATION);
    }
}
