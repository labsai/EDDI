/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.mongo;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The round trip both datastore tests run, so they check the same thing. */
final class EmailControlRoundTrip {

    private EmailControlRoundTrip() {
    }

    static void assertRoundTrip(ChannelIntegrationStore store) throws Exception {
        var control = new EmailControl(
                List.of(new SenderRole("exec", List.of("cfo@company.com")), new SenderRole("customer", List.of("*@customer-a.com"))),
                Map.of("exec", new RolePolicy(null, null, null, null, null),
                        "customer", new RolePolicy(List.of("support"), List.of("kb_search"), "none", false, 10),
                        "anonymous", new RolePolicy(List.of("support"), List.of(), null, null, null)));
        var config = new ChannelIntegrationConfiguration();
        config.setName("Support");
        config.setChannelType("email");
        config.setPlatformConfig(Map.of("mode", "trigger", "address", "support@company.com"));
        var target = new ChannelTarget();
        target.setName("support");
        target.setTargetId("agent-1");
        config.setTargets(List.of(target));
        config.setDefaultTargetName("support");
        config.setEmailControl(control);

        IResourceStore.IResourceId id = store.create(config);
        ChannelIntegrationConfiguration read = store.read(id.getId(), id.getVersion());
        assertEquals(control.getSenderRoles(), read.getEmailControl().getSenderRoles(), "order and senders kept: first match wins");
        assertEquals(control.getRolePolicies(), read.getEmailControl().getRolePolicies());

        // A new version keeps it, and an old version keeps its own.
        read.setEmailControl(new EmailControl(List.of(new SenderRole("exec", List.of("ceo@company.com"))),
                Map.of("exec", new RolePolicy(null, null, null, null, null))));
        Integer next = store.update(id.getId(), id.getVersion(), read);
        assertEquals(List.of("ceo@company.com"), store.read(id.getId(), next).getEmailControl().getSenderRoles().getFirst().senders());
        assertEquals(control.getSenderRoles(), store.read(id.getId(), id.getVersion()).getEmailControl().getSenderRoles());

        // A non-email document simply has none.
        var slack = new ChannelIntegrationConfiguration();
        slack.setName("Slack");
        slack.setChannelType("slack");
        IResourceStore.IResourceId slackId = store.create(slack);
        assertNull(store.read(slackId.getId(), slackId.getVersion()).getEmailControl());
    }
}
