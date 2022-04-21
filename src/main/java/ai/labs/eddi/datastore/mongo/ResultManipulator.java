package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.utils.RuntimeUtilities;
import ai.labs.eddi.utils.StringUtilities;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author ginccc
 */
public class ResultManipulator<T> {
    public static final String ASCENDING = "asc";
    public static final String DESCENDING = "desc";

    private List<T> listForManipulation;
    private Class<T> clazz;

    public ResultManipulator(List<T> listForManipulation, Class<T> clazz) {
        RuntimeUtilities.checkNotNull(listForManipulation, "listForManipulation");
        RuntimeUtilities.checkNotNull(clazz, "clazz");

        this.listForManipulation = listForManipulation;
        this.clazz = clazz;
    }

    public void filterEntities(String filter) throws FilterEntriesException {
        RuntimeUtilities.checkNotNull(filter, "filter");

        Method[] methods = clazz.getMethods();

        filter = StringUtilities.convertToSearchString(filter);
        boolean matches;
        for (int i = 0; i < listForManipulation.size(); ) {
            T obj = listForManipulation.get(i);
            if (obj == null) {
                String message = "Error while filtering! Null values are not allowed. Operation was aborted.";
                throw new FilterEntriesException(message);
            }

            matches = false;

            for (Method method : methods) {
                if (method.getName().startsWith("get")) {
                    try {
                        Object returnValue = method.invoke(obj);
                        if (!RuntimeUtilities.isNullOrEmpty(returnValue) && returnValue.toString().matches(filter)) {
                            matches = true;
                            break;
                        }
                    } catch (IllegalAccessException e) {
                        String message = "Error while filtering. Cannot access method: %s";
                        message = String.format(message, method.getName());
                        throw new FilterEntriesException(message, e);
                    } catch (InvocationTargetException e) {
                        String message = "Error while filtering. Cannot invoke target method: %s";
                        message = String.format(message, method.getName());
                        throw new FilterEntriesException(message, e);
                    }
                }
            }

            if (!matches) {
                listForManipulation.remove(i);
            } else {
                i++;
            }
        }
    }

    public void sortEntities(Comparator<T> comparator, String order) {
        RuntimeUtilities.checkNotNull(comparator, "comparator");
        RuntimeUtilities.checkNotNull(order, "order");

        if (!order.isEmpty() && !listForManipulation.isEmpty()) {
            if (ASCENDING.equals(order)) {
                Collections.sort(listForManipulation, comparator);
            } else if (DESCENDING.equals(order)) {
                Collections.sort(listForManipulation, Collections.reverseOrder(comparator));
            } else {
                throw new IllegalArgumentException("Argument (order) must be either \"asc\" or \"desc\"");
            }
        }
    }

    public void limitEntities(Integer index, Integer limit) {
        RuntimeUtilities.checkNotNegative(index, "index");
        RuntimeUtilities.checkNotNegative(limit, "limit");

        if (limit == 0) {
            limit = listForManipulation.size();
        }

        List<T> limitedList = new ArrayList<>();
        index = index * limit;
        for (int i = index; i < (index + limit) && i < listForManipulation.size(); i++) {
            limitedList.add(listForManipulation.get(i));
        }

        listForManipulation.clear();
        listForManipulation.addAll(limitedList);
    }

    public List<T> getManipulatedList() {
        return listForManipulation;
    }

    public static class FilterEntriesException extends Exception {
        FilterEntriesException(String message) {
            super(message);
        }

        FilterEntriesException(String message, Throwable e) {
            super(message, e);
        }
    }
}
