package ai.labs.core.behavior.extensions.descriptor;

import ai.labs.core.behavior.extensions.IExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class ExtensionDescriptorBuilder {

    private String id;
    private String description;
    private String className;

    private Map<String, String> attributeDescriptions = new HashMap<String, String>();
    private Map<String, String> attributeTypes = new HashMap<String, String>();
    private int maxChildCount = -1;

    private ExtensionDescriptorBuilder(String id, String description, String className) {
        this.id = id;
        this.description = description;
        this.className = className;
    }

    public ExtensionDescriptorBuilder maxChildCount(int maxChildCount) {
        this.maxChildCount = maxChildCount;
        return this;
    }

    public ExtensionDescriptorBuilder attribute(String name, String type, String description) {
        attributeTypes.put(name, type);
        attributeDescriptions.put(name, description);
        return this;
    }

    public IExtensionDescriptor build() {
        return new IExtensionDescriptor() {

            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public int getMaxChildCount() {
                return maxChildCount;
            }

            @Override
            public List<Attribute> getAttributes() {

                List<Attribute> result = new ArrayList<Attribute>();

                for (final Map.Entry<String, String> descriptionEntry : attributeDescriptions.entrySet()) {
                    result.add(new Attribute() {
                        @Override
                        public String getName() {
                            return descriptionEntry.getKey();
                        }

                        @Override
                        public String getType() {
                            return attributeTypes.get(descriptionEntry.getKey());
                        }

                        @Override
                        public String getDescription() {
                            return descriptionEntry.getValue();
                        }
                    });
                }

                return result;
            }

            @Override
            public IExtension createInstance() throws ClassNotFoundException, InstantiationException, IllegalAccessException {
                return (IExtension) Class.forName(className).newInstance();
            }
        };
    }

    public static ExtensionDescriptorBuilder create(String id, String description, String className) {
        return new ExtensionDescriptorBuilder(id, description, className);
    }
}
