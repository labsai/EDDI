/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
    // Declared as the concrete type, not List: addIfAbsent() is the atomic
    // "track this once" primitive this class needs and it exists only on
    // CopyOnWriteArrayList.
    private final CopyOnWriteArrayList<AgentId> deployedAgents;
    private final IAgentStoreClientLibrary agentStoreClientLibrary;
    private final IDeploymentListener deploymentListener;

    private static final Logger log = Logger.getLogger(AgentFactory.class);

    @Inject
    public AgentFactory(IAgentStoreClientLibrary agentStoreClientLibrary, IDeploymentListener deploymentListener, MeterRegistry meterRegistry) {
        this.agentStoreClientLibrary = agentStoreClientLibrary;
        this.deploymentListener = deploymentListener;
        // CopyOnWriteArrayList, not LinkedList: deployAgent appended under a
        // synchronized block while undeployAgent removed with no lock at all, and the
        // Micrometer gauge below reads size() from the metrics thread — three
        // unordered accesses to a structure that tolerates none. addIfAbsent also
        // makes the "already tracked?" check atomic, which the old
        // contains()-then-add() was not.
        this.deployedAgents = new CopyOnWriteArrayList<>();
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
            String agentId = agentIdObj.getId();
            // One resolve per distinct agent id. getLatestAgent already returns the
            // highest version, so the version comparison this loop used to do after
            // calling it could never be true — both calls for one id returned the
            // same instance.
            if (ret.containsKey(agentId)) {
                continue;
            }
            IAgent latest = getLatestAgent(environment, agentId);
            if (latest != null) {
                ret.put(agentId, latest);
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
            if (agent == null) {
                log.error("Agent deployment did not complete successfully for agentId: " + agentIdObj);
                return null;
            }
            if (agent.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
                // Still in flight. Only a caller that registered a deployment future
                // can actually be waited for; without one there was nothing to await
                // and "not ready yet" is an ordinary answer, not a failure worth an
                // ERROR on every poll.
                if (deploymentFuture == null) {
                    log.debugf("Agent %s is still deploying and no deployment future was registered — reporting not ready", agentIdObj);
                } else {
                    log.error("Agent deployment did not complete successfully for agentId: " + agentIdObj);
                }
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

        // Claim the slot with an IN_PROGRESS placeholder, then load OUTSIDE the map.
        //
        // This whole body used to run inside agentEnvironment.compute(...), which
        // holds the bin lock for the key while the mapping function runs — and the
        // function's real work is agentStoreClientLibrary.getAgent(), a store read
        // plus full workflow construction. ConcurrentHashMap documents that a
        // mapping function must be short and must not touch other mappings of the
        // same map; holding a bin lock across multi-second I/O blocks every other
        // key that hashes to that bin, and any re-entrant agent resolution during
        // construction would deadlock outright.
        //
        // putIfAbsent is the same atomic claim without the long hold. It also
        // PUBLISHES the IN_PROGRESS placeholder, which compute never did (the dummy
        // was only ever returned on the failure path) — so a concurrent getAgent()
        // can now actually observe "deployment in progress" instead of a bare null.
        var placeholder = createInProgressDummyAgent(agentId, version);
        IAgent existingAgent = agentEnvironment.putIfAbsent(id, placeholder);
        if (existingAgent != null) {
            if (existingAgent.getDeploymentStatus() == Deployment.Status.READY) {
                log.debug(String.format("Agent is already deployed: %s (environment=%s, version=%d)", agentId, environment, version));
                finalDeploymentProcess.completed(Deployment.Status.READY);
                return;
            }
            if (existingAgent.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
                log.debug(String.format("Agent deployment is already in progress: %s (environment=%s, version=%d)", agentId, environment, version));
                return;
            }
            // ERROR — retry, but only if nobody else claimed the retry first.
            if (!agentEnvironment.replace(id, existingAgent, placeholder)) {
                log.debug(String.format("Agent redeploy already claimed by another caller: %s (environment=%s, version=%d)", agentId, environment,
                        version));
                return;
            }
        }

        logAgentDeployment(environment.toString(), agentId, version, Deployment.Status.IN_PROGRESS);
        try {
            IAgent agent = agentStoreClientLibrary.getAgent(agentId, version);
            ((Agent) agent).setDeploymentStatus(Deployment.Status.READY);
            agentEnvironment.put(id, agent); // replace the placeholder with the real agent

            finalDeploymentProcess.completed(Deployment.Status.READY);
            logAgentDeployment(environment.toString(), agentId, version, Deployment.Status.READY);

            deployedAgents.addIfAbsent(id);
        } catch (ServiceException e) {
            log.error("Agent deployment failed for " + agentId + " v" + version + ": " + e.getMessage(), e);
            placeholder.setDeploymentStatus(Deployment.Status.ERROR);
            finalDeploymentProcess.completed(Deployment.Status.ERROR);
            logAgentDeployment(environment.toString(), agentId, version, Deployment.Status.ERROR);
        } catch (IllegalAccessException e) {
            // The placeholder is still published; mark it ERROR so a retry is not
            // mistaken for a deployment still in flight.
            placeholder.setDeploymentStatus(Deployment.Status.ERROR);
            finalDeploymentProcess.completed(Deployment.Status.ERROR);
            throw new RuntimeException(e);
        }
    }

    private DeploymentProcess defaultIfNull(DeploymentProcess deploymentProcess) {
        return deploymentProcess == null ? status -> {
        } : deploymentProcess;
    }

    /**
     * {@inheritDoc}
     * <p>
     * A {@code null} version means every deployed version of {@code agentId}.
     * {@link AgentId} keys on (id, version), so {@code new AgentId(id, null)}
     * equals no key this map ever holds — the removal used to be a silent no-op on
     * exactly the two call sites that pass null ({@code GroupLifecycleOps}'
     * ephemeral cleanup and {@code TeardownAgentTool}), leaving the constructed
     * agent resolvable in memory after its configuration had been deleted from the
     * store, and leaving the {@code eddi_agents_deployed} gauge to grow for the
     * lifetime of the process.
     */
    @Override
    public void undeployAgent(Deployment.Environment environment, String agentId, Integer version) {
        Map<AgentId, IAgent> agentEnvironment = getAgentEnvironment(environment);

        if (version == null) {
            List<AgentId> allVersions = agentEnvironment.keySet().stream()
                    .filter(key -> Objects.equals(key.getId(), agentId))
                    .toList();
            for (AgentId key : allVersions) {
                agentEnvironment.remove(key);
                deployedAgents.remove(key);
            }
            return;
        }

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
