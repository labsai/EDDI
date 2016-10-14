package io.sls.memory.bootstrap;

import com.google.inject.Scopes;
import io.sls.memory.IConversationMemoryStore;
import io.sls.memory.descriptor.IConversationDescriptorStore;
import io.sls.memory.descriptor.impl.ConversationDescriptorStore;
import io.sls.memory.impl.ConversationMemoryStore;
import io.sls.runtime.bootstrap.AbstractBaseModule;

/**
 * Created by jariscgr on 09.08.2016.
 */
public class ConversationMemoryModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IConversationDescriptorStore.class).to(ConversationDescriptorStore.class).in(Scopes.SINGLETON);
        bind(IConversationMemoryStore.class).to(ConversationMemoryStore.class).in(Scopes.SINGLETON);
    }
}
