/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

/**
 * The OAuth identity a channel integration's connection must hold, declared by
 * its type through
 * {@link ChannelTypeDescriptor#requiredConnectionIdentity(java.util.Map)}: the
 * client id (the application the integration says it is), the exact token
 * endpoint and a scope. At save time the connection must also prove it by
 * obtaining a token (see {@link ChannelConnectionCredentials#validateForSave});
 * on every inbound request the three values are checked again without a network
 * call.
 *
 * @param clientId
 *            the application id the connection's {@code oauth.clientId} must
 *            equal
 * @param tokenUrl
 *            the token endpoint the connection must use, exactly
 * @param scope
 *            a scope the connection must request
 * @since 6.7.0
 */
public record ConnectionIdentityRequirement(String clientId, String tokenUrl, String scope) {
}
