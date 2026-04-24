/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class ConversationLog {
    private List<ConversationPart> messages = new LinkedList<>();

    @Override
    public String toString() {
        return messages.stream().map(part -> part.getRole() + ": " + part.getContent()).collect(Collectors.joining("\n"));
    }

    public Object toObject() {
        return messages;
    }

    public static class ConversationPart {
        private String role;
        private List<Content> content;

        public static class Content {
            private ContentType type;
            private String value;

            public Content() {
            }

            public Content(ContentType type, String value) {
                this.type = type;
                this.value = value;
            }

            public ContentType getType() {
                return type;
            }

            public void setType(ContentType type) {
                this.type = type;
            }

            public String getValue() {
                return value;
            }

            public void setValue(String value) {
                this.value = value;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o)
                    return true;
                if (o == null || getClass() != o.getClass())
                    return false;
                Content that = (Content) o;
                return java.util.Objects.equals(type, that.type) && java.util.Objects.equals(value, that.value);
            }

            @Override
            public int hashCode() {
                return java.util.Objects.hash(type, value);
            }

            @Override
            public String toString() {
                return "Content(" + "type=" + type + ", value=" + value + ")";
            }
        }

        public enum ContentType {
            text, image, pdf, audio, video
        }

        public ConversationPart() {
        }

        public ConversationPart(String role, List<Content> content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public List<Content> getContent() {
            return content;
        }

        public void setContent(List<Content> content) {
            this.content = content;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ConversationPart that = (ConversationPart) o;
            return java.util.Objects.equals(role, that.role) && java.util.Objects.equals(content, that.content);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(role, content);
        }

        @Override
        public String toString() {
            return "ConversationPart(" + "role=" + role + ", content=" + content + ")";
        }
    }

    public ConversationLog() {
    }

    public ConversationLog(List<ConversationPart> messages) {
        this.messages = messages;
    }

    public List<ConversationPart> getMessages() {
        return messages;
    }

    public void setMessages(List<ConversationPart> messages) {
        this.messages = messages;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ConversationLog that = (ConversationLog) o;
        return java.util.Objects.equals(messages, that.messages);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(messages);
    }
}
