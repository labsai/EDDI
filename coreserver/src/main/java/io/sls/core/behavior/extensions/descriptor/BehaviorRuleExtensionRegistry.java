package io.sls.core.behavior.extensions.descriptor;

import io.sls.core.behavior.extensions.IExtension;
import io.sls.core.extensions.IExtensionRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * User: jarisch
 * Date: 01.05.12
 * Time: 16:05
 */
public class BehaviorRuleExtensionRegistry implements IExtensionRegistry<IExtensionDescriptor, IExtension> {

    private static BehaviorRuleExtensionRegistry instance;
    private Map<String, IExtensionDescriptor> descriptorMap = new HashMap<String, IExtensionDescriptor>();

    public static BehaviorRuleExtensionRegistry getInstance() {
        if (instance == null) {
            instance = new BehaviorRuleExtensionRegistry();
        }
        return instance;
    }

    @Override
    public void register(String id, IExtensionDescriptor extension) {
        descriptorMap.put(extension.getId(), extension);
    }

    @Override
    public void remove(String id) {
        descriptorMap.remove(id);
    }

    @Override
    public IExtension getExtension(String id) throws ExtensionRegistryException {
        try {
            if (descriptorMap.containsKey(id)) {
                return (IExtension) descriptorMap.get(id).createInstance();
            }

            throw new ExtensionRegistryException("No behavior extension found for id: " + id);
        } catch (ClassNotFoundException e) {
            throw new ExtensionRegistryException(e.getLocalizedMessage(), e);
        } catch (InstantiationException e) {
            throw new ExtensionRegistryException(e.getLocalizedMessage(), e);
        } catch (IllegalAccessException e) {
            throw new ExtensionRegistryException(e.getLocalizedMessage(), e);
        }
    }
}
