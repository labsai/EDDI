/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.SimpleDocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import java.util.List;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestDocumentDescriptorStore implements IRestDocumentDescriptorStore {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard accessGuard;

    private static final Logger log = Logger.getLogger(RestDocumentDescriptorStore.class);

    @Inject
    public RestDocumentDescriptorStore(IDocumentDescriptorStore documentDescriptorStore, ResourceAccessGuard accessGuard) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.accessGuard = accessGuard;
    }

    @Override
    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit) {
        try {
            // The cross-resource listing: it takes the descriptor type as a query
            // parameter rather than deriving it from a store, which makes it the one
            // endpoint that can enumerate every configuration type in the deployment. It
            // has to carry the caller's scope for the same reason each typed store does.
            return documentDescriptorStore.readDescriptors(type, filter, index, limit, false, accessGuard.listingScope());
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor readDescriptor(String id, Integer version) {
        accessGuard.requireAccess(id, AccessLevel.VIEW, "resource");
        try {
            return documentDescriptorStore.readDescriptor(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public SimpleDocumentDescriptor readSimpleDescriptor(String id, @Parameter(name = "version", required = true, example = "1") Integer version) {
        DocumentDescriptor documentDescriptor = readDescriptor(id, version);
        return new SimpleDocumentDescriptor(documentDescriptor.getName(), documentDescriptor.getDescription());
    }

    /**
     * Applies a partial update to a descriptor.
     * <p>
     * <b>SET merges.</b> Only the fields the patch actually carries are written;
     * everything else is left alone. This endpoint calls itself "Partial update"
     * and used to assign both fields unconditionally, so a caller sending just
     * {@code description} — exactly what the documentation describes — wiped the
     * descriptor's {@code name} and got a 204 for it. The agent then rendered as
     * unnamed everywhere it was listed, and recovering it meant knowing to re-send
     * both fields.
     * <p>
     * <b>DELETE clears.</b> With no document, or one naming no fields, it clears
     * both, as before; when the document names fields, it clears exactly those.
     */
    @Override
    public void patchDescriptor(String id, Integer version, PatchInstruction<DocumentDescriptor> patchInstruction) {
        // A body that is not the PatchInstruction wrapper deserialises to an
        // instruction
        // with a null operation, which used to be dereferenced — a client-side shape
        // error surfaced as a 500 error page naming a line of Java. Say what was
        // expected instead.
        // Renaming somebody else's agent is a modification of it, even though the
        // configuration document is untouched — the name is what everyone identifies it
        // by in every listing.
        accessGuard.requireAccess(id, AccessLevel.EDIT, "resource");

        if (patchInstruction == null || patchInstruction.getOperation() == null) {
            throw new BadRequestException("A patch body must be a PatchInstruction: "
                    + "{\"operation\":\"SET|DELETE\",\"document\":{\"name\":\"…\",\"description\":\"…\"}}");
        }

        try {
            DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptor(id, version);
            DocumentDescriptor patch = patchInstruction.getDocument();

            if (patchInstruction.getOperation() == PatchInstruction.PatchOperation.SET) {
                if (patch == null) {
                    throw new BadRequestException("A SET patch must carry a 'document' with the fields to update.");
                }
                if (patch.getName() != null) {
                    documentDescriptor.setName(patch.getName());
                }
                if (patch.getDescription() != null) {
                    documentDescriptor.setDescription(patch.getDescription());
                }
            } else {
                boolean namesFields = patch != null && (patch.getName() != null || patch.getDescription() != null);
                if (!namesFields || patch.getName() != null) {
                    documentDescriptor.setName("");
                }
                if (!namesFields || patch.getDescription() != null) {
                    documentDescriptor.setDescription("");
                }
            }

            // Name/description only; ownership is untouched here. The index is rebuilt so
            // a descriptor predating it converges on this write rather than waiting for
            // the backfill migration.
            accessGuard.stampModification(documentDescriptor);
            documentDescriptorStore.setDescriptor(id, version, documentDescriptor);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }
}
