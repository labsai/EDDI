/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.api.IRestGroupConversation.AttachmentRef;
import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import ai.labs.eddi.engine.api.IRestGroupConversation.FollowUpRequest;
import ai.labs.eddi.engine.memory.model.validation.MaxInlineAttachmentSizeValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request-body validation for the group discussion endpoints.
 * <p>
 * These bodies are the ones the review singled out: {@code question} is
 * unbounded free text that goes straight into an LLM prompt and is fanned out
 * to every member agent, and {@code attachments[].data} is inline base64 that
 * used to be accepted at any size, decoded into a byte array, and only then
 * rejected by the blob store — where the failure was logged and the attachment
 * silently dropped.
 * <p>
 * Deliberately a plain JUnit test: it drives Hibernate Validator
 * programmatically rather than booting an HTTP endpoint, so it needs no
 * container and no socket.
 */
class IRestGroupConversationValidationTest {

    /**
     * A validator factory that pins the config-backed inline-attachment ceiling so
     * the oversized-base64 case can be exercised with a 13-character string instead
     * of a 28-million-character one.
     */
    private record CappedValidatorFactory(long maxDecodedBytes) implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            try {
                Constructor<T> ctor = key.getDeclaredConstructor();
                ctor.setAccessible(true);
                T instance = ctor.newInstance();
                if (instance instanceof MaxInlineAttachmentSizeValidator v) {
                    Field field = MaxInlineAttachmentSizeValidator.class.getDeclaredField("maxDecodedBytes");
                    field.setAccessible(true);
                    field.setLong(v, maxDecodedBytes);
                }
                return instance;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not instantiate " + key.getName(), e);
            }
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // no-op
        }
    }

    private static Validator validator(long inlineAttachmentCapBytes) {
        return Validation.byProvider(HibernateValidator.class)
                .configure()
                // Avoids requiring a Jakarta EL implementation on the test classpath;
                // {max} placeholders are still substituted.
                .messageInterpolator(new ParameterMessageInterpolator())
                .constraintValidatorFactory(new CappedValidatorFactory(inlineAttachmentCapBytes))
                .buildValidatorFactory()
                .getValidator();
    }

    private static Validator validator() {
        return validator(MaxInlineAttachmentSizeValidator.DEFAULT_MAX_DECODED_BYTES);
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static String messageFor(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(path))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no violation on '" + path + "', got " + paths(violations)));
    }

    @Nested
    @DisplayName("DiscussRequest")
    class DiscussRequestValidation {

        @Test
        @DisplayName("a realistic request produces no violations — valid bodies still succeed unchanged")
        void validRequestPasses() {
            var request = new DiscussRequest(
                    "Should we migrate the billing service to event sourcing?",
                    "user-1",
                    List.of(new AttachmentRef("application/pdf", "QUJD", null, "rfc.pdf")));

            assertTrue(validator().validate(request).isEmpty());
        }

        @Test
        @DisplayName("a question at the ceiling is still accepted")
        void questionAtCeilingPasses() {
            var request = new DiscussRequest("q".repeat(IRestGroupConversation.MAX_QUESTION_CHARS), "user-1");

            assertTrue(validator().validate(request).isEmpty());
        }

        @Test
        @DisplayName("a blank question is rejected with a message naming the field")
        void blankQuestionRejected() {
            for (String blank : new String[]{"", "   ", "\t\n"}) {
                var violations = validator().validate(new DiscussRequest(blank, "user-1"));

                assertTrue(paths(violations).contains("question"), "blank=<" + blank + ">");
                assertTrue(messageFor(violations, "question").contains("'question'"));
            }
        }

        @Test
        @DisplayName("a null question is rejected")
        void nullQuestionRejected() {
            var violations = validator().validate(new DiscussRequest(null, "user-1"));

            assertTrue(paths(violations).contains("question"));
        }

        @Test
        @DisplayName("a question one character over the ceiling is rejected, naming the field and the limit")
        void oversizedQuestionRejected() {
            var request = new DiscussRequest("q".repeat(IRestGroupConversation.MAX_QUESTION_CHARS + 1), "user-1");

            var violations = validator().validate(request);

            assertTrue(paths(violations).contains("question"));
            String message = messageFor(violations, "question");
            assertTrue(message.contains("'question'"), message);
            assertTrue(message.contains(String.valueOf(IRestGroupConversation.MAX_QUESTION_CHARS)), message);
        }

        @Test
        @DisplayName("an oversized userId is rejected")
        void oversizedUserIdRejected() {
            var request = new DiscussRequest("q", "u".repeat(IRestGroupConversation.MAX_IDENTIFIER_CHARS + 1));

            var violations = validator().validate(request);

            assertTrue(paths(violations).contains("userId"));
            assertTrue(messageFor(violations, "userId").contains("'userId'"));
        }

        @Test
        @DisplayName("more attachments than a conversation can hold is rejected")
        void tooManyAttachmentsRejected() {
            var many = new ArrayList<AttachmentRef>();
            for (int i = 0; i <= IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST; i++) {
                many.add(new AttachmentRef("image/png", "QUJD", null, "f" + i + ".png"));
            }

            var violations = validator().validate(new DiscussRequest("q", "user-1", many));

            assertTrue(paths(violations).contains("attachments"));
            assertTrue(messageFor(violations, "attachments").contains("'attachments'"));
        }

        @Test
        @DisplayName("the attachment list at the ceiling is accepted")
        void attachmentsAtCeilingPass() {
            var many = new ArrayList<AttachmentRef>();
            for (int i = 0; i < IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST; i++) {
                many.add(new AttachmentRef("image/png", "QUJD", null, "f" + i + ".png"));
            }

            assertTrue(validator().validate(new DiscussRequest("q", "user-1", many)).isEmpty());
        }
    }

    @Nested
    @DisplayName("AttachmentRef (cascaded from DiscussRequest)")
    class AttachmentRefValidation {

        @Test
        @DisplayName("oversized inline base64 data is rejected against the configured attachment cap")
        void oversizedInlineDataRejected() {
            // 9 decoded bytes → a 12-character base64 ceiling.
            var request = new DiscussRequest("q", "user-1",
                    List.of(new AttachmentRef("image/png", "a".repeat(13), null, "big.png")));

            var violations = validator(9).validate(request);

            assertTrue(paths(violations).contains("attachments[0].data"), "got " + paths(violations));
            assertTrue(messageFor(violations, "attachments[0].data").contains("eddi.attachments.max-size-bytes"));
        }

        @Test
        @DisplayName("inline base64 data at the configured cap is accepted")
        void inlineDataAtCapAccepted() {
            var request = new DiscussRequest("q", "user-1",
                    List.of(new AttachmentRef("image/png", "a".repeat(12), null, "ok.png")));

            assertTrue(validator(9).validate(request).isEmpty());
        }

        @Test
        @DisplayName("constraints inside an attachment are reached — @Valid cascades into the list")
        void nestedConstraintsCascade() {
            var request = new DiscussRequest("q", "user-1", List.of(
                    new AttachmentRef("m".repeat(IRestGroupConversation.MAX_MIME_TYPE_CHARS + 1), null,
                            "https://example.org/a.png", "a.png")));

            var violations = validator().validate(request);

            assertTrue(paths(violations).contains("attachments[0].mimeType"), "got " + paths(violations));
            assertTrue(messageFor(violations, "attachments[0].mimeType").contains("'mimeType'"));
        }

        @Test
        @DisplayName("an oversized url and fileName are rejected")
        void oversizedUrlAndFileNameRejected() {
            var request = new DiscussRequest("q", "user-1", List.of(
                    new AttachmentRef("image/png", null,
                            "https://example.org/" + "u".repeat(IRestGroupConversation.MAX_URL_CHARS),
                            "f".repeat(IRestGroupConversation.MAX_FILE_NAME_CHARS + 1))));

            var violations = validator().validate(request);

            assertTrue(paths(violations).contains("attachments[0].url"), "got " + paths(violations));
            assertTrue(paths(violations).contains("attachments[0].fileName"), "got " + paths(violations));
        }
    }

    @Nested
    @DisplayName("FollowUpRequest")
    class FollowUpRequestValidation {

        @Test
        @DisplayName("a valid follow-up produces no violations")
        void validFollowUpPasses() {
            assertTrue(validator().validate(new FollowUpRequest("Why?", "agent-1", "user-1")).isEmpty());
        }

        @Test
        @DisplayName("blank question and blank targetAgentId are both reported, each naming its field")
        void blankFieldsRejected() {
            var violations = validator().validate(new FollowUpRequest("  ", "", "user-1"));

            assertTrue(paths(violations).contains("question"));
            assertTrue(paths(violations).contains("targetAgentId"));
            assertTrue(messageFor(violations, "targetAgentId").contains("'targetAgentId'"));
        }

        @Test
        @DisplayName("an oversized question is rejected")
        void oversizedQuestionRejected() {
            var violations = validator().validate(new FollowUpRequest(
                    "q".repeat(IRestGroupConversation.MAX_QUESTION_CHARS + 1), "agent-1", "user-1"));

            assertTrue(paths(violations).contains("question"));
        }

        @Test
        @DisplayName("an oversized targetAgentId is rejected")
        void oversizedTargetAgentIdRejected() {
            var violations = validator().validate(new FollowUpRequest(
                    "q", "a".repeat(IRestGroupConversation.MAX_IDENTIFIER_CHARS + 1), "user-1"));

            assertTrue(paths(violations).contains("targetAgentId"));
        }
    }

    @Nested
    @DisplayName("endpoint contract")
    class EndpointContract {

        /**
         * The record constraints above only fire in production if the endpoint's body
         * parameter is actually marked for cascaded validation. Direct-invocation unit
         * tests cannot see this, so assert it on the annotations themselves — the same
         * technique {@code IRestGroupConversationRoutingTest} uses for {@code @Path}.
         */
        @Test
        @DisplayName("every DiscussRequest/FollowUpRequest body parameter carries @NotNull and @Valid")
        void bodyParametersAreValidated() {
            var unguarded = new ArrayList<String>();
            int checked = 0;

            for (Method method : IRestGroupConversation.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    Class<?> type = parameter.getType();
                    if (type != DiscussRequest.class && type != FollowUpRequest.class) {
                        continue;
                    }
                    checked++;
                    if (parameter.getAnnotation(Valid.class) == null) {
                        unguarded.add(method.getName() + " is missing @Valid");
                    }
                    if (parameter.getAnnotation(NotNull.class) == null) {
                        unguarded.add(method.getName() + " is missing @NotNull");
                    }
                }
            }

            assertTrue(unguarded.isEmpty(), () -> "unvalidated request bodies: " + unguarded);
            // discuss, discussStreaming, continueDiscussion,
            // continueDiscussionStreaming, followUpWithMember
            assertEquals(5, checked, "expected 5 request-body parameters to be guarded");
        }

        @Test
        @DisplayName("the ceilings are documented constants, not inline magic numbers")
        void ceilingsAreNamedConstants() {
            assertEquals(50_000, IRestGroupConversation.MAX_QUESTION_CHARS);
            assertEquals(256, IRestGroupConversation.MAX_IDENTIFIER_CHARS);
            assertEquals(50, IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST);
            assertEquals(255, IRestGroupConversation.MAX_MIME_TYPE_CHARS);
            assertEquals(255, IRestGroupConversation.MAX_FILE_NAME_CHARS);
            assertEquals(2048, IRestGroupConversation.MAX_URL_CHARS);
        }
    }
}
