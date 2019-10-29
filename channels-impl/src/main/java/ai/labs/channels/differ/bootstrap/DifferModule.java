package ai.labs.channels.differ.bootstrap;

import ai.labs.channels.differ.*;
import ai.labs.channels.differ.storage.IDifferBotMappingStore;
import ai.labs.channels.differ.storage.IDifferConversationStore;
import ai.labs.channels.differ.storage.IRestDifferBotMappingStore;
import ai.labs.channels.differ.storage.botidentitities.DifferBotMappingStore;
import ai.labs.channels.differ.storage.botidentitities.RestDifferBotMappingStore;
import ai.labs.channels.differ.storage.conversations.DifferConversationStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

public class DifferModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IRestDifferEndpoint.class).to(RestDifferEndpoint.class).in(Scopes.SINGLETON);
        bind(IDifferOutputTransformer.class).to(DifferOutputTransformer.class).in(Scopes.SINGLETON);
        bind(IDifferPublisher.class).to(DifferPublisher.class).in(Scopes.SINGLETON);

        bind(IDifferConversationStore.class).to(DifferConversationStore.class).in(Scopes.SINGLETON);
        bind(IDifferBotMappingStore.class).to(DifferBotMappingStore.class).in(Scopes.SINGLETON);

        bind(IRestDifferBotMappingStore.class).to(RestDifferBotMappingStore.class).in(Scopes.SINGLETON);
    }
}
