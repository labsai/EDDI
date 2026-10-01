/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Knowledge-base ingestion over real HTTP — the full JAX-RS filter chain, not
 * the resource class called directly.
 * <p>
 * That distinction is the point: deleting an uploaded file answered {@code 400}
 * after it had succeeded, because {@code DocumentDescriptorFilter} ran after
 * the resource and misread the file id as a configuration id. Every unit test
 * called {@code RestRagIngestion} directly and passed.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RagIngestionIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/ragstore/rags/";

    private static ResourceId kb;
    private static String uploadSourceId;

    @AfterAll
    static void cleanup() {
        if (kb != null) {
            deleteResourceQuietly(ROOT_PATH, kb.id(), kb.version());
        }
    }

    private static String knowledgeBase(List<String> sitemapUrls) {
        String sitemaps = String.join("\", \"", sitemapUrls);
        return """
                {
                  "name": "ingestion-it-kb",
                  "embeddingProvider": "openai",
                  "embeddingParameters": { "model": "text-embedding-3-small", "apiKey": "${vault:not-used}" },
                  "storeType": "in-memory",
                  "chunkSize": 512,
                  "chunkOverlap": 64,
                  "sources": [
                    { "name": "files", "type": "upload" },
                    { "name": "docs-site", "type": "web",
                      "web": { "startUrl": "https://docs.example.com/", "sitemapUrls": ["%s"] } }
                  ]
                }
                """.formatted(sitemaps);
    }

    private static String version() {
        return "?version=" + kb.version();
    }

    @Test
    @Order(1)
    @DisplayName("a knowledge base with sitemapUrls saves, and reads back with them")
    void createWithSitemaps() {
        String location = given().body(knowledgeBase(List.of("https://docs.example.com/sitemap_index.xml.gz")))
                .contentType(ContentType.JSON).post(ROOT_PATH)
                .then().statusCode(201).extract().header("location");
        kb = extractResourceId(location);

        Response read = given().get(ROOT_PATH + kb.id() + version());
        read.then().statusCode(200)
                .body("sources.find { it.name == 'docs-site' }.web.sitemapUrls",
                        hasItem("https://docs.example.com/sitemap_index.xml.gz"));
        uploadSourceId = read.jsonPath().getString("sources.find { it.name == 'files' }.id");
        assertNotNull(uploadSourceId);
    }

    @Test
    @Order(2)
    @DisplayName("a sitemap at a private address is refused at save time, naming the field")
    void privateSitemapRefused() {
        given().body(knowledgeBase(List.of("http://169.254.169.254/sitemap.xml")))
                .contentType(ContentType.JSON).post(ROOT_PATH)
                .then().statusCode(400).body(containsString("sitemapUrls"));
    }

    @Test
    @Order(3)
    @DisplayName("an uploaded file is stored and listed")
    void uploadAndList() {
        given().multiPart("files", "handbook.txt", "The handbook says hello.".getBytes(StandardCharsets.UTF_8), "text/plain")
                .post(ROOT_PATH + kb.id() + "/sources/" + uploadSourceId + "/files" + version())
                .then().statusCode(200).body("stored.fileName", hasItem("handbook.txt"));

        given().get(ROOT_PATH + kb.id() + "/sources/" + uploadSourceId + "/files" + version())
                .then().statusCode(200).body("fileName", hasItem("handbook.txt"));
    }

    @Test
    @Order(4)
    @DisplayName("deleting a file answers 200 through the filter chain, and leaves the knowledge base alone")
    void deleteFile() {
        String fileId = given().get(ROOT_PATH + kb.id() + "/sources/" + uploadSourceId + "/files" + version())
                .jsonPath().getString("find { it.fileName == 'handbook.txt' }.fileId");

        given().delete(ROOT_PATH + kb.id() + "/sources/" + uploadSourceId + "/files/" + fileId + version())
                .then().statusCode(200).body("status", equalTo("deleted"));

        given().get(ROOT_PATH + kb.id() + "/sources/" + uploadSourceId + "/files" + version())
                .then().statusCode(200).body("$", empty());
        // The filter used to mark a descriptor as deleted on every successful DELETE.
        // Here it must not have touched the knowledge base's own.
        given().get("/descriptorstore/descriptors/" + kb.id() + version())
                .then().statusCode(200).body("deleted", equalTo(false));
        given().get(ROOT_PATH + kb.id() + version()).then().statusCode(200);
    }

    @Test
    @Order(5)
    @DisplayName("replace=true without a documentName is refused before anything is ingested")
    void replaceNeedsAName() {
        given().contentType(ContentType.TEXT).body("Refunds within 47 days.")
                .post(ROOT_PATH + kb.id() + "/ingest" + version() + "&replace=true")
                .then().statusCode(400).body("error", containsString("documentName"));
    }

    @Test
    @Order(6)
    @DisplayName("replace=true with a documentName is accepted and tracked")
    void replaceAccepted() {
        Map<String, Object> started = given().contentType(ContentType.TEXT).body("Refunds within 47 days.")
                .post(ROOT_PATH + kb.id() + "/ingest" + version() + "&documentName=policy.txt&replace=true")
                .then().statusCode(202).extract().jsonPath().getMap("$");
        assertEquals("ingestion-it-kb", started.get("kbId"));

        // No embedding provider is reachable here, so the ingestion ends failed —
        // what matters is that it was accepted and can be followed.
        given().get(ROOT_PATH + kb.id() + "/ingestion/" + started.get("ingestionId") + "/status")
                .then().statusCode(200).body("ingestionId", equalTo(started.get("ingestionId")));
    }
}
