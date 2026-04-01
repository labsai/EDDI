package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * @author ginccc
 */

@ApplicationScoped
public class AgentFactory implements IAgentFactory {
    private final Map<Deployment.Environment, ConcurrentHashMap<AgentId, IAgent>> environments;
    private final List<AgentId> deployedAgents;
    private final IAgentStoreClientLibrary agentStoreClientLibrary;
    private final IDeploymentListener deploymentListener;

    private static final Logger log = Logger.getLogger(AgentFactory.class);

    @Inject
    public AgentFactory(IAgentStoreClientLibrary agentStoreClientLibrary, IDeploymentListener deploymentListener, MeterRegistry meterRegistry) {
        this.agentStoreClientLibrary = agentStoreClientLibrary;
        this.deploymentListener = deploymentListener;
        this.deployedAgents = new LinkedList<>();
        meterRegistry.gaugeCollectionSize("eddi_agents_deployed", Tags.empty(), deployedAgents);
        this.environments = Collections.unmodifiableMap(createEmptyEnvironments());
    }

    private Map<Deployment.Environment, ConcurrentHashMap<AgentId, IAgent>> createEmptyEnvironments() {
        Map<Deployment.Environment, ConcurrentHashMap<AgentId, IAgent>> environments = new HashMap<>(Deployment.Environment.values().length);
        environments.put(Deployment.Environment.production, new ConcurrentHashMap<>());
        environments.put(Deployment.Environment.test, new ConcurrentHashMap<>());
        return environments;
    }

    @Override
    public IAgent getLatestAgent(Deployment.Environment environment, String agentId) {
        return findLatestAgent(environment, agentId, null);
    }

    @Override
    public IAgent getLatestReadyAgent(Deployment.Environment environment, String agentId) {
        return findLatestAgent(environment, agentId, Deployment.Status.READY);
    }

    private IAgent findLatestAgent(Deployment.Environment environment, String agentId, Deployment.Status requiredStatus) {
        Map<AgentId, IAgent> agents = getAgentEnvironment(environment);
        List<AgentId> agentVersions = agents.keySet().stream().filter(id -> id.getId().equals(agentId))
                .sorted(Collections.reverseOrder(Comparator.comparingInt(AgentId::getVersion))).toList();

        for (AgentId agentVersion : agentVersions) {
            IAgent agent = agents.get(agentVersion);
            if (agent != null && (requiredStatus == null || agent.getDeploymentStatus() == requiredStatus)) {
                return agent;
            }
        }

        return null;
    }

    @Override
    public List<IAgent> getAllLatestAgents(Deployment.Environment environment) {
        Map<String, IAgent> ret = new LinkedHashMap<>();

        for (AgentId agentIdObj : getAgentEnvironment(environment).keySet()) {
            IAgent nextAgent = getLatestAgent(environment, agentIdObj.getId());
            if (nextAgent == null) {
                continue;
            }

            String agentId = agentIdObj.getId();
            IAgent currentAgent = ret.get(agentId);
            if (ret.containsKey(agentId)) {
                if (currentAgent.getAgentVersion() < nextAgent.getAgentVersion()) {
                    ret.put(agentId, nextAgent);
                }
            } else {
                ret.put(agentIdObj.getId(), nextAgent);
            }

        }

        return new LinkedList<>(ret.values());
    }

    @Override
    public IAgent getAgent(Deployment.Environment environment, final String agentId, final Integer version) {
        var agents = getAgentEnvironment(environment);
        var agentIdObj = new AgentId(agentId, version);

        // Check if the agent is already in a non-IN_PROGRESS state
        IAgent agent = agents.get(agentIdObj);
        if (agent != null) {
            if (agent.getDeploymentStatus() != Deployment.Status.IN_PROGRESS) {
                return agent;
            } else {
                return waitForDeploymentCompletion(agentIdObj, environment);
            }
        }

        return null;
    }

    private IAgent waitForDeploymentCompletion(AgentId agentIdObj, Deployment.Environment environment) {
        var deploymentFuture = deploymentListener.getRegisteredDeploymentEvent(agentIdObj.getId(), agentIdObj.getVersion());

        try {
            if (deploymentFuture != null) {
                deploymentFuture.orTimeout(60, TimeUnit.SECONDS).join();
            }

            // Re-fetch the agent after deployment is complete
            IAgent agent = getAgentEnvironment(environment).get(agentIdObj);
            if (agent == null || agent.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
                log.error("Agent deployment did not complete successfully for agentId: " + agentIdObj);
                return null;
            }

            return agent;
        } catch (CancellationException e) {
            log.error("Waited too long for agent deployment to complete (timeout reached at 60s).", e);
            return null;
        } catch (Exception e) {
            log.error("Error while waiting for agent deployment: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void deployAgent(Deployment.Environment environment, final String agentId, final Integer version, DeploymentProcess deploymentProcess) {
        var finalDeploymentProcess = defaultIfNull(deploymentProcess);

        AgentId id = new AgentId(agentId, version);
        ConcurrentHashMap<AgentId, IAgent> agentEnvironment = getAgentEnvironment(environment);

        agentEnvironment.compute(id, (key, existingAgent) -> {
            if (existingAgent != null) {
                // If an agent already exists, ensure it is in a valid state
                if (existingAgent.getDeploymentStatus() == Deployment.Status.READY) {
                    log.debug(String.format("Agent is already deployed: %s (environment=%s, version=%d)", agentId, environment, version));
                    finalDeploymentProcess.completed(Deployment.Status.READY);
                    return existingAgent; // No need to redeploy
                }

                if (existingAgent.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
                    log.debug(
                            String.format("Agent deployment is already in progress: %s (environment=%s, version=%d)", agentId, environment, version));
                    return existingAgent; // Keep the IN_PROGRESS state
                }
            }

            // Begin deployment
            logAgentDeployment(environment.toString(), agentId, version, Deployment.Status.IN_PROGRESS);
            var progressDummyAgent = createInProgressDummyAgent(agentId, version);

            try {
                IAgent agent = agentStoreClientLibrary.getAgent(agentId, version);
                ((Agent) agent).setDeploymentStatus(Deployment.Status.READY);

                finalDeploymentProcess.completed(Deployment.Status.READY);
                logAgentDeployment(environment.toString(), agentId, version, Deployment.Status.READY);

                // Add the deployed agent to the deployedAgents list if it's not already there
                synchronized (deployedAgents) {
                    if (!deployedAgents.contains(id)) {
                        deployedAgents.add(id);
                    }
                }

                return agent; // Replace the dummy agent with the actual agent
            } catch (ServiceException e) {
                log.error("Agent deployment failed for " + agentId + " v" + version + ": " + e.getMessage(), e);
                progressDummyAgent.setDeploymentStatus(Deployment.Status.ERROR);
                finalDeploymentProcess.completed(Deployment.Status.ERROR);
                logAgentDeployment(environment.toString(), agentId, version, Deployment.Status.ERROR);
                return progressDummyAgent;
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private DeploymentProcess defaultIfNull(DeploymentProcess deploymentProcess) {
        return deploymentProcess == null ? status -> {
        } : deploymentProcess;
    }

    @Override
    public void undeployAgent(Deployment.Environment environment, String agentId, Integer version) {
        Map<AgentId, IAgent> agentEnvironment = getAgentEnvironment(environment);

        AgentId id = new AgentId(agentId, version);
        agentEnvironment.remove(id);
        deployedAgents.remove(id);
    }

    private ConcurrentHashMap<AgentId, IAgent> getAgentEnvironment(Deployment.Environment environment) {
        return environments.get(environment);
    }

    private Agent createInProgressDummyAgent(String agentId, Integer version) {
        Agent dummyAgent = new Agent(agentId, version) {
            @Override
            public void addWorkflow(IExecutableWorkflow executableWorkflow) throws IllegalAccessException {
                throw createAgentInProgressException();
            }

            @Override
            public IConversation startConversation(String userId, Map<String, Context> context, IPropertiesHandler propertiesHandler,
                                                   IConversationOutputRenderer outputProvider)
                    throws IllegalAccessException {

                throw createAgentInProgressException();
            }

            @Override
            public IConversation continueConversation(IConversationMemory conversationMemory, IPropertiesHandler propertiesHandler,
                                                      IConversationOutputRenderer outputProvider)
                    throws IllegalAccessException {

                throw createAgentInProgressException();
            }
        };

        dummyAgent.setDeploymentStatus(Deployment.Status.IN_PROGRESS);
        return dummyAgent;
    }

    private static IllegalAccessException createAgentInProgressException() {
        return new IllegalAccessException("Agent deployment is still in progress!");
    }

    private void logAgentDeployment(String environment, String agentId, Integer agentVersion, Deployment.Status status) {
        if (status == Deployment.Status.IN_PROGRESS) {
            log.info(String.format("Deploying agent... (environment=%s, agentId=%s, version=%s)", environment, agentId, agentVersion));
        } else {
            log.info(String.format("Agent deployed with status: %s (environment=%s, agentId=%s, version=%s)", status, environment, agentId,
                    agentVersion));
        }
    }

    private static class AgentId {
        private final String id;
        private final Integer version;

        @Override
        public String toString() {
            return id + ":" + version;
        }

        public AgentId(String id, Integer version) {
            this.id = id;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public Integer getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            AgentId that = (AgentId) o;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(version, that.version);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, version);
        }
    }
}
