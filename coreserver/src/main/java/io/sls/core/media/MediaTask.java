package io.sls.core.media;

import io.sls.core.lifecycle.AbstractLifecycleTask;
import io.sls.core.lifecycle.ILifecycleTask;
import io.sls.core.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.memory.impl.Data;

import javax.inject.Singleton;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Singleton
public class MediaTask extends AbstractLifecycleTask implements ILifecycleTask {
    private static final String ACTION_KEY = "action";
    private Map<String, URI> mediaResourceURIs = new HashMap<String, URI>();

    @Override
    public String getId() {
        return "io.sls.media";
    }

    @Override
    public Object getComponent() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public List<String> getComponentDependencies() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public List<String> getOutputDependencies() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void init() {
        String prefix = "3_R_Activity_slide_";
        mediaResourceURIs.put(prefix + "1", URI.create("image://io.sls.images/b3092f73fd719f0c932c2abbd651f81696ea279e.png"));
        mediaResourceURIs.put(prefix + "2", URI.create("image://io.sls.images/90a59f0e5d23f246c12914c79a66b9b1e665d5f9.png"));
        mediaResourceURIs.put(prefix + "3", URI.create("image://io.sls.images/17820a6b4eb141136e42ecb5472c66bc6814aaae.png"));
        mediaResourceURIs.put(prefix + "4", URI.create("image://io.sls.images/d8681f1d4329f22b65dabf36bea81347a3857030.png"));
        mediaResourceURIs.put(prefix + "5", URI.create("image://io.sls.images/598127839cd5001c8f006606f626c881d5c7b830.png"));
        mediaResourceURIs.put(prefix + "6", URI.create("image://io.sls.images/23c1817791d5e121323e033b1971672b7cd2ce65.png"));
        mediaResourceURIs.put(prefix + "7", URI.create("image://io.sls.images/16d69e9d4a904b57d592728b8b8679edd85d4b41.png"));
        mediaResourceURIs.put(prefix + "8", URI.create("image://io.sls.images/c6fb9f0eb61a51eac6240fc9770a2e5187b41d7a.png"));
        mediaResourceURIs.put("writ_1010_2013.pdf", URI.create("pdf://io.sls.pdf/writ_1010_2013.pdf"));
        mediaResourceURIs.put("writ_2000_2013.pdf", URI.create("pdf://io.sls.pdf/writ_2000_2013.pdf"));
        mediaResourceURIs.put("basic_essay_model.pdf", URI.create("pdf://io.sls.pdf/basic_essay_model.pdf"));
        mediaResourceURIs.put("articles.pdf", URI.create("pdf://io.sls.pdf/articles.pdf"));
        mediaResourceURIs.put("brainstorming.pdf", URI.create("pdf://io.sls.pdf/brainstorming.pdf"));
        mediaResourceURIs.put("my_favorite_teacher.pdf", URI.create("pdf://io.sls.pdf/my_favorite_teacher.pdf"));
        mediaResourceURIs.put("narrative_essay.pdf", URI.create("pdf://io.sls.pdf/narrative_essay.pdf"));
        mediaResourceURIs.put("the_curse_of_the_dump_descriptive_essay.pdf", URI.create("pdf://io.sls.pdf/the_curse_of_the_dump_descriptive_essay.pdf"));
        mediaResourceURIs.put("sample_basic_essay_model.pdf", URI.create("pdf://io.sls.pdf/sample_basic_essay_model.pdf"));
        mediaResourceURIs.put("basic_punctuation_rules.pdf", URI.create("pdf://io.sls.pdf/basic_punctuation_rules.pdf"));
        mediaResourceURIs.put("basic_paragraph_format.pdf", URI.create("pdf://io.sls.pdf/basic_paragraph_format.pdf"));
        mediaResourceURIs.put("basic_essay.pdf", URI.create("pdf://io.sls.pdf/basic_essay.pdf"));
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        List<String> actions = (List<String>) latestData.getResult();

        List<URI> mediaURIs = new LinkedList<URI>();
        for (String action : actions) {
            if (mediaResourceURIs.containsKey(action)) {
                mediaURIs.add(mediaResourceURIs.get(action));
            }
        }
        if(!mediaURIs.isEmpty()) {
            Data mediaData = new Data("media", mediaURIs);
            mediaData.setPublic(true);
            memory.getCurrentStep().storeData(mediaData);
        }
    }
}
