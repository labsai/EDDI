package io.sls.core.runtime.internal;

import io.sls.memory.model.Deployment;
import io.sls.core.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.impl.ConversationMemory;
import io.sls.core.runtime.IBot;
import io.sls.core.runtime.IConversation;
import io.sls.core.runtime.IExecutablePackage;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: michael
 * Date: 19.02.2012
 * Time: 18:41:37
 */
public class Bot implements IBot {
    private String id;
    private Integer version;
    private List<IExecutablePackage> executablePackages;

    private Deployment.Status deploymentStatus;

    public Bot(String id, Integer version) {
        this.id = id;
        this.version = version;
        executablePackages = new LinkedList<IExecutablePackage>();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Integer getVersion() {
        return version;
    }

    @Override
    public Deployment.Status getDeploymentStatus() {
        return deploymentStatus;
    }

    void setDeploymentStatus(Deployment.Status deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    @Override
    public void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException {
        executablePackages.add(executablePackage);
    }

    @Override
    public IConversation startConversation(final IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException, LifecycleException {
        Conversation conversation = new Conversation(executablePackages, new ConversationMemory(id, version), outputProvider);
        conversation.init();
        return conversation;
    }

    @Override
    public IConversation continueConversation(final IConversationMemory conversationMemory, final IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException {
        return new Conversation(executablePackages, conversationMemory, outputProvider);
    }
}
