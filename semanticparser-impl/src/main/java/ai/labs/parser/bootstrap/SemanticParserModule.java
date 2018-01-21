package ai.labs.parser.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.InputParserTask;
import ai.labs.parser.extensions.corrections.providers.*;
import ai.labs.parser.extensions.dictionaries.providers.*;
import ai.labs.parser.extensions.normalizers.providers.*;
import ai.labs.parser.rest.IRestSemanticParser;
import ai.labs.parser.rest.impl.RestSemanticParser;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

/**
 * @author ginccc
 */
public class SemanticParserModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IRestSemanticParser.class).to(RestSemanticParser.class);

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding(InputParserTask.ID).to(InputParserTask.class);

        initNormalizerPlugins();
        initDictionaryPlugins();
        initCorrectionPlugins();
    }

    private void initNormalizerPlugins() {
        MapBinder<String, INormalizerProvider> parserNormalizerPlugins
                = MapBinder.newMapBinder(binder(), String.class, INormalizerProvider.class);
        parserNormalizerPlugins
                .addBinding(PunctuationNormalizerProvider.ID)
                .to(PunctuationNormalizerProvider.class);
        parserNormalizerPlugins
                .addBinding(ConvertSpecialCharacterNormalizerProvider.ID)
                .to(ConvertSpecialCharacterNormalizerProvider.class);
        parserNormalizerPlugins
                .addBinding(ContractedWordNormalizerProvider.ID)
                .to(ContractedWordNormalizerProvider.class);
        parserNormalizerPlugins
                .addBinding(RemoveUndefinedCharacterNormalizerProvider.ID)
                .to(RemoveUndefinedCharacterNormalizerProvider.class);
    }

    private void initDictionaryPlugins() {
        MapBinder<String, IDictionaryProvider> parserDictionaryPlugins
                = MapBinder.newMapBinder(binder(), String.class, IDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(IntegerDictionaryProvider.ID)
                .to(IntegerDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(DecimalDictionaryProvider.ID)
                .to(DecimalDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(OrdinalNumbersDictionaryProvider.ID)
                .to(OrdinalNumbersDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(PunctuationDictionaryProvider.ID)
                .to(PunctuationDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(TimeExpressionDictionaryProvider.ID)
                .to(TimeExpressionDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(EmailDictionaryProvider.ID)
                .to(EmailDictionaryProvider.class);
        parserDictionaryPlugins
                .addBinding(RegularDictionaryProvider.ID)
                .to(RegularDictionaryProvider.class);
    }

    private void initCorrectionPlugins() {
        MapBinder<String, ICorrectionProvider> parserCorrectionPlugins
                = MapBinder.newMapBinder(binder(), String.class, ICorrectionProvider.class);
        parserCorrectionPlugins
                .addBinding(DamerauLevenshteinCorrectionProvider.ID)
                .to(DamerauLevenshteinCorrectionProvider.class);
        parserCorrectionPlugins
                .addBinding(StemmingCorrectionProvider.ID)
                .to(StemmingCorrectionProvider.class);
        parserCorrectionPlugins
                .addBinding(PhoneticCorrectionProvider.ID)
                .to(PhoneticCorrectionProvider.class);
        parserCorrectionPlugins
                .addBinding(MergedTermsCorrectionProvider.ID)
                .to(MergedTermsCorrectionProvider.class);
    }
}
