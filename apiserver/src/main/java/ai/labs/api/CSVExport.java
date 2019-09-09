package ai.labs.api;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.ConversationMemoryUtilities;
import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.memory.rest.IRestConversationStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.utilities.URIUtilities;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
public class CSVExport implements ICSVExport {
    private final IExpressionProvider expressionProvider;
    private final IRestConversationStore restConversationStore;
    private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd' 'HH:mm:ss");

    @Inject
    public CSVExport(IExpressionProvider expressionProvider, IRestConversationStore restConversationStore) {
        this.expressionProvider = expressionProvider;
        this.restConversationStore = restConversationStore;
    }

    @Override
    public Response export(String botId, Integer index, Integer limit, String lastModifiedSince, Boolean addAnswerTimestamp) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        if (botId.endsWith(".csv")) {
            botId = botId.substring(0, botId.indexOf(".csv"));
        }

        StringBuilder ret = new StringBuilder();

        StringBuilder retHeader = new StringBuilder();
        StringBuilder retValues = new StringBuilder();

        List<ConversationDescriptor> conversationDescriptors = restConversationStore.
                readConversationDescriptors(index, limit, botId, null, null, null, lastModifiedSince);


        List<String> csvAvailableHeaderKeys = new LinkedList<>();
        for (ConversationDescriptor conversationDescriptor : conversationDescriptors) {
            String tic = null;
            String psID = null;
            boolean firstIteration = retHeader.length() == 0;
            String conversationId = URIUtilities.extractResourceId(conversationDescriptor.getResource()).getId();

            Date timestampStartConversation = conversationDescriptor.getCreatedOn();
            var memorySnapshot = ConversationMemoryUtilities.convertSimpleConversationMemory(restConversationStore.readRawConversationLog(conversationId), true);
            LinkedHashMap<String, Values> csvMap = new LinkedHashMap<>();
            for (var conversationStep : memorySnapshot.getConversationSteps()) {
                for (var conversationStepData : conversationStep.getConversationStep()) {
                    String key = conversationStepData.getKey();
                    String value = conversationStepData.getValue().toString();
                    Date timestamp = conversationStepData.getTimestamp();
                    if (key.equals("context:url") && value.contains("tic=") && value.contains("psID=")) {
                        tic = value.substring(value.indexOf("?") + 5, value.indexOf("&", value.indexOf("?")));
                        psID = value.substring(value.indexOf("psID=", value.indexOf("?")) + 5, value.indexOf(",", value.indexOf("?")));
                    }

                    if (key.equals("expressions:parsed") && value.startsWith("property")) {
                        Expressions expressions = expressionProvider.parseExpressions(value);
                        Expression property = expressions.get(0);
                        Expression categoryExp = property.getSubExpressions()[0];
                        String questionId = categoryExp.getExpressionName();
                        questionId = questionId.replace("_", "[").concat("]");
                        String answer = categoryExp.getSubExpressions()[0].getExpressionName();
                        var values = new Values(answer, timestamp);
                        csvMap.put(questionId, values);
                    }
                }
            }

            if (!csvMap.isEmpty()) {
                if (firstIteration) {
                    wrapInQuotes(retHeader, "datestamp");
                    wrapInQuotes(retHeader, "tic");
                    wrapInQuotes(retHeader, "psID");
                }

                if (timestampStartConversation != null) {
                    wrapInQuotes(retValues, dateFormat.format(timestampStartConversation));
                    wrapInQuotes(retValues, tic == null ? "null" : tic);
                    wrapInQuotes(retValues, psID == null ? "null" : psID);
                }
                addNewHeaderKeys(csvAvailableHeaderKeys, csvMap.keySet());
                for (String key : csvAvailableHeaderKeys) {
                    if (firstIteration || retHeader.indexOf(key) == -1) {
                        wrapInQuotes(retHeader, key);
                        if (addAnswerTimestamp) {
                            wrapInQuotes(retHeader, key + "-datestamp");
                        }
                    }
                    if (csvMap.containsKey(key)) {
                        Values values = csvMap.get(key);
                        wrapInQuotes(retValues, values.getAnswer());
                        if (addAnswerTimestamp) {
                            wrapInQuotes(retValues, dateFormat.format(values.getTimestamp()));
                        }
                    } else {
                        wrapInQuotes(retValues, "");
                    }
                }

                retValues.deleteCharAt(retValues.length() - 1);
                retValues.append("\n");
            }
        }

        if (retHeader.length() > 0) {
            ret.append(retHeader.substring(0, retHeader.length() - 1));
            ret.append("\n").append(retValues.substring(0, retValues.length() - 1));
        }

        return Response.ok(ret.toString()).type("text/csv").build();
    }

    private void addNewHeaderKeys(List<String> csvAvailableHeaderKeys, Set<String> keySet) {
        keySet.stream().
                filter(key -> !csvAvailableHeaderKeys.contains(key)).
                forEach(csvAvailableHeaderKeys::add);
    }

    private void wrapInQuotes(StringBuilder stringBuilder, Object obj) {
        stringBuilder.append("\"").append(obj).append("\",");
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Values {
        private String answer;
        private Date timestamp;
    }
}

