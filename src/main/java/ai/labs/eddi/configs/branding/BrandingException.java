/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandViolation;

import java.util.List;

/**
 * A refused brand write, carrying the HTTP status the REST layer answers with.
 */
public class BrandingException extends RuntimeException {

    /** {@code eddi.branding.locked=true}. 409. */
    public static final int LOCKED = 409;
    /** The edit was based on an older revision. 409. */
    public static final int CONFLICT = 409;
    /** A value failed validation. 400. */
    public static final int INVALID = 400;
    /** The image is over the size cap. 413. */
    public static final int TOO_LARGE = 413;
    /** The image is not a type the slot accepts. 415. */
    public static final int UNSUPPORTED_TYPE = 415;

    private final int status;
    private final List<BrandViolation> violations;
    private final Long currentRevision;

    private BrandingException(int status, String message, List<BrandViolation> violations, Long currentRevision) {
        super(message);
        this.status = status;
        this.violations = violations;
        this.currentRevision = currentRevision;
    }

    public static BrandingException locked() {
        return new BrandingException(LOCKED, "The brand is locked by eddi.branding.locked=true: it comes from the eddi.branding.* "
                + "properties and cannot be changed here. Change the properties and restart, or unset eddi.branding.locked.", List.of(), null);
    }

    public static BrandingException conflict(long currentRevision) {
        return new BrandingException(CONFLICT, "The brand was changed by someone else (now at revision " + currentRevision
                + "). Reload it and apply your change again.", List.of(), currentRevision);
    }

    public static BrandingException invalid(List<BrandViolation> violations) {
        StringBuilder message = new StringBuilder("Invalid brand: ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                message.append("; ");
            }
            message.append(violations.get(i).field()).append(' ').append(violations.get(i).message());
        }
        return new BrandingException(INVALID, message.toString(), List.copyOf(violations), null);
    }

    public static BrandingException asset(int status, String message) {
        return new BrandingException(status, message, List.of(), null);
    }

    public int status() {
        return status;
    }

    public List<BrandViolation> violations() {
        return violations;
    }

    /** For a conflict, the revision now stored; otherwise {@code null}. */
    public Long currentRevision() {
        return currentRevision;
    }
}
