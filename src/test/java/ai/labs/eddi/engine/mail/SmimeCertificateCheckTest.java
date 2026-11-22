/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmimeCertificateCheckTest {

    private static final String ADDRESS = "agent@contoso.com";

    private static SmimeCertificateCheck.Result check(SmimeTestCertificates.Leaf leaf, String address) throws Exception {
        return SmimeCertificateCheck.check(SmimeTestCertificates.issue(leaf).identity(), address, Instant.now(), Set.of());
    }

    @Nested
    class Address {

        @Test
        void aCertificateForTheMailboxIsUsable() throws Exception {
            var result = check(SmimeTestCertificates.Leaf.valid(ADDRESS), ADDRESS);
            assertTrue(result.usable(), result.describe());
        }

        @Test
        void comparesNormalised() throws Exception {
            assertTrue(check(SmimeTestCertificates.Leaf.valid(ADDRESS), "Agent@CONTOSO.com").usable());
        }

        @Test
        void aCertificateForAnotherAddressIsRefused() throws Exception {
            var result = check(SmimeTestCertificates.Leaf.valid("ceo@contoso.com"), ADDRESS);
            assertFalse(result.usable());
            assertEquals(List.of(SmimeCertificateCheck.Problem.ADDRESS_MISMATCH), result.problems());
            assertTrue(result.describe().contains("ceo@contoso.com"), result.describe());
        }

        @Test
        void anotherDomainIsRefused() throws Exception {
            assertFalse(check(SmimeTestCertificates.Leaf.valid("agent@contoso.com.evil.example"), ADDRESS).usable());
        }
    }

    @Nested
    class Usages {

        @Test
        void keyUsageWithoutDigitalSignatureIsRefused() throws Exception {
            var result = check(SmimeTestCertificates.Leaf.valid(ADDRESS).withoutDigitalSignature(), ADDRESS);
            assertEquals(List.of(SmimeCertificateCheck.Problem.KEY_USAGE), result.problems());
        }

        @Test
        void nonRepudiationAloneMaySign() throws Exception {
            // RFC 8550 §4.4.2: digitalSignature or nonRepudiation.
            var result = check(SmimeTestCertificates.Leaf.valid(ADDRESS).nonRepudiationOnly(), ADDRESS);
            assertTrue(result.usable(), result.describe());
        }

        @Test
        void extendedKeyUsageWithoutEmailProtectionIsRefused() throws Exception {
            var result = check(SmimeTestCertificates.Leaf.valid(ADDRESS).withoutEmailProtection(), ADDRESS);
            assertEquals(List.of(SmimeCertificateCheck.Problem.EXTENDED_KEY_USAGE), result.problems());
        }
    }

    @Nested
    class Validity {

        @Test
        void anExpiredCertificateIsRefused() throws Exception {
            var result = check(SmimeTestCertificates.Leaf.valid(ADDRESS).expired(), ADDRESS);
            assertTrue(result.problems().contains(SmimeCertificateCheck.Problem.EXPIRED));
            assertTrue(result.daysLeft() < 0);
        }

        @Test
        void expiryWithinThirtyDaysIsReportedButUsable() throws Exception {
            var result = check(SmimeTestCertificates.Leaf.valid(ADDRESS).expiringIn(Duration.ofDays(10).plusHours(1)), ADDRESS);
            assertTrue(result.usable());
            assertTrue(result.expiresSoon());
            assertEquals(10, result.daysLeft());
        }

        @Test
        void aYearLeftIsNotSoon() throws Exception {
            assertFalse(check(SmimeTestCertificates.Leaf.valid(ADDRESS), ADDRESS).expiresSoon());
        }
    }

    @Nested
    class Chain {

        @Test
        void aChainShippedWithItsRootIsCompleteButNotTrusted() throws Exception {
            assertEquals(SmimeCertificateCheck.Chain.COMPLETE, check(SmimeTestCertificates.Leaf.valid(ADDRESS), ADDRESS).chain());
        }

        @Test
        void aChainToAGivenRootIsTrusted() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var result = SmimeCertificateCheck.check(issued.identity(), ADDRESS, Instant.now(),
                    Set.of(new TrustAnchor(SmimeTestCertificates.ca(), null)));
            assertEquals(SmimeCertificateCheck.Chain.TRUSTED, result.chain());
        }

        @Test
        void aLeafWithoutItsIssuerIsBroken() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var result = SmimeCertificateCheck.check(new SmimeIdentity(issued.key(), List.of(issued.leaf())), ADDRESS, Instant.now(), Set.of());
            assertEquals(SmimeCertificateCheck.Chain.BROKEN, result.chain());
            assertTrue(result.problems().contains(SmimeCertificateCheck.Problem.CHAIN));
        }

        @Test
        void leafAndIntermediateWithoutTheRootAreUsableButUntrusted() throws Exception {
            // The typical .p12 of an S/MIME-only CA the JDK does not ship.
            var issued = SmimeTestCertificates.issueUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var result = SmimeCertificateCheck.check(issued.identity(), ADDRESS, Instant.now(), Set.of());
            assertEquals(SmimeCertificateCheck.Chain.UNTRUSTED_ROOT, result.chain());
            assertTrue(result.usable(), result.describe());
            assertTrue(result.chain().untrusted());
        }

        @Test
        void leafAndIntermediateBuildToAGivenRoot() throws Exception {
            var issued = SmimeTestCertificates.issueUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var result = SmimeCertificateCheck.check(issued.identity(), ADDRESS, Instant.now(),
                    Set.of(new TrustAnchor(SmimeTestCertificates.ca(), null)));
            assertEquals(SmimeCertificateCheck.Chain.TRUSTED, result.chain());
            assertFalse(result.chain().untrusted());
        }

        @Test
        void aLeafTheShippedIntermediateDidNotSignIsBroken() throws Exception {
            var issued = SmimeTestCertificates.issueForgedUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var result = SmimeCertificateCheck.check(issued.identity(), ADDRESS, Instant.now(), Set.of());
            assertEquals(SmimeCertificateCheck.Chain.BROKEN, result.chain());
            assertTrue(result.problems().contains(SmimeCertificateCheck.Problem.CHAIN));
        }

        @Test
        void anIntermediateTheShippedRootDidNotSignIsBroken() throws Exception {
            // The root's name is in the bundle, but its key did not sign the intermediate:
            // broken, not "an absent root".
            var issued = SmimeTestCertificates.issueUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            List<X509Certificate> bundle = new ArrayList<>(issued.chain());
            bundle.add(SmimeTestCertificates.impostorRoot());
            var result = SmimeCertificateCheck.check(new SmimeIdentity(issued.key(), bundle), ADDRESS, Instant.now(), Set.of());
            assertEquals(SmimeCertificateCheck.Chain.BROKEN, result.chain());
            assertTrue(result.problems().contains(SmimeCertificateCheck.Problem.CHAIN));
        }

        @Test
        void anUntrustedRootDoesNotExcuseAnExpiredOrMismatchedLeaf() throws Exception {
            var expired = SmimeTestCertificates.issueUnderIntermediate(SmimeTestCertificates.Leaf.valid(ADDRESS).expired());
            var result = SmimeCertificateCheck.check(expired.identity(), ADDRESS, Instant.now(), Set.of());
            assertTrue(result.problems().contains(SmimeCertificateCheck.Problem.EXPIRED), result.describe());
            var other = SmimeTestCertificates.issueUnderIntermediate(SmimeTestCertificates.Leaf.valid("ceo@contoso.com"));
            assertEquals(List.of(SmimeCertificateCheck.Problem.ADDRESS_MISMATCH),
                    SmimeCertificateCheck.check(other.identity(), ADDRESS, Instant.now(), Set.of()).problems());
        }

        @Test
        void aSelfSignedLeafIsComplete() throws Exception {
            assertEquals(SmimeCertificateCheck.Chain.COMPLETE, check(SmimeTestCertificates.Leaf.valid(ADDRESS).selfSignedOnly(), ADDRESS).chain());
        }
    }

    @Nested
    class Loading {

        @Test
        void loadsPemKeyAndChain() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            SmimeIdentity identity = SmimeIdentity.load(SmimeTestCertificates.pem(issued), null);
            assertEquals(issued.leaf(), identity.certificate());
            assertEquals(2, identity.chain().size());
        }

        @Test
        void loadsAPkcs12Keystore() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS).ec());
            SmimeIdentity identity = SmimeIdentity.load(SmimeTestCertificates.pkcs12(issued, "fixturefixture"), "fixturefixture");
            assertEquals(issued.leaf(), identity.certificate());
        }

        @Test
        void aWrongKeystorePasswordNamesNoMaterial() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            String keystore = SmimeTestCertificates.pkcs12(issued, "fixturefixture");
            var e = assertThrows(IllegalArgumentException.class, () -> SmimeIdentity.load(keystore, "wrongwrong"));
            assertFalse(e.getMessage().contains(keystore.substring(0, 20)));
        }

        @Test
        void aKeyWithoutItsCertificateIsRefused() throws Exception {
            var one = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var other = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            String mixed = SmimeTestCertificates.pem(new SmimeTestCertificates.Issued(one.key(), other.chain().subList(0, 1)));
            var e = assertThrows(IllegalArgumentException.class, () -> SmimeIdentity.load(mixed, null));
            assertTrue(e.getMessage().contains("belongs to its private key"), e.getMessage());
        }

        @Test
        void garbageIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> SmimeIdentity.load("not a keystore !!", null));
        }
    }
}
