/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

/**
 * The entity types the built-in detector knows. Entity types are open strings:
 * a remote detector may report others (e.g. {@code PERSON}), so this is a list
 * of names, not an enum. A national identifier is named after its country's ISO
 * 3166 code; {@link PiiCountries} groups them.
 */
public final class PiiEntities {

    // ---- universal ----

    public static final String EMAIL = "EMAIL";
    public static final String PHONE = "PHONE";
    public static final String CREDIT_CARD = "CREDIT_CARD";
    public static final String IBAN = "IBAN";
    /** An IPv4 or IPv6 address. */
    public static final String IP_ADDRESS = "IP_ADDRESS";
    /** The second line of a passport's machine-readable zone (ICAO 9303 TD3). */
    public static final String PASSPORT_MRZ = "PASSPORT_MRZ";
    /** A date written next to a birth keyword ("geb.", "born", "DOB"). */
    public static final String DATE_OF_BIRTH = "DATE_OF_BIRTH";

    // ---- Americas ----

    public static final String US_SSN = "US_SSN";
    /** US Individual Taxpayer Identification Number. */
    public static final String US_ITIN = "US_ITIN";
    /** Canadian Social Insurance Number. */
    public static final String CA_SIN = "CA_SIN";
    /** Brazilian individual taxpayer number (CPF). */
    public static final String BR_CPF = "BR_CPF";
    /** Mexican population registry key (CURP). */
    public static final String MX_CURP = "MX_CURP";

    // ---- Europe ----

    /** UK National Insurance number. */
    public static final String GB_NINO = "GB_NINO";
    /** NHS number (England, Wales, Isle of Man). */
    public static final String GB_NHS = "GB_NHS";
    /** Irish Personal Public Service number. */
    public static final String IE_PPS = "IE_PPS";
    /** French social security number (NIR). */
    public static final String FR_NIR = "FR_NIR";
    /** Italian tax code (codice fiscale). */
    public static final String IT_FISCAL_CODE = "IT_FISCAL_CODE";
    /** Spanish national identity number (DNI) or foreigner number (NIE). */
    public static final String ES_DNI = "ES_DNI";
    /** Portuguese tax number (NIF). */
    public static final String PT_NIF = "PT_NIF";
    /** Dutch citizen service number (BSN). */
    public static final String NL_BSN = "NL_BSN";
    /** Belgian national register number. */
    public static final String BE_NATIONAL_NUMBER = "BE_NATIONAL_NUMBER";
    /** The German tax identification number (Steuer-IdNr), 11 digits. */
    public static final String DE_TAX_ID = "DE_TAX_ID";
    /** A German ID card or passport serial number. */
    public static final String DE_ID_CARD = "DE_ID_CARD";
    /** The German pension insurance number (Rentenversicherungsnummer). */
    public static final String DE_SOCIAL_SECURITY = "DE_SOCIAL_SECURITY";
    /** Austrian social insurance number (Sozialversicherungsnummer). */
    public static final String AT_SOCIAL_SECURITY = "AT_SOCIAL_SECURITY";
    /** Swiss social security number (AHV / AVS). */
    public static final String CH_AHV = "CH_AHV";
    /** Polish national identification number (PESEL). */
    public static final String PL_PESEL = "PL_PESEL";
    /** Swedish personal identity number (personnummer). */
    public static final String SE_PERSONNUMMER = "SE_PERSONNUMMER";
    /** Danish civil registration number (CPR). */
    public static final String DK_CPR = "DK_CPR";
    /** Finnish personal identity code (henkilötunnus). */
    public static final String FI_HETU = "FI_HETU";

    // ---- Asia-Pacific ----

    /** Indian Aadhaar number. */
    public static final String IN_AADHAAR = "IN_AADHAAR";
    /** Indian Permanent Account Number. */
    public static final String IN_PAN = "IN_PAN";
    /** Chinese resident identity card number. */
    public static final String CN_RESIDENT_ID = "CN_RESIDENT_ID";
    /** Japanese individual number (My Number). */
    public static final String JP_MY_NUMBER = "JP_MY_NUMBER";
    /** Korean resident registration number. */
    public static final String KR_RRN = "KR_RRN";
    /** Singapore NRIC or FIN. */
    public static final String SG_NRIC = "SG_NRIC";
    /** Australian tax file number. */
    public static final String AU_TFN = "AU_TFN";
    /** Australian Medicare card number. */
    public static final String AU_MEDICARE = "AU_MEDICARE";
    /** New Zealand IRD number. */
    public static final String NZ_IRD = "NZ_IRD";

    // ---- Africa ----

    /** South African identity number. */
    public static final String ZA_ID = "ZA_ID";

    private PiiEntities() {
    }
}
