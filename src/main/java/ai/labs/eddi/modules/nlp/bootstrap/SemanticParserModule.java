package ai.labs.eddi.modules.nlp.bootstrap;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.nlp.InputParserTask;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.*;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.providers.*;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.*;
import jakarta.annotation.PostConstruct;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class SemanticParserModule {
    @PostConstruct
    @Inject
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put(InputParserTask.ID, () -> instance.select(InputParserTask.class).get());
    }

    @ParserNormalizerExtensions
    @ApplicationScoped
    Map<String, Provider<INormalizerProvider>> produceNormalizerProvider(Instance<INormalizerProvider> instance) {
        Map<String, Provider<INormalizerProvider>> map = new LinkedHashMap<>();

        map.put(PunctuationNormalizerProvider.ID, () ->
                instance.select(PunctuationNormalizerProvider.class).get());
        map.put(ConvertSpecialCharacterNormalizerProvider.ID, () ->
                instance.select(ConvertSpecialCharacterNormalizerProvider.class).get());
        map.put(ContractedWordNormalizerProvider.ID, () ->
                instance.select(ContractedWordNormalizerProvider.class).get());
        map.put(RemoveUndefinedCharacterNormalizerProvider.ID, () ->
                instance.select(RemoveUndefinedCharacterNormalizerProvider.class).get());

        return map;
    }

    @ParserDictionaryExtensions
    @ApplicationScoped
    Map<String, Provider<IDictionaryProvider>> produceDictionaryProvider(Instance<IDictionaryProvider> instance) {
        Map<String, Provider<IDictionaryProvider>> map = new LinkedHashMap<>();

        map.put(IntegerDictionaryProvider.ID, () ->
                instance.select(IntegerDictionaryProvider.class).get());
        map.put(DecimalDictionaryProvider.ID, () ->
                instance.select(DecimalDictionaryProvider.class).get());
        map.put(OrdinalNumbersDictionaryProvider.ID, () ->
                instance.select(OrdinalNumbersDictionaryProvider.class).get());
        map.put(PunctuationDictionaryProvider.ID, () ->
                instance.select(PunctuationDictionaryProvider.class).get());
        map.put(TimeExpressionDictionaryProvider.ID, () ->
                instance.select(TimeExpressionDictionaryProvider.class).get());
        map.put(EmailDictionaryProvider.ID, () ->
                instance.select(EmailDictionaryProvider.class).get());
        map.put(RegularDictionaryProvider.ID, () ->
                instance.select(RegularDictionaryProvider.class).get());

        return map;
    }

    @ParserCorrectionExtensions
    @ApplicationScoped
    Map<String, Provider<ICorrectionProvider>> produceCorrectionsProvider(Instance<ICorrectionProvider> instance) {
        Map<String, Provider<ICorrectionProvider>> map = new LinkedHashMap<>();

        map.put(DamerauLevenshteinCorrectionProvider.ID, () ->
                instance.select(DamerauLevenshteinCorrectionProvider.class).get());
        map.put(StemmingCorrectionProvider.ID, () ->
                instance.select(StemmingCorrectionProvider.class).get());
        map.put(PhoneticCorrectionProvider.ID, () ->
                instance.select(PhoneticCorrectionProvider.class).get());
        map.put(MergedTermsCorrectionProvider.ID, () ->
                instance.select(MergedTermsCorrectionProvider.class).get());

        return map;
    }
}