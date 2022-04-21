package ai.labs.eddi.configs.packages.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@Getter
@Setter
@NoArgsConstructor
public class PackageConfiguration {
    private List<PackageExtension> packageExtensions = new LinkedList<>();

    @Getter
    @Setter
    public static class PackageExtension {
        private URI type;
        private Map<String, Object> extensions = new HashMap<>();
        private Map<String, Object> config = new HashMap<>();
    }
}
