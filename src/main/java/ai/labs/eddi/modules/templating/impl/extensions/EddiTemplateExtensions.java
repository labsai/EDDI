/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.TemplateExtension;

import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

import static ai.labs.eddi.datastore.serialization.SerializationCustomizer.configureObjectMapper;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Qute template extensions for EDDI custom utilities. Replaces the Thymeleaf
 * UUIDDialect, JsonDialect, and EncoderDialect.
 * <p>
 * Usage in templates:
 * <ul>
 * <li>{uuidUtils:generateUUID()}</li>
 * <li>{uuidUtils:extractId(locationUri)}</li>
 * <li>{uuidUtils:extractVersion(locationUri)}</li>
 * <li>{json:serialize(obj)}</li>
 * <li>{json:deserialize(str)}</li>
 * <li>{encoder:base64(str)}</li>
 * <li>{encoder:base64Url(str)}</li>
 * <li>{encoder:base64Mime(str)}</li>
 * </ul>
 */
public class EddiTemplateExtensions {

    private static final JsonSerialization JSON_SERIALIZATION = new JsonSerialization(configureObjectMapper(new ObjectMapper(), false));

    // ---- UUID utilities (namespace: uuidUtils) ----

    @TemplateExtension(namespace = "uuidUtils")
    static String generateUUID() {
        return UUID.randomUUID().toString();
    }

    @TemplateExtension(namespace = "uuidUtils")
    static String extractId(String locationUri) {
        if (locationUri == null || locationUri.isEmpty()) {
            return "";
        }
        String path = locationUri.contains("?") ? locationUri.substring(0, locationUri.indexOf('?')) : locationUri;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : "";
    }

    @TemplateExtension(namespace = "uuidUtils")
    static String extractVersion(String locationUri) {
        if (locationUri == null || locationUri.isEmpty()) {
            return "";
        }
        String versionParam = "version=";
        int versionIdx = locationUri.indexOf(versionParam);
        if (versionIdx < 0) {
            return "";
        }
        String afterVersion = locationUri.substring(versionIdx + versionParam.length());
        int ampIdx = afterVersion.indexOf('&');
        return ampIdx >= 0 ? afterVersion.substring(0, ampIdx) : afterVersion;
    }

    // ---- JSON utilities (namespace: json) ----

    @TemplateExtension(namespace = "json")
    static String serialize(Object obj) throws IOException {
        return JSON_SERIALIZATION.serialize(obj);
    }

    @TemplateExtension(namespace = "json")
    static Object deserialize(String json) throws IOException {
        return JSON_SERIALIZATION.deserialize(json);
    }

    // ---- Encoder utilities (namespace: encoder) ----

    @TemplateExtension(namespace = "encoder")
    static String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(UTF_8));
    }

    @TemplateExtension(namespace = "encoder")
    static String base64Url(String plain) {
        return Base64.getUrlEncoder().encodeToString(plain.getBytes(UTF_8));
    }

    @TemplateExtension(namespace = "encoder")
    static String base64Mime(String plain) {
        return Base64.getMimeEncoder().encodeToString(plain.getBytes(UTF_8));
    }
}
