package ai.labs.eddi.configs.dictionary.mongo;

import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.mongo.ResultManipulator;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class DictionaryStore extends AbstractResourceStore<DictionaryConfiguration>
        implements IDictionaryStore {

    private static final WordComparator WORD_COMPARATOR = new WordComparator();
    private static final PhraseComparator PHRASE_COMPARATOR = new PhraseComparator();

    @Inject
    public DictionaryStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "dictionaries", documentBuilder, DictionaryConfiguration.class);
    }

    @Override
    public IResourceStore.IResourceId create(DictionaryConfiguration regularDictionaryConfiguration)
            throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getWords(), "words");
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getPhrases(), "phrases");
        return super.create(regularDictionaryConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, DictionaryConfiguration regularDictionaryConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getWords(), "words");
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getPhrases(), "phrases");

        return super.update(id, version, regularDictionaryConfiguration);
    }

    @Override
    public DictionaryConfiguration read(String id, Integer version, String filter, String order, Integer index,
            Integer limit) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(filter, "filter");
        RuntimeUtilities.checkNotNull(order, "order");
        RuntimeUtilities.checkNotNull(index, "index");
        RuntimeUtilities.checkNotNull(limit, "limit");

        ResultManipulator<DictionaryConfiguration.WordConfiguration> wordManipulator;
        ResultManipulator<DictionaryConfiguration.PhraseConfiguration> phraseManipulator;

        DictionaryConfiguration regularDictionary = read(id, version);

        wordManipulator = new ResultManipulator<>(regularDictionary.getWords(),
                DictionaryConfiguration.WordConfiguration.class);
        phraseManipulator = new ResultManipulator<>(regularDictionary.getPhrases(),
                DictionaryConfiguration.PhraseConfiguration.class);

        try {
            wordManipulator.filterEntities(filter);
            phraseManipulator.filterEntities(filter);
        } catch (ResultManipulator.FilterEntriesException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }

        wordManipulator.sortEntities(WORD_COMPARATOR, order);
        phraseManipulator.sortEntities(PHRASE_COMPARATOR, order);

        wordManipulator.limitEntities(index, limit);
        phraseManipulator.limitEntities(index, limit);

        return regularDictionary;
    }

    @Override
    public List<String> readExpressions(String id, Integer version, String filter, String order, Integer index,
            Integer limit) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<String> retExpressions = new LinkedList<>();
        for (int i = index;; i++) {
            DictionaryConfiguration regularDictionary = read(id, version, filter, order, i, limit);
            List<DictionaryConfiguration.WordConfiguration> words = regularDictionary.getWords();
            if (words.size() == 0 || retExpressions.size() >= 20) {
                break;
            }
            for (DictionaryConfiguration.WordConfiguration word : words) {
                String exp = word.getExpressions();
                if (!RuntimeUtilities.isNullOrEmpty(exp) && exp.contains(filter) && !retExpressions.contains(exp)) {
                    retExpressions.add(exp);
                    if (retExpressions.size() >= 20) {
                        break;
                    }
                }
            }

            List<DictionaryConfiguration.PhraseConfiguration> phrases = regularDictionary.getPhrases();
            for (DictionaryConfiguration.PhraseConfiguration phrase : phrases) {
                String exp = phrase.getExpressions();
                if (!RuntimeUtilities.isNullOrEmpty(exp) && exp.contains(filter) && !retExpressions.contains(exp)) {
                    retExpressions.add(exp);
                    if (retExpressions.size() >= 20) {
                        break;
                    }
                }
            }
        }

        return retExpressions;
    }

    private static class WordComparator implements Comparator<DictionaryConfiguration.WordConfiguration> {
        @Override
        public int compare(DictionaryConfiguration.WordConfiguration word1,
                DictionaryConfiguration.WordConfiguration word2) {
            return word1.getWord().compareTo(word2.getWord());
        }
    }

    private static class PhraseComparator implements Comparator<DictionaryConfiguration.PhraseConfiguration> {
        @Override
        public int compare(DictionaryConfiguration.PhraseConfiguration phrase1,
                DictionaryConfiguration.PhraseConfiguration phrase2) {
            return phrase1.getPhrase().compareTo(phrase2.getPhrase());
        }
    }
}
