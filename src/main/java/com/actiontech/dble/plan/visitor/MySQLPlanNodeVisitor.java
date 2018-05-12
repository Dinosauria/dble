/*
 * Copyright (C) 2016-2018 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.plan.visitor;

import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.meta.ProxyMetaManager;
import com.actiontech.dble.plan.common.exception.MySQLOutPutException;
import com.actiontech.dble.plan.common.item.Item;
import com.actiontech.dble.plan.common.item.ItemField;
import com.actiontech.dble.plan.common.item.function.ItemFunc;
import com.actiontech.dble.plan.common.item.function.operator.cmpfunc.ItemFuncEqual;
import com.actiontech.dble.plan.common.item.function.operator.logic.ItemCondAnd;
import com.actiontech.dble.plan.common.item.subquery.ItemScalarSubQuery;
import com.actiontech.dble.plan.common.ptr.BoolPtr;
import com.actiontech.dble.plan.node.*;
import com.actiontech.dble.plan.util.FilterUtils;
import com.actiontech.dble.util.StringUtil;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlOrderingExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUnionQuery;

import java.sql.SQLNonTransientException;
import java.util.ArrayList;
import java.util.List;

public class MySQLPlanNodeVisitor {
    private PlanNode tableNode;
    private final String currentDb;
    private final int charsetIndex;
    private final ProxyMetaManager metaManager;
    private boolean containSchema = false;
    private boolean isSubQuery = false;

    public MySQLPlanNodeVisitor(String currentDb, int charsetIndex, ProxyMetaManager metaManager, boolean isSubQuery) {
        this.currentDb = currentDb;
        this.charsetIndex = charsetIndex;
        this.metaManager = metaManager;
        this.isSubQuery = isSubQuery;
    }

    public PlanNode getTableNode() {
        return tableNode;
    }

    public boolean isContainSchema() {
        return containSchema;
    }

    public boolean visit(SQLSelectStatement node) {
        SQLSelectQuery sqlSelect = node.getSelect().getQuery();
        MySQLPlanNodeVisitor mtv = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
        mtv.visit(sqlSelect);
        this.tableNode = mtv.getTableNode();
        this.containSchema = mtv.isContainSchema();
        return true;
    }

    public void visit(SQLSelectQuery node) {
        if (node instanceof MySqlSelectQueryBlock) {
            visit((MySqlSelectQueryBlock) node);
        } else if (node instanceof MySqlUnionQuery) {
            visit((MySqlUnionQuery) node);
        }
    }

    public boolean visit(SQLUnionQuery sqlSelectQuery) {
        MergeNode mergeNode = new MergeNode();
        SQLSelectQuery left = sqlSelectQuery.getLeft();
        MySQLPlanNodeVisitor mtvLeft = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
        mtvLeft.visit(left);
        mergeNode.addChild(mtvLeft.getTableNode());

        BoolPtr containSchemaPtr = new BoolPtr(mtvLeft.isContainSchema());
        mergeNode = checkRightChild(mergeNode, sqlSelectQuery.getRight(), isUnion(sqlSelectQuery.getOperator()), containSchemaPtr);
        this.tableNode = mergeNode;
        this.containSchema = containSchemaPtr.get();
        SQLOrderBy orderBy = sqlSelectQuery.getOrderBy();
        if (orderBy != null) {
            handleOrderBy(orderBy);
        }
        return true;
    }

    public boolean visit(MySqlUnionQuery sqlSelectQuery) {
        visit((SQLUnionQuery) sqlSelectQuery);
        SQLLimit limit = sqlSelectQuery.getLimit();
        if (limit != null) {
            handleLimit(limit);
        }
        return true;
    }

    public boolean visit(MySqlSelectQueryBlock sqlSelectQuery) {
        SQLTableSource from = sqlSelectQuery.getFrom();
        if (from != null) {
            visit(from);
            if (this.tableNode instanceof NoNameNode) {
                this.tableNode.setSql(SQLUtils.toMySqlString(sqlSelectQuery));
            }
        } else {
            this.tableNode = new NoNameNode(currentDb, SQLUtils.toMySqlString(sqlSelectQuery));
        }

        if (tableNode != null && (sqlSelectQuery.getDistionOption() == SQLSetQuantifier.DISTINCT || sqlSelectQuery.getDistionOption() == SQLSetQuantifier.DISTINCTROW)) {
            this.tableNode.setDistinct(true);
        }

        List<SQLSelectItem> items = sqlSelectQuery.getSelectList();
        if (items != null) {
            List<Item> selectItems = handleSelectItems(items);
            if (selectItems != null) {
                this.tableNode.select(selectItems);
            }
        }

        SQLExpr whereExpr = sqlSelectQuery.getWhere();
        if (whereExpr != null) {
            handleWhereCondition(whereExpr);
        }

        SQLOrderBy orderBy = sqlSelectQuery.getOrderBy();
        if (orderBy != null) {
            handleOrderBy(orderBy);
        }

        SQLSelectGroupByClause groupBy = sqlSelectQuery.getGroupBy();
        if (groupBy != null) {
            handleGroupBy(groupBy);
        }

        SQLLimit limit = sqlSelectQuery.getLimit();
        if (limit != null) {
            handleLimit(limit);
        }
        return true;
    }

    public boolean visit(SQLExprTableSource tableSource) {
        PlanNode table;
        SQLExpr expr = tableSource.getExpr();
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr propertyExpr = (SQLPropertyExpr) expr;
            try {
                table = new TableNode(StringUtil.removeBackQuote(propertyExpr.getOwner().toString()), StringUtil.removeBackQuote(propertyExpr.getName()), this.metaManager);
            } catch (SQLNonTransientException e) {
                throw new MySQLOutPutException(e.getErrorCode(), e.getSQLState(), e.getMessage());
            }
            containSchema = true;
        } else if (expr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr identifierExpr = (SQLIdentifierExpr) expr;
            if (identifierExpr.getName().equalsIgnoreCase("dual")) {
                this.tableNode = new NoNameNode(currentDb, null);
                return true;
            }
            //here to check if the table name is a view in metaManager
            QueryNode viewNode = null;
            try {
                viewNode = metaManager.getSyncView(currentDb, identifierExpr.getName());
            } catch (SQLNonTransientException e) {
                throw new MySQLOutPutException(e.getErrorCode(), e.getSQLState(), e.getMessage());
            }
            if (viewNode != null) {
                //consider if the table with other name
                viewNode.setAlias(tableSource.getAlias() == null ? identifierExpr.getName() : tableSource.getAlias());
                this.tableNode = viewNode;
                this.tableNode.setSubQuery(true);
                this.tableNode.setExistView(true);
                return true;
            } else {
                try {
                    table = new TableNode(this.currentDb, StringUtil.removeBackQuote(identifierExpr.getName()), this.metaManager);
                } catch (SQLNonTransientException e) {
                    throw new MySQLOutPutException(e.getErrorCode(), e.getSQLState(), e.getMessage());
                }
            }
        } else {
            throw new MySQLOutPutException(ErrorCode.ER_PARSE_ERROR, "42000", "table is " + tableSource.toString());
        }
        ((TableNode) table).setHintList(tableSource.getHints());
        this.tableNode = table;
        return true;
    }

    public boolean visit(SQLUnionQueryTableSource unionTables) {
        visit(unionTables.getUnion());
        this.tableNode.setSubQuery(true);
        if (unionTables.getAlias() != null) {
            tableNode.setAlias(unionTables.getAlias());
        }
        return true;
    }

    public boolean visit(SQLJoinTableSource joinTables) {
        SQLTableSource left = joinTables.getLeft();
        MySQLPlanNodeVisitor mtvLeft = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
        mtvLeft.visit(left);

        SQLTableSource right = joinTables.getRight();
        MySQLPlanNodeVisitor mtvRight = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
        mtvRight.visit(right);
        JoinNode joinNode = new JoinNode(mtvLeft.getTableNode(), mtvRight.getTableNode());
        switch (joinTables.getJoinType()) {
            case JOIN:
            case CROSS_JOIN:
            case INNER_JOIN:
            case STRAIGHT_JOIN:
                joinNode.setInnerJoin();
                break;
            case LEFT_OUTER_JOIN:
                if ((joinTables.getCondition() == null) && (joinTables.getUsing() == null || joinTables.getUsing().size() == 0) && !joinTables.isNatural()) {
                    throw new MySQLOutPutException(ErrorCode.ER_PARSE_ERROR, "42000", "left join without join_condition!");
                }
                joinNode.setLeftOuterJoin();
                break;
            case RIGHT_OUTER_JOIN:
                if ((joinTables.getCondition() == null) && (joinTables.getUsing() == null || joinTables.getUsing().size() == 0) && !joinTables.isNatural()) {
                    throw new MySQLOutPutException(ErrorCode.ER_PARSE_ERROR, "42000", "right join without join_condition!");
                }
                joinNode.setRightOuterJoin();
                break;
            case NATURAL_JOIN:// never happen
                break;
            default:
                break;
        }
        int condTypeCnt = 0;
        if (joinTables.isNatural()) {
            condTypeCnt++;
            joinNode.setNatural(true);
        }
        SQLExpr cond = joinTables.getCondition();
        if (cond != null) {
            condTypeCnt++;
            if (condTypeCnt > 1) {
                throw new MySQLOutPutException(ErrorCode.ER_PARSE_ERROR, "42000",
                        "You have an error in your SQL syntax;check the manual that corresponds to your MySQL server version for the right syntax to use near '" + cond.toString() + "'");
            }
            MySQLItemVisitor ev = new MySQLItemVisitor(this.currentDb, this.charsetIndex, this.metaManager);
            cond.accept(ev);
            addJoinOnColumns(ev.getItem(), joinNode);
        }
        if (joinTables.getUsing() != null && joinTables.getUsing().size() != 0) {
            condTypeCnt++;
            if (condTypeCnt > 1) {
                throw new MySQLOutPutException(ErrorCode.ER_PARSE_ERROR, "42000",
                        "You have an error in your SQL syntax;check the manual that corresponds to your MySQL server version for the right syntax to use near '" + joinTables.getUsing().toString() + "'");
            }
            joinNode.setUsingFields(this.getUsingFields(joinTables.getUsing()));
        }
        this.tableNode = joinNode;
        this.containSchema = mtvLeft.isContainSchema() || mtvRight.isContainSchema();
        return true;
    }

    public boolean visit(SQLSubqueryTableSource subQueryTables) {
        SQLSelect sqlSelect = subQueryTables.getSelect();
        visit(sqlSelect.getQuery());
        this.tableNode.setSubQuery(true);
        return true;
    }

    public boolean visit(SQLSelect node) {
        MySQLPlanNodeVisitor mtv = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
        mtv.visit(node.getQuery());
        this.tableNode = mtv.getTableNode();
        this.containSchema = mtv.isContainSchema();
        return true;
    }

    public void visit(SQLTableSource tables) {
        if (tables instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) tables;
            MySQLPlanNodeVisitor mtv = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
            mtv.visit(table);
            this.tableNode = mtv.getTableNode();
            this.containSchema = mtv.isContainSchema();
        } else if (tables instanceof SQLJoinTableSource) {
            SQLJoinTableSource joinTables = (SQLJoinTableSource) tables;
            MySQLPlanNodeVisitor mtv = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
            mtv.visit(joinTables);
            this.tableNode = mtv.getTableNode();
            this.containSchema = mtv.isContainSchema();
        } else if (tables instanceof SQLUnionQueryTableSource) {
            if (tables.getAlias() == null) {
                throw new MySQLOutPutException(ErrorCode.ER_DERIVED_MUST_HAVE_ALIAS, "", "Every derived table must have its own alias");
            }
            SQLUnionQueryTableSource unionTables = (SQLUnionQueryTableSource) tables;
            MySQLPlanNodeVisitor mtv = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
            mtv.visit(unionTables);
            this.tableNode = new QueryNode(mtv.getTableNode());
            this.containSchema = mtv.isContainSchema();
        } else if (tables instanceof SQLSubqueryTableSource) {
            if (tables.getAlias() == null) {
                throw new MySQLOutPutException(ErrorCode.ER_DERIVED_MUST_HAVE_ALIAS, "", "Every derived table must have its own alias");
            }
            SQLSubqueryTableSource subQueryTables = (SQLSubqueryTableSource) tables;
            MySQLPlanNodeVisitor mtv = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
            mtv.visit(subQueryTables);
            this.tableNode = new QueryNode(mtv.getTableNode());
            this.containSchema = mtv.isContainSchema();
        }
        if (tables.getAlias() != null) {
            this.tableNode.setAlias(tables.getAlias());
        }
    }

    private List<Item> handleSelectItems(List<SQLSelectItem> items) {
        List<Item> selectItems = new ArrayList<>();
        for (SQLSelectItem item : items) {
            SQLExpr expr = item.getExpr();
            MySQLItemVisitor ev = new MySQLItemVisitor(currentDb, this.charsetIndex, this.metaManager);
            expr.accept(ev);
            Item selItem = ev.getItem();
            if (selItem.isWithSubQuery()) {
                setSubQueryNode(selItem);
            }
            selItem.setAlias(item.getAlias());
            if (isSubQuery && selItem.getAlias() == null) {
                selItem.setAlias("autoalias_scalar");
            }
            selectItems.add(selItem);
        }
        return selectItems;
    }

    private void setSubQueryNode(Item selItem) {
        if (selItem instanceof ItemScalarSubQuery) {
            ((ItemScalarSubQuery) selItem).setField(true);
            tableNode.getSubQueries().add((ItemScalarSubQuery) selItem);
            tableNode.setSubQuery(true);
        } else if (selItem instanceof ItemFunc) {
            for (Item args : selItem.arguments()) {
                setSubQueryNode(args);
            }
        }
    }

    private void handleWhereCondition(SQLExpr whereExpr) {
        MySQLItemVisitor mev = new MySQLItemVisitor(this.currentDb, this.charsetIndex, this.metaManager);
        whereExpr.accept(mev);
        if (this.tableNode != null) {
            Item whereFilter = mev.getItem();
            tableNode.query(whereFilter);
            if (whereFilter.isWithSubQuery()) {
                tableNode.setSubQuery(true);
                tableNode.setCorrelatedSubQuery(whereFilter.isCorrelatedSubQuery());
            }
        } else {
            throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "from expression is null,check the sql!");
        }
    }

    private void handleOrderBy(SQLOrderBy orderBy) {
        for (SQLSelectOrderByItem p : orderBy.getItems()) {
            SQLExpr expr = p.getExpr();
            MySQLItemVisitor v = new MySQLItemVisitor(this.currentDb, this.charsetIndex, this.metaManager);
            expr.accept(v);
            if (v.getItem() instanceof ItemScalarSubQuery) {
                tableNode.setSubQuery(true);
                tableNode.setCorrelatedSubQuery(false);
            }
            this.tableNode = tableNode.orderBy(v.getItem(), p.getType());
        }
    }

    private void handleGroupBy(SQLSelectGroupByClause groupBy) {
        for (SQLExpr p : groupBy.getItems()) {
            if (p instanceof MySqlOrderingExpr) {
                MySqlOrderingExpr groupitem = (MySqlOrderingExpr) p;
                SQLExpr q = groupitem.getExpr();
                MySQLItemVisitor v = new MySQLItemVisitor(this.currentDb, this.charsetIndex, this.metaManager);
                q.accept(v);
                this.tableNode = tableNode.groupBy(v.getItem(), groupitem.getType());
            } else {
                MySQLItemVisitor v = new MySQLItemVisitor(this.currentDb, this.charsetIndex, this.metaManager);
                p.accept(v);
                this.tableNode = tableNode.groupBy(v.getItem(), SQLOrderingSpecification.ASC);
            }
        }

        if (groupBy.isWithRollUp()) {
            throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "with rollup is not supported yet!");
        }
        if (groupBy.getHaving() != null) {
            handleHavingCondition(groupBy.getHaving());
        }
    }


    private MergeNode checkRightChild(MergeNode mergeNode, SQLSelectQuery rightQuery, boolean isUnion, BoolPtr containSchemaPtr) {
        if (rightQuery instanceof SQLUnionQuery) {
            SQLUnionQuery subUnion = (SQLUnionQuery) rightQuery;
            SQLSelectQuery subLeft = subUnion.getLeft();
            MySQLPlanNodeVisitor mtvRight = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
            mtvRight.visit(subLeft);
            mergeNode.addChild(mtvRight.getTableNode());
            containSchemaPtr.set(containSchemaPtr.get() || mtvRight.isContainSchema());
            mergeNode.setUnion(isUnion);

            MergeNode mergeParentNode = new MergeNode();
            mergeParentNode.addChild(mergeNode);
            return checkRightChild(mergeParentNode, subUnion.getRight(), isUnion(subUnion.getOperator()), containSchemaPtr);

        } else {
            MySQLPlanNodeVisitor mtvRight = new MySQLPlanNodeVisitor(this.currentDb, this.charsetIndex, this.metaManager, this.isSubQuery);
            mtvRight.visit(rightQuery);
            mergeNode.addChild(mtvRight.getTableNode());
            mergeNode.setUnion(isUnion);
            containSchemaPtr.set(containSchemaPtr.get() || mtvRight.isContainSchema());
            return mergeNode;
        }
    }

    private boolean isUnion(SQLUnionOperator unionOperator) {
        return unionOperator == SQLUnionOperator.UNION || unionOperator == SQLUnionOperator.DISTINCT;
    }

    private void handleHavingCondition(SQLExpr havingExpr) {
        MySQLItemVisitor mev = new MySQLItemVisitor(currentDb, this.charsetIndex, this.metaManager);
        havingExpr.accept(mev);
        Item havingFilter = mev.getItem();
        if (this.tableNode == null) {
            throw new IllegalArgumentException("from expression is null,check the sql!");
        }
        if (havingFilter.isWithSubQuery()) {
            tableNode.setSubQuery(true);
            tableNode.setCorrelatedSubQuery(havingFilter.isCorrelatedSubQuery());
        }
        this.tableNode = this.tableNode.having(havingFilter);

    }

    private void handleLimit(SQLLimit limit) {
        long from = 0;
        SQLExpr offset = limit.getOffset();
        if (offset != null) {
            SQLIntegerExpr offsetExpr = (SQLIntegerExpr) offset;
            from = offsetExpr.getNumber().longValue();
        }
        SQLExpr rowCount = limit.getRowCount();
        long to = ((SQLIntegerExpr) rowCount).getNumber().longValue();
        tableNode.setLimitFrom(from);
        tableNode.setLimitTo(to);
    }

    private void addJoinOnColumns(Item ifilter, JoinNode joinNode) {
        if (ifilter instanceof ItemFuncEqual) {
            ItemFuncEqual filter = (ItemFuncEqual) ifilter;
            Item column = filter.arguments().get(0);
            Item value = filter.arguments().get(1);
            if (column != null && column instanceof ItemField && value != null && value instanceof ItemField) {
                joinNode.addJoinFilter(filter);
            } else {
                joinNode.setOtherJoinOnFilter(filter);
            }
        } else if (ifilter instanceof ItemCondAnd) {
            ItemCondAnd ilfand = (ItemCondAnd) ifilter;
            List<Item> subFilter = ilfand.arguments();
            if (subFilter != null) {
                for (Item arg : subFilter) {
                    Item orgOtherJoin = joinNode.getOtherJoinOnFilter();
                    addJoinOnColumns(arg, joinNode);
                    joinNode.setOtherJoinOnFilter(FilterUtils.and(orgOtherJoin, joinNode.getOtherJoinOnFilter()));
                }
            } else {
                throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "and has no other columns , " + ifilter);
            }

        } else {
            joinNode.setOtherJoinOnFilter(ifilter);
        }
    }

    private List<String> getUsingFields(List<SQLExpr> using) {
        List<String> fds = new ArrayList<>(using.size());
        for (SQLExpr us : using) {
            fds.add(StringUtil.removeBackQuote(us.toString().toLowerCase()));
        }
        return fds;
    }
}
