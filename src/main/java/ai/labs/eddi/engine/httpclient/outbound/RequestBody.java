/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A request body, materialised to bytes once so it can be re-sent unchanged
 * when a 307/308 preserves the method.
 */
public sealed interface RequestBody permits RequestBody.Empty, RequestBody.StringBody, RequestBody.BytesBody,
        RequestBody.FormBody, RequestBody.MultipartBody {

    RequestBody EMPTY = new Empty();

    /** The bytes on the wire; empty for no body. */
    byte[] toBytes();

    /**
     * The {@code Content-Type} this body implies, used only when the request sets
     * none itself; {@code null} when it implies nothing.
     */
    String contentType();

    static RequestBody ofString(String content) {
        return new StringBody(content, StandardCharsets.UTF_8);
    }

    static RequestBody ofBytes(byte[] bytes) {
        return new BytesBody(bytes);
    }

    record Empty() implements RequestBody {
        @Override
        public byte[] toBytes() {
            return new byte[0];
        }

        @Override
        public String contentType() {
            return null;
        }
    }

    /** Text in the given charset. Sets no content type: the caller knows it. */
    record StringBody(String content, Charset charset) implements RequestBody {
        public StringBody {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(charset, "charset");
        }

        @Override
        public byte[] toBytes() {
            return content.getBytes(charset);
        }

        @Override
        public String contentType() {
            return null;
        }
    }

    record BytesBody(byte[] bytes) implements RequestBody {
        public BytesBody {
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public byte[] toBytes() {
            return bytes;
        }

        @Override
        public String contentType() {
            return null;
        }
    }

    /**
     * {@code application/x-www-form-urlencoded}, fields in the given order. A list
     * rather than a map, because a form may repeat a name.
     */
    record FormBody(List<Map.Entry<String, String>> fields) implements RequestBody {
        public FormBody {
            fields = List.copyOf(fields);
        }

        @Override
        public byte[] toBytes() {
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, String> field : fields) {
                if (!encoded.isEmpty()) {
                    encoded.append('&');
                }
                encoded.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(field.getValue() == null ? "" : field.getValue(), StandardCharsets.UTF_8));
            }
            return encoded.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "application/x-www-form-urlencoded";
        }
    }

    /**
     * {@code multipart/form-data}: text fields and files, in order.
     *
     * @param boundary
     *            the part delimiter; {@link #of(List)} picks a random one
     */
    record MultipartBody(List<Part> parts, String boundary) implements RequestBody {
        public MultipartBody {
            parts = List.copyOf(parts);
            Objects.requireNonNull(boundary, "boundary");
        }

        public static MultipartBody of(List<Part> parts) {
            return new MultipartBody(parts, "eddi-" + UUID.randomUUID());
        }

        /**
         * One part. {@code filename} and {@code contentType} are {@code null} for a
         * plain field.
         */
        public record Part(String name, String filename, String contentType, byte[] content) {
            public Part {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(content, "content");
                // Written unquoted as a part header: a line break would end it and let
                // the value add headers, or a whole forged part, to the body.
                if (contentType != null && (contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0)) {
                    throw new IllegalArgumentException("part content type must not contain line breaks");
                }
            }

            public static Part field(String name, String value) {
                return new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8));
            }

            public static Part file(String name, String filename, String contentType, byte[] content) {
                return new Part(name, filename, contentType, content);
            }
        }

        @Override
        public byte[] toBytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Part part : parts) {
                write(out, "--" + boundary + "\r\n");
                StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"")
                        .append(quoted(part.name())).append('"');
                if (part.filename() != null) {
                    disposition.append("; filename=\"").append(quoted(part.filename())).append('"');
                }
                write(out, disposition.append("\r\n").toString());
                if (part.contentType() != null) {
                    write(out, "Content-Type: " + part.contentType() + "\r\n");
                }
                write(out, "\r\n");
                out.writeBytes(part.content());
                write(out, "\r\n");
            }
            write(out, "--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        @Override
        public String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        /**
         * A name or filename inside a quoted header parameter: a quote or a line break
         * there would end the parameter early and let the value inject headers.
         */
        private static String quoted(String value) {
            return value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
        }

        private static void write(ByteArrayOutputStream out, String text) {
            out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
