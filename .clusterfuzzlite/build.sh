#!/bin/bash -eu
# ClusterFuzzLite build script for EDDI
# Compiles ONLY the specific utility classes needed by fuzz targets.
# Source files are vendored in .clusterfuzzlite/ to avoid needing the
# full project source tree (which is blocked by .dockerignore).
#
# The base-builder-jvm image provides:
#   $OUT          — directory for final fuzzer executables
#   $SRC          — source directory (this is /src)
#   $JAZZER_API_PATH — path to jazzer_agent_deploy.jar

cd $SRC/project

# ── Patch vendored sources for pre-22 JDK compatibility ──
# The project targets Java 25 and uses unnamed variables (catch Exception _)
# which is a Java 22+ feature. Patch these for the CFL image's JDK.
FUZZ_SRC=$SRC/fuzz-src
mkdir -p $FUZZ_SRC/ai/labs/eddi/utils

for f in PathNavigator.java MatchingUtilities.java RuntimeUtilities.java; do
    cp ".clusterfuzzlite/$f" "$FUZZ_SRC/ai/labs/eddi/utils/$f"
done

# Patch: catch (SomeException _) → catch (SomeException ignored)
# Only target catch-block patterns to avoid false matches in other contexts.
perl -pi -e 's/\bcatch\s*\(\s*(\w+)\s+_\s*\)/catch ($1 ignored)/g' \
    $FUZZ_SRC/ai/labs/eddi/utils/*.java

# Detect JDK version (base image may ship 17 or 21)
JAVA_VER=$(javac -version 2>&1 | grep -oP '\d+' | head -1)
if [ "$JAVA_VER" -ge 21 ] 2>/dev/null; then
    RELEASE_VER=21
else
    RELEASE_VER=17
fi
echo "Detected JDK $JAVA_VER — targeting --release $RELEASE_VER"

# Compile utility classes
mkdir -p $OUT/classes
javac --release $RELEASE_VER -encoding UTF-8 \
    -d $OUT/classes \
    $FUZZ_SRC/ai/labs/eddi/utils/PathNavigator.java \
    $FUZZ_SRC/ai/labs/eddi/utils/MatchingUtilities.java \
    $FUZZ_SRC/ai/labs/eddi/utils/RuntimeUtilities.java

echo "✅ Utility classes compiled (--release $RELEASE_VER)"

# ── Fuzz Target: PathNavigatorFuzzer ──
cat > $SRC/PathNavigatorFuzzer.java << 'EOF'
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import ai.labs.eddi.utils.PathNavigator;
import java.util.*;

public class PathNavigatorFuzzer {
    private static final Map<String, Object> SEED_DATA = buildSeedData();

    private static Map<String, Object> buildSeedData() {
        Map<String, Object> root = new HashMap<>();
        root.put("memory", Map.of(
                "current", Map.of("output", "hello world", "input", "user said something"),
                "last", Map.of("output", "previous response")));
        root.put("properties", Map.of(
                "count", 42, "name", "test-agent", "score", 3.14,
                "flag", true, "tags", List.of("alpha", "beta", "gamma")));
        root.put("items", List.of(
                Map.of("name", "first", "value", 100),
                Map.of("name", "second", "value", 200)));
        root.put("nested", Map.of("deep", Map.of("deeper", Map.of("deepest", "found-it"))));
        return root;
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String path = data.consumeString(500);
        try {
            PathNavigator.getValue(path, SEED_DATA);
        } catch (IllegalArgumentException | StackOverflowError ignored) {
            // Expected: invalid paths and deep recursion are normal fuzz inputs
        }
    }
}
EOF

# ── Fuzz Target: MatchingUtilitiesFuzzer ──
cat > $SRC/MatchingUtilitiesFuzzer.java << 'EOF'
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import ai.labs.eddi.utils.MatchingUtilities;
import java.util.*;

public class MatchingUtilitiesFuzzer {
    private static final Map<String, Object> DATA = buildData();

    private static Map<String, Object> buildData() {
        Map<String, Object> data = new HashMap<>();
        data.put("memory", Map.of(
                "current", Map.of("input", "hello", "output", "world")));
        data.put("properties", Map.of(
                "language", "en", "count", 5, "active", true,
                "tags", List.of("premium", "beta", "eu")));
        data.put("context", Map.of("channel", "web"));
        return data;
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String valuePath = data.consumeString(300);
        String equals = data.consumeBoolean() ? data.consumeString(100) : null;
        String contains = data.consumeBoolean() ? data.consumeString(100) : null;
        try {
            MatchingUtilities.executeValuePath(DATA, valuePath, equals, contains);
        } catch (IllegalArgumentException ignored) {
            // Expected: invalid value paths are normal fuzz inputs
        }
    }
}
EOF

# ── Compile fuzz targets against the utility classes + Jazzer API ──
javac --release $RELEASE_VER -encoding UTF-8 \
    -cp "$OUT/classes:$JAZZER_API_PATH" \
    -d $OUT \
    $SRC/PathNavigatorFuzzer.java \
    $SRC/MatchingUtilitiesFuzzer.java

echo "✅ Fuzz targets compiled"

# ── Build runtime classpath (relative to $OUT via $this_dir) ──
RUNTIME_CP="\$this_dir/classes:\$this_dir"

# ── Generate Jazzer wrapper scripts ──
# The "LLVMFuzzerTestOneInput" comment is required — bad_build_check scans
# wrapper scripts for this string to detect valid fuzz targets.
for fuzzer in PathNavigatorFuzzer MatchingUtilitiesFuzzer; do
    echo "#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname \"\$0\")
if [[ \"\$@\" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH=\"$JVM_LD_LIBRARY_PATH\":\$this_dir \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
--cp=$RUNTIME_CP \\
--target_class=$fuzzer \\
--jvm_args=\"\$mem_settings\" \\
\$@" > $OUT/$fuzzer
    chmod +x $OUT/$fuzzer
done

echo "=== ClusterFuzzLite build complete ==="
echo "Fuzz targets: PathNavigatorFuzzer, MatchingUtilitiesFuzzer"
