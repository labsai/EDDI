/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that reads or writes {@code <working directory>/tmp/}, the
 * scratch root {@link RestExportService} and {@link RestImportService} fix from
 * {@code user.dir}. Every test JVM of one build has the same working directory,
 * so two such classes running at once in different surefire forks share that
 * tree: one test's cleanup, or the export service's own sweep of stale
 * archives, deletes the other's files mid-test.
 * <p>
 * Classes carrying this run one at a time across all forks — the extension
 * holds an OS-level file lock for the whole class — while every other test
 * keeps running in parallel. {@link SharedWorkingDirTmpGuardTest} fails if a
 * test constructs either service without it.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(SharedWorkingDirTmpLock.class)
public @interface SharedWorkingDirTmp {
}
