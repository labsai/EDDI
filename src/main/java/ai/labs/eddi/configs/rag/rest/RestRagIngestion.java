package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.rag.IRestRagIngestion;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.rag.RagIngestionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * REST implementation for RAG document ingestion. Delegates to
 * {@link RagIngestionService} for async processing.
 */
@ApplicationScoped
public class RestRagIngestion implements IRestRagIngestion {

    private static final Logger LOGGER = Logger.getLogger(RestRagIngestion.class);

    private final IRestRagStore restRagStore;
    private final RagIngestionService ragIngestionService;

    @Inject
    public RestRagIngestion(IRestRagStore restRagStore, RagIngestionService ragIngestionService) {
        this.restRagStore = restRagStore;
        this.ragIngestionService = ragIngestionService;
    }

    @Override
    public Response ingestDocument(String ragConfigId, Integer version, String kbId, String documentName, String documentContent) {
        if (documentContent == null || documentContent.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Document content is required")).build();
        }

        RagConfiguration ragConfig;
        try {
            ragConfig = restRagStore.readRag(ragConfigId, version);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load RAG config %s v%d: %s", ragConfigId, version, e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "RAG configuration not found: " + ragConfigId + " v" + version))
                    .build();
        }

        // Use provided kbId, or fall back to the RAG config name, or the config ID
        String effectiveKbId = kbId != null && !kbId.isBlank() ? kbId : ragConfig.getName() != null ? ragConfig.getName() : ragConfigId;

        String ingestionId = ragIngestionService.ingest(effectiveKbId, documentContent, documentName, ragConfig);

        LOGGER.infof("Ingestion started: id=%s, kb=%s, doc=%s, chars=%d", ingestionId, effectiveKbId, documentName, documentContent.length());

        return Response.accepted(Map.of("ingestionId", ingestionId, "kbId", effectiveKbId, "status", "pending")).build();
    }

    @Override
    public Response getIngestionStatus(String ragConfigId, String ingestionId) {
        String status = ragIngestionService.getStatus(ingestionId);
        return Response.ok(Map.of("ingestionId", ingestionId, "status", status)).build();
    }
}
