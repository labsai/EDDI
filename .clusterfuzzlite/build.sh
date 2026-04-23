#!/bin/bash -eu
# ClusterFuzzLite build script for EDDI
# Compiles ONLY the specific utility classes needed by fuzz targets.
# No Maven, no full project build — avoids JDK version mismatches.
#
# The base-builder-jvm image provides:
#   $OUT          — directory for final fuzzer executables
#   $SRC          — source directory (this is /src)
#   $JAZZER_API_PATH — path to jazzer_agent_deploy.jar

cd $SRC/project

# ── Copy and patch source files for JDK 21 compatibility ──
# The project targets Java 25 and uses unnamed variables (catch Exception _)
# which is a Java 22+ feature. Patch these for JDK 21 compatibility.
FUZZ_SRC=$SRC/fuzz-src
mkdir -p $FUZZ_SRC/ai/labs/eddi/utils

for f in PathNavigator.java MatchingUtilities.java RuntimeUtilities.java; do
    cp "src/main/java/ai/labs/eddi/utils/$f" "$FUZZ_SRC/ai/labs/eddi/utils/$f"
done

# Patch unnamed variables: catch (SomeException _) → catch (SomeException ignored)
# This is a Java 22+ feature; CFL runtime uses Java 21.
perl -pi -e 's/(\w+)\s+_\s*\)/\1 ignored)/g' $FUZZ_SRC/ai/labs/eddi/utils/*.java

# Compile utility classes (target Java 21 to match CFL runtime JVM)
mkdir -p $OUT/classes
javac -source 21 -target 21 \
    -d $OUT/classes \
    $FUZZ_SRC/ai/labs/eddi/utils/PathNavigator.java \
    $FUZZ_SRC/ai/labs/eddi/utils/MatchingUtilities.java \
    $FUZZ_SRC/ai/labs/eddi/utils/RuntimeUtilities.java

echo "✅ Utility classes compiled (Java 21 target)"

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
            // Expected — fuzzer explores error paths
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
            // Expected — fuzzer explores error paths
        }
    }
}
EOF

# ── Compile fuzz targets against the utility classes ──
javac -source 21 -target 21 \
    -cp "$OUT/classes" \
    -d $OUT \
    $SRC/PathNavigatorFuzzer.java \
    $SRC/MatchingUtilitiesFuzzer.java

echo "✅ Fuzz targets compiled"

# ── Build runtime classpath (relative to $OUT via $this_dir) ──
RUNTIME_CP="\$this_dir/classes:\$this_dir"

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
