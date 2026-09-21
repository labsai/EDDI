/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

/**
 * Fields that exist on the wire but must never reach storage.
 *
 * <h3>Why a mix-in and not an annotation on the field</h3> Jackson has no
 * single annotation for "serialise here, not there" — the model class is shared
 * by the REST mapper and the {@link PersistenceMapper} one, and any annotation
 * on it applies to both. A mix-in is registered per mapper, so the distinction
 * lives with the mapper that has the requirement rather than with the model
 * that does not know about either.
 *
 * <h3>Why the requirement is structural rather than a convention</h3>
 * {@code DocumentDescriptor.callerLevel} describes the relationship between a
 * resource and whoever asked for it, so a value stamped for Alice is wrong for
 * everyone else. Several paths read a descriptor and write it back —
 * {@code patchDescriptor}, {@code ResourceSharingService.writeBack} — and
 * {@code redactForCaller} documents that it must not be called on a descriptor
 * that will be written. Documenting it is not the same as preventing it: the
 * next read-modify-write added to this codebase would persist one user's access
 * level as if it were part of the resource, and every subsequent reader would
 * be told they hold whatever the last writer happened to hold.
 *
 * @author ginccc
 */
public final class PersistenceMixins {

    private PersistenceMixins() {
    }

    /** Registers every mix-in on a mapper that writes to storage. */
    public static ObjectMapper register(ObjectMapper mapper) {
        mapper.addMixIn(DocumentDescriptor.class, DocumentDescriptorMixin.class);
        return mapper;
    }

    /** @see PersistenceMixins */
    abstract static class DocumentDescriptorMixin {

        @JsonIgnore
        abstract String getCallerLevel();

        @JsonIgnore
        abstract void setCallerLevel(String callerLevel);
    }
}
