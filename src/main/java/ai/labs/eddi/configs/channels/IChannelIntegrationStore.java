/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Persistence store for channel integration configurations (Slack and similar
 * platforms). The REST channel API manages them; {@code ChannelTargetRouter}
 * loads them to route incoming events onto agents or groups. They replace the
 * legacy {@code ChannelConnector} entries on {@code AgentConfiguration}: where
 * an integration here covers a channel type and id, the legacy entries for that
 * pair are ignored.
 *
 * @since 6.1.0
 */
public interface IChannelIntegrationStore extends IResourceStore<ChannelIntegrationConfiguration> {
}
