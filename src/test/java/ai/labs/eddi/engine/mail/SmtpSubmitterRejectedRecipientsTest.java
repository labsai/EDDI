/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which recipients a failed submission names as refused, so a refused
 * {@code Cc} can be dropped and the reply resent.
 */
class SmtpSubmitterRejectedRecipientsTest {

    private static SMTPAddressFailedException refusal(String address, int code) throws Exception {
        return new SMTPAddressFailedException(new InternetAddress(address), "RCPT TO", code, code + " refused");
    }

    @Test
    @DisplayName("a 552 on a Cc is read from the exception chain, where Angus files it among the valid-but-unsent")
    void mailboxFull() throws Exception {
        SendFailedException failed = new SendFailedException("relay refused", refusal("bob@supplier.com", 552), new Address[0],
                new Address[]{new InternetAddress("alice@supplier.com"), new InternetAddress("bob@supplier.com")}, new Address[0]);
        assertEquals(List.of("bob@supplier.com"), SmtpSubmitter.rejectedRecipients(failed));
    }

    @Test
    @DisplayName("invalid addresses are still read, a 4xx is not a refusal, and nobody is listed twice")
    void invalidAndTransient() throws Exception {
        SendFailedException failed = new SendFailedException("relay refused", refusal("carol@supplier.com", 450), new Address[0], new Address[0],
                new Address[]{new InternetAddress("eve@supplier.com")});
        assertEquals(List.of("eve@supplier.com"), SmtpSubmitter.rejectedRecipients(failed));

        MessagingException chained = new MessagingException("x", refusal("eve@supplier.com", 550));
        assertEquals(List.of("eve@supplier.com"), SmtpSubmitter.rejectedRecipients(chained));
    }
}
