/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A non-admin reads a channel integration through {@link ConfigSecretRedactor}.
 * The sender roles must survive that copy: the Manager saves what it read, and
 * a copy without {@code emailControl} would be refused as dropping the roles.
 */
@DisplayName("emailControl through the read redaction")
class EmailControlRedactionTest {

    @Test
    @DisplayName("a redacted copy keeps the sender roles and their order")
    void rolesSurviveRedaction() {
        var control = new EmailControl(List.of(new SenderRole("exec", List.of("cfo@company.com")), new SenderRole("customer",
                List.of("*@customer-a.com"))),
                Map.of("exec", new RolePolicy(null, null, null, null, null), "customer",
                        new RolePolicy(List.of("support"), List.of("kb_search"), null, null, 5)));
        var config = new ChannelIntegrationConfiguration();
        config.setName("Support");
        config.setChannelType("email");
        // A literal credential forces the redactor to rebuild the document.
        config.setPlatformConfig(new HashMap<>(Map.of("address", "support@company.com", "password", "fixturefixturefixture")));
        config.setEmailControl(control);

        ChannelIntegrationConfiguration redacted = ConfigSecretRedactor.redact(config, ChannelIntegrationConfiguration.class);

        assertNotEquals("fixturefixturefixture", redacted.getPlatformConfig().get("password"), "the probe must take the rebuild path");
        assertEquals(control.getSenderRoles(), redacted.getEmailControl().getSenderRoles());
        assertEquals(control.getRolePolicies(), redacted.getEmailControl().getRolePolicies());
    }
}
