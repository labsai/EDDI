package ai.labs.core.behavior;

import ai.labs.core.behavior.extensions.IExtension;
import ai.labs.core.behavior.extensions.descriptor.BehaviorRuleExtensionRegistry;
import ai.labs.core.extensions.IExtensionRegistry;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorGroupConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleElementConfiguration;
import ai.labs.serialization.DeserializationException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorSerialization implements IBehaviorSerialization {

    private final ObjectMapper objectMapper;

    @Inject
    public BehaviorSerialization(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public BehaviorSet deserialize(String json) throws DeserializationException {
        try {
            BehaviorSet behaviorSet = new BehaviorSet();

            BehaviorConfiguration behaviorJson = objectMapper.readerFor(BehaviorConfiguration.class).readValue(json);
            for (BehaviorGroupConfiguration groupConfiguration : behaviorJson.getBehaviorGroups()) {
                BehaviorGroup behaviorGroup = new BehaviorGroup();
                behaviorGroup.setName(groupConfiguration.getName());
                behaviorSet.getBehaviorGroups().add(behaviorGroup);

                for (BehaviorRuleConfiguration behaviorRuleJson : groupConfiguration.getBehaviorRules()) {
                    BehaviorRule behaviorRule = new BehaviorRule(behaviorRuleJson.getName());
                    behaviorRule.setActions(behaviorRuleJson.getActions());
                    behaviorRule.setExtensions(convert(behaviorRuleJson.getChildren(), behaviorSet));
                    behaviorGroup.getBehaviorRules().add(behaviorRule);
                }
            }

            return behaviorSet;
        } catch (IOException | CloneNotSupportedException | IExtensionRegistry.ExtensionRegistryException e) {
            throw new DeserializationException(e.getLocalizedMessage(), e);
        }
    }

    private static List<IExtension> convert(List<BehaviorRuleElementConfiguration> children, BehaviorSet behaviorSet) throws CloneNotSupportedException, IExtensionRegistry.ExtensionRegistryException {
        List<IExtension> ret = new LinkedList<IExtension>();
        for (BehaviorRuleElementConfiguration child : children) {
            IExtension extension = BehaviorRuleExtensionRegistry.getInstance().getExtension(child.getType());
            extension.setValues(child.getValues());
            IExtension[] extensions = convert(child.getChildren(), behaviorSet).toArray(new IExtension[child.getChildren().size()]);
            IExtension[] executablesClone = new IExtension[extensions.length];
            //deep copy
            for (int i = 0, executablesLength = extensions.length; i < executablesLength; i++) {
                executablesClone[i] = extensions[i].clone();
            }
            extension.setChildren(executablesClone);
            extension.setContainingBehaviorRuleSet(behaviorSet);
            ret.add(extension);
        }

        return ret;
    }

    @Override
    public String serialize(BehaviorSet set) throws IOException {
        BehaviorConfiguration result = new BehaviorConfiguration();

        for (BehaviorGroup group : set.getBehaviorGroups()) {
            BehaviorGroupConfiguration behaviorGroupConfiguration = new BehaviorGroupConfiguration();
            behaviorGroupConfiguration.setName(group.getName());
            result.getBehaviorGroups().add(behaviorGroupConfiguration);
            for (BehaviorRule behaviorRule : group.getBehaviorRules()) {
                behaviorGroupConfiguration.getBehaviorRules().add(convert(behaviorRule));
            }
        }

        StringWriter writer = new StringWriter();
        objectMapper.writeValue(writer, result);

        return writer.toString();
    }

    private static BehaviorRuleConfiguration convert(BehaviorRule behaviorRule) {
        BehaviorRuleConfiguration result = new BehaviorRuleConfiguration();

        result.setName(behaviorRule.getName());
        result.setActions(behaviorRule.getActions());
        for (IExtension extension : behaviorRule.getExtensions()) {
            result.getChildren().add(convert(extension));
        }

        return result;
    }

    private static BehaviorRuleElementConfiguration convert(IExtension extension) {
        BehaviorRuleElementConfiguration behaviorRuleElementJson = new BehaviorRuleElementConfiguration();

        behaviorRuleElementJson.setType(extension.getId());
        behaviorRuleElementJson.getValues().putAll(extension.getValues());

        for (IExtension e : extension.getChildren()) {
            behaviorRuleElementJson.getChildren().add(convert(e));
        }

        return behaviorRuleElementJson;
    }
}
