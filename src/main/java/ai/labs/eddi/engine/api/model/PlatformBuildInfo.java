/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api.model;

/**
 * Which build of EDDI is answering. Read live from the running process, so it
 * stays correct across an upgrade — unlike anything persisted in an agent
 * document, which keeps describing the build that wrote it.
 *
 * @param version
 *            the Maven project version, e.g. {@code 6.4.0}
 * @param commit
 *            the full git commit the image was built from, or {@code unknown}
 *            when the build did not record one (a jar run outside the image)
 * @param commitShort
 *            the first 12 characters of {@code commit}, or {@code unknown}
 * @since 6.5.0
 */
public record PlatformBuildInfo(String version, String commit, String commitShort) {
}
