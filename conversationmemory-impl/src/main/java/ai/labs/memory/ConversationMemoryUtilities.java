package ai.labs.memory;

import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class ConversationMemoryUtilities {
    public static ConversationMemorySnapshot convertConversationMemory(IConversationMemory conversationMemory) {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();

        if (conversationMemory.getConversationId() != null) {
            snapshot.setConversationId(conversationMemory.getConversationId());
        }

        snapshot.setBotId(conversationMemory.getBotId());
        snapshot.setBotVersion(conversationMemory.getBotVersion());
        snapshot.setConversationState(conversationMemory.getConversationState());

        for (IConversationMemory.IConversationStep redoStep : conversationMemory.getRedoCache()) {
            ConversationMemorySnapshot.ConversationStepSnapshot redoStepSnapshot = iterateConversationStep(redoStep);
            snapshot.getRedoCache().push(redoStepSnapshot);
        }

        for (int i = conversationMemory.getAllSteps().size() - 1; i >= 0; i--) {
            IConversationMemory.IConversationStep conversationStep = conversationMemory.getAllSteps().get(i);
            snapshot.getConversationSteps().add(iterateConversationStep(conversationStep));
        }

        snapshot.getConversationOutputs().addAll(conversationMemory.getConversationOutputs());

        return snapshot;
    }

    private static ConversationMemorySnapshot.ConversationStepSnapshot iterateConversationStep(IConversationMemory.IConversationStep conversationStep) {
        ConversationMemorySnapshot.ConversationStepSnapshot conversationStepSnapshot = new ConversationMemorySnapshot.ConversationStepSnapshot();

        if (!conversationStep.isEmpty()) {
            ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot = new ConversationMemorySnapshot.PackageRunSnapshot();
            conversationStepSnapshot.getPackages().add(packageRunSnapshot);
            for (IData data : conversationStep.getAllElements()) {
                ConversationMemorySnapshot.ResultSnapshot resultSnapshot = new ConversationMemorySnapshot.ResultSnapshot(data.getKey(), data.getResult(), data.getPossibleResults(), data.getTimestamp(), data.isPublic());
                packageRunSnapshot.getLifecycleTasks().add(resultSnapshot);
            }
        }

        return conversationStepSnapshot;
    }

    private static List<IConversationMemory.IConversationStep> iterateRedoCache(List<ConversationMemorySnapshot.ConversationStepSnapshot> redoSteps) {
        List<IConversationMemory.IConversationStep> conversationSteps = new LinkedList<>();
        for (ConversationMemorySnapshot.ConversationStepSnapshot redoStep : redoSteps) {
            IConversationMemory.IWritableConversationStep conversationStep = new ConversationStep(new ConversationOutput());
            conversationSteps.add(conversationStep);
            for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : redoStep.getPackages()) {
                for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    Data data = new Data(resultSnapshot.getKey(), resultSnapshot.getResult(), resultSnapshot.getPossibleResults(), resultSnapshot.getTimestamp(), resultSnapshot.isPublic());
                    conversationStep.storeData(data);
                }
            }
        }

        return conversationSteps;
    }

    public static IConversationMemory convertConversationMemorySnapshot(ConversationMemorySnapshot snapshot) {
        ConversationMemory conversationMemory = new ConversationMemory(snapshot.getConversationId(), snapshot.getBotId(), snapshot.getBotVersion());
        conversationMemory.setConversationState(snapshot.getConversationState());

        List<IConversationMemory.IConversationStep> redoSteps = iterateRedoCache(snapshot.getRedoCache());
        for (IConversationMemory.IConversationStep redoStep : redoSteps) {
            conversationMemory.getRedoCache().add(redoStep);
        }

        List<ConversationMemorySnapshot.ConversationStepSnapshot> conversationSteps = snapshot.getConversationSteps();
        for (int i = 0; i < conversationSteps.size(); i++) {
            ConversationOutput conversationOutput = snapshot.getConversationOutputs().get(i);
            ConversationMemorySnapshot.ConversationStepSnapshot conversationStepSnapshot = conversationSteps.get(i);

            if (i > 0) {
                conversationMemory.startNextStep(conversationOutput);
            }

            for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : conversationStepSnapshot.getPackages()) {
                for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    Data data = new Data(resultSnapshot.getKey(), resultSnapshot.getResult(), resultSnapshot.getPossibleResults(), resultSnapshot.getTimestamp(), resultSnapshot.isPublic());
                    conversationMemory.getCurrentStep().storeData(data);
                }
            }
        }

        return conversationMemory;
    }

    public static SimpleConversationMemorySnapshot convertSimpleConversationMemory(ConversationMemorySnapshot conversationMemorySnapshot, boolean returnDetailed) {
        SimpleConversationMemorySnapshot simpleSnapshot = new SimpleConversationMemorySnapshot();

        simpleSnapshot.setBotId(conversationMemorySnapshot.getBotId());
        simpleSnapshot.setBotVersion(conversationMemorySnapshot.getBotVersion());
        simpleSnapshot.setConversationState(conversationMemorySnapshot.getConversationState());
        simpleSnapshot.setEnvironment(conversationMemorySnapshot.getEnvironment());
        simpleSnapshot.setRedoCacheSize(conversationMemorySnapshot.getRedoCache().size());

        simpleSnapshot.getConversationOutputs().addAll(conversationMemorySnapshot.getConversationOutputs());

        for (ConversationMemorySnapshot.ConversationStepSnapshot conversationStepSnapshot : conversationMemorySnapshot.getConversationSteps()) {
            SimpleConversationMemorySnapshot.SimpleConversationStep simpleConversationStep = new SimpleConversationMemorySnapshot.SimpleConversationStep();
            simpleSnapshot.getConversationSteps().add(simpleConversationStep);
            for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : conversationStepSnapshot.getPackages()) {
                for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    if (returnDetailed || resultSnapshot.isPublic()) {
                        Object result = resultSnapshot.getResult();
                        simpleConversationStep.getConversationStep().add(
                                new SimpleConversationMemorySnapshot.ConversationStepData(resultSnapshot.getKey(), result));
                    } else {
                        continue;
                    }

                    simpleConversationStep.setTimestamp(resultSnapshot.getTimestamp());
                }
            }
        }

        return simpleSnapshot;
    }
}
