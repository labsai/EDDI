package ai.labs.resources.rest.packages.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@Getter
@Setter
@NoArgsConstructor
public class PackageConfiguration {
    private List<PackageExtension> packageExtensions;

    @Getter
    @Setter
    public static class PackageExtension {
        private URI type;
        private Map<String, Object> extensions;
        private Map<String, Object> config;
    }
}
