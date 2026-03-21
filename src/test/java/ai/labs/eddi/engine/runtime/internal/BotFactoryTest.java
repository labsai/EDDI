package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.model.Deployment;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory.DeploymentProcess;
import ai.labs.eddi.engine.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static ai.labs.eddi.model.Deployment.Environment.unrestricted;
import static ai.labs.eddi.model.Deployment.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class BotFactoryTest {

    @Mock
    private IBotStoreClientLibrary botStoreClientLibrary;
    @Mock
    private IDeploymentListener deploymentListener;

    private BotFactory botFactory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        botFactory = new BotFactory(botStoreClientLibrary, deploymentListener, meterRegistry);
    }

    // ==================== getBot Tests ====================

    @Nested
    @DisplayName("getBot")
    class GetBotTests {

        @Test
        @DisplayName("should return null when bot is not deployed")
        void getBot_notDeployed_returnsNull() {
            IBot result = botFactory.getBot(unrestricted, "bot1", 1);
            assertNull(result);
        }

        @Test
        @DisplayName("should return bot when deployed and READY")
        void getBot_deployed_returnsBot() throws Exception {
            // Deploy a bot
            Bot mockBot = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(mockBot);

            botFactory.deployBot(unrestricted, "bot1", 1, null);

            IBot result = botFactory.getBot(unrestricted, "bot1", 1);
            assertNotNull(result);
            assertEquals(READY, result.getDeploymentStatus());
        }
    }

    // ==================== deployBot Tests ====================

    @Nested
    @DisplayName("deployBot")
    class DeployBotTests {

        @Test
        @DisplayName("should deploy bot and set status to READY")
        void deployBot_success() throws Exception {
            Bot mockBot = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(mockBot);

            DeploymentProcess process = mock(DeploymentProcess.class);
            botFactory.deployBot(unrestricted, "bot1", 1, process);

            verify(process).completed(READY);

            IBot result = botFactory.getBot(unrestricted, "bot1", 1);
            assertNotNull(result);
            assertEquals(READY, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("should set ERROR status when botStoreClientLibrary throws")
        void deployBot_storeError_setsErrorStatus() throws Exception {
            when(botStoreClientLibrary.getBot("bot1", 1))
                    .thenThrow(new ServiceException("DB connection failed"));

            DeploymentProcess process = mock(DeploymentProcess.class);
            // BotFactory.deployBot() catches ServiceException inside compute() lambda
            // and sets ERROR status — it does NOT propagate the exception
            assertDoesNotThrow(
                    () -> botFactory.deployBot(unrestricted, "bot1", 1, process));

            verify(process).completed(ERROR);

            // The bot should be stored with ERROR status (not removed)
            IBot errorBot = botFactory.getBot(unrestricted, "bot1", 1);
            assertNotNull(errorBot, "Bot with ERROR status should still be in the environment");
            assertEquals(ERROR, errorBot.getDeploymentStatus());
        }

        @Test
        @DisplayName("should not redeploy an already READY bot")
        void deployBot_alreadyReady_skips() throws Exception {
            Bot mockBot = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(mockBot);

            DeploymentProcess process1 = mock(DeploymentProcess.class);
            DeploymentProcess process2 = mock(DeploymentProcess.class);

            botFactory.deployBot(unrestricted, "bot1", 1, process1);
            botFactory.deployBot(unrestricted, "bot1", 1, process2);

            // First deploy creates bot, second is a no-op
            verify(process1).completed(READY);
            verify(process2).completed(READY);
            verify(botStoreClientLibrary, times(1)).getBot("bot1", 1);
        }

        @Test
        @DisplayName("should handle null deploymentProcess gracefully")
        void deployBot_nullProcess_handledGracefully() throws Exception {
            Bot mockBot = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(mockBot);

            assertDoesNotThrow(() -> botFactory.deployBot(unrestricted, "bot1", 1, null));

            IBot result = botFactory.getBot(unrestricted, "bot1", 1);
            assertNotNull(result);
        }
    }

    // ==================== undeployBot Tests ====================

    @Nested
    @DisplayName("undeployBot")
    class UndeployBotTests {

        @Test
        @DisplayName("should remove deployed bot")
        void undeployBot_removes() throws Exception {
            Bot mockBot = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(mockBot);

            botFactory.deployBot(unrestricted, "bot1", 1, null);
            assertNotNull(botFactory.getBot(unrestricted, "bot1", 1));

            botFactory.undeployBot(unrestricted, "bot1", 1);
            assertNull(botFactory.getBot(unrestricted, "bot1", 1));
        }

        @Test
        @DisplayName("should handle undeploy of non-existent bot gracefully")
        void undeployBot_nonExistent_noError() {
            assertDoesNotThrow(() -> botFactory.undeployBot(unrestricted, "nobot", 1));
        }
    }

    // ==================== getLatestBot / getLatestReadyBot Tests
    // ====================

    @Nested
    @DisplayName("getLatestBot/getLatestReadyBot")
    class LatestBotTests {

        @Test
        @DisplayName("should return null when no bots deployed")
        void getLatestBot_noBots_returnsNull() {
            assertNull(botFactory.getLatestBot(unrestricted, "bot1"));
        }

        @Test
        @DisplayName("should return latest version bot")
        void getLatestBot_multipleVersions_returnsLatest() throws Exception {
            Bot bot1v1 = createReadyBot("bot1", 1);
            Bot bot1v2 = createReadyBot("bot1", 2);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(bot1v1);
            when(botStoreClientLibrary.getBot("bot1", 2)).thenReturn(bot1v2);

            botFactory.deployBot(unrestricted, "bot1", 1, null);
            botFactory.deployBot(unrestricted, "bot1", 2, null);

            IBot latest = botFactory.getLatestBot(unrestricted, "bot1");
            assertNotNull(latest);
            assertEquals(2, latest.getBotVersion());
        }

        @Test
        @DisplayName("getLatestReadyBot should skip non-READY bots")
        void getLatestReadyBot_skipsNonReady() throws Exception {
            Bot bot1v1 = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1)).thenReturn(bot1v1);
            botFactory.deployBot(unrestricted, "bot1", 1, null);

            IBot readyBot = botFactory.getLatestReadyBot(unrestricted, "bot1");
            assertNotNull(readyBot);
            assertEquals(READY, readyBot.getDeploymentStatus());
        }
    }

    // ==================== getAllLatestBots Tests ====================

    @Nested
    @DisplayName("getAllLatestBots")
    class GetAllBotsTests {

        @Test
        @DisplayName("should return empty list when no bots deployed")
        void getAllLatestBots_empty() {
            var bots = botFactory.getAllLatestBots(unrestricted);
            assertTrue(bots.isEmpty());
        }

        @Test
        @DisplayName("should return one bot per unique botId")
        void getAllLatestBots_uniquePerBotId() throws Exception {
            Bot botA = createReadyBot("botA", 1);
            Bot botB = createReadyBot("botB", 1);
            when(botStoreClientLibrary.getBot("botA", 1)).thenReturn(botA);
            when(botStoreClientLibrary.getBot("botB", 1)).thenReturn(botB);

            botFactory.deployBot(unrestricted, "botA", 1, null);
            botFactory.deployBot(unrestricted, "botB", 1, null);

            var all = botFactory.getAllLatestBots(unrestricted);
            assertEquals(2, all.size());
        }
    }

    // ==================== Environment Isolation Tests ====================

    @Nested
    @DisplayName("Environment isolation")
    class EnvironmentTests {

        @Test
        @DisplayName("bots deployed in different environments should not interfere")
        void environments_isolated() throws Exception {
            Bot unrestrictedBot = createReadyBot("bot1", 1);
            Bot restrictedBot = createReadyBot("bot1", 1);
            when(botStoreClientLibrary.getBot("bot1", 1))
                    .thenReturn(unrestrictedBot)
                    .thenReturn(restrictedBot);

            botFactory.deployBot(unrestricted, "bot1", 1, null);
            botFactory.deployBot(Deployment.Environment.restricted, "bot1", 1, null);

            assertNotNull(botFactory.getBot(unrestricted, "bot1", 1));
            assertNotNull(botFactory.getBot(Deployment.Environment.restricted, "bot1", 1));

            botFactory.undeployBot(unrestricted, "bot1", 1);
            assertNull(botFactory.getBot(unrestricted, "bot1", 1));
            assertNotNull(botFactory.getBot(Deployment.Environment.restricted, "bot1", 1));
        }
    }

    // ==================== Helper ====================

    private Bot createReadyBot(String botId, int version) {
        return new Bot(botId, version);
    }
}
