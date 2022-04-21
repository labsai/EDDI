package ai.labs.eddi.modules.nlp.expressions;

import ai.labs.eddi.utils.CharacterUtilities;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;

/**
 * @author ginccc
 */
@Slf4j
public class Expression implements Cloneable {
    protected String domain;
    protected String expressionName;
    protected Expressions subExpressions = new Expressions();

    protected Expression() {
    }

    public Expression(String expressionName) {
        this.expressionName = expressionName;
    }

    public Expression(String expressionName, Expression... subExpressions) {
        this(expressionName);
        Collections.addAll(this.subExpressions, subExpressions);
    }

    public Expression(String expressionName, Expressions subExpressions) {
        this(expressionName);
        this.subExpressions = subExpressions;
    }

    public String getExpressionName() {
        return expressionName;
    }

    public void setExpressionName(String expressionName) {
        int dot = expressionName.indexOf(".");
        if (dot == -1 || CharacterUtilities.isNumber(expressionName, false))
            this.expressionName = expressionName;
        else if (dot == 0)
            this.expressionName = expressionName.substring(1);
        else {
            this.domain = expressionName.substring(0, dot);
            this.expressionName = expressionName.substring(dot + 1);
        }
    }

    public Expression[] getSubExpressions() {
        return subExpressions.toArray(new Expression[subExpressions.size()]);
    }

    public void setSubExpressions(Expression... subExpressions) {
        this.subExpressions = new Expressions();
        Collections.addAll(this.subExpressions, subExpressions);
    }

    public void setSubExpressions(Expressions subExpressions) {
        this.subExpressions = subExpressions;
    }

    public void setSubExpression(int index, Expression subExpression) {
        if (index < 0 || index >= this.subExpressions.size()) {
            log.error("Tried to set a subexpression out of bounds!");
            return;
        }

        this.subExpressions.set(index, subExpression);
    }

    public void addSubExpressions(Expression... subExpressions) {
        Expressions subExpsList = new Expressions();
        Collections.addAll(subExpsList, subExpressions);
        this.subExpressions.addAll(subExpsList);
    }

    public void addSubExpressions(int index, Expression... subExpressions) {
        if (index < 0 || index > this.subExpressions.size()) {
            log.error("Tried to set a subexpression out of bounds!");
            return;
        }

        Expressions subExpsList = new Expressions();
        Collections.addAll(subExpsList, subExpressions);
        this.subExpressions.addAll(index, subExpsList);
    }

    public void addSubExpressions(Expressions subExpressions) {
        this.subExpressions.addAll(subExpressions);
    }

    public void removeSubExpressions(Expression... subExpressions) {
        for (Expression exp : subExpressions) {
            this.subExpressions.remove(exp);
        }
    }

    public void removeSubExpression(Integer index) {
        if (index > this.subExpressions.size())
            return;

        Expression expToRemove = this.subExpressions.get(index);
        removeSubExpressions(expToRemove);
    }

    public void clearSubExpressions() {
        this.subExpressions.clear();
    }

    // needs to be overridden by subclasses

    public boolean isExpressionSubCategory() {
        return false;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getFullExpression() {
        return domain + "." + expressionName;
    }

    public boolean canBeConnected(Expression exp) {
        return getDomain() == null || getDomain().equals(exp.getDomain());
    }


    public Boolean containsExpressionWithName(String expressionName) {
        return getAllExpressionsWithNames(expressionName).size() > 0;
    }

    /**
     * Returns a list of expressions that have the given expressionName.
     *
     * @param expressionNames
     * @return list of expressions matching of of the expression names of the param
     */
    public Expressions getAllExpressionsWithNames(String... expressionNames) {
        Expressions result = new Expressions();
        for (String expressionName : expressionNames) {
            if (getExpressionName().equals(expressionName)) {
                result.add(this);
            }

            if (this.getSubExpressions().length != 0) {
                for (Expression subExp : getSubExpressions())
                    result.addAll(subExp.getAllExpressionsWithNames(expressionName));
            }
        }

        return result;
    }

    public Expression safeClone() {
        try {
            Object clone = this.clone();
            return (Expression) clone;
        } catch (CloneNotSupportedException e) {
            log.error("Cloning error!", e);
        }
        return null;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        Expression ret = (Expression) super.clone();
        Expressions tmp = new Expressions();

        for (Expression exp : subExpressions) {
            Expression subExpClone = (Expression) exp.clone();
            tmp.add(subExpClone);
        }

        ret.subExpressions = tmp;

        return ret;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Expression)) return false;

        Expression that = (Expression) o;

        return expressionName.equals("*") || that.getExpressionName().equals("*") ||
                (expressionName.equals(that.getExpressionName()) && getSubExpressions().length == that.getSubExpressions().length &&
                        Arrays.equals(getSubExpressions(), that.getSubExpressions()));
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder();

        if (domain != null) {
            ret.append(domain).append(".");
        }

        ret.append(expressionName);

        if (!subExpressions.isEmpty()) {
            ret.append("(");

            for (Expression subExp : subExpressions) {
                ret.append(subExp).append(", ");
            }

            ret.deleteCharAt(ret.length() - 1);
            ret.deleteCharAt(ret.length() - 1);

            ret.append(")");
        }

        return ret.toString();
    }

    public String getGUIString() {
        StringBuilder ret = new StringBuilder();
        for (Expression subExp : subExpressions) {
            ret.append(subExp.getGUIString());
        }
        return ret.toString();
    }
}
