/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenAPI security scheme's NAME is a component key
 * ({@code #/components/securitySchemes/<name>}), and OpenAPI restricts
 * component keys to {@code ^[a-zA-Z0-9.\-_]+$}. It was "Keycloak OIDC":
 * swagger-parser warned about it on every boot, when {@code McpApiToolBuilder}
 * parsed the deployment's own spec to build the Platform Operator's tools — a
 * warning nobody could act on, in the log every operator administrator reads.
 * <p>
 * Read from the source tree, like {@link CspPolicyTest}, and run through the
 * same parser the tool builder uses.
 */
@DisplayName("OpenAPI security scheme name is a valid component key")
class OpenApiSecuritySchemeNameTest {

    private static final String COMPONENT_KEY = "^[a-zA-Z0-9.\\-_]+$";

    private static String schemeName() throws Exception {
        var path = Path.of(System.getProperty("basedir", ".")).resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        String name = properties.getProperty("quarkus.smallrye-openapi.security-scheme-name");
        assertNotNull(name, "quarkus.smallrye-openapi.security-scheme-name is not set");
        return name.trim();
    }

    private static List<String> parseMessages(String scheme) {
        String spec = """
                {"openapi":"3.0.3","info":{"title":"t","version":"1"},"paths":{},
                 "components":{"securitySchemes":{"%s":{"type":"openIdConnect","openIdConnectUrl":"https://idp.example/.well-known/openid-configuration"}}},
                 "security":[{"%s":[]}]}
                """
                .formatted(scheme, scheme);
        var options = new ParseOptions();
        options.setResolve(true);
        var result = new OpenAPIV3Parser().readContents(spec, null, options);
        return result.getMessages() == null ? List.of() : result.getMessages();
    }

    @Test
    @DisplayName("the configured name matches the OpenAPI component-key pattern")
    void configuredNameIsAComponentKey() throws Exception {
        String name = schemeName();
        assertTrue(name.matches(COMPONENT_KEY), "security-scheme-name '" + name + "' is not a valid OpenAPI component key " + COMPONENT_KEY);
    }

    @Test
    @DisplayName("a spec carrying the configured name parses without a warning")
    void configuredNameParsesCleanly() throws Exception {
        List<String> messages = parseMessages(schemeName());
        assertTrue(messages.isEmpty(), "swagger-parser warned: " + messages);
    }

    @Test
    @DisplayName("the old name with a space is what produced the boot warning")
    void spacedNameWarns() {
        assertFalse(parseMessages("Keycloak OIDC").isEmpty(), "swagger-parser no longer warns about a key with a space; the guard above"
                + " may be redundant, but keep the name space-free");
    }
}
