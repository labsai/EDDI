/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * The full-application email IT's {@link SafeSocketConnector}: the production
 * author policy, unchanged, over a DNS that answers a public documentation
 * address for {@link E2eMailServers#IMAP_HOST} and
 * {@link E2eMailServers#SMTP_HOST}. Every check {@code resolve} makes — blocked
 * names, {@code eddi.email.allowed-hosts}, the protocol's TLS ports, the
 * private-address rule ({@link UrlValidationUtils#isPrivateAddress}) — runs on
 * every call, at save time and before every socket. Only an endpoint that
 * passed is then re-pinned to the loopback GreenMail server, on its ephemeral
 * port, with the same host name, protocol and TLS mode, so TLS and host-name
 * verification still run against {@code imap.mail.test}.
 * <p>
 * Enabled only by the IT's test profile ({@code getEnabledAlternatives}).
 */
@Alternative
@Singleton
public class E2eMailSocketConnector extends SafeSocketConnector {

    public E2eMailSocketConnector() throws Exception {
        super(List.of(), E2eMailServers::resolve, UrlValidationUtils::isPrivateAddress, E2eMailServers.clientContext(), Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }

    @Override
    public Endpoint resolve(String host, int port, Protocol protocol) throws SocketPolicyException {
        Endpoint vetted = super.resolve(host, port, protocol);
        if (!E2eMailServers.isTestHost(vetted.host())) {
            return vetted;
        }
        int testPort = protocol == Protocol.IMAP ? E2eMailServers.imapsPort : E2eMailServers.smtpsPort;
        if (vetted.tlsMode() != TlsMode.IMPLICIT || testPort <= 0) {
            // The GreenMail stand-ins speak implicit TLS only.
            throw new SocketPolicyException(SocketPolicyException.Reason.UNRESOLVABLE, "No test server for " + vetted);
        }
        return new Endpoint(vetted.host(), testPort, protocol, vetted.tlsMode(), InetAddress.getLoopbackAddress());
    }
}
