/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.github.tjake.jlama.util.MachineSpec;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether this JVM can actually run Jlama at speed, and a single actionable
 * warning when it cannot.
 *
 * <h2>Why this class exists</h2>
 *
 * Jlama picks its tensor backend once, at first use, in
 * {@code TensorOperationsProvider.pickFastestImplementation()}:
 *
 * <ol>
 * <li>it tries {@code NativeSimdTensorOperations}, which needs the separate
 * {@code com.github.tjake:jlama-native} artifact on the classpath. EDDI does
 * not ship it — deliberately, because it would put platform-specific,
 * glibc-sensitive shared objects into a digest-pinned UBI base image. Jlama
 * logs "Native operations not available" and moves on;</li>
 * <li>it falls back to {@code MachineSpec.VECTOR_TYPE}, which reads
 * {@code FloatVector.SPECIES_PREFERRED} inside a {@code catch (Throwable)}.
 * Without {@code --add-modules jdk.incubator.vector} that throws
 * {@link NoClassDefFoundError}, the catch logs one line, and the type stays
 * {@code NONE};</li>
 * <li>{@code NONE} selects {@code NaiveTensorOperations} — scalar Java matrix
 * arithmetic.</li>
 * </ol>
 *
 * So a Jlama agent on a JVM without the flag <em>works</em>. It is simply
 * orders of magnitude slower than the SIMD path, which for interactive chat
 * means it does not work at all.
 * <p>
 * Jlama does say something — {@code MachineSpec} logs its own WARN through
 * slf4j, which Quarkus routes into JBoss LogManager — but it names neither
 * Jlama nor the agent that is about to be slow, so in a running EDDI it reads
 * as unattributed noise. What this class adds is attribution and a diagnosis:
 * which of the two possible causes applies, and whether there is anything the
 * operator can do about it.
 * <p>
 * The flag is set for every JVM EDDI controls — both container images, the
 * surefire fork and both mise dev tasks (see {@code JlamaRuntimeFlagsTest},
 * which fails the build if any of them loses it). This class covers the cases
 * those cannot reach: somebody running {@code quarkus-run.jar} with their own
 * command line, and a host whose CPU exposes no vector species Jlama accepts.
 * <p>
 * <b>Why this warns rather than refusing to start the model.</b> A degraded
 * Jlama still answers correctly, so failing closed would turn a slow deployment
 * into a broken one on upgrade — for a condition the operator may not be able
 * to fix at all in the CPU case. It is also not agent behaviour, so the "expose
 * it as config" rule in AGENTS.md §4.1 does not apply: nothing about a
 * conversation changes, only how loudly EDDI reports its own environment.
 *
 * <h2>Why the probe is written this way</h2>
 *
 * It reads {@link MachineSpec#VECTOR_TYPE} — the exact field
 * {@code TensorOperationsProvider} branches on — rather than asking whether the
 * module resolved. Those are not the same question, and an earlier draft of
 * this class got it wrong by assuming they were. {@code MachineSpec} accepts
 * only a 512- or 256-bit preferred species, or 128 bits on ARM; on an x86 host
 * that exposes a 128-bit species (a hypervisor that masks AVX2, or an operator
 * running with {@code -XX:UseAVX=0}) the module resolves,
 * {@code jdk.incubator.vector.FloatVector} loads, and Jlama <em>still</em>
 * selects the scalar backend. A module-resolution probe reports "fine" and the
 * warning never fires — the precise failure this class exists to make visible.
 * <p>
 * Reading Jlama's own field cannot diverge from Jlama's own decision. It is
 * also safe to touch without the flag: {@code MachineSpec}'s static initialiser
 * wraps the Vector API call in {@code catch (Throwable)} and falls back to
 * {@code NONE}. EDDI still never names a {@code jdk.incubator.vector} type
 * itself, so {@code javac} needs no {@code --add-modules}.
 * <p>
 * The module check is kept, but only to tell the two causes apart in the
 * warning: a missing flag is a one-line fix, an unsupported CPU is not.
 */
public final class JlamaRuntimeSupport {

    private static final Logger LOGGER = Logger.getLogger(JlamaRuntimeSupport.class);

    /** The incubator module Jlama's SIMD backend needs. */
    static final String VECTOR_MODULE = "jdk.incubator.vector";

    /**
     * The flag to add, spelled exactly as it goes on a command line.
     * <p>
     * Referenced by {@code JlamaRuntimeFlagsTest} when it greps both Dockerfiles,
     * {@code pom.xml} and {@code mise.toml}, so the string in the warning and the
     * string in the build configuration are the same string.
     */
    public static final String REQUIRED_JVM_FLAG = "--add-modules=" + VECTOR_MODULE;

    /**
     * Resolved once, because Jlama resolves it once:
     * {@code MachineSpec.VECTOR_TYPE} is a {@code static final} computed in a class
     * initialiser, so the answer is fixed for the life of the JVM and re-probing
     * could not produce a different one.
     */
    private static final boolean SIMD_BACKEND_AVAILABLE = probeSimdBackend();

    /** One warning per JVM, not one per cache miss. */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private JlamaRuntimeSupport() {
        // non-instantiable utility
    }

    /**
     * @return {@code true} when Jlama will select a SIMD backend, {@code false}
     *         when it will fall back to scalar arithmetic
     */
    public static boolean isSimdBackendAvailable() {
        return SIMD_BACKEND_AVAILABLE;
    }

    /**
     * The live probe, kept package-visible and side-effect-free so the test suite
     * can assert against the JVM it is running in rather than against a cached
     * boolean it cannot influence.
     *
     * @return whether Jlama's own backend selection will find a vector type
     */
    static boolean probeSimdBackend() {
        return MachineSpec.VECTOR_TYPE != MachineSpec.Type.NONE;
    }

    /**
     * Whether {@code jdk.incubator.vector} is resolved in this JVM.
     * <p>
     * Not the question {@link #probeSimdBackend()} answers, and not a substitute
     * for it — it is only used to choose between two different pieces of advice in
     * {@link #degradedWarning()}.
     */
    static boolean isVectorModuleResolved() {
        return ModuleLayer.boot().findModule(VECTOR_MODULE).isPresent();
    }

    /**
     * Logs the degraded-performance warning at most once per JVM, and only when the
     * JVM is actually degraded.
     * <p>
     * Called from {@link JlamaLanguageModelBuilder#build} rather than at startup on
     * purpose: a deployment with no Jlama agent should not be told about a flag it
     * does not need.
     */
    static void warnOnceIfDegraded() {
        if (shouldWarn(SIMD_BACKEND_AVAILABLE, WARNED)) {
            LOGGER.warn(degradedWarning());
        }
    }

    /**
     * The decision behind {@link #warnOnceIfDegraded()}, as a pure function of its
     * two inputs.
     * <p>
     * Separated so both branches are reachable from a test. The interesting one — a
     * JVM on which Jlama picks the scalar backend — cannot be reproduced
     * in-process, because {@code MachineSpec} decides once at class-init and the
     * test JVM deliberately has the flag. Taking the verdict and the latch as
     * parameters lets the test supply the degraded case directly, instead of the
     * alternative: exposing mutators on the real latch so a test can poke it, which
     * would put test-only setters into production code and still leave the
     * {@code false} branch unexercised.
     *
     * @param simdBackendAvailable
     *            whether Jlama will use a SIMD backend on this JVM
     * @param latch
     *            the once-per-JVM guard; set as a side effect when this returns
     *            {@code true}
     *
     * @return {@code true} exactly once, and only on a degraded JVM
     */
    static boolean shouldWarn(boolean simdBackendAvailable, AtomicBoolean latch) {
        return !simdBackendAvailable && latch.compareAndSet(false, true);
    }

    /**
     * The warning text, built separately from the logging so a test can pin its
     * content without capturing a log appender.
     */
    static String degradedWarning() {
        String cause = isVectorModuleResolved()
                ? VECTOR_MODULE + " is resolved, but this CPU exposes no vector species Jlama accepts "
                        + "(it takes 512- or 256-bit on x86, or 128-bit on ARM). That is usually a hypervisor "
                        + "masking AVX2, or an explicit -XX:UseAVX setting. Adding a JVM flag will not help; "
                        + "the host or the VM's CPU model has to change."
                : VECTOR_MODULE + " is not resolved in this JVM. Add '" + REQUIRED_JVM_FLAG + "' to the JVM "
                        + "command line. The EDDI container images, the Maven test fork and the mise dev tasks "
                        + "all set it already, so this JVM was most likely started by hand or by a custom "
                        + "entrypoint — note also that overriding JAVA_OPTS or JAVA_OPTS_APPEND on the "
                        + "container replaces the image's values but NOT JDK_JAVA_OPTIONS, which is where the "
                        + "image keeps this flag.";

        return "Jlama will use scalar tensor operations (NaiveTensorOperations), which is orders of magnitude "
                + "slower than SIMD and not usable for interactive chat. Cause: " + cause
                + " See docs/langchain.md, section 'Jlama (Local Java Inference)'.";
    }

}
