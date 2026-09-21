/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Rejects a configuration document that carries a field the model does not
 * declare, instead of dropping it and answering 201.
 *
 * <p>
 * EDDI is a config-driven engine: a typo'd key in {@code behavior.json} or
 * {@code langchain.json} is a behavioural bug, and silently discarding it is
 * the worst possible failure mode — the author is told the save succeeded, a
 * subsequent GET no longer shows the key, and the agent misbehaves with no
 * signal anywhere. This interceptor turns that into a 400 naming the offending
 * field and listing the legal ones.
 * </p>
 *
 * <h2>Why not simply flip {@code FAIL_ON_UNKNOWN_PROPERTIES}</h2>
 *
 * <p>
 * Because there is no mapper in this application on which that flag is
 * REST-only.
 * </p>
 * <ul>
 * <li>{@code SerializationCustomizer.configureObjectMapper} is the shared
 * recipe behind the CDI mapper, the {@code @PersistenceMapper} mapper and the
 * Postgres JSONB mapper. Enabling the flag there stops every stored document
 * that predates a field removal from loading.</li>
 * <li>{@code SerializationCustomizer.customize} is not REST-only either:
 * {@code PersistenceModule.buildMongoClientOptions} calls it on the MongoDB
 * BSON mapper, so enabling the flag there makes MongoDB document <em>reads</em>
 * strict — exactly the persistence mapper that must stay
 * schema-evolution-tolerant.</li>
 * <li>The CDI {@code ObjectMapper} is not REST-only either: it is injected into
 * the MicroProfile REST client and into a dozen services that parse third-party
 * JSON (Slack payloads, web-search responses, LLM output). Strictness there
 * would reject payloads EDDI does not own.</li>
 * </ul>
 *
 * <p>
 * So strictness is applied here, at the one boundary where it belongs: an
 * inbound HTTP body being read into a first-party configuration model. Stored
 * documents, ZIP imports (which deserialize through {@code IJsonSerialization},
 * i.e. the persistence mapper) and instance-to instance sync all keep loading
 * unknown fields exactly as before.
 * </p>
 *
 * <p>
 * Only an unrecognised property is translated. Every other parse failure is
 * left to the regular message-body reader, so no existing error response
 * changes shape.
 * </p>
 *
 * <p>
 * The check itself lives in {@link StrictConfigurationParser}, because it
 * belongs to <em>writing a configuration</em> rather than to reading an HTTP
 * body: a {@code ReaderInterceptor} never fires for MCP's {@code
 * create_resource}, which calls the same stores in-process, so that surface has
 * to invoke the parser directly to get the same answer.
 * </p>
 */
@Provider
public class StrictConfigurationBodyInterceptor implements ReaderInterceptor {

    /**
     * Configuration models live in {@code ai.labs.eddi.configs.<area>.model}, with
     * the LLM configuration as the single exception — it sits under the module that
     * executes it while its store and REST resource live under {@code configs}.
     */
    private static final String CONFIGS_PACKAGE_PREFIX = "ai.labs.eddi.configs.";
    private static final String MODEL_PACKAGE_SUFFIX = ".model";
    private static final String LLM_MODEL_PACKAGE = "ai.labs.eddi.modules.llm.model";

    private static final String JSON_SUBTYPE = "json";

    private final StrictConfigurationParser parser;

    @Inject
    public StrictConfigurationBodyInterceptor(StrictConfigurationParser parser) {
        this.parser = parser;
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        if (!isConfigurationModel(context.getType()) || !isJson(context.getMediaType())) {
            return context.proceed();
        }

        // The body has to be buffered: the strict parse consumes the stream, and the
        // regular reader still needs to see it.
        byte[] body = context.getInputStream().readAllBytes();

        if (body.length == 0) {
            // Deliberately leave the original stream in place. The Quarkus readers
            // short-circuit an empty body by identity (`instanceof EmptyInputStream`),
            // and swapping in an empty ByteArrayInputStream would turn today's "entity
            // is null" path into a Jackson "no content" failure.
            return context.proceed();
        }

        context.setInputStream(new ByteArrayInputStream(body));
        parser.rejectUnknownFields(body, context.getType());

        return context.proceed();
    }

    static boolean isConfigurationModel(Class<?> type) {
        if (type == null) {
            return false;
        }

        String packageName = type.getPackageName();
        return LLM_MODEL_PACKAGE.equals(packageName)
                || (packageName.startsWith(CONFIGS_PACKAGE_PREFIX) && packageName.endsWith(MODEL_PACKAGE_SUFFIX));
    }

    static boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }

        String subtype = mediaType.getSubtype();
        // "json" and the structured-suffix forms such as "merge-patch+json".
        return subtype != null && subtype.toLowerCase(Locale.ROOT).contains(JSON_SUBTYPE);
    }
}
