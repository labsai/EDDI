package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.PackageRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import static ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */

@ApplicationScoped
public class ConversationMemoryUtilities {
    private static final String KEY_INPUT_INITIAL = "input:initial";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_QUICK_REPLIES = "quickReplies";

    public static ConversationMemorySnapshot convertConversationMemory(IConversationMemory conversationMemory) {
        var snapshot = getMemorySnapshot(conversationMemory);

        for (var redoStep : conversationMemory.getRedoCache()) {
            var redoStepSnapshot = iterateConversationStep(redoStep);
            snapshot.getRedoCache().push(redoStepSnapshot);
        }

        for (int i = conversationMemory.getAllSteps().size() - 1; i >= 0; i--) {
            var conversationStep = conversationMemory.getAllSteps().get(i);
            snapshot.getConversationSteps().add(iterateConversationStep(conversationStep));
        }

        snapshot.getConversationOutputs().addAll(conversationMemory.getConversationOutputs());
        snapshot.getConversationProperties().putAll(conversationMemory.getConversationProperties());

        return snapshot;
    }

    private static ConversationMemorySnapshot getMemorySnapshot(IConversationMemory conversationMemory) {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();

        if (conversationMemory.getUserId() != null) {
            snapshot.setUserId(conversationMemory.getUserId());
        }

        if (conversationMemory.getConversationId() != null) {
            snapshot.setConversationId(conversationMemory.getConversationId());
        }

        snapshot.setBotId(conversationMemory.getBotId());
        snapshot.setBotVersion(conversationMemory.getBotVersion());
        snapshot.setConversationState(conversationMemory.getConversationState());
        return snapshot;
    }

    private static ConversationStepSnapshot iterateConversationStep(IConversationStep conversationStep) {
        ConversationStepSnapshot conversationStepSnapshot = new ConversationStepSnapshot();

        if (!conversationStep.isEmpty()) {
            var packageRunSnapshot = new PackageRunSnapshot();
            conversationStepSnapshot.getPackages().add(packageRunSnapshot);
            for (var data : conversationStep.getAllElements()) {
                var resultSnapshot = new ResultSnapshot(
                        data.getKey(),
                        data.getResult(),
                        data.getPossibleResults(),
                        data.getTimestamp(),
                        data.getOriginPackageId(),
                        data.isPublic());
                packageRunSnapshot.getLifecycleTasks().add(resultSnapshot);
            }
        }

        return conversationStepSnapshot;
    }

    private static List<IConversationStep> iterateRedoCache(List<ConversationStepSnapshot> redoSteps) {
        List<IConversationStep> conversationSteps = new LinkedList<>();
        for (var redoStep : redoSteps) {
            IWritableConversationStep conversationStep = new ConversationStep(new ConversationOutput());
            conversationSteps.add(conversationStep);
            for (var packageRunSnapshot : redoStep.getPackages()) {
                for (var resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    var data = new Data(resultSnapshot.getKey(), resultSnapshot.getResult(), resultSnapshot.getPossibleResults(), resultSnapshot.getTimestamp(), resultSnapshot.isPublic());
                    conversationStep.storeData(data);
                }
            }
        }

        return conversationSteps;
    }

    public static IConversationMemory convertConversationMemorySnapshot(ConversationMemorySnapshot snapshot) {
        ConversationMemory conversationMemory = new ConversationMemory(snapshot.getConversationId(),
                snapshot.getBotId(), snapshot.getBotVersion(), snapshot.getUserId());

        conversationMemory.setConversationState(snapshot.getConversationState());
        conversationMemory.getConversationProperties().putAll(snapshot.getConversationProperties());

        var redoSteps = iterateRedoCache(snapshot.getRedoCache());
        for (var redoStep : redoSteps) {
            conversationMemory.getRedoCache().add(redoStep);
        }

        var conversationSteps = snapshot.getConversationSteps();
        var conversationOutputs = snapshot.getConversationOutputs();
        for (int i = 0; i < conversationOutputs.size(); i++) {
            var conversationOutput = conversationOutputs.get(i);
            if (i > 0) {
                conversationMemory.startNextStep(conversationOutput);
            } else {
                conversationMemory.getConversationOutputs().get(i).putAll(conversationOutput);
            }

            var conversationStepSnapshot = conversationSteps.get(i);
            for (var packageRunSnapshot : conversationStepSnapshot.getPackages()) {
                for (var resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    var data = new Data(resultSnapshot.getKey(), resultSnapshot.getResult(), resultSnapshot.getPossibleResults(), resultSnapshot.getTimestamp(), resultSnapshot.isPublic());
                    conversationMemory.getCurrentStep().storeData(data);
                }
            }
        }

        return conversationMemory;
    }

    public static SimpleConversationMemorySnapshot convertSimpleConversationMemory(
            ConversationMemorySnapshot conversationMemorySnapshot, boolean returnDetailed) {

        var simpleSnapshot = getSimpleMemorySnapshot(conversationMemorySnapshot);

        if (!returnDetailed) {
            var simpleConversationOutputs = simpleSnapshot.getConversationOutputs();
            var memoryConversationOutputs = conversationMemorySnapshot.getConversationOutputs();
            for (int i = 0; i < memoryConversationOutputs.size(); i++) {
                var conversationOutput = memoryConversationOutputs.get(i);
                simpleConversationOutputs.add(new ConversationOutput());
                for (var key : conversationOutput.keySet()) {
                    if (key.startsWith(KEY_INPUT_INITIAL) || key.startsWith(KEY_ACTIONS) ||
                            key.startsWith(KEY_OUTPUT) || key.startsWith(KEY_QUICK_REPLIES)) {
                        simpleConversationOutputs.get(i).put(key, conversationOutput.get(key));
                    }
                }
            }
        } else {
            simpleSnapshot.getConversationOutputs().addAll(conversationMemorySnapshot.getConversationOutputs());
            simpleSnapshot.getConversationProperties().putAll(conversationMemorySnapshot.getConversationProperties());
        }

        for (var conversationStepSnapshot : conversationMemorySnapshot.getConversationSteps()) {
            var simpleConversationStep = new SimpleConversationStep();
            simpleSnapshot.getConversationSteps().add(simpleConversationStep);
            for (var packageRunSnapshot : conversationStepSnapshot.getPackages()) {
                for (var resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    var key = resultSnapshot.getKey();
                    if (returnDetailed ||
                            key.equals(KEY_INPUT_INITIAL) || key.startsWith(KEY_ACTIONS) ||
                            key.startsWith(KEY_OUTPUT) || key.startsWith(KEY_QUICK_REPLIES)) {

                        var result = resultSnapshot.getResult();
                        simpleConversationStep.getConversationStep().add(
                                new ConversationStepData(
                                        key,
                                        result,
                                        resultSnapshot.getTimestamp(),
                                        resultSnapshot.getOriginPackageId()));

                    } else {
                        continue;
                    }

                    simpleConversationStep.setTimestamp(resultSnapshot.getTimestamp());
                }
            }
        }

        return simpleSnapshot;
    }

    private static SimpleConversationMemorySnapshot getSimpleMemorySnapshot(ConversationMemorySnapshot conversationMemorySnapshot) {
        SimpleConversationMemorySnapshot simpleSnapshot = new SimpleConversationMemorySnapshot();

        if (conversationMemorySnapshot.getUserId() != null) {
            simpleSnapshot.setUserId(conversationMemorySnapshot.getUserId());
        }

        simpleSnapshot.setConversationId(conversationMemorySnapshot.getConversationId());
        simpleSnapshot.setBotId(conversationMemorySnapshot.getBotId());
        simpleSnapshot.setBotVersion(conversationMemorySnapshot.getBotVersion());
        simpleSnapshot.setConversationState(conversationMemorySnapshot.getConversationState());
        simpleSnapshot.setEnvironment(conversationMemorySnapshot.getEnvironment());
        simpleSnapshot.setUndoAvailable(conversationMemorySnapshot.getConversationSteps().size() > 1);
        simpleSnapshot.setRedoAvailable(!conversationMemorySnapshot.getRedoCache().isEmpty());
        return simpleSnapshot;
    }

    public static SimpleConversationMemorySnapshot convertSimpleConversationMemorySnapshot(
            IConversationMemory returnConversationMemory,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields) {

        return convertSimpleConversationMemorySnapshot(
                convertConversationMemory(returnConversationMemory),
                returnDetailed,
                returnCurrentStepOnly, returningFields);
    }

    public static SimpleConversationMemorySnapshot convertSimpleConversationMemorySnapshot(
            ConversationMemorySnapshot conversationMemorySnapshot,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields) {

        var memorySnapshot = convertSimpleConversationMemory(conversationMemorySnapshot, returnDetailed);
        if (returnCurrentStepOnly) {
            if (isNullOrEmpty(returningFields) || returningFields.contains("conversationSteps")) {
                var conversationSteps = memorySnapshot.getConversationSteps();
                if (!conversationSteps.isEmpty()) {
                    var conversationStep = conversationSteps.get(conversationSteps.size() - 1);
                    conversationSteps.clear();
                    conversationSteps.add(conversationStep);
                }
            } else {
                memorySnapshot.setConversationSteps(null);
            }

            if (isNullOrEmpty(returningFields) || returningFields.contains("conversationOutputs")) {
                var conversationOutputs = memorySnapshot.getConversationOutputs();
                if (!conversationOutputs.isEmpty()) {
                    var conversationOutput = conversationOutputs.get(conversationOutputs.size() - 1);
                    conversationOutputs.clear();
                    conversationOutputs.add(conversationOutput);
                }
            } else {
                memorySnapshot.setConversationOutputs(null);
            }

            if (!isNullOrEmpty(returningFields) && !returningFields.contains("conversationProperties")) {
                memorySnapshot.setConversationProperties(null);
            }
        }
        return memorySnapshot;
    }

    public static Map<String, Object> prepareContext(List<IData<Context>> contextDataList) {
        Map<String, Object> dynamicAttributesMap = new HashMap<>();
        contextDataList.forEach(contextData -> {
            Context context = contextData.getResult();
            String dataKey = contextData.getKey();
            String key = dataKey.substring(dataKey.indexOf(":") + 1);
            if (context != null) {
                var contextType = context.getType();
                switch (contextType) {
                    case object, array, string -> dynamicAttributesMap.put(key, context.getValue());
                }
            } else {
                dynamicAttributesMap.put(key, null);
            }
        });
        return dynamicAttributesMap;
    }
}
