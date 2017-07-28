package ai.labs.behavior.impl;

import ai.labs.behavior.impl.extensions.IBehaviorExtension;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleElementConfiguration;
import ai.labs.serialization.DeserializationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@Slf4j
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

            behaviorSet.getBehaviorGroups().addAll(behaviorJson.getBehaviorGroups().stream().map(
                    groupConfiguration -> {
                        BehaviorGroup behaviorGroup = new BehaviorGroup();
                        behaviorGroup.setName(groupConfiguration.getName());

                        behaviorGroup.getBehaviorRules().addAll(groupConfiguration.getBehaviorRules().stream().map(
                                behaviorRuleJson -> {
                                    BehaviorRule behaviorRule = new BehaviorRule(behaviorRuleJson.getName());
                                    behaviorRule.setActions(behaviorRuleJson.getActions());
                                    behaviorRule.setExtensions(convert(behaviorRuleJson.getChildren(), behaviorSet));
                                    return behaviorRule;
                                }
                        ).collect(Collectors.toList()));

                        return behaviorGroup;
                    }
            ).collect(Collectors.toList()));

            return behaviorSet;
        } catch (IOException e) {
            throw new DeserializationException(e.getLocalizedMessage(), e);
        }
    }

    private List<IBehaviorExtension> convert(List<BehaviorRuleElementConfiguration> children, BehaviorSet behaviorSet) {
        return children.stream().map(
                child -> {
                    try {
                        String key = IBehaviorExtension.EXTENSION_PREFIX + child.getType();
                        IBehaviorExtension extension = extensionProvider.get(key).get();
                        extension.setValues(child.getValues());
                        List<IBehaviorExtension> convert = convert(child.getChildren(), behaviorSet);
                        IBehaviorExtension[] executablesClone = deepCopy(child, convert);
                        extension.setChildren(executablesClone);
                        extension.setContainingBehaviorRuleSet(behaviorSet);
                        return extension;
                    } catch (CloneNotSupportedException e) {
                        log.error(e.getLocalizedMessage(), e);
                        return null;
                    }
                }
        ).collect(Collectors.toList());
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
