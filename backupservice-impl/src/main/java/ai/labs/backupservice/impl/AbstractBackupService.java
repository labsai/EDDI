package ai.labs.backupservice.impl;

import ai.labs.resources.rest.packages.model.PackageConfiguration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author ginccc
 */
abstract class AbstractBackupService {
    private static final String CONFIG_KEY_URI = "uri";
    private static final String PARSER_URI = "eddi://ai.labs.parser";
    private static final String DICTIONARY_URI = "eddi://ai.labs.parser.dictionaries.regular";
    static final String BEHAVIOR_URI = "eddi://ai.labs.behavior";
    static final String OUTPUT_URI = "eddi://ai.labs.output";

    List<URI> extractResources(PackageConfiguration packageConfiguration, String type) {
        return packageConfiguration.getPackageExtensions().stream().
                filter(packageExtension ->
                        packageExtension.getType().toString().startsWith(type) &&
                                packageExtension.getConfig().containsKey(CONFIG_KEY_URI)).
                map(packageExtension ->
                        URI.create(packageExtension.getConfig().get(CONFIG_KEY_URI).toString())).
                collect(Collectors.toList());
    }

    List<URI> extractRegularDictionaries(PackageConfiguration packageConfiguration) {
        return packageConfiguration.getPackageExtensions().stream().
                filter(packageExtension ->
                        packageExtension.getType().toString().startsWith(PARSER_URI) &&
                                packageExtension.getExtensions().containsKey("dictionaries")).
                flatMap(packageExtension -> {
                    Map<String, Object> extensions = packageExtension.getExtensions();
                    for (String extensionKey : extensions.keySet()) {
                        List<Map<String, Object>> extensionElements = (List<Map<String, Object>>) extensions.get(extensionKey);
                        for (Map<String, Object> extensionElement : extensionElements) {
                            if (DICTIONARY_URI.equals(extensionElement.get("type")) &&
                                    extensionElement.containsKey("config")) {
                                Map<String, String> config = (Map<String, String>) extensionElement.get("config");
                                if (config.containsKey(CONFIG_KEY_URI)) {
                                    return Stream.of(URI.create(config.get(CONFIG_KEY_URI)));
                                }
                            }
                        }
                    }

                    return Stream.empty();
                }).collect(Collectors.toList());
    }
}
