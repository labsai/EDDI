package io.sls.core.externalSearch;

import io.sls.core.lifecycle.AbstractLifecycleTask;
import io.sls.core.lifecycle.ILifecycleTask;
import io.sls.core.lifecycle.LifecycleException;
import io.sls.expressions.Expression;
import io.sls.memory.IConversationMemory;
import io.sls.memory.impl.Data;

import java.util.LinkedList;
import java.util.List;

/**
 * User: Alex
 * Date: 15.06.2011
 * Time: 11:01:38
 */
public class SearchArticles extends AbstractLifecycleTask implements ILifecycleTask {
    private List<Long> result;
    private List<Expression> secondResult;

    /**
     * @return A list of Longs that represent the ArticleIDs found.
     */
    public List<Long> getResult() {
        return result;
    }

    /**
     * @return A list of Expressions that will be used for the DB-search.
     */
    public List<Expression> getSecondResult() {
        return secondResult;
    }

    @Override
    public String getId() {
        return null;
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public List<String> getComponentDependencies() {
        return null;
    }

    @Override
    public List<String> getOutputDependencies() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        List<Expression> linkedProperties = (List<Expression>) memory.getCurrentStep().getLatestData("expressions:linked");
        List resultArticles = new LinkedList();/*ProductUtilities.getArticlesByProperties(linkedProperties);*/
        memory.getCurrentStep().storeData(new Data("search_result:haym_info:handy", resultArticles));
    }
}
