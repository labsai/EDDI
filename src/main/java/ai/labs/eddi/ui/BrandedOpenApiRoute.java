/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.smallrye.openapi.runtime.OpenApiDocumentService;
import io.smallrye.openapi.runtime.io.Format;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Puts the deployment's product name on the OpenAPI document ({@code /openapi},
 * which Swagger UI renders) while a brand is set: {@code info.title} becomes
 * {@code "<product> API"} and the LABS.AI contact block is dropped.
 * <p>
 * Why a route and not an {@code OASFilter}: SmallRye builds the document once
 * at startup, and even a per-request filter cannot win — SmallRye applies the
 * {@code quarkus.smallrye-openapi.info-*} properties AFTER every filter, so the
 * configured title and contact would overwrite the brand on each build. So the
 * document stays exactly as SmallRye serves it, cached, and this route — ahead
 * of the OpenAPI handler, for the configured path and its {@code .json} /
 * {@code .yaml} / {@code .yml} forms — rewrites two fields of that document on
 * the way out, only while a brand is set. The format is negotiated the way the
 * handler negotiates it (suffix, then {@code ?format=}, then {@code Accept}).
 * <p>
 * <b>Unbranded, every request falls through untouched</b>, as does any request
 * when the brand or SmallRye's document service cannot be reached. Branded, the
 * document is re-serialised by Jackson: the same content, with Jackson's
 * whitespace rather than SmallRye's.
 */
@ApplicationScoped
public class BrandedOpenApiRoute {

    private static final Logger LOGGER = Logger.getLogger(BrandedOpenApiRoute.class);

    /** SmallRye's name for the default document. */
    static final String DEFAULT_DOCUMENT = "<default>";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private final BrandingService branding;
    private final String path;

    @Inject
    public BrandedOpenApiRoute(BrandingService branding,
            @ConfigProperty(name = "quarkus.smallrye-openapi.path", defaultValue = "openapi") String openApiPath,
            @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "q") String nonApplicationRoot) {
        this.branding = branding;
        this.path = BrandedSwaggerUiRoute.swaggerRoot(openApiPath, nonApplicationRoot);
    }

    void register(@Observes Router router) {
        for (String suffix : List.of("", ".json", ".yaml", ".yml")) {
            router.route(path + suffix).order(BrandedShellRoute.ORDER).method(HttpMethod.GET).method(HttpMethod.HEAD)
                    .blockingHandler(this::handle);
        }
    }

    void handle(RoutingContext context) {
        ResolvedBrand brand;
        try {
            brand = branding.current(BrandRequest.ofHost(context.request().getHeader("Host")));
        } catch (RuntimeException e) {
            LOGGER.debugf("Brand unavailable for the OpenAPI document: %s", e.getMessage());
            context.next();
            return;
        }
        if (!brand.branded()) {
            context.next();
            return;
        }
        Format format = format(context.normalizedPath(), context.queryParam("format"), context.request().getHeader("Accept"));
        Optional<byte[]> document = document(format);
        if (document.isEmpty()) {
            context.next();
            return;
        }
        byte[] branded;
        try {
            branded = rebrand(document.get(), format, brand.productName());
        } catch (IOException e) {
            LOGGER.warnf("Could not brand the OpenAPI document; serving it as built: %s", e.getMessage());
            context.next();
            return;
        }
        context.response()
                .putHeader("Content-Type", format.getMimeType() + ";charset=UTF-8")
                .end(Buffer.buffer(branded));
    }

    /**
     * The handler's negotiation: a suffix, then {@code ?format=}, then
     * {@code Accept}; YAML otherwise.
     */
    static Format format(String requestPath, List<String> formatParam, String accept) {
        if (requestPath.endsWith(".json")) {
            return Format.JSON;
        }
        if (requestPath.endsWith(".yaml") || requestPath.endsWith(".yml")) {
            return Format.YAML;
        }
        if (formatParam != null && !formatParam.isEmpty()) {
            return "JSON".equalsIgnoreCase(formatParam.getFirst()) ? Format.JSON : Format.YAML;
        }
        return accept != null && accept.contains(Format.JSON.getMimeType()) ? Format.JSON : Format.YAML;
    }

    private static Optional<byte[]> document(Format format) {
        try {
            ArcContainer container = Arc.container();
            if (container == null) {
                return Optional.empty();
            }
            // Not closed: an application-scoped bean's handle is the shared instance.
            InstanceHandle<OpenApiDocumentService> handle = container.instance(OpenApiDocumentService.class);
            return handle.isAvailable() ? Optional.ofNullable(handle.get().getDocument(DEFAULT_DOCUMENT, format)) : Optional.empty();
        } catch (RuntimeException e) {
            LOGGER.debugf("OpenAPI document unavailable for branding: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The document with {@code info.title} set to {@code "<product> API"} and
     * {@code info.contact} removed. The description and the Apache 2.0 licence
     * stay: neither names EDDI, and the licence is still the engine's.
     */
    static byte[] rebrand(byte[] document, Format format, String productName) throws IOException {
        ObjectMapper mapper = format == Format.JSON ? JSON : YAML;
        JsonNode root = mapper.readTree(document);
        if (root instanceof ObjectNode object && object.get("info") instanceof ObjectNode info) {
            info.put("title", productName + " API");
            info.remove("contact");
        }
        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
    }
}
