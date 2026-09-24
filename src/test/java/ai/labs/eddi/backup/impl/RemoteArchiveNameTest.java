/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a first-time sync will accept from the source's export answer.
 * <p>
 * The {@code Location} an export returns is an absolute URL built by the
 * <em>other</em> instance. Following it would let that instance decide where
 * this deployment sends the caller's bearer token — the SSRF
 * {@link SourceUrlValidator} exists to prevent — so only a plain archive file
 * name is taken from it, and the download is issued against the base URL the
 * operator's policy already approved.
 */
@DisplayName("RemoteApiResourceSource — archive name from the export's Location")
class RemoteArchiveNameTest {

    @Test
    @DisplayName("takes the file name from an absolute URL")
    void takesFileNameFromAbsoluteUrl() {
        assertEquals("Support-Bot-abc123-1.zip",
                RemoteApiResourceSource.archiveNameFrom("https://staging.example.com/backup/export/Support-Bot-abc123-1.zip"));
    }

    @Test
    @DisplayName("takes the file name from a relative path")
    void takesFileNameFromRelativePath() {
        assertEquals("agent.zip", RemoteApiResourceSource.archiveNameFrom("/backup/export/agent.zip"));
    }

    @Test
    @DisplayName("drops a query string")
    void dropsQuery() {
        assertEquals("agent.zip",
                RemoteApiResourceSource.archiveNameFrom("https://staging.example.com/backup/export/agent.zip?token=abc"));
    }

    @Test
    @DisplayName("keeps only the last segment, so a traversal in the path cannot escape")
    void discardsThePath() {
        // The download is issued as {approved base}/backup/export/<name>, so a path
        // the remote puts in front of the name never reaches the request at all.
        assertEquals("passwd.zip",
                RemoteApiResourceSource.archiveNameFrom("https://evil.example.com/../../etc/passwd.zip"));
    }

    @Test
    @DisplayName("refuses a traversal inside the name itself")
    void refusesTraversalInTheName() {
        assertNull(RemoteApiResourceSource.archiveNameFrom("..%2F..%2Fetc%2Fpasswd.zip"));
        assertNull(RemoteApiResourceSource.archiveNameFrom("..\\..\\windows\\system32\\config.zip"));
        assertNull(RemoteApiResourceSource.archiveNameFrom("https://evil.example.com/backup/export/..zip"));
    }

    @Test
    @DisplayName("refuses anything that is not an archive")
    void refusesNonArchive() {
        assertNull(RemoteApiResourceSource.archiveNameFrom("https://staging.example.com/backup/export/agent.json"));
        assertNull(RemoteApiResourceSource.archiveNameFrom("https://staging.example.com/backup/export/"));
    }

    @Test
    @DisplayName("refuses a missing or empty header rather than guessing a name")
    void refusesMissingHeader() {
        assertNull(RemoteApiResourceSource.archiveNameFrom(null));
        assertNull(RemoteApiResourceSource.archiveNameFrom("   "));
    }
}
