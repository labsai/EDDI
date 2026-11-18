/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.BodyTooLargeException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundBlockedException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * The Drive API over {@link OutboundHttp}: the {@code DEFAULT} profile, so the
 * SSRF policy checks the target and every redirect hop, and a redirect to
 * another origin loses the {@code Authorization} header.
 */
@ApplicationScoped
public class OutboundDriveTransport implements DriveTransport {

    /**
     * Per hop. A download's body gets the facade's multiple of this as its own read
     * budget, which a 25 MB file needs on a slow link.
     */
    private static final Duration HOP_TIMEOUT = Duration.ofSeconds(30);

    private final OutboundHttp http;

    @Inject
    public OutboundDriveTransport(OutboundHttp http) {
        this.http = http;
    }

    @Override
    public DriveResponse get(URI uri, ResolvedCredential credential, long maxBytes) throws SourceException {
        OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, uri)
                .header(credential.headerName(), credential.headerValue())
                .timeout(HOP_TIMEOUT)
                .build();
        try {
            OutboundResponse<byte[]> response = http.send(request, BodyMode.bytes(maxBytes, BodyMode.OnOverflow.FAIL));
            return new DriveResponse(response.status(), response.headers(), response.body(), false);
        } catch (BodyTooLargeException e) {
            return DriveResponse.ofTooLarge(200);
        } catch (OutboundBlockedException e) {
            // The API origin is fixed in code, so this is a deployment's SSRF policy
            // or proxy refusing it — a fault in the request, not in any document.
            throw new SourceException(new SourceError.Rejected(
                    "The request to the Google Drive API was blocked: " + e.getMessage()), e);
        } catch (IOException e) {
            throw new SourceException(new SourceError.Transient(
                    "The Google Drive API did not answer: " + describe(e)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(new SourceError.Transient("Interrupted while calling the Google Drive API"), e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
