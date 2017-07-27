package ai.labs.behavior.impl;

import ai.labs.behavior.impl.extensions.IBehaviorExtension;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorGroupConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleElementConfiguration;
import ai.labs.serialization.DeserializationException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class BehaviorDeserialization implements IBehaviorDeserialization {
    private final ObjectMapper objectMapper;
    private final Map<String, Provider<IBehaviorExtension>> extensionProvider;

    @Inject
    public BehaviorDeserialization(ObjectMapper objectMapper,
                                   Map<String, Provider<IBehaviorExtension>> extensionProvider) {
        this.objectMapper = objectMapper;
        this.extensionProvider = extensionProvider;
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
        } catch (IOException | CloneNotSupportedException e) {
            throw new DeserializationException(e.getLocalizedMessage(), e);
        }
    }

    private List<IBehaviorExtension> convert(List<BehaviorRuleElementConfiguration> children,
                                             BehaviorSet behaviorSet) throws CloneNotSupportedException {
        List<IBehaviorExtension> ret = new LinkedList<>();
        for (BehaviorRuleElementConfiguration child : children) {
            String key = IBehaviorExtension.EXTENSION_PREFIX + child.getType();
            IBehaviorExtension extension = extensionProvider.get(key).get();
            extension.setValues(child.getValues());
            List<IBehaviorExtension> convert = convert(child.getChildren(), behaviorSet);
            IBehaviorExtension[] executablesClone = deepCopy(child, convert);
            extension.setChildren(executablesClone);
            extension.setContainingBehaviorRuleSet(behaviorSet);
            ret.add(extension);
        }

        return ret;
    }

    private IBehaviorExtension[] deepCopy(BehaviorRuleElementConfiguration child,
                                          List<IBehaviorExtension> behaviorExtensionList)
            throws CloneNotSupportedException {
        IBehaviorExtension[] behaviorExtensions = behaviorExtensionList.
                toArray(new IBehaviorExtension[child.getChildren().size()]);
        IBehaviorExtension[] executablesClone = new IBehaviorExtension[behaviorExtensions.length];
        //deep copy
        for (int i = 0, executablesLength = behaviorExtensions.length; i < executablesLength; i++) {
            executablesClone[i] = behaviorExtensions[i].clone();
        }
        return executablesClone;
    }
}
