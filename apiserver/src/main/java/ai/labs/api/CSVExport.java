package ai.labs.api;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.ConversationMemoryUtilities;
import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.memory.rest.IRestConversationStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.URIUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class CSVExport implements ICSVExport {

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IExpressionProvider expressionProvider;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public CSVExport(IRestInterfaceFactory restInterfaceFactory, IExpressionProvider expressionProvider, IJsonSerialization jsonSerialization) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.expressionProvider = expressionProvider;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public void export(String apiServerUri, Date date) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        IRestConversationStore restConversationStore = restInterfaceFactory.get(IRestConversationStore.class, apiServerUri);
        String tic = null, psID = null;

        String gamificationBotId = "5d414f4dd9bc8649b4e1c7b6";

        for (int i = 0; i < 1000; i++) {
            log.info("i: " + i);
            List<ConversationDescriptor> conversationDescriptors = restConversationStore.readConversationDescriptors(i, 20, "", null, null, null);
            if (conversationDescriptors.isEmpty()) {
                break;
            }
            for (ConversationDescriptor conversationDescriptor : conversationDescriptors) {
                String conversationId = URIUtilities.extractResourceId(conversationDescriptor.getResource()).getId();

                var memorySnapshot = ConversationMemoryUtilities.convertSimpleConversationMemory(restConversationStore.readRawConversationLog(conversationId), true);
                Map<String, String> csvMap = new LinkedHashMap<>();
                for (var conversationStep : memorySnapshot.getConversationSteps()) {
                    for (var conversationStepData : conversationStep.getConversationStep()) {
                        String key = conversationStepData.getKey();
                        String value = conversationStepData.getValue().toString();
                        Date timestamp = conversationStepData.getTimestamp();
                        if (key.equals("context:url") && value.contains("tic=") && value.contains("psID=")) {
                            tic = value.substring(value.indexOf("?") + 5, value.indexOf("&", value.indexOf("?")));
                            psID = value.substring(value.indexOf("psID=", value.indexOf("?")) + 5, value.indexOf(",", value.indexOf("?")));
                            System.out.println("tic=" + tic + " psID=" + psID);
                        }

                        if (key.equals("expressions:parsed") && value.startsWith("property")) {
                            Expressions expressions = expressionProvider.parseExpressions(value);
                            Expression property = expressions.get(0);
                            Expression categoryExp = property.getSubExpressions()[0];
                            String category = categoryExp.getExpressionName();
                            category = category.replace("_", "[");
                            csvMap.put(category + "]", categoryExp.getSubExpressions()[0].getExpressionName());
                        }
                    }
                }

                StringBuilder header = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (String key : csvMap.keySet()) {
                    header.append("\"").append(key).append("\",");
                    values.append("\"").append(csvMap.get(key)).append("\",");
                }

                if (header.length() > 0) {
                    System.out.println(header.substring(0, header.length() - 1));
                    System.out.println(values.substring(0, values.length() - 1));
                }

                //"""datestamp"",""tic"",""PU[PU1]"",""PU[PU2]"",""PU[PU3]"",""PU[PU4]"",""PU[A1]"",""PU[A2]"",""PU[A3]"",""ATI[ATI1]"",""ATI[ATI2]"",""ATI[ATI3]"",""ATI[ATI4]"",""ATI[ATI5]"",""ATI[ATI6]"",""ATI[ATI7]"",""ATI[ATI8]"",""ATI[ATI9]"",""THANKS2"",""UMFRAGE"",""UHUP[HED1]"",""UHUP[HED2]"",""UHUP[UTIL]"",""UHUP[PRAC]"",""USEQ[USEQ]"",""CHATBOT"",""CHUP[HED1]"",""CHUP[HED2]"",""CHUP[UTIL]"",""CHUP[PRAC]"",""CSEQ[CSEQ]"",""TEMPO[TEMPO]"",""IMPROVE"",""RANK"",""DEVICE"",""CBFREQ[SQ001]"",""UFREQ[SQ001]"",""Alter"",""Gender"",""Gender[other]"",""BB"",""THANKS"""
                //System.out.print(String.format("\"\"\"2019-08-21 18:12:11\"\",\"\"%s\"\",", tic));
                //System.out.println("\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"\"\",\"\"\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A4\"\",\"\"A3\"\",\"\"\"\",\"\"A1\"\",\"\"A1\"\",\"\"A3\"\",\"\"A3\"\",\"\"1900\"\",\"\"\"\",\"\"\"\",\"\"-\"\",\"\"\"\"\"");
            }

        }

    }
}

