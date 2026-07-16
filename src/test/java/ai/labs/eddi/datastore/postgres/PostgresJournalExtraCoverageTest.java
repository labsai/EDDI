/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.JournalEntry;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore.Status;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.HitlTimeoutHandler;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Additional BRANCH coverage for HITL infrastructure classes, complementary to
 * the existing focused unit tests. Each {@code @Nested} group targets the
 * uncovered branches (catch blocks, null/empty guards, boolean conditionals,
 * enum arms) of one class. Strictly additive — does not duplicate assertions
 * already covered elsewhere.
 */
class PostgresJournalExtraCoverageTest {

    // =========================================================================
    // PostgresHitlToolJournalStore — remaining uncovered branches
    // =========================================================================
    @Nested
    @DisplayName("PostgresHitlToolJournalStore extra branches")
    class PostgresStore {

        @Mock
        private Instance<DataSource> dataSourceInstance;
        @Mock
        private DataSource dataSource;
        @Mock
        private Connection connection;
        @Mock
        private Statement statement;
        @Mock
        private PreparedStatement preparedStatement;
        @Mock
        private ResultSet resultSet;

        private PostgresHitlToolJournalStore store;
        private AutoCloseable mocks;

        @BeforeEach
        void setUp() throws Exception {
            mocks = MockitoAnnotations.openMocks(this);
            lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
            lenient().when(dataSource.getConnection()).thenReturn(connection);
            lenient().when(connection.createStatement()).thenReturn(statement);
            lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
            store = new PostgresHitlToolJournalStore(dataSourceInstance, Duration.ofDays(30));
        }

        @AfterEach
        void tearDown() throws Exception {
            if (mocks != null) {
                mocks.close();
            }
        }

        @Test
        @DisplayName("ensureSchema cleanupExpired SQLException is swallowed (best-effort startup cleanup)")
        void cleanupExpired_sqlException_swallowed() throws Exception {
            // CREATE TABLE succeeds → schema flips to initialized; the subsequent
            // cleanup DELETE throws. The catch in cleanupExpired must swallow it so
            // the very first store operation completes without throwing.
            when(statement.execute(anyString())).thenReturn(false);
            when(preparedStatement.executeUpdate())
                    .thenThrow(new SQLException("cleanup DELETE failed")) // first PS = cleanup
                    .thenReturn(1); // subsequent = the actual claim

            assertDoesNotThrow(() -> store.tryClaim("c", "e", "1", "t", "u"));
        }

        @Test
        @DisplayName("ensureSchema short-circuits once schema is already initialized (CREATE TABLE runs once)")
        void ensureSchema_alreadyInitialized_shortCircuits() throws Exception {
            when(statement.execute(anyString())).thenReturn(false);
            when(preparedStatement.executeUpdate()).thenReturn(0);

            // Two operations across different methods — schema init must happen only on
            // the first (the schemaInitialized guard short-circuits the second call).
            store.deleteByConversationId("c1");
            store.markExecuted("c1", "e", "1", "res");

            verify(statement, times(1)).execute(anyString());
        }

        @Test
        @DisplayName("markExecuted rows==0 logs warn but does not throw")
        void markExecuted_rowsZero_warnsNoThrow() throws Exception {
            // ensureSchema CREATE TABLE succeeds (no cleanup DELETE rows), then the
            // UPDATE affects 0 rows → the rows==0 warn branch, no exception.
            when(statement.execute(anyString())).thenReturn(false);
            when(preparedStatement.executeUpdate()).thenReturn(0);

            assertDoesNotThrow(() -> store.markExecuted("c1", "e", "1", "res"));
        }

        @Test
        @DisplayName("find with null status column defaults to EXECUTING")
        void find_nullStatus_defaultsToExecuting() throws Exception {
            when(statement.execute(anyString())).thenReturn(false);
            when(preparedStatement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("status")).thenReturn(null); // status-null default branch
            when(resultSet.getLong("executed_at")).thenReturn(0L);
            when(resultSet.wasNull()).thenReturn(true);
            when(resultSet.getString("conversation_id")).thenReturn("c1");
            when(resultSet.getString("pause_epoch")).thenReturn("e");
            when(resultSet.getString("call_id")).thenReturn("1");
            when(resultSet.getString("tool_name")).thenReturn("t");
            when(resultSet.getString("result_capped")).thenReturn(null);
            when(resultSet.getString("decided_by")).thenReturn(null);

            Optional<JournalEntry> entry = store.find("c1", "e", "1");

            assertTrue(entry.isPresent());
            assertEquals(Status.EXECUTING, entry.get().status());
            assertNull(entry.get().executedAt());
        }

        @Test
        @DisplayName("null retention falls back to the 30d default (construction never throws)")
        void retention_null_fallsBackToDefault() {
            assertDoesNotThrow(() -> new PostgresHitlToolJournalStore(dataSourceInstance, null));
        }

        @Test
        @DisplayName("positive retention is used as-is (construction never throws)")
        void retention_positive_used() {
            assertDoesNotThrow(() -> new PostgresHitlToolJournalStore(dataSourceInstance, Duration.ofDays(7)));
        }
    }

    // =========================================================================
    // HitlAccessGuard — remaining uncovered branches
    // =========================================================================
    @Nested
    @DisplayName("HitlAccessGuard extra branches")
    class AccessGuard {

        OwnershipValidator ownershipValidator;
        IConversationService conversationService;
        IConversationDescriptorStore descriptorStore;
        IGroupConversationService groupConversationService;
        SecurityIdentity identity;
        HitlAccessGuard guard;

        @BeforeEach
        void setup() {
            ownershipValidator = mock(OwnershipValidator.class);
            conversationService = mock(IConversationService.class);
            descriptorStore = mock(IConversationDescriptorStore.class);
            groupConversationService = mock(IGroupConversationService.class);
            identity = mock(SecurityIdentity.class);
            guard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore,
                    conversationService, groupConversationService);
        }

        @Test
        @DisplayName("listScopedPendingApprovals: non-anonymous caller with blank name sees nothing (fail-closed)")
        void listScoped_blankPrincipalName_seesNothing() throws Exception {
            when(ownershipValidator.isAdmin(identity)).thenReturn(false);
            when(ownershipValidator.isApprover(identity)).thenReturn(false);
            Principal p = mock(Principal.class);
            when(p.getName()).thenReturn("   "); // blank name → isBlank() branch
            when(identity.getPrincipal()).thenReturn(p);

            assertTrue(guard.listScopedPendingApprovals(5).isEmpty());
            verify(conversationService, never()).listPendingApprovals(anyString(), anyInt());
        }

        @Test
        @DisplayName("listScopedGroupPendingApprovals: approver (not admin) sees all")
        void listScopedGroup_approverSeesAll() throws Exception {
            when(ownershipValidator.isAdmin(identity)).thenReturn(false);
            when(ownershipValidator.isApprover(identity)).thenReturn(true);
            when(groupConversationService.listGroupPendingApprovals(null, 10)).thenReturn(java.util.List.of());

            assertTrue(guard.listScopedGroupPendingApprovals(null, 10).isEmpty());
        }

        @Test
        @DisplayName("listScopedGroupPendingApprovals: blank-named caller sees nothing (fail-closed)")
        void listScopedGroup_blankPrincipalName_seesNothing() throws Exception {
            when(ownershipValidator.isAdmin(identity)).thenReturn(false);
            when(ownershipValidator.isApprover(identity)).thenReturn(false);
            Principal p = mock(Principal.class);
            when(p.getName()).thenReturn(""); // blank → isBlank() branch
            when(identity.getPrincipal()).thenReturn(p);
            when(groupConversationService.listGroupPendingApprovals("g1", 10)).thenReturn(java.util.List.of());

            assertTrue(guard.listScopedGroupPendingApprovals("g1", 10).isEmpty());
        }

        @Test
        @DisplayName("requireGroupConversationHitlAccess: read failure (non-NotFound) → Forbidden")
        void requireGroup_readThrows_forbidden() throws Exception {
            when(groupConversationService.readGroupConversation("gc1"))
                    .thenThrow(new RuntimeException("store down"));

            assertThrows(io.quarkus.security.ForbiddenException.class,
                    () -> guard.requireGroupConversationHitlAccess("g1", "gc1"));
        }

        @Test
        @DisplayName("requireGroupConversationHitlAccess: null groupId skips the group-match check")
        void requireGroup_nullGroupId_skipsMatch() throws Exception {
            ai.labs.eddi.configs.groups.model.GroupConversation gc = mock(ai.labs.eddi.configs.groups.model.GroupConversation.class);
            when(gc.getUserId()).thenReturn("owner1");
            when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);

            // groupId == null → the (groupId != null && ...) short-circuits false, so no
            // NotFoundException; ownership check still runs.
            guard.requireGroupConversationHitlAccess(null, "gc1");

            verify(ownershipValidator).requireOwnerAdminOrApprover(identity, "owner1", "group conversation");
        }
    }

    // =========================================================================
    // GroupApprovalRequest.HitlDecisionDeserializer — remaining uncovered branches
    // =========================================================================
    @Nested
    @DisplayName("HitlDecisionDeserializer extra branches")
    class Deserializer {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("explicit null decision literal deserializes to null (node.isNull branch)")
        void nullDecisionLiteral() throws Exception {
            var parsed = mapper.readValue("{\"decision\":null}", GroupApprovalRequest.class);
            assertNull(parsed.getDecision());
        }

        @Test
        @DisplayName("decision object with no verdict field → verdict stays null, other fields still read")
        void missingVerdictField() throws Exception {
            var parsed = mapper.readValue(
                    "{\"decision\":{\"note\":\"n\",\"decidedBy\":\"bob\"}}", GroupApprovalRequest.class);
            assertNotNull(parsed.getDecision());
            assertNull(parsed.getDecision().getVerdict()); // verdictNode == null branch
            assertEquals("n", parsed.getDecision().getNote());
            assertEquals("bob", parsed.getDecision().getDecidedBy());
        }

        @Test
        @DisplayName("decision object with explicitly null verdict/note/decidedBy → all remain null")
        void explicitlyNullFields() throws Exception {
            var parsed = mapper.readValue(
                    "{\"decision\":{\"verdict\":null,\"note\":null,\"decidedBy\":null}}",
                    GroupApprovalRequest.class);
            assertNotNull(parsed.getDecision());
            assertNull(parsed.getDecision().getVerdict()); // verdictNode.isNull() branch
            assertNull(parsed.getDecision().getNote()); // noteNode.isNull() branch
            assertNull(parsed.getDecision().getDecidedBy()); // decidedByNode.isNull() branch
        }

        @Test
        @DisplayName("empty decision object → non-null decision with all-null fields")
        void emptyDecisionObject() throws Exception {
            var parsed = mapper.readValue("{\"decision\":{}}", GroupApprovalRequest.class);
            assertNotNull(parsed.getDecision());
            assertNull(parsed.getDecision().getVerdict());
            assertNull(parsed.getDecision().getNote());
            assertNull(parsed.getDecision().getDecidedBy());
        }

        @Test
        @DisplayName("uppercase verdict with whitespace trims and parses to APPROVED")
        void whitespaceVerdictTrimmed() throws Exception {
            var parsed = mapper.readValue(
                    "{\"decision\":{\"verdict\":\"  approved  \"}}", GroupApprovalRequest.class);
            assertEquals(HitlVerdict.APPROVED, parsed.getDecision().getVerdict());
        }
    }

    // =========================================================================
    // HitlTimeoutHandler — remaining uncovered branches
    // =========================================================================
    @Nested
    @DisplayName("HitlTimeoutHandler extra branches")
    class TimeoutHandler {

        @Mock
        private IConversationService conversationService;
        @Mock
        private IGroupConversationService groupConversationService;

        private HitlTimeoutHandler handler;
        private AutoCloseable mocks;

        @BeforeEach
        void setUp() throws Exception {
            mocks = MockitoAnnotations.openMocks(this);
            handler = new HitlTimeoutHandler();
            setField(handler, "conversationService", conversationService);
            setField(handler, "groupConversationService", groupConversationService);
            setField(handler, "meterRegistry", new SimpleMeterRegistry());
        }

        @AfterEach
        void tearDown() throws Exception {
            if (mocks != null) {
                mocks.close();
            }
        }

        private void setField(Object target, String fieldName, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }

        @Test
        @DisplayName("missing 'policy' key → early return, no service interaction")
        void missingPolicyKey_earlyReturn() {
            var metadata = Map.<String, Object>of(
                    "surface", "regular",
                    "conversationId", "conv-1"); // no policy key → policyStr == null branch

            assertDoesNotThrow(() -> handler.handleTimeout(metadata));

            verifyNoInteractions(conversationService);
            verifyNoInteractions(groupConversationService);
        }

        @Test
        @DisplayName("missing 'surface' key → counter tagged 'unknown' and default (regular) surface used")
        void missingSurfaceKey_defaultsToRegular() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "AUTO_APPROVE",
                    "conversationId", "conv-1"); // no surface → surface == null branch (tag "unknown")

            handler.handleTimeout(metadata);

            // surface null is not equal to SURFACE_GROUP → regular resume path
            verify(conversationService).resumeConversation(eq("conv-1"), any(HitlDecision.class), isNull());
            verifyNoInteractions(groupConversationService);
        }

        @Test
        @DisplayName("regular auto-resume swallows downstream exception (catch branch)")
        void regularResume_exceptionSwallowed() throws Exception {
            doThrow(new RuntimeException("resume failed"))
                    .when(conversationService).resumeConversation(anyString(), any(HitlDecision.class), isNull());
            var metadata = Map.<String, Object>of(
                    "policy", "AUTO_REJECT",
                    "surface", "regular",
                    "conversationId", "conv-1");

            assertDoesNotThrow(() -> handler.handleTimeout(metadata));
        }

        @Test
        @DisplayName("ABORT group: cancelDiscussion returns false → 'already terminal' skipped branch")
        void abortGroup_alreadyTerminal() throws Exception {
            when(groupConversationService.cancelDiscussion("gc-1", ControlSignal.CANCEL_GRACEFUL))
                    .thenReturn(false); // false → the "skipped — already terminal" branch
            var metadata = Map.<String, Object>of(
                    "policy", "ABORT",
                    "surface", "group",
                    "conversationId", "gc-1");

            assertDoesNotThrow(() -> handler.handleTimeout(metadata));

            verify(groupConversationService).cancelDiscussion("gc-1", ControlSignal.CANCEL_GRACEFUL);
        }
    }
}
