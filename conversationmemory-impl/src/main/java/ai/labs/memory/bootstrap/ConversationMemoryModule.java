package ai.labs.memory.bootstrap;

import ai.labs.memory.ConversationDescriptorStore;
import ai.labs.memory.ConversationMemoryStore;
import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class ConversationMemoryModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IConversationDescriptorStore.class).to(ConversationDescriptorStore.class).in(Scopes.SINGLETON);
        bind(IConversationMemoryStore.class).to(ConversationMemoryStore.class).in(Scopes.SINGLETON);
    }
}
