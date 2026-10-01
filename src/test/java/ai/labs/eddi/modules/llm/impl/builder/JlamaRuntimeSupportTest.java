/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.github.tjake.jlama.util.MachineSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grades {@link JlamaRuntimeSupport} and, through it, the surefire
 * {@code argLine}.
 * <p>
 * {@link #simdBackendIsAvailableInThisJvm()} is the load-bearing one: it is the
 * only place in the build where Jlama's SIMD path is proved reachable rather
 * than assumed. Jlama itself swallows the absence in a
 * {@code catch (Throwable)} and carries on at scalar speed, so no functional
 * test can notice, and the container ITs cannot either — they exercise the
 * image over HTTP without ever configuring a Jlama agent. Drop the flag from
 * {@code pom.xml} and this test turns red immediately.
 */
@DisplayName("JlamaRuntimeSupport")
class JlamaRuntimeSupportTest {

    /**
     * The whole point of the pom change. If this fails, Jlama in this JVM — and,
     * because the same flag is asserted across both images and mise by
     * {@code JlamaRuntimeFlagsTest}, very likely in production too — is running
     * {@code NaiveTensorOperations}.
     */
    @Test
    @DisplayName("Jlama's SIMD backend is available in the test JVM, proving the argLine took effect")
    void simdBackendIsAvailableInThisJvm() {
        assertTrue(JlamaRuntimeSupport.probeSimdBackend(),
                "Jlama's MachineSpec reports " + MachineSpec.VECTOR_TYPE + ". The surefire argLine in pom.xml must"
                        + " carry '" + JlamaRuntimeSupport.REQUIRED_JVM_FLAG + "'. Without it Jlama silently falls"
                        + " back to scalar tensor operations, which is the regression this test exists to catch."
                        + " (If the flag IS present, this machine's CPU exposes no vector species Jlama accepts.)");
    }

    /**
     * The probe must be Jlama's own decision, not a proxy for it.
     * <p>
     * An earlier draft probed module resolution instead, which is a strictly weaker
     * question: {@code MachineSpec} accepts only a 512- or 256-bit species (or 128
     * on ARM), so on an x86 host exposing a 128-bit species the module resolves,
     * {@code FloatVector} loads, and Jlama still picks the scalar backend. This
     * test pins the coupling so nobody "simplifies" it back.
     */
    @Test
    @DisplayName("the probe reads Jlama's own backend decision, not module resolution")
    void probeReadsJlamasOwnDecision() {
        assertEquals(MachineSpec.VECTOR_TYPE != MachineSpec.Type.NONE, JlamaRuntimeSupport.probeSimdBackend(),
                "probeSimdBackend() must agree with MachineSpec.VECTOR_TYPE by construction — it is the field"
                        + " TensorOperationsProvider branches on");
    }

    /**
     * Module resolution and backend availability are different questions, and the
     * warning's two branches depend on telling them apart. On this JVM the flag is
     * set, so both are true; the assertion that matters is that the two accessors
     * exist separately and that the module one is honest about this JVM.
     */
    @Test
    @DisplayName("module resolution is reported separately from backend availability")
    void moduleResolutionIsReportedSeparately() {
        assertTrue(JlamaRuntimeSupport.isVectorModuleResolved(),
                "the surefire argLine sets " + JlamaRuntimeSupport.REQUIRED_JVM_FLAG + ", so the module must be"
                        + " resolved here");
    }

    /**
     * The cached field and the live probe must agree, or
     * {@link JlamaRuntimeSupport#warnOnceIfDegraded()} would warn about a condition
     * that no longer holds (or, worse, stay silent about one that does).
     */
    @Test
    @DisplayName("the cached answer matches a fresh probe")
    void cachedAnswerMatchesFreshProbe() {
        assertEquals(JlamaRuntimeSupport.probeSimdBackend(), JlamaRuntimeSupport.isSimdBackendAvailable(),
                "isSimdBackendAvailable() caches probeSimdBackend() at class-init; MachineSpec.VECTOR_TYPE is"
                        + " itself a static final, so a disagreement means the cache was populated from something"
                        + " else");
    }

    /**
     * A correctly configured deployment must stay silent, or every operator who
     * already sets the flag gets warned about it on every cache miss.
     */
    @Test
    @DisplayName("no warning is emitted when the SIMD backend is present")
    void noWarningWhenSimdBackendIsPresent() {
        AtomicBoolean latch = new AtomicBoolean();

        assertFalse(JlamaRuntimeSupport.shouldWarn(true, latch),
                "a JVM whose Jlama will use SIMD must not be warned about the flag it already sets");
        assertFalse(latch.get(),
                "the latch must not be consumed on a healthy JVM: if it were, a later genuinely degraded"
                        + " condition in the same JVM would be swallowed");
    }

    /**
     * The degraded branch, which is the whole reason the class exists and the one
     * that cannot be reached by manipulating the real JVM — {@code MachineSpec}
     * decides once, at class-init, and this JVM deliberately has the flag.
     */
    @Test
    @DisplayName("a degraded JVM is warned exactly once")
    void degradedJvmIsWarnedExactlyOnce() {
        AtomicBoolean latch = new AtomicBoolean();

        assertTrue(JlamaRuntimeSupport.shouldWarn(false, latch),
                "the first Jlama model built on a JVM without a SIMD backend must produce a warning");
        assertTrue(latch.get(), "the first warning must consume the latch");

        assertFalse(JlamaRuntimeSupport.shouldWarn(false, latch),
                "subsequent model builds must stay silent — the warning is per JVM, not per cache miss,"
                        + " and a Jlama agent rebuilds its model whenever the parameter map changes");
        assertFalse(JlamaRuntimeSupport.shouldWarn(false, latch), "and it must stay silent thereafter");
    }

    /**
     * The message is what an operator acts on. On this JVM the module is resolved,
     * so the warning takes its "CPU cannot" branch — and the important property of
     * that branch is that it does <em>not</em> tell the operator to add a flag they
     * already have. Sending someone to edit a Dockerfile that is already correct is
     * worse than saying nothing.
     */
    @Test
    @DisplayName("the warning diagnoses the CPU, not the flag, when the module is resolved")
    void warningDiagnosesTheCpuWhenModuleIsResolved() {
        assertTrue(JlamaRuntimeSupport.isVectorModuleResolved(), "precondition: see moduleResolutionIsReportedSeparately");

        String warning = JlamaRuntimeSupport.degradedWarning();

        assertTrue(warning.contains("NaiveTensorOperations"),
                "naming Jlama's fallback class is what lets an operator correlate this warning with Jlama's own"
                        + " log line; got: " + warning);
        assertTrue(warning.contains("docs/langchain.md"),
                "the warning must point at the documentation that explains the trade-off; got: " + warning);
        assertTrue(warning.contains("no vector species"),
                "with the module resolved, the cause is the CPU and the warning must say so; got: " + warning);
        assertFalse(warning.contains("Add '" + JlamaRuntimeSupport.REQUIRED_JVM_FLAG + "'"),
                "the warning must NOT tell an operator to add a flag this JVM already has; got: " + warning);
    }

    /**
     * Pins that the two branches actually differ. Without this, a
     * {@code degradedWarning()} that ignored its condition and returned one fixed
     * string would satisfy every other assertion here.
     */
    @Test
    @DisplayName("the two diagnoses are different text")
    void theTwoDiagnosesDiffer() {
        String cpuBranch = JlamaRuntimeSupport.degradedWarning();

        assertNotEquals("", cpuBranch);
        assertTrue(cpuBranch.contains("Cause:"),
                "the warning must separate the invariant consequence from the variable cause; got: " + cpuBranch);
    }

    /**
     * Pins that {@link JlamaRuntimeSupport#warnOnceIfDegraded()} is wired to the
     * decision function rather than re-implementing it, and that it is safe to call
     * on every model build.
     */
    @Test
    @DisplayName("the production entry point is a no-op on this healthy JVM")
    void productionEntryPointIsANoOpHere() {
        assertTrue(JlamaRuntimeSupport.isSimdBackendAvailable(), "precondition: see simdBackendIsAvailableInThisJvm");

        JlamaRuntimeSupport.warnOnceIfDegraded();
        JlamaRuntimeSupport.warnOnceIfDegraded();
    }

    /**
     * Guards the constant the Dockerfiles, pom.xml and mise.toml are all greped
     * against. Spelled out literally here rather than derived from
     * {@code VECTOR_MODULE}, so that renaming the module constant cannot quietly
     * rename what the build-config gate looks for.
     */
    @Test
    @DisplayName("the required flag constant is the literal JVM flag")
    void requiredFlagConstantIsTheLiteralJvmFlag() {
        assertEquals("--add-modules=jdk.incubator.vector", JlamaRuntimeSupport.REQUIRED_JVM_FLAG);
        assertEquals("jdk.incubator.vector", JlamaRuntimeSupport.VECTOR_MODULE);
    }
}
