/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

/**
 * The entity types the built-in detector knows. Entity types are open strings:
 * a remote detector may report others (e.g. {@code PERSON}), so this is a list
 * of names, not an enum.
 */
public final class PiiEntities {

    public static final String EMAIL = "EMAIL";
    public static final String PHONE = "PHONE";
    public static final String CREDIT_CARD = "CREDIT_CARD";
    public static final String US_SSN = "US_SSN";
    public static final String IBAN = "IBAN";
    /** The German tax identification number (Steuer-IdNr), 11 digits. */
    public static final String DE_TAX_ID = "DE_TAX_ID";
    /** A German ID card or passport serial number. */
    public static final String DE_ID_CARD = "DE_ID_CARD";
    /** The German pension insurance number (Rentenversicherungsnummer). */
    public static final String DE_SOCIAL_SECURITY = "DE_SOCIAL_SECURITY";
    /** A date written next to a birth keyword ("geb.", "born", "DOB"). */
    public static final String DATE_OF_BIRTH = "DATE_OF_BIRTH";

    private PiiEntities() {
    }
}
