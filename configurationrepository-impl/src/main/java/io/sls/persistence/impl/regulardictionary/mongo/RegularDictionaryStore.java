package io.sls.persistence.impl.regulardictionary.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.ResultManipulator;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.regulardictionary.IRegularDictionaryStore;
import io.sls.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.utilities.RuntimeUtilities;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class RegularDictionaryStore implements IRegularDictionaryStore {
    private final String collectionName = "regulardictionaries";
    private HistorizedResourceStore<RegularDictionaryConfiguration> regularDictionaryResourceStore;

    private static final WordComparator WORD_COMPARATOR = new WordComparator();
    private static final PhraseComparator PHRASE_COMPARATOR = new PhraseComparator();

    @Inject
    public RegularDictionaryStore(DB database) {
        RuntimeUtilities.checkNotNull(database, "database");
        MongoResourceStorage<RegularDictionaryConfiguration> resourceStorage = new MongoResourceStorage<RegularDictionaryConfiguration>(database, collectionName, new IDocumentBuilder<RegularDictionaryConfiguration>() {
            @Override
            public RegularDictionaryConfiguration build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<RegularDictionaryConfiguration>() {});
            }
        });
        this.regularDictionaryResourceStore = new HistorizedResourceStore<RegularDictionaryConfiguration>(resourceStorage);
    }

    @Override
    public IResourceId create(RegularDictionaryConfiguration regularDictionaryConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getWords(), "words");
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getPhrases(), "phrases");
        return regularDictionaryResourceStore.create(regularDictionaryConfiguration);
    }

    @Override
    public RegularDictionaryConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return regularDictionaryResourceStore.read(id, version);
    }

    @Override
    public RegularDictionaryConfiguration read(String id, Integer version, String filter, String order, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException {
        RuntimeUtilities.checkNotNull(filter, "filter");
        RuntimeUtilities.checkNotNull(order, "order");
        RuntimeUtilities.checkNotNull(index, "index");
        RuntimeUtilities.checkNotNull(limit, "limit");

        ResultManipulator<RegularDictionaryConfiguration.WordConfiguration> wordManipulator;
        ResultManipulator<RegularDictionaryConfiguration.PhraseConfiguration> phraseManipulator;

        RegularDictionaryConfiguration regularDictionary = regularDictionaryResourceStore.read(id, version);

        wordManipulator = new ResultManipulator<RegularDictionaryConfiguration.WordConfiguration>(regularDictionary.getWords(),
                RegularDictionaryConfiguration.WordConfiguration.class);
        phraseManipulator = new ResultManipulator<RegularDictionaryConfiguration.PhraseConfiguration>(regularDictionary.getPhrases(),
                RegularDictionaryConfiguration.PhraseConfiguration.class);

        try {
            wordManipulator.filterEntities(filter);
            phraseManipulator.filterEntities(filter);
        } catch (ResultManipulator.FilterEntriesException e) {
            throw new ResourceStoreException(e.getLocalizedMessage(), e);
        }

        wordManipulator.sortEntities(WORD_COMPARATOR, order);
        phraseManipulator.sortEntities(PHRASE_COMPARATOR, order);

        wordManipulator.limitEntities(index, limit);
        phraseManipulator.limitEntities(index, limit);

        return regularDictionary;
    }

    @Override
    public List<String> readExpressions(String id, Integer version, String filter, String order, Integer index, Integer limit) throws ResourceStoreException, ResourceNotFoundException {
        List<String> retExpressions = new LinkedList<String>();
        for (int i = index; ; i++) {
            RegularDictionaryConfiguration regularDictionary = read(id, version, filter, order, i, limit);
            List<RegularDictionaryConfiguration.WordConfiguration> words = regularDictionary.getWords();
            if (words.size() == 0 || retExpressions.size() >= 20) {
                break;
            }
            for (RegularDictionaryConfiguration.WordConfiguration word : words) {
                String exp = word.getExp();
                if (!RuntimeUtilities.isNullOrEmpty(exp) && exp.contains(filter) && !retExpressions.contains(exp)) {
                    retExpressions.add(exp);
                    if (retExpressions.size() >= 20) {
                        break;
                    }
                }
            }

            List<RegularDictionaryConfiguration.PhraseConfiguration> phrases = regularDictionary.getPhrases();
            for (RegularDictionaryConfiguration.PhraseConfiguration phrase : phrases) {
                String exp = phrase.getExp();
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

    @Override
    public Integer update(String id, Integer version, RegularDictionaryConfiguration regularDictionaryConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getWords(), "words");
        RuntimeUtilities.checkCollectionNoNullElements(regularDictionaryConfiguration.getPhrases(), "phrases");

        return regularDictionaryResourceStore.update(id, version, regularDictionaryConfiguration);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        regularDictionaryResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        regularDictionaryResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return regularDictionaryResourceStore.getCurrentResourceId(id);
    }

    private static class WordComparator implements Comparator<RegularDictionaryConfiguration.WordConfiguration> {
        @Override
        public int compare(RegularDictionaryConfiguration.WordConfiguration word1, RegularDictionaryConfiguration.WordConfiguration word2) {
            return word1.getWord().compareTo(word2.getWord());
        }
    }

    private static class PhraseComparator implements Comparator<RegularDictionaryConfiguration.PhraseConfiguration> {
        @Override
        public int compare(RegularDictionaryConfiguration.PhraseConfiguration phrase1, RegularDictionaryConfiguration.PhraseConfiguration phrase2) {
            return phrase1.getPhrase().compareTo(phrase2.getPhrase());
        }
    }
}
