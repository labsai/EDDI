package ai.labs.memory;

import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.persistence.IResourceStore;
import ai.labs.utilities.CharacterUtilities;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class ConversationMemoryUtilities {
    public static ConversationMemorySnapshot convertConversationMemory(IConversationMemory conversationMemory) throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();

        if (conversationMemory.getId() != null) {
            snapshot.setId(conversationMemory.getId());
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

        return snapshot;
    }

    private static ConversationMemorySnapshot.ConversationStepSnapshot iterateConversationStep(IConversationMemory.IConversationStep conversationStep) {
        ConversationMemorySnapshot.ConversationStepSnapshot conversationStepSnapshot = new ConversationMemorySnapshot.ConversationStepSnapshot();
        for (IConversationMemory.IConversationContext context : conversationStep.getAllConversationContexts()) {
            if (conversationStep.isEmpty()) {
                continue;
            }
            ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot = new ConversationMemorySnapshot.PackageRunSnapshot();
            packageRunSnapshot.setContext(context.getContext());
            conversationStepSnapshot.getPackages().add(packageRunSnapshot);
            for (IData data : conversationStep.getAllElements(context)) {
                ConversationMemorySnapshot.ResultSnapshot resultSnapshot = new ConversationMemorySnapshot.ResultSnapshot(data.getKey(), data.getResult(), data.getPossibleResults(), data.getTimestamp(), data.isPublic());
                packageRunSnapshot.getLifecycleTasks().add(resultSnapshot);
            }
        }

        return conversationStepSnapshot;
    }

    private static List<IConversationMemory.IConversationStep> iterateRedoCache(List<ConversationMemorySnapshot.ConversationStepSnapshot> redoSteps) {
        List<IConversationMemory.IConversationStep> conversationSteps = new LinkedList<IConversationMemory.IConversationStep>();
        for (ConversationMemorySnapshot.ConversationStepSnapshot redoStep : redoSteps) {
            IConversationMemory.IWritableConversationStep conversationStep = new ConversationStep(new ConversationMemory.ConversationContext());
            conversationSteps.add(conversationStep);
            for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : redoStep.getPackages()) {
                conversationStep.setCurrentConversationContext(new ConversationMemory.ConversationContext(packageRunSnapshot.getContext()));
                for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    Data data = new Data(resultSnapshot.getKey(), resultSnapshot.getResult(), resultSnapshot.getPossibleResults(), resultSnapshot.getTimestamp(), resultSnapshot.isPublic());
                    conversationStep.storeData(data);
                }
            }
        }

        return conversationSteps;
    }

    public static IConversationMemory convertConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        IConversationMemory conversationMemory = new ConversationMemory(snapshot.getId(), snapshot.getBotId(), snapshot.getBotVersion());
        conversationMemory.setConversationState(snapshot.getConversationState());

        List<IConversationMemory.IConversationStep> redoSteps = iterateRedoCache(snapshot.getRedoCache());
        for (IConversationMemory.IConversationStep redoStep : redoSteps) {
            conversationMemory.getRedoCache().add(redoStep);
        }

        List<ConversationMemorySnapshot.ConversationStepSnapshot> conversationSteps = snapshot.getConversationSteps();
        for (int i = 0; i < conversationSteps.size(); i++) {
            ConversationMemorySnapshot.ConversationStepSnapshot conversationStepSnapshot = conversationSteps.get(i);

            if (i > 0) {
                ((ConversationMemory) conversationMemory).startNextStep();
            }

            for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : conversationStepSnapshot.getPackages()) {
                conversationMemory.setCurrentContext(packageRunSnapshot.getContext());
                for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    Data data = new Data(resultSnapshot.getKey(), resultSnapshot.getResult(), resultSnapshot.getPossibleResults(), resultSnapshot.getTimestamp(), resultSnapshot.isPublic());
                    conversationMemory.getCurrentStep().storeData(data);
                }
            }
        }

        return conversationMemory;
    }

    public static SimpleConversationMemorySnapshot convertSimpleConversationMemory(ConversationMemorySnapshot conversationMemorySnapshot, boolean includeAll) {
        SimpleConversationMemorySnapshot simpleSnapshot = new SimpleConversationMemorySnapshot();

        simpleSnapshot.setBotId(conversationMemorySnapshot.getBotId());
        simpleSnapshot.setBotVersion(conversationMemorySnapshot.getBotVersion());
        simpleSnapshot.setConversationState(conversationMemorySnapshot.getConversationState());
        simpleSnapshot.setEnvironment(conversationMemorySnapshot.getEnvironment());
        simpleSnapshot.setRedoCacheSize(conversationMemorySnapshot.getRedoCache().size());

        for (ConversationMemorySnapshot.ConversationStepSnapshot conversationStepSnapshot : conversationMemorySnapshot.getConversationSteps()) {
            SimpleConversationMemorySnapshot.SimpleConversationStep simpleConversationStep = new SimpleConversationMemorySnapshot.SimpleConversationStep();
            simpleSnapshot.getConversationSteps().add(simpleConversationStep);
            for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : conversationStepSnapshot.getPackages()) {
                for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                    if (includeAll || resultSnapshot.isPublic()) {
                        Object result = resultSnapshot.getResult();
                        String value = result instanceof List ? CharacterUtilities.arrayToString((List) result, ",") : result.toString();
                        simpleConversationStep.getData().add(new SimpleConversationMemorySnapshot.SimpleData(resultSnapshot.getKey(), value));
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
