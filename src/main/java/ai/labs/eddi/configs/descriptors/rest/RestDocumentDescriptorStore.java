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
import ai.labs.eddi.utils.RestUtilities;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, String space) {
        try {
            // The cross-resource listing: it takes the descriptor type as a query
            // parameter rather than deriving it from a store, which makes it the one
            // endpoint that can enumerate every configuration type in the deployment. It
            // has to carry the caller's scope for the same reason each typed store does.
            List<DocumentDescriptor> descriptors = documentDescriptorStore.readDescriptors(type, filter, index, limit, false,
                    accessGuard.listingScope().withinSpace(space));
            descriptors.forEach(accessGuard::redactForCaller);
            return descriptors;
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor readDescriptor(String id, Integer version) {
        // The level is decided against the CURRENT descriptor and carried into the
        // redaction below, because the version being read may be an older one whose
        // recorded owner and grants predate a transfer or a re-share.
        AccessLevel callerLevel = accessGuard.requireAccess(id, AccessLevel.VIEW, "resource");
        try {
            // Redaction mutates in place; the return value is deliberately unused so the
            // response can never become whatever a decorator (or a test double) returns.
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(id, version);
            accessGuard.redactUnlessOwner(descriptor, callerLevel);
            return descriptor;
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
            version = patchTargetVersion(id, version);
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
            requireStillCurrent(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * The descriptor version a patch addressed at {@code version} must write.
     * <p>
     * A name is metadata of the resource, so a patch belongs on the current
     * descriptor. Addressing an older version used to rewrite that history row -
     * or, before the store wrote history rows at all, nothing - and answer 204
     * either way, so a rename from a stale tab silently went nowhere. It is a 409
     * now. Descriptor versions drift from resource versions (a merge import bumps
     * one before the other), so the version of the resource the current descriptor
     * points at is accepted as naming the current descriptor too; that is the
     * number every client holds.
     */
    private Integer patchTargetVersion(String id, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        IResourceStore.IResourceId current = documentDescriptorStore.getCurrentResourceId(id);
        if (version == null || current == null || current.getVersion() == null || current.getVersion().equals(version)) {
            return version;
        }
        DocumentDescriptor currentDescriptor = documentDescriptorStore.readDescriptor(id, current.getVersion());
        IResourceStore.IResourceId live = currentDescriptor == null || currentDescriptor.getResource() == null
                ? null
                : RestUtilities.extractResourceId(currentDescriptor.getResource());
        if (live != null && live.getVersion() != null && live.getVersion().equals(version)) {
            return current.getVersion();
        }
        String message = "Version " + version + " of '" + id + "' is not its current version; patch the current version ("
                + (live != null && live.getVersion() != null ? live.getVersion() : current.getVersion()) + ").";
        throw conflict(message);
    }

    /**
     * A {@code PUT} on the resource that lands between {@link #patchTargetVersion}
     * and the write moves the descriptor on, and the rename then went into the
     * history row of the version it was aimed at, answered with 204. Checked after
     * the write, as {@code ResourceSharingService.writeBack} does, and reported as
     * the 409 it is, so the client can re-read and retry.
     */
    private void requireStillCurrent(String id, Integer writtenVersion) throws IResourceStore.ResourceNotFoundException {
        IResourceStore.IResourceId current = documentDescriptorStore.getCurrentResourceId(id);
        if (writtenVersion != null && current != null && current.getVersion() != null && !current.getVersion().equals(writtenVersion)) {
            throw conflict("The descriptor of '" + id + "' moved to version " + current.getVersion()
                    + " while it was being patched; the change did not reach it. Re-read and patch again.");
        }
    }

    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(message, Response.status(Response.Status.CONFLICT)
                .entity(message)
                .type(MediaType.TEXT_PLAIN)
                .build());
    }
}
