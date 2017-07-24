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
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorDeserialization implements IBehaviorDeserialization {
    private final ObjectMapper objectMapper;

    @Inject
    public BehaviorDeserialization(ObjectMapper objectMapper) {
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
        List<IExtension> ret = new LinkedList<>();
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
}
