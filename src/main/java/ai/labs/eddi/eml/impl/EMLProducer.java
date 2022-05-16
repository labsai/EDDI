package ai.labs.eddi.eml.impl;

import ai.labs.eddi.configs.behavior.IBehaviorStore;
import ai.labs.eddi.configs.behavior.IRestBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConditionConfiguration;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.eml.IEMLProducer;
import ai.labs.eddi.modules.behavior.impl.conditions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RestUtilities.extractResourceId;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class EMLProducer implements IEMLProducer {

    public static final String NEW_LINE = System.getProperty("line.separator");
    public static final String TAB_STRING = "    ";
    private final IPackageStore packageStore;
    private final IBehaviorStore behaviorStore;
    private final IOutputStore outputStore;
    private final ObjectMapper objectMapper;

    @Inject
    public EMLProducer(IPackageStore packageStore,
                       IBehaviorStore behaviorStore,
                       IOutputStore outputStore,
                       ObjectMapper objectMapper) {
        this.packageStore = packageStore;

        this.behaviorStore = behaviorStore;
        this.outputStore = outputStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public String produceMarkup(String packageId, Integer packageVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        var packageMarkup = new StringBuilder();

        var packageConfiguration = packageStore.read(packageId, packageVersion);
        for (var packageExtension : packageConfiguration.getPackageExtensions()) {
            var extensionUri = packageExtension.getType();
            switch (extensionUri.toString()) {
                case IRestBehaviorStore
                        .resourceBaseType -> {
                    var config = packageExtension.getConfig();
                    var behaviorId = extractResourceId(URI.create(config.get("uri").toString()));
                    if (behaviorId != null) {
                        var behaviorConfiguration = behaviorStore.read(behaviorId.getId(), behaviorId.getVersion());
                        packageMarkup.append(produceBehaviorMarkup(behaviorConfiguration));
                    }
                }
                case IRestOutputStore
                        .resourceBaseType -> {
                    var config = packageExtension.getConfig();
                    var outputId = extractResourceId(URI.create(config.get("uri").toString()));
                    if (outputId != null) {
                        var configurationConfig = outputStore.read(outputId.getId(), outputId.getVersion());
                        packageMarkup.append(produceOutputMarkup(configurationConfig));
                    }
                }
            }
        }

        return packageMarkup.toString();
    }

    private String produceBehaviorMarkup(BehaviorConfiguration behaviorConfiguration) {
        var behaviorMarkup = new StringBuilder();
        for (var behaviorGroup : behaviorConfiguration.getBehaviorGroups()) {
            var groupName = behaviorGroup.getName();
            if (!isNullOrEmpty(groupName)) {
                behaviorMarkup.append("GROUP ").append(groupName).append(NEW_LINE).append(NEW_LINE);
            }
            for (var behaviorRule : behaviorGroup.getBehaviorRules()) {
                behaviorMarkup.append("WHEN ").append(NEW_LINE);

                List<String> conditions = iterateCondition(behaviorRule.getConditions(), 1);
                for (String condition : conditions) {
                    behaviorMarkup.append(condition).append(" AND").append(NEW_LINE);
                }
                if (conditions.size() > 0) {
                    behaviorMarkup.delete(behaviorMarkup.lastIndexOf(" AND"), behaviorMarkup.length());
                }

                var actions = behaviorRule.getActions();
                behaviorMarkup.append(NEW_LINE).
                        append("THEN TRIGGER ACTION").append(actions.size() >= 2 ? "S" : "").append(NEW_LINE);
                for (String action : actions) {
                    behaviorMarkup.append(action).append(", ");
                }

                if (!actions.isEmpty()) {
                    behaviorMarkup.delete(behaviorMarkup.length() - 2, behaviorMarkup.length());
                }

                behaviorMarkup.append(NEW_LINE).append(NEW_LINE);
            }

            behaviorMarkup.append(NEW_LINE).append(NEW_LINE);
        }

        return behaviorMarkup.toString();
    }

    private List<String> iterateCondition(List<BehaviorRuleConditionConfiguration> conditions, int spacing) {
        List<String> ret = new LinkedList<>();
        for (var condition : conditions) {
            ret.add(generateConditionMarkup(condition, spacing));
            var subConditions = condition.getConditions();
            if (!subConditions.isEmpty()) {
                ret.addAll(iterateCondition(subConditions, spacing + 1));
            }
        }

        return ret;
    }

    private String generateConditionMarkup(BehaviorRuleConditionConfiguration condition, int spacing) {
        var conditionString = "";
        var spaces = createSpacing(spacing);
        conditionString += spaces;
        switch (condition.getType()) {
            case InputMatcher.ID -> {
                var occurrence = condition.getConfigs().getOrDefault("occurrence", "currentStep");
                String expressions = condition.getConfigs().get("expressions");
                conditionString += "USER INPUT MATCHES ";
                if (expressions.equals("*")) {
                    conditionString += "ANY EXPRESSION";
                } else {
                    conditionString += "EXPRESSION";
                    conditionString += (expressions.contains(",") ? "S " + expressions : " " + expressions);
                }
                switch (BaseMatcher.ConversationStepOccurrence.valueOf(occurrence)) {

                    case currentStep -> conditionString += " IN THE CURRENT STEP";
                    case lastStep -> conditionString += " IN THE LAST STEP ";
                    case anyStep -> conditionString += " AT ANY TIME IN THE CONVERSATION";
                    case never -> conditionString += " AT NO TIME IN THE CONVERSATION";
                    default -> throw new IllegalStateException("Unexpected occurrence in InputMatcher: " + occurrence);
                }
            }

            case ActionMatcher.ID -> {
                var occurrence = condition.getConfigs().getOrDefault("occurrence", "currentStep");
                conditionString += "BOT ACTION " + condition.getConfigs().get("actions");
                switch (BaseMatcher.ConversationStepOccurrence.valueOf(occurrence)) {

                    case currentStep -> conditionString += " IN THE CURRENT STEP";
                    case lastStep -> conditionString += " IN THE LAST STEP ";
                    case anyStep -> conditionString += " AT ANY TIME IN THE CONVERSATION";
                    case never -> conditionString += " AT NO TIME IN THE CONVERSATION";
                    default -> throw new IllegalStateException("Unexpected occurrence in ActionMatcher: " + occurrence);
                }
            }

            case Negation.ID -> conditionString += "NOT";

            case Connector.ID -> conditionString += condition.getConfigs().get("connector").toUpperCase();

            case ContextMatcher.ID -> conditionString += "a context match";
        }

        return conditionString + NEW_LINE;
    }

    private String produceOutputMarkup(OutputConfigurationSet configurationConfig) {
        StringBuilder outputMarkup = new StringBuilder();

        for (var outputDefinition : configurationConfig.getOutputSet()) {
            var action = outputDefinition.getAction();
            outputMarkup.append("IF ACTION ").
                    append(action).append(" WAS TRIGGERED ").append(outputDefinition.getTimesOccurred()).
                    append("THEN: ").append(NEW_LINE);
            for (var output : outputDefinition.getOutputs()) {
                outputMarkup.append("OUTPUT TYPE: ").append(output.getType()).append(NEW_LINE);

                var valueAlternatives = output.getValueAlternatives();
                if (!isNullOrEmpty(valueAlternatives)) {
                    if (valueAlternatives.size() > 1) {
                        outputMarkup.append("CHOOSE RANDOMLY ONE OF THE FOLLOWING:").append(NEW_LINE);
                    } else {
                        switch (output.getType()) {
                            case "text" -> {
                                var outputObj = valueAlternatives.get(0);
                                if (outputObj instanceof String) {
                                    outputMarkup.append("SAY ").append(outputObj);
                                } else if (outputObj instanceof Map) {
                                    var outputMap = convertObjectToOutputMap(outputObj);
                                    switch (outputMap.get("type").toString()) {
                                        case "text" -> outputMarkup.append(outputMap.get("text"));
                                        case "image" -> outputMarkup.append(outputMap.get("url"));
                                        case "quickReply" -> {
                                            outputMarkup.append("USER'S QUICK REPLIES");
                                            outputMarkup.append("TEXT: ").append(outputMap.get("value"));
                                            outputMarkup.append("EXPRESSIONS: ").append(outputMap.get("expressions"));
                                        }
                                    }
                                }
                            }
                        }
                        outputMarkup.append(NEW_LINE);
                    }
                }
            }
        }

        return outputMarkup.toString();
    }

    private static String createSpacing(int spacing) {
        return TAB_STRING.repeat(Math.max(0, spacing));
    }

    private Map<String, Object> convertObjectToOutputMap(Object randomOutputMap) {
        return objectMapper.convertValue(randomOutputMap, new TypeReference<>() {});
    }
}
