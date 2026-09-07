/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Persistence store for channel integration configurations (Slack and similar
 * platforms). {@code ChannelTargetRouter} and the REST channel API load these
 * to route incoming events onto agents or groups, independently of
 * {@code AgentConfiguration}.
 *
 * @since 6.1.0
 */
public interface IChannelIntegrationStore extends IResourceStore<ChannelIntegrationConfiguration> {
}
