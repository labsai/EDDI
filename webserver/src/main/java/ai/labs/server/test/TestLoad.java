package ai.labs.server.test;

import ai.labs.models.Deployment;
import ai.labs.persistence.model.ResourceId;
import ai.labs.rest.MockAsyncResponse;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.utilities.URIUtilities;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class TestLoad implements ITestLoad {
    private final IRestBotEngine restBotEngine;

    @Inject
    public TestLoad(IRestBotEngine restBotEngine) {
        this.restBotEngine = restBotEngine;
    }

    @Override
    public void runLoadTest() throws InterruptedException {
        String botId = "5cfa31d130a94c6b86f1ab4b";
        for (int i = 0; i < 10000; i++) {
            Response response = restBotEngine.startConversation(Deployment.Environment.unrestricted,
                    botId, "testuser" +
                            RandomStringUtils.randomAlphanumeric(10));

            String location = response.getHeaderString("location");
            ResourceId resourceId = URIUtilities.extractResourceId(URI.create(location));
            String conversationId = resourceId.getId();

            List<String> messages = Arrays.asList("Show me your skillz", "Sure, buddy", "Yeah, I got it");
            for (var message : messages) {
                restBotEngine.say(Deployment.Environment.unrestricted, botId, conversationId,
                        false, true, null, message, new MockAsyncResponse());
                Thread.sleep(50);
            }

            log.info("load test nr {}.", i);
        }

        log.info("load test finished.");
    }
}
