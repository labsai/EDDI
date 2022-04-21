package ai.labs.eddi.modules.nlp.expressions;

import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

@NoArgsConstructor
public class Expressions extends LinkedList<Expression> {

    public Expressions(Expressions expressions) {
        addAll(expressions);
    }

    public Expressions(Expression... expressions) {
        addAll(Arrays.asList(expressions));
    }

    public Expressions(List<Expression> expressions) {
        addAll(expressions);
    }

    public Expressions(Expression expression) {
        add(expression);
    }

    @Override
    public String toString() {
        Iterator<Expression> it = iterator();
        if (!it.hasNext()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        while (it.hasNext()) {
            Expression e = it.next();
            sb.append(e);
            if (it.hasNext()) {
                sb.append(',').append(' ');
            }
        }

        return sb.toString();
    }
}
