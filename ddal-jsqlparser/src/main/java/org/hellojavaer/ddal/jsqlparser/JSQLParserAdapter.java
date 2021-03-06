/*
 * #%L
 * ddal-jsqlparser
 * %%
 * Copyright (C) 2016 - 2017 the original author or authors.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.hellojavaer.ddal.jsqlparser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Database;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.hellojavaer.ddal.ddr.datasource.exception.CrossPreparedStatementException;
import org.hellojavaer.ddal.ddr.shard.RangeShardValue;
import org.hellojavaer.ddal.ddr.shard.ShardRouteConfig;
import org.hellojavaer.ddal.ddr.shard.ShardRouteInfo;
import org.hellojavaer.ddal.ddr.shard.ShardRouter;
import org.hellojavaer.ddal.ddr.sqlparse.SQLParsedResult;
import org.hellojavaer.ddal.ddr.sqlparse.SQLParsedState;
import org.hellojavaer.ddal.ddr.sqlparse.exception.*;
import org.hellojavaer.ddal.ddr.utils.DDRJSONUtils;
import org.hellojavaer.ddal.ddr.utils.DDRStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.*;

/**
 *
 *
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 12/11/2016.
 */
public class JSQLParserAdapter extends JSQLBaseVisitor {

    private Logger             logger              = LoggerFactory.getLogger(this.getClass());

    private String             sql;
    private ShardRouter        shardRouter;
    private Statement          statement;
    private Set<String>        schemas             = new HashSet<>();

    private boolean            enableLimitCheck    = false;

    private List<TableWrapper> toBeConvertedTables = new ArrayList<>();

    static {
        try {
            checkJSqlParserFeature();
        } catch (Exception e) {
            throw new RuntimeException("JSqlParser feature check failed", e);
        }
    }

    /**
     * To make ddal-jsqlparser work well, JSqlParser should include the feature of 'support getting jdbc parameter index'.
     * And this feature is provided on the version of {@link <a href="https://github.com/JSQLParser/JSqlParser/releases/tag/jsqlparser-0.9.7">0.9.7</a>}.
     * This method is designed to check the necessary feature.
     */
    private static void checkJSqlParserFeature() throws JSQLParserException {
        CCJSqlParserManager parserManager = new CCJSqlParserManager();
        String sql = "SELECT * FROM tab_1 WHERE tab_1.col_1 = ? AND col_2 IN (SELECT DISTINCT col_2 FROM tab_2 WHERE col_3 LIKE ? AND col_4 > ?) LIMIT ?, ?";
        Select select = (Select) parserManager.parse(new StringReader(sql));
        PlainSelect selectBody = (PlainSelect) select.getSelectBody();
        //
        AndExpression andExpression = (AndExpression) selectBody.getWhere();
        EqualsTo equalsTo = (EqualsTo) andExpression.getLeftExpression();
        JdbcParameter jdbcParameter = (JdbcParameter) equalsTo.getRightExpression();
        Integer index1 = jdbcParameter.getIndex();
        if (index1 != 1) {
            throw new IllegalStateException("Current version of JSQLParser doesn't support the feature of 'support "
                                            + "get jdbc parameter index'");
        }
        //
        InExpression inExpression = (InExpression) andExpression.getRightExpression();
        SubSelect subSelect = (SubSelect) inExpression.getRightItemsList();
        PlainSelect subSelectBody = (PlainSelect) subSelect.getSelectBody();
        AndExpression subAndExpression = (AndExpression) subSelectBody.getWhere();
        LikeExpression likeExpression = (LikeExpression) subAndExpression.getLeftExpression();
        if (((JdbcParameter) likeExpression.getRightExpression()).getIndex() != 2) {
            throw new IllegalStateException(
                                            "Current version of JSQLParser doesn't support the feature of 'support get jdbc parameter index'");
        }
        //
        GreaterThan greaterThan = (GreaterThan) subAndExpression.getRightExpression();
        if (((JdbcParameter) greaterThan.getRightExpression()).getIndex() != 3) {
            throw new IllegalStateException(
                                            "Current version of JSQLParser doesn't support the feature of 'support get jdbc parameter index'");
        }
        //
        Expression offset = selectBody.getLimit().getOffset();
        Expression rowCount = selectBody.getLimit().getRowCount();
        if (((JdbcParameter) offset).getIndex() != 4 || ((JdbcParameter) rowCount).getIndex() != 5) {
            throw new IllegalStateException(
                                            "Current version of JSQLParser doesn't support the feature of 'support get jdbc parameter index'");
        }
    }

    public JSQLParserAdapter(String sql, ShardRouter shardRouter, boolean enableLimitCheck) {
        this.sql = sql;
        this.shardRouter = shardRouter;
        this.enableLimitCheck = enableLimitCheck;
        try {
            this.statement = CCJSqlParserUtil.parse(sql);
        } catch (Throwable e) {
            throw new SQLSyntaxErrorException("sql is [" + sql + "]", e);
        }
        if (statement instanceof Select //
            || statement instanceof Update//
            || statement instanceof Insert//
            || statement instanceof Delete) {
            // ok
        } else {
            throw new UnsupportedSQLExpressionException(
                                                        "Sql ["
                                                                + sql
                                                                + "] is not supported in shard sql. Only support 'select' 'insert' 'update' and 'delete' sql statement");
        }
    }

    private String generateSplitString(String str) {
        Random random = new Random(System.currentTimeMillis());
        while (true) {
            long l = random.nextLong();
            if (l < 0) {
                l = -l;
            }
            String tar = "__" + l;
            if (str.indexOf(tar) < 0) {
                return tar;
            }
        }
    }

    public SQLParsedState parse() {
        try {
            statement.accept(this);
            String targetSql = statement.toString();
            //
            String splitString = generateSplitString(targetSql);
            for (int i = 0; i < toBeConvertedTables.size(); i++) {
                TableWrapper tab = toBeConvertedTables.get(i);
                tab.setSchemaName(null);
                tab.setName("_" + i + splitString);
            }
            //
            targetSql = statement.toString();
            //
            final List<Object> splitSqls = new ArrayList<>();
            String[] sqls = targetSql.split(splitString);// table切分
            for (int i = 0; i < sqls.length - 1; i++) {
                String s = sqls[i];
                int index = s.lastIndexOf('_');
                splitSqls.add(s.substring(0, index));
                Integer paramIndex = Integer.valueOf(s.substring(index + 1));
                splitSqls.add(toBeConvertedTables.get(paramIndex));
            }
            splitSqls.add(sqls[sqls.length - 1]);
            //
            SQLParsedState parsedResult = new SQLParsedState() {

                @Override
                public SQLParsedResult parse(final Map<Object, Object> jdbcParams) {

                    final Map<TableWrapper, String> convertedTables = new HashMap<>();
                    final Set<String> schemas = new HashSet<>(JSQLParserAdapter.this.schemas);
                    final SQLParsedResult result = new SQLParsedResult() {

                        @Override
                        public void checkIfCrossPreparedStatement(Map<Object, Object> jdbcParam)
                                                                                                throws CrossPreparedStatementException {
                            for (Map.Entry<TableWrapper, String> entry : convertedTables.entrySet()) {
                                TableWrapper tab = entry.getKey();
                                route1(tab, jdbcParams, entry.getValue(), this.getSql());
                            }
                        }
                    };

                    StringBuilder sb = new StringBuilder();
                    for (Object obj : splitSqls) {
                        if (obj instanceof TableWrapper) {
                            TableWrapper tab = (TableWrapper) obj;
                            ShardRouteInfo routeInfo = route1(tab, jdbcParams, tab.getRoutedFullTableName(), null);
                            schemas.add(routeInfo.getScName());
                            String routedFullTableName = routeInfo.toString();
                            convertedTables.put(tab, routedFullTableName);
                            sb.append(routedFullTableName);
                        } else {
                            sb.append(obj);
                        }
                    }
                    result.setSql(sb.toString());
                    result.setSchemas(schemas);
                    return result;
                }
            };
            return parsedResult;
        } finally {
            // reset context
            this.getStack().clear();
        }
    }

    private ShardRouteInfo route1(TableWrapper tab, Map<Object, Object> jdbcParams, String routedFullTableName,
                                  String routedSql) {
        ShardRouteInfo routeInfo = null;
        // 1. no shard key
        if (tab.getJdbcParamKeys() == null || tab.getJdbcParamKeys().isEmpty()) {
            routeInfo = getRouteInfo(tab, null);
            if (routeInfo != null) {
                String fullTableName = routeInfo.toString();
                if (tab.getRoutedFullTableName() != null) {// 多重路由
                    if (!tab.getRoutedFullTableName().equals(fullTableName)) {
                        throw new AmbiguousRouteResultException("In sql[" + sql + "], table:'"
                                                                + tab.getOriginalConfig().toString()
                                                                + "' has multiple routing results["
                                                                + tab.getRoutedFullTableName() + "," + fullTableName
                                                                + "]");
                    }
                }
            } else {
                throw new GetRouteInfoException("Can't get route information for table:'"
                                                + tab.getOriginalConfig().toString()
                                                + "' 'sdValue':null and 'routeConfig':"
                                                + tab.getRouteConfig().toString());
            }
            return routeInfo;
        }
        // 2. jdbc param
        for (Object sqlParam : tab.getJdbcParamKeys()) {// size > 0
            if (sqlParam instanceof SqlParam) {
                Object sdValue = null;
                Object key = ((SqlParam) sqlParam).getValue();
                if (jdbcParams != null) {
                    sdValue = jdbcParams.get(key);
                }
                if (sdValue == null) {// sql中指定的sdValue不能为空
                    throw new IllegalSQLParameterException("For jdbc parameter key " + key
                                                           + ", jdbc parameter value is null. Jdbc parameter map is "
                                                           + DDRJSONUtils.toJSONString(jdbcParams) + " and sql is ["
                                                           + sql + "]");
                }
                routeInfo = getRouteInfo(tab, sdValue);
                String next = routeInfo.toString();
                if (routedFullTableName == null) {
                    routedFullTableName = next;
                } else {
                    if (!routedFullTableName.equals(next)) {
                        throw new AmbiguousRouteResultException("In sql[" + sql + "], table:'"
                                                                + tab.getOriginalConfig().toString()
                                                                + "' has multiple routing results["
                                                                + routedFullTableName + "," + next
                                                                + "]. Jdbc parameter is "
                                                                + DDRJSONUtils.toJSONString(jdbcParams));
                    }
                }
            } else {// range
                RangeParam rangeParam = (RangeParam) sqlParam;
                SqlParam begin = rangeParam.getBeginValue();
                SqlParam end = rangeParam.getEndValue();
                long s0 = 0;
                long e0 = 0;
                if (begin.isJdbcParamType()) {
                    Number number = (Number) jdbcParams.get(begin.getValue());
                    if (number == null) {
                        throw new IllegalSQLParameterException("Jdbc parameter can't be null. Jdbc parameter key is "
                                                               + begin.getValue() + ", jdbc parameter is "
                                                               + DDRJSONUtils.toJSONString(jdbcParams)
                                                               + " and sql is [" + sql + "]");
                    }
                    s0 = number.longValue();
                } else {
                    s0 = ((Number) begin.getValue()).longValue();
                }
                if (end.isJdbcParamType()) {
                    Number number = (Number) jdbcParams.get(end.getValue());
                    if (number == null) {

                    }
                    e0 = number.longValue();
                } else {
                    e0 = ((Number) end.getValue()).longValue();
                }

                routeInfo = getRouteInfo(tab, new RangeShardValue(s0, e0));
                String next = routeInfo.toString();
                if (routedFullTableName == null) {
                    routedFullTableName = next;
                } else {
                    if (!routedFullTableName.equals(next)) {
                        if (routedSql != null) {
                            throw new CrossPreparedStatementException("Sql[" + sql + "] has been routed to ["
                                                                      + routedSql + "] and table:'"
                                                                      + tab.getOriginalConfig().toString()
                                                                      + "' has been route to '" + routedFullTableName
                                                                      + "'. But current jdbc parameter:"
                                                                      + DDRJSONUtils.toJSONString(jdbcParams)
                                                                      + " require route to " + next
                                                                      + DDRJSONUtils.toJSONString(jdbcParams));
                        } else {
                            throw new AmbiguousRouteResultException("In sql[" + sql + "], table:'"
                                                                    + tab.getOriginalConfig().toString()
                                                                    + "' has multiple routing results["
                                                                    + routedFullTableName + "," + next
                                                                    + "]. Jdbc parameter is "
                                                                    + DDRJSONUtils.toJSONString(jdbcParams));
                        }
                    }
                }

            }
        }
        return routeInfo;
    }

    private void afterVisitBaseStatement() {
        FrameContext context = this.getStack().pop();
        for (Map.Entry<String, TableWrapper> entry : context.entrySet()) {
            TableWrapper tab = entry.getValue();
            if (tab == AMBIGUOUS_TABLE) {
                continue;
            }
            if (tab.getJdbcParamKeys() != null && !tab.getJdbcParamKeys().isEmpty()) {// 含jdbc路由
                toBeConvertedTables.add(tab);
            } else {// 不含jdbc路由
                if (tab.getRoutedFullTableName() == null) {// sql未路由
                    toBeConvertedTables.add(tab);
                }// else ok
            }
        }
    }

    private ShardRouteInfo getRouteInfo(TableWrapper tab, Object sdValue) {
        try {
            ShardRouteInfo routeInfo = this.shardRouter.getRouteInfo(tab.getOriginalConfig().getSchemaName(),
                                                                     tab.getOriginalConfig().getName(), sdValue);
            return routeInfo;
        } catch (Throwable e) {
            String fullTableName = null;
            if (tab.getOriginalConfig().getAlias() != null) {
                fullTableName = tab.getOriginalConfig().getAlias().getName();
            } else {
                fullTableName = tab.getOriginalConfig().getName();
            }
            String sdKey = null;
            if (tab.getRouteConfig().getSdKey() != null) {
                sdKey = fullTableName + "." + tab.getRouteConfig().getSdKey();
            }
            String msg = String.format("Current state is table:'%s', sdKey:'%s', sdValue:%s, routeConfig:%s, sql:[%s]",
                                       tab.getOriginalConfig().toString(), sdKey, sdValue,
                                       tab.getRouteConfig().toString(), sql);
            throw new GetRouteInfoException(msg, e);
        }
    }

    private void route0(TableWrapper tab, ShardRouteInfo routeInfo) {
        String fullTableName = routeInfo.toString();
        if (tab.getRoutedFullTableName() != null) {// 多重路由
            if (!tab.getRoutedFullTableName().equals(fullTableName)) {
                throw new AmbiguousRouteResultException("In sql[" + sql + "], table:'"
                                                        + tab.getOriginalConfig().toString()
                                                        + "' has multiple routing results["
                                                        + tab.getRoutedFullTableName() + "," + fullTableName + "]");
            }
        } else {// 是否使用alias在put的时候设置,这里只需要设置scName和tbName
            tab.setRoutedFullTableName(fullTableName);//
            tab.setSchemaName(routeInfo.getScName());
            tab.setName(routeInfo.getTbName());
            schemas.add(routeInfo.getScName());
        }
    }

    @Override
    public void visit(Insert insert) {
        this.getStack().push(new FrameContext());
        ShardRouteConfig routeConfig = shardRouter.getRouteConfig(insert.getTable().getSchemaName(),
                                                                  insert.getTable().getName());
        if (routeConfig != null) {
            TableWrapper table = new TableWrapper(insert.getTable(), routeConfig);
            addRoutedTableIntoContext(table, routeConfig, false);
            List<Column> columns = insert.getColumns();
            if (columns != null) {
                ExpressionList expressionList = (ExpressionList) insert.getItemsList();
                List<Expression> valueList = expressionList.getExpressions();
                for (int i = 0; i < columns.size(); i++) {
                    Column column = columns.get(i);
                    TableWrapper tab = getTableFromContext(column);
                    if (tab != null) {
                        Expression expression = valueList.get(i);
                        routeTable(tab, column, expression);
                    }
                }
            }
        }
        super.visit(insert);
        afterVisitBaseStatement();
    }

    /**
     * mysql 'delete' doesn't support alais
     */
    @Override
    public void visit(Delete delete) {
        if (enableLimitCheck && delete.getLimit() == null) {
            throw new IllegalStateException("no limit in sql: " + sql);
        }
        this.getStack().push(new FrameContext());
        ShardRouteConfig routeConfig = shardRouter.getRouteConfig(delete.getTable().getSchemaName(),
                                                                  delete.getTable().getName());
        if (routeConfig != null) {
            TableWrapper tab = new TableWrapper(delete.getTable(), routeConfig);
            delete.setTable(tab);
            addRoutedTableIntoContext(tab, routeConfig, false);
        }
        super.visit(delete);
        afterVisitBaseStatement();
    }

    @Override
    public void visit(Update update) {
        if (enableLimitCheck && update.getLimit() == null) {
            throw new IllegalStateException("no limit in sql: " + sql);
        }
        this.getStack().push(new FrameContext());
        if (update.getTables() != null) {
            for (Table table : update.getTables()) {
                ShardRouteConfig routeConfig = shardRouter.getRouteConfig(table.getSchemaName(), table.getName());
                if (routeConfig != null) {
                    TableWrapper tab = new TableWrapper(table, routeConfig);
                    addRoutedTableIntoContext(tab, routeConfig, true);
                }
            }
        }
        super.visit(update);
        afterVisitBaseStatement();
    }

    @Override
    public void visit(Select select) {
        if (enableLimitCheck && select.getSelectBody() != null && select.getSelectBody() instanceof PlainSelect
            && ((PlainSelect) select.getSelectBody()).getLimit() == null) {
            throw new IllegalStateException("no limit in sql: " + sql);
        }
        this.getStack().push(new FrameContext());
        super.visit(select);
        afterVisitBaseStatement();
    }

    @Override
    public void visit(SubSelect subSelect) {
        this.getStack().push(new FrameContext());
        super.visit(subSelect);
        afterVisitBaseStatement();

    }

    private TableWrapper getTableFromContext(Column col) {
        FrameContext frameContext = this.getStack().peek();
        String colFullName = col.toString();
        colFullName = DDRStringUtils.toLowerCase(colFullName);
        return frameContext.get(colFullName);
    }

    private void addRoutedTableIntoContext(TableWrapper table, ShardRouteConfig routeInfo) {
        addRoutedTableIntoContext(table, routeInfo, true);
    }

    /**
     * value:'scName.tbAliasName' value:table
     * @param table
     * @param appendAlias
     */
    private void addRoutedTableIntoContext(TableWrapper table, ShardRouteConfig routeConfig, boolean appendAlias) {
        FrameContext frameContext = this.getStack().peek();
        String tbName = table.getName();
        String tbAliasName = tbName;
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            tbAliasName = table.getAlias().getName();
        } else {
            if (appendAlias) {
                table.setAlias(new Alias(tbName, true));
            }
        }
        String sdKey = DDRStringUtils.toLowerCase(routeConfig.getSdKey());// sdKey可以为null,当为null时需要通过context路由
        if (table.getSchemaName() != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(DDRStringUtils.toLowerCase(table.getSchemaName()));
            sb.append('.');
            sb.append(DDRStringUtils.toLowerCase(tbAliasName));
            sb.append('.');
            sb.append(sdKey);
            String key = sb.toString();
            putIntoContext(frameContext, key, table);
            putIntoContext(frameContext, key.substring(table.getSchemaName().length() + 1), table);
            putIntoContext(frameContext, key.substring(table.getSchemaName().length() + 2 + tbAliasName.length()),
                           table);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(DDRStringUtils.toLowerCase(tbAliasName));
            sb.append('.');
            sb.append(sdKey);
            String key = sb.toString();
            putIntoContext(frameContext, key, table);
            putIntoContext(frameContext, key.substring(tbAliasName.length() + 1), table);
        }
    }

    private Object getRouteValue(Column column, Expression obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof LongValue) {
            return ((LongValue) obj).getValue();
        } else if (obj instanceof StringValue) {
            return ((StringValue) obj).getValue();
        } else if (obj instanceof HexValue) {
            return Long.parseLong(((HexValue) obj).getValue(), 16);
        } else if (obj instanceof DateValue) {
            return ((DateValue) obj).getValue();
        } else if (obj instanceof DoubleValue) {
            return ((DoubleValue) obj).getValue();
        } else if (obj instanceof TimeValue) {
            return ((TimeValue) obj).getValue();
        } else if (obj instanceof TimestampValue) {
            return ((TimestampValue) obj).getValue();
        } else {// NullValue
            throw new UnsupportedSQLParameterTypeException("Type '" + obj.getClass()
                                                           + "' is not supported for shard value '" + column.toString()
                                                           + "'. Sql is [" + sql + "]");
        }
    }

    @Override
    public void visit(InExpression inExpression) {
        if (inExpression.isNot()) {
            super.visit(inExpression);
            return;
        }
        Column column = (Column) inExpression.getLeftExpression();
        if (inExpression.getRightItemsList() instanceof ExpressionList) {
            TableWrapper tab = getTableFromContext(column);
            if (tab == null) {
                super.visit(inExpression);
                return;
            }
            ExpressionList itemsList = (ExpressionList) inExpression.getRightItemsList();
            List<Expression> list = itemsList.getExpressions();
            if (list == null || list.isEmpty()) {
                super.visit(inExpression);
            }
            for (Expression exp : list) {
                routeTable(tab, column, exp);
            }
        } else {
            super.visit(inExpression);
            return;
        }
    }

    @Override
    public void visit(Between between) {
        if (between.isNot()) {
            super.visit(between);
            return;
        }
        Column column = (Column) between.getLeftExpression();
        TableWrapper tab = getTableFromContext(column);
        if (tab == null) {
            super.visit(between);
            return;
        }
        Expression begin = between.getBetweenExpressionStart();
        Expression end = between.getBetweenExpressionEnd();
        if (begin instanceof SubSelect || end instanceof SubSelect) {
            super.visit(between);
            return;
        } else if ((begin instanceof JdbcParameter || begin instanceof JdbcNamedParameter) //
                   || (end instanceof JdbcParameter || end instanceof JdbcNamedParameter)) {
            tab.getJdbcParamKeys().add(new RangeParam(new SqlParam(column, begin), new SqlParam(column, end)));
            return;
        } else {
            long s1 = ((Number) getRouteValue(column, begin)).longValue();
            long e1 = ((Number) getRouteValue(column, end)).longValue();
            if (s1 > e1) {
                long temp = s1;
                s1 = e1;
                e1 = temp;
            }
            for (long l = s1; l <= e1; l++) {
                routeTable(tab, column, l);
            }
        }
    }

    @Override
    public void visit(EqualsTo equalsTo) {
        Column column = (Column) equalsTo.getLeftExpression();
        if (equalsTo.getRightExpression() instanceof SubSelect) {
            super.visit(equalsTo);
            return;
        } else {
            String fullColumnName = column.toString();
            fullColumnName = DDRStringUtils.toLowerCase(fullColumnName);
            TableWrapper tab = this.getStack().peek().get(fullColumnName);
            if (tab != null) {// 需要路由的table
                routeTable(tab, column, equalsTo.getRightExpression());
            } else {// there maybe contains sub query,so we show invoke super.visit
                super.visit(equalsTo);
            }
        }
    }

    private void routeTable(TableWrapper tab, Column column, Expression routeValueExpression) {
        // jdbc参数
        if (routeValueExpression != null && routeValueExpression instanceof JdbcParameter
            || routeValueExpression instanceof JdbcNamedParameter) {
            tab.getJdbcParamKeys().add(new SqlParam(column, routeValueExpression));
            return;
        } else {// 普通sql参数
            Object sdValue = getRouteValue(column, routeValueExpression);//
            routeTable(tab, column, sdValue);
        }
    }

    private void routeTable(TableWrapper tab, Column column, Object sdValue) {
        if (tab == null) {//
            return;
        }
        if (tab == AMBIGUOUS_TABLE) {
            throw new RuntimeException("Shard value '" + column.toString() + "' in where clause is ambiguous. Sql is ["
                                       + sql + "]");
        }
        ShardRouteInfo routeInfo = getRouteInfo(tab, sdValue);
        route0(tab, routeInfo);
    }

    @Override
    public void visit(Table table) {
        String tbName = table.getName();
        ShardRouteConfig routeConfig = shardRouter.getRouteConfig(table.getSchemaName(), tbName);
        if (routeConfig != null) {
            TableWrapper tab = new TableWrapper(table, routeConfig);
            addRoutedTableIntoContext(tab, routeConfig);
        }
    }

    private void putIntoContext(FrameContext frameContext, String key, TableWrapper tab) {
        TableWrapper tab0 = frameContext.get(key);
        if (tab0 == null) {
            frameContext.put(key, tab);
        } else {
            frameContext.put(key, AMBIGUOUS_TABLE);
        }
    }

    private static final TableWrapper        AMBIGUOUS_TABLE = new TableWrapper(null, null);

    private ThreadLocal<Stack<FrameContext>> context         = new ThreadLocal<Stack<FrameContext>>() {

                                                                 @Override
                                                                 protected Stack<FrameContext> initialValue() {
                                                                     return new Stack<FrameContext>();
                                                                 }
                                                             };

    private class FrameContext extends HashMap<String, TableWrapper> {

    }

    private Stack<FrameContext> getStack() {
        return context.get();
    }

    private static class TableWrapper extends Table {

        public TableWrapper(Table table, ShardRouteConfig routeConfig) {
            this.routeConfig = routeConfig;
            if (table != null) {
                this.table = table;
                originalConfig.setDatabase(table.getDatabase());
                originalConfig.setSchemaName(table.getSchemaName());
                originalConfig.setName(table.getName());
                originalConfig.setAlias(table.getAlias());
                originalConfig.setPivot(table.getPivot());
                originalConfig.setASTNode(table.getASTNode());
            }
        }

        private Table            table;

        private Table            originalConfig = new Table();

        private ShardRouteConfig routeConfig;                       // route config info

        private String           routedFullTableName;               // 由routeInfo计算出,如果有sql路由时该字段不为空,如果该参数为空,表示需要jdbc路由

        private List<Object>     jdbcParamKeys  = new ArrayList<>(); // table 关联的jdbc列

        public ShardRouteConfig getRouteConfig() {
            return routeConfig;
        }

        public void setRouteConfig(ShardRouteConfig routeConfig) {
            this.routeConfig = routeConfig;
        }

        public String getRoutedFullTableName() {
            return routedFullTableName;
        }

        public void setRoutedFullTableName(String routedFullTableName) {
            this.routedFullTableName = routedFullTableName;
        }

        public List<Object> getJdbcParamKeys() {
            return jdbcParamKeys;
        }

        public void setJdbcParamKeys(List<Object> jdbcParamKeys) {
            this.jdbcParamKeys = jdbcParamKeys;
        }

        public Table getOriginalConfig() {
            return originalConfig;
        }

        public void setOriginalConfig(Table originalConfig) {
            this.originalConfig = originalConfig;
        }

        @Override
        public Database getDatabase() {
            return table.getDatabase();
        }

        @Override
        public void setDatabase(Database database) {
            table.setDatabase(database);
        }

        @Override
        public String getSchemaName() {
            return table.getSchemaName();
        }

        @Override
        public void setSchemaName(String string) {
            table.setSchemaName(string);
        }

        @Override
        public String getName() {
            return table.getName();
        }

        @Override
        public void setName(String string) {
            table.setName(string);
        }

        @Override
        public Alias getAlias() {
            return table.getAlias();
        }

        @Override
        public void setAlias(Alias alias) {
            table.setAlias(alias);
        }

        @Override
        public String getFullyQualifiedName() {
            return table.getFullyQualifiedName();
        }

        @Override
        public void accept(FromItemVisitor fromItemVisitor) {
            table.accept(fromItemVisitor);
        }

        @Override
        public void accept(IntoTableVisitor intoTableVisitor) {
            table.accept(intoTableVisitor);
        }

        @Override
        public Pivot getPivot() {
            return table.getPivot();
        }

        @Override
        public void setPivot(Pivot pivot) {
            table.setPivot(pivot);
        }

    }

    private class RangeParam {

        private SqlParam beginValue;
        private SqlParam endValue;

        public RangeParam(SqlParam beginValue, SqlParam endValue) {
            this.beginValue = beginValue;
            this.endValue = endValue;
        }

        public SqlParam getBeginValue() {
            return beginValue;
        }

        public void setBeginValue(SqlParam beginValue) {
            this.beginValue = beginValue;
        }

        public SqlParam getEndValue() {
            return endValue;
        }

        public void setEndValue(SqlParam endValue) {
            this.endValue = endValue;
        }
    }

    private class SqlParam {

        private Column     column;
        private Expression expression;
        private Object     value;
        private boolean    jdbcParamType = false;

        public SqlParam(Column column, Expression expression) {
            this.column = column;
            this.expression = expression;
            if (expression instanceof JdbcParameter) {
                value = ((JdbcParameter) expression).getIndex();
                jdbcParamType = true;
            } else if (expression instanceof JdbcNamedParameter) {
                value = ((JdbcNamedParameter) expression).getName();
                jdbcParamType = true;
            } else {
                value = getRouteValue(column, expression);
                jdbcParamType = false;
            }
        }

        public Column getColumn() {
            return column;
        }

        public void setColumn(Column column) {
            this.column = column;
        }

        public Expression getExpression() {
            return expression;
        }

        public void setExpression(Expression expression) {
            this.expression = expression;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public boolean isJdbcParamType() {
            return jdbcParamType;
        }

        public void setJdbcParamType(boolean jdbcParamType) {
            this.jdbcParamType = jdbcParamType;
        }
    }
}
