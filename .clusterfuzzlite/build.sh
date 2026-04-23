#!/bin/bash -eu
# ClusterFuzzLite build script for EDDI
# Compiles the project and packages fuzz targets for Jazzer
#
# The base-builder-jvm image provides:
#   $OUT          — directory for final fuzzer executables
#   $SRC          — source directory
#   $JAZZER_API_PATH — path to jazzer_agent_deploy.jar

cd $SRC/project

# Build project (skip tests — we just need the compiled classes + deps)
mvn clean compile test-compile -DskipTests -B -q

# Copy dependency JARs into a flat directory
mvn dependency:copy-dependencies \
    -DoutputDirectory=target/deps \
    -DincludeScope=test -B -q

# ── Stage all runtime artifacts into $OUT ────────────────────────

# Project classes and test classes
cp -r target/classes $OUT/classes
cp -r target/test-classes $OUT/test-classes

# All dependency JARs
mkdir -p $OUT/deps
cp target/deps/*.jar $OUT/deps/ 2>/dev/null || true

# Build the runtime classpath (relative to $OUT via $this_dir)
# This will be interpolated into each wrapper script
RUNTIME_CP="\$this_dir/classes:\$this_dir/test-classes:\$this_dir/deps/*"

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
        } catch (Exception ignored) {
            // Expected — fuzzer explores crash paths
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
        } catch (Exception ignored) {
            // Expected — fuzzer explores crash paths
        }
    }
}
EOF

# ── Compile standalone fuzz targets against the project classpath ──
BUILD_CP="target/classes:target/test-classes:target/deps/*"
javac -cp "${BUILD_CP}" \
    -d $OUT \
    $SRC/PathNavigatorFuzzer.java \
    $SRC/MatchingUtilitiesFuzzer.java

# ── Generate Jazzer wrapper scripts ──
for fuzzer in PathNavigatorFuzzer MatchingUtilitiesFuzzer; do
    cat > $OUT/${fuzzer} << WRAPPER
#!/bin/bash
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="\${JVM_LD_LIBRARY_PATH:-}":\$this_dir
\$this_dir/jazzer_driver \\
    --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
    --cp=${RUNTIME_CP} \\
    --target_class=${fuzzer} \\
    --jvm_args="-Xmx2048m" \\
    "\$@"
WRAPPER
    chmod +x $OUT/${fuzzer}
done

echo "=== ClusterFuzzLite build complete ==="
echo "Fuzz targets: PathNavigatorFuzzer, MatchingUtilitiesFuzzer"
