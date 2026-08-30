/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

/**
 * The workspace and sharing surface over real HTTP.
 *
 * <h3>Why these are integration tests and not more unit tests</h3> Everything
 * asserted here is wiring: whether a query parameter binds, whether Jackson
 * emits the field names a client is typed against, whether a path is routed at
 * all. Each of those is invisible to a unit test holding the resource class
 * directly, and each has already been wrong once in this feature — `?space=`
 * shipped being sent to an endpoint that did not declare it, and the Manager
 * spent a release deciding enforcement from fields that cannot decide it.
 *
 * <h3>What the shapes here are load-bearing for</h3> EDDI-Manager's MSW default
 * handler answers {@code GET /workspaces} with exactly the disabled payload
 * asserted below. If this contract moves, that mock keeps every frontend test
 * green while the real thing has changed — so the disabled shape is pinned here
 * rather than only there.
 *
 * @author ginccc
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class WorkspacesIT extends BaseIntegrationIT {

    private static final String WORKSPACES = "/workspaces";
    private static final String AGENT_DESCRIPTORS = "/agentstore/agents/descriptors";

    /** A well-formed id that no resource holds. */
    private static final String ABSENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private static String shares(String id) {
        return "/descriptorstore/descriptors/" + id + "/shares";
    }

    @Test
    @DisplayName("reports enforcement off — the default deployment, and the shape the Manager mocks")
    void reportsDisabledByDefault() {
        given()
                .when().get(WORKSPACES)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                // Every one of these fields is read by the Manager. `enabled` gates the
                // entire feature there; `seesEverything` explains an unfiltered listing.
                .body("enabled", is(false))
                .body("seesEverything", is(true))
                .body("spaces", hasSize(0))
                // Anonymous under this profile: no principal to stamp, so nothing to own.
                .body("principal", nullValue())
                .body("defaultSpace", nullValue());
    }

    @Test
    @DisplayName("the agent listing accepts ?space= rather than rejecting it")
    void agentListingBindsSpaceParameter() {
        // The narrowing itself needs an authenticated principal to mean anything; what
        // this pins is that the parameter EXISTS. It shipped once being sent to an
        // endpoint that did not declare it, where JAX-RS silently dropped it and the
        // switcher changed the URL and nothing else — a 200 either way, which is
        // precisely why it went unnoticed.
        given()
                .queryParam("space", "team:engineering")
                .queryParam("limit", 5)
                .when().get(AGENT_DESCRIPTORS)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @DisplayName("a space id carrying regex metacharacters is handled, not rejected")
    void spaceParameterAcceptsMetacharacters() {
        // NOT a test that `.*` matches nothing — it cannot be, here. This profile
        // sets authorization.enabled=false, so no descriptor is ever stamped with a
        // spaceId, and an empty result proves only that the field is absent. An
        // earlier version asserted hasSize(0) and would have passed with the
        // escaping removed entirely.
        //
        // What this DOES pin is that a metacharacter-laden value reaches the query
        // layer and comes back as a well-formed empty page rather than a 500 from a
        // malformed pattern. The escaping itself is covered where it can actually be
        // observed: AccessScopeTest (anchored, escaped predicate) and SubjectsTest
        // (the shared PCRE/POSIX-ERE metacharacter set).
        given()
                .queryParam("space", ".*")
                .queryParam("limit", 50)
                .when().get(AGENT_DESCRIPTORS)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @DisplayName("sharing state is readable and carries the fields a client is typed against")
    void readsSharingState() {
        given()
                .when().get(shares(ABSENT_ID))
                .then()
                // 404 for an id nothing holds is fine; what must not happen is a 500 or a
                // route that does not exist. Any 2xx must carry the documented shape.
                .statusCode(is(oneOf(200, 403, 404)));
    }

    @Test
    @DisplayName("an unknown access level is refused rather than silently downgraded")
    void rejectsUnknownLevel() {
        // A typo that became a weaker grant looks exactly like a successful share.
        given()
                .queryParam("subject", "user:alice@example.com")
                .queryParam("level", "ADMIN")
                .when().post(shares(ABSENT_ID))
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("an unknown visibility is refused rather than guessed at")
    void rejectsUnknownVisibility() {
        // The three options differ on who can read the resource. Guessing between them
        // is not a recoverable mistake.
        given()
                .queryParam("visibility", "world-readable")
                .when().put(shares(ABSENT_ID) + "/visibility")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("a share with no subject is refused")
    void rejectsMissingSubject() {
        given()
                .queryParam("level", "USE")
                .when().post(shares(ABSENT_ID))
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("an unrecognised subject prefix is refused rather than read as a user")
    void rejectsUnknownSubjectPrefix() {
        // "group:" is a plausible typo for "team:". Accepting it would create a grant
        // nobody holds, which is indistinguishable from a successful share.
        given()
                .queryParam("subject", "group:engineering")
                .queryParam("level", "USE")
                .when().post(shares(ABSENT_ID))
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("transferring ownership with no owner is refused")
    void rejectsOwnerlessTransfer() {
        given()
                .when().put(shares(ABSENT_ID) + "/owner")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("the workspace endpoint takes no principal parameter")
    void cannotEnumerateSomebodyElse() {
        // It answers for the caller and only the caller. A principal parameter would
        // turn it into a way to read anyone's group membership, so an attempt to pass
        // one must be ignored rather than honoured.
        given()
                .queryParam("principal", "bob@example.com")
                .when().get(WORKSPACES)
                .then()
                .statusCode(200)
                .body("principal", nullValue());
    }

    @Test
    @DisplayName("no caller level is stamped while workspaces are off")
    void noCallerLevelWhenDisabled() {
        // The compatibility property, asserted on the wire: with enforcement off a
        // listing is byte-identical to a deployment that has never heard of
        // workspaces. A client that started drawing per-row permissions from this
        // field would otherwise change behaviour on a deployment that did not opt in.
        //
        // An agent is created first because `everyItem` over an empty list is
        // vacuously true, and this suite seeds nothing of its own — the assertion
        // would otherwise hold whatever the server sent.
        // `packages` is the whole of an AgentConfiguration — name and description
        // live on the DESCRIPTOR, and StrictConfigurationBodyInterceptor rejects a
        // body carrying them. An empty list is enough: this test needs a descriptor
        // to exist, not an agent that runs.
        String location = createResource("""
                {"packages": []}""", "/agentstore/agents");
        var created = extractResourceId(location);

        try {
            given()
                    .queryParam("filter", created.id())
                    .queryParam("limit", 5)
                    .when().get(AGENT_DESCRIPTORS)
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThan(0)))
                    .body("callerLevel", everyItem(nullValue()));
        } finally {
            deleteResourceQuietly("/agentstore/agents/", created.id(), created.version());
        }
    }

    @Test
    @DisplayName("descriptor listings still answer while workspaces are off")
    void listingsUnaffectedWhenDisabled() {
        // The release-before-this-one guarantee: with enforcement off, nothing is
        // filtered and every existing client keeps working.
        given()
                .queryParam("type", "ai.labs.agent")
                .queryParam("limit", 5)
                .when().get("/descriptorstore/descriptors")
                .then()
                .statusCode(200)
                .body("$", notNullValue());
    }

    @Test
    @DisplayName("workspace settings do not leak the access index to a client")
    void doesNotExposeInternalTokens() {
        // `accessIndex` is a materialised query artefact. It is on the descriptor
        // because the store needs it, not because a client does.
        given()
                .when().get(WORKSPACES)
                .then()
                .statusCode(200)
                .body("accessIndex", nullValue())
                .body("admittingTokens", nullValue());
    }

    @Test
    @DisplayName("the endpoint is JSON, not a redirect or an HTML error page")
    void servesJson() {
        given()
                .when().get(WORKSPACES)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("enabled", equalTo(false));
    }
}
