/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Manager's permission map ({@code CAPABILITY_ROLES} in
 * {@code ui/packages/app-kit/src/lib/roles.ts}) to the {@code @RolesAllowed}
 * annotations it was derived from. The UI only decides what to OFFER; when an
 * endpoint's roles change and the map does not, the Manager either offers a
 * button the server refuses or hides one it would accept. This test fails on
 * either, naming the capability.
 * <p>
 * Capabilities that are a judgement over several endpoints (an editor-and-admin
 * "authoring" set, say) are listed in the TS file only; the ones below have a
 * single backing resource or method that decides them.
 */
@DisplayName("Manager permission map matches the REST annotations")
class ManagerPermissionMapPinTest {

    private static final Path ROLES_TS = Path.of("ui", "packages", "app-kit", "src", "lib", "roles.ts");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    private static final Map<String, String> ROLE_NAMES = Map.of("ROLE_ADMIN", "eddi-admin", "ROLE_EDITOR", "eddi-editor", "ROLE_USER", "eddi-user",
            "ROLE_VIEWER", "eddi-viewer", "ROLE_APPROVER", "eddi-approver");

    /** capability -> "Interface" (class-level roles) or "Interface#method". */
    private static final Map<String, String> BACKING = new TreeMap<>(Map.ofEntries(Map.entry("agents.read", "IRestAgentStore"),
            Map.entry("workflows.read", "IRestWorkflowStore"), Map.entry("groups.read", "IRestAgentGroupStore"),
            Map.entry("channels.manage", "IRestChannelIntegrationStore"), Map.entry("triggers.manage", "IRestAgentTriggerStore"),
            Map.entry("schedules.manage", "IRestScheduleStore"), Map.entry("capabilities.read", "IRestCapabilityRegistry"),
            Map.entry("variables.manage", "IRestGlobalVariableStore"), Map.entry("alerts.manage", "IRestAlertRules"),
            Map.entry("feedback.read", "IRestConversationRatingAdmin"), Map.entry("setup.guided", "IRestAgentSetup"),
            Map.entry("secrets.manage", "IRestSecretStore"), Map.entry("logs.read", "IRestLogAdmin"), Map.entry("audit.read", "IRestAuditStore"),
            Map.entry("quotas.manage", "IRestTenantQuota"), Map.entry("orphans.manage", "IRestOrphanAdmin"),
            Map.entry("gdpr.admin", "IRestGdprAdmin"),
            Map.entry("branding.manage", "IRestBrandingAdmin"), Map.entry("userdata.own", "IRestUserMemoryStore"),
            Map.entry("userdata.conversations", "IRestUserConversationStore"), Map.entry("connections.manage", "IRestConnectionStore"),
            Map.entry("connections.read", "IRestConnectionStore#readConnectionDescriptors"), Map.entry("cluster.act", "IRestClusterAdmin"),
            Map.entry("cluster.read", "IRestClusterAdmin#getOverview"), Map.entry("oversight.read", "IRestApprovalAnalytics"),
            Map.entry("workspaces.read", "IRestWorkspaces"), Map.entry("workspaces.manage", "IRestWorkspaces#updateSettings"),
            Map.entry("conversations.monitor", "IRestConversationStore#getActiveConversations"),
            Map.entry("conversations.purge", "IRestConversationStore#permanentlyDeleteEndedConversationLogs"),
            Map.entry("approvals.decide", "IRestAgentEngine#getApprovalStatus"),
            Map.entry("modelPrices.manage", "IRestModelPriceCatalog#replaceCatalog"),
            Map.entry("teamBudgets.transfer", "IRestTeamBudgets#transfer"), Map.entry("hitl.analyticsRebuild", "IRestApprovalAnalytics#rebuild")));

    @Test
    @DisplayName("every capability with a single backing resource lists exactly the roles the annotation admits")
    void mapMatchesAnnotations() throws Exception {
        Map<String, Set<String>> map = readMap();
        assertFalse(map.isEmpty(), "found no capabilities in " + ROLES_TS);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> backing : BACKING.entrySet()) {
            Set<String> fromUi = map.get(backing.getKey());
            if (fromUi == null) {
                problems.add(backing.getKey() + " is pinned here but missing from roles.ts");
                continue;
            }
            Set<String> fromJava = rolesOf(backing.getValue());
            if (!fromUi.equals(fromJava)) {
                problems.add(backing.getKey() + ": roles.ts says " + fromUi + ", " + backing.getValue() + " says " + fromJava);
            }
        }
        assertEquals(List.of(), problems, "the Manager's permission map has drifted from the backend");
    }

    private static Map<String, Set<String>> readMap() throws IOException {
        String ts = Files.readString(ROLES_TS);
        int start = ts.indexOf("export const CAPABILITY_ROLES");
        int end = ts.indexOf("} as const", start);
        assertTrue(start >= 0 && end > start, "CAPABILITY_ROLES not found in " + ROLES_TS);
        Matcher m = Pattern.compile("\"([a-zA-Z.]+)\":\\s*\\[([^\\]]*)\\]").matcher(ts.substring(start, end));
        Map<String, Set<String>> out = new TreeMap<>();
        while (m.find()) {
            Set<String> roles = new TreeSet<>();
            for (String token : m.group(2).split(",")) {
                String name = token.trim();
                if (name.isEmpty()) {
                    continue;
                }
                String role = ROLE_NAMES.get(name);
                assertNotNull(role, "unknown role constant " + name + " in " + m.group(1));
                roles.add(role);
            }
            out.put(m.group(1), roles);
        }
        return out;
    }

    private static Set<String> rolesOf(String backing) throws Exception {
        String[] parts = backing.split("#");
        Class<?> type = findInterface(parts[0]);
        RolesAllowed roles = null;
        if (parts.length == 2) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(parts[1]) && method.isAnnotationPresent(RolesAllowed.class)) {
                    roles = method.getAnnotation(RolesAllowed.class);
                    break;
                }
            }
        }
        // A method-level annotation REPLACES the class gate (no role hierarchy).
        if (roles == null) {
            roles = type.getAnnotation(RolesAllowed.class);
        }
        assertNotNull(roles, backing + " carries no @RolesAllowed");
        return new TreeSet<>(Set.of(roles.value()));
    }

    private static Class<?> findInterface(String simpleName) throws Exception {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            Path file = files.filter(p -> p.getFileName().toString().equals(simpleName + ".java")).findFirst()
                    .orElseThrow(() -> new AssertionError(simpleName + ".java not found under " + MAIN_JAVA));
            Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+);").matcher(Files.readString(file));
            assertTrue(pkg.find(), "no package in " + file);
            return Class.forName(pkg.group(1) + "." + simpleName);
        }
    }
}
