/**
 * Copyright 2009-2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.executor.resultset;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object DEFERRED = new Object();

    /**
     * 执行器
     */
    private final Executor executor;

    /**
     * 配置
     */
    private final Configuration configuration;

    /**
     * sql 语句映射
     */
    private final MappedStatement mappedStatement;

    /**
     * 分页信息
     */
    private final RowBounds rowBounds;

    /**
     * 参数处理器
     */
    private final ParameterHandler parameterHandler;

    /**
     * 结果处理器，处理每一行的记录
     */
    private final ResultHandler<?> resultHandler;

    /**
     * 带参数信息的 SQL
     */
    private final BoundSql boundSql;

    /**
     * 类型处理器注册中心
     */
    private final TypeHandlerRegistry typeHandlerRegistry;

    /**
     * 对象工厂
     */
    private final ObjectFactory objectFactory;

    /**
     * 反射器工厂
     */
    private final ReflectorFactory reflectorFactory;

    // nested resultmaps
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
    private final Map<String, Object> ancestorObjects = new HashMap<>();
    private Object previousRowValue;


    // multiple resultsets
    /**
     * ResultSet 名称 -> 列映射
     */
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
    /**
     * 待处理的 collection|association
     */
    private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

    // Cached Automappings
    private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

    // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
    private boolean useConstructorMappings;

    private static class PendingRelation {

        /**
         * 每行对应的 Java 对象
         */
        public MetaObject metaObject;

        /**
         * collection|association 映射
         */
        public ResultMapping propertyMapping;
    }

    private static class UnMappedColumnAutoMapping {
        private final String column;
        private final String property;
        private final TypeHandler<?> typeHandler;
        private final boolean primitive;

        public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
            this.column = column;
            this.property = property;
            this.typeHandler = typeHandler;
            this.primitive = primitive;
        }
    }

    public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                   RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.reflectorFactory = configuration.getReflectorFactory();
        this.resultHandler = resultHandler;
    }

    //
    // HANDLE OUTPUT PARAMETER
    //

    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    // 输出参数设置到参数的属性中
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    /**
     * 处理参数映射中 Java 类型为 ResultSet 的输出参数
     *
     * @param rs               结果集
     * @param parameterMapping 参数属性映射
     * @param metaParam        参数对象
     * @throws SQLException
     */
    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        if (rs == null) {
            return;
        }
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            if (this.resultHandler == null) {
                // 根据参数指定的 resultMap 处理行，然后设置到参数的属性中
                final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
                handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
                metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
            } else {
                handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    //
    // HANDLE RESULT SETS
    //
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        final List<Object> multipleResults = new ArrayList<>();

        /**
         * 处理过的结果集数量
         */
        int resultSetCount = 0;


        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);
        // 仍存在结果集并且 resultMap 的数量大于处理过的结果集数量
        while (rsw != null && resultMapCount > resultSetCount) {
            // 使用未处理的 ResultSet 处理未处理的 ResultMap
            ResultMap resultMap = resultMaps.get(resultSetCount);
            handleResultSet(rsw, resultMap, multipleResults, null);
            // 获取下一个 ResultSet 进行处理
            rsw = getNextResultSet(stmt);
            cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }

        String[] resultSets = mappedStatement.getResultSets();
        if (resultSets != null) {
            while (rsw != null && resultSetCount < resultSets.length) {
                //  collection|association 指定了 resultSet，使用剩下的结果集处理嵌套 resultMap
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                rsw = getNextResultSet(stmt);
                cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }

        return collapseSingleResultList(multipleResults);
    }

    @Override
    public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);
        if (resultMapCount != 1) {
            throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
        }

        ResultMap resultMap = resultMaps.get(0);
        return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
    }

    /**
     * 获取第一个结果集包装器
     *
     * @param stmt
     * @return
     * @throws SQLException
     */
    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        ResultSet rs = stmt.getResultSet();
        while (rs == null) {
            // 如果驱动返回的第一个 ResultSet 是 null，则向前移动获取第一个 ResultSet
            // move forward to get the first resultset in case the driver
            // doesn't return the resultset as the first result (HSQLDB 2.1)
            if (stmt.getMoreResults()) {
                rs = stmt.getResultSet();
            } else {
                if (stmt.getUpdateCount() == -1) {
                    // no more results. Must be no resultset
                    break;
                }
            }
        }
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    /**
     * 获取下一个结果集
     *
     * @param stmt
     * @return
     */
    private ResultSetWrapper getNextResultSet(Statement stmt) {
        // Making this method tolerant of bad JDBC drivers
        try {
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                // Crazy Standard JDBC way of determining if there are more results
                if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
                    ResultSet rs = stmt.getResultSet();
                    if (rs == null) {
                        // 如果前面获取的 ResultSet 为 null，则继续获取下一个 ResultSet
                        return getNextResultSet(stmt);
                    } else {
                        return new ResultSetWrapper(rs, configuration);
                    }
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        return null;
    }

    /**
     * 关闭结果集
     *
     * @param rs
     */
    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
    }

    /**
     * 校验 resultMap 和结果集的数量是否匹配
     *
     * @param rsw
     * @param resultMapCount
     */
    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    /**
     * 处理结果集
     *
     * @param rsw             结果集
     * @param resultMap       结果映射
     * @param multipleResults 返回结果
     * @param parentMapping   collection|association 节点对应的映射
     * @throws SQLException
     */
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
        try {
            if (parentMapping != null) {
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                if (resultHandler == null) {
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rsw.getResultSet());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
    }

    //
    // HANDLE ROWS FOR SIMPLE RESULTMAP
    //

    /**
     * 处理结果
     *
     * @param rsw           结果集
     * @param resultMap     结果映射
     * @param resultHandler 结果处理器
     * @param rowBounds     分页信息
     * @param parentMapping collection|association 节点对应的映射
     * @throws SQLException
     */
    public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        if (resultMap.hasNestedResultMaps()) {
            // collection|association 节点指定了 resultMap
            ensureNoRowBounds();
            checkResultHandler();
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    /**
     * resultMap 中存在嵌套查询时,不允许使用分页则抛出异常
     */
    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            // 不允许在嵌套 resultMap 中存在分页
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    /**
     * 校验嵌套 resultMap 中不允许使用 resultHandler 时不能存在 resultHandler
     */
    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                + "Use safeResultHandlerEnabled=false setting to bypass this check "
                + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    /**
     * 处理每一行
     *
     * @param rsw           结果集
     * @param resultMap     结果映射
     * @param resultHandler 结果处理器
     * @param rowBounds     分页信息
     * @param parentMapping collection|association 节点对应的映射
     * @throws SQLException
     */
    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
        throws SQLException {
        DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
        ResultSet resultSet = rsw.getResultSet();
        skipRows(resultSet, rowBounds);
        while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
            // 如果 resultMap 中不存在 discriminator 则处理当前 resultMap ，否则处理 discriminator 值对应的 resultMap
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
            // 获取当前处理行的值
            Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
            // 使用 ResultHandler 处理
            storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
    }

    /**
     * @param resultHandler 结果处理器
     * @param resultContext 结果上下文
     * @param rowValue      行对应的 Java 对象
     * @param parentMapping collection|association 节点对应的映射
     * @param rs            结果集
     * @throws SQLException
     */
    private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
        if (parentMapping != null) {
            linkToParents(rs, parentMapping, rowValue);
        } else {
            callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    /**
     * 调用 ResultHandler，处理当前行的值
     *
     * @param resultHandler
     * @param resultContext
     * @param rowValue
     */
    @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
    private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
        resultContext.nextResultObject(rowValue);
        ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
    }

    /**
     * 是否应该处理更多的行
     *
     * @param context
     * @param rowBounds
     * @return
     */
    private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
        // 没有停止并且处理的结果的数量小于取的数量
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
    }

    /**
     * 跳过偏移的行
     *
     * @param rs        结果集
     * @param rowBounds 分页信息
     * @throws SQLException
     */
    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                if (!rs.next()) {
                    break;
                }
            }
        }
    }

    //
    // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
    //

    /**
     * 获取当前处理行的值
     *
     * @param rsw          结果集
     * @param resultMap    结果映射
     * @param columnPrefix 列前缀
     * @return
     * @throws SQLException
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        // 创建行对应的对象
        Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
        if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            // 返回结果非单列单行
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            boolean foundValues = this.useConstructorMappings;
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                // 自动映射处理，设置和列名相同属性的值
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
            }
            // 设置映射的属性值
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
        }
        return rowValue;
    }

    //
    // GET VALUE FROM ROW FOR NESTED RESULT MAP
    //

    /**
     * 获取行的值
     *
     * @param rsw           结果集
     * @param resultMap     结果映射
     * @param combinedKey   表示 resultMap 实例标识的 key
     * @param columnPrefix  列前缀
     * @param partialObject 缓存的对象
     * @return
     * @throws SQLException
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object rowValue = partialObject;
        if (rowValue != null) {
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            putAncestor(rowValue, resultMapId);
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(resultMapId);
        } else {
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            // 创建行对应的返回对象
            rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
                // 非单列单行值作为返回结果
                final MetaObject metaObject = configuration.newMetaObject(rowValue);
                boolean foundValues = this.useConstructorMappings;
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    // 自动映射，设置和列名相同的名称的属性值
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                // 根据映射关系设置属性值
                foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                // 存放外层 resultMap 的值
                putAncestor(rowValue, resultMapId);
                // 设置嵌套的 resultMap 对应值到属性中
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                ancestorObjects.remove(resultMapId);
                foundValues = lazyLoader.size() > 0 || foundValues;
                rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                nestedResultObjects.put(combinedKey, rowValue);
            }
        }
        return rowValue;
    }

    /**
     * 存放祖先 resultMap 的值
     *
     * @param resultObject
     * @param resultMapId
     */
    private void putAncestor(Object resultObject, String resultMapId) {
        ancestorObjects.put(resultMapId, resultObject);
    }

    /**
     * 是否自动映射
     *
     * @param resultMap 结果映射
     * @param isNested  是否为嵌套的 resultMap
     * @return
     */
    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        if (resultMap.getAutoMapping() != null) {
            return resultMap.getAutoMapping();
        } else {
            if (isNested) {
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
            }
        }
    }

    //
    // PROPERTY MAPPINGS
    //

    /**
     * 设置映射的属性值
     *
     * @param rsw          结果集
     * @param resultMap    结果映射
     * @param metaObject   行对应的 java  对象
     * @param lazyLoader   懒加载器
     * @param columnPrefix 列前缀
     * @return
     * @throws SQLException
     */
    private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
        for (ResultMapping propertyMapping : propertyMappings) {
            String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            if (propertyMapping.getNestedResultMapId() != null) {
                // the user added a column attribute to a nested result map, ignore it
                column = null;
            }
            if (propertyMapping.isCompositeResult()
                || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
                || propertyMapping.getResultSet() != null) {
                // 获取属性值
                Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
                // issue #541 make property optional
                final String property = propertyMapping.getProperty();
                if (property == null) {
                    continue;
                } else if (value == DEFERRED) {
                    // collection|association 稍后处理
                    foundValues = true;
                    continue;
                }
                if (value != null) {
                    foundValues = true;
                }
                // 设置属性值
                if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    metaObject.setValue(property, value);
                }
            }
        }
        return foundValues;
    }

    /**
     * 获取属性值
     *
     * @param rs               结果集
     * @param metaResultObject 列对应的 Java 对象
     * @param propertyMapping  列映射
     * @param lazyLoader       懒加载器
     * @param columnPrefix     列前缀
     * @return
     * @throws SQLException
     */
    private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
        if (propertyMapping.getNestedQueryId() != null) {
            // 属性存在嵌套查询，获取嵌套查询的值
            return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) {
            // 列中指定了结果集（collection/association 节点）
            addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
            return DEFERRED;
        } else {
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            return typeHandler.getResult(rs, column);
        }
    }

    /**
     * 创建自动映射列表
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        final String mapKey = resultMap.getId() + ":" + columnPrefix;
        List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
        if (autoMapping == null) {
            autoMapping = new ArrayList<>();
            final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
            for (String columnName : unmappedColumnNames) {
                String propertyName = columnName;
                if (columnPrefix != null && !columnPrefix.isEmpty()) {
                    // When columnPrefix is specified,
                    // ignore columns without the prefix.
                    if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                        propertyName = columnName.substring(columnPrefix.length());
                    } else {
                        continue;
                    }
                }
                final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
                if (property != null && metaObject.hasSetter(property)) {
                    if (resultMap.getMappedProperties().contains(property)) {
                        continue;
                    }
                    final Class<?> propertyType = metaObject.getSetterType(property);
                    if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
                        final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                        autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
                    } else {
                        configuration.getAutoMappingUnknownColumnBehavior()
                            .doAction(mappedStatement, columnName, property, propertyType);
                    }
                } else {
                    configuration.getAutoMappingUnknownColumnBehavior()
                        .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
                }
            }
            autoMappingsCache.put(mapKey, autoMapping);
        }
        return autoMapping;
    }

    /**
     * 自动映射处理，设置列名对应的属性值
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param columnPrefix
     * @return 是否存在可以处理的未映射字段
     * @throws SQLException
     */
    private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
        boolean foundValues = false;
        if (!autoMapping.isEmpty()) {
            for (UnMappedColumnAutoMapping mapping : autoMapping) {
                final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    metaObject.setValue(mapping.property, value);
                }
            }
        }
        return foundValues;
    }

    // MULTIPLE RESULT SETS

    /**
     * @param rs            结果集
     * @param parentMapping collection|association 节点对应的映射
     * @param rowValue      行的值
     * @throws SQLException
     */
    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        if (parents != null) {
            for (PendingRelation parent : parents) {
                // 循环待处理的关系，设置属性值
                if (parent != null && rowValue != null) {
                    linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
                }
            }
        }
    }

    /**
     * 添加待处理的映射关系
     *
     * @param rs               结果集
     * @param metaResultObject 列对应的 Java 对象
     * @param parentMapping    collection|association 映射
     * @throws SQLException
     */
    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
        // issue #255
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    /**
     * 创建 CacheKey
     *
     * @param rs
     * @param resultMapping
     * @param names
     * @param columns
     * @return
     * @throws SQLException
     */
    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }

    //
    // INSTANTIATION & CONSTRUCTOR MAPPING
    //

    /**
     * 创建返回结果
     *
     * @param rsw          结果集
     * @param resultMap    结果映射
     * @param lazyLoader   结果的属性加载器 Map
     * @param columnPrefix 列前缀
     * @return
     * @throws SQLException
     */
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        this.useConstructorMappings = false; // reset previous mapping result
        final List<Class<?>> constructorArgTypes = new ArrayList<>();
        final List<Object> constructorArgs = new ArrayList<>();
        Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                // issue gcode #109 && issue #149
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    // 创建懒加载的代理，用于懒加载属性
                    resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                    break;
                }
            }
        }
        this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
        return resultObject;
    }

    /**
     * 创建返回结果的实例
     *
     * @param rsw
     * @param resultMap
     * @param constructorArgTypes
     * @param constructorArgs
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
        throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
        if (hasTypeHandlerForResultObject(rsw, resultType)) {
            // 如果只返回一列或存在给定类型的类型处理器，则创建单列对应的 Java 对象
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        } else if (!constructorMappings.isEmpty()) {
            // 构造器的映射不为空，使用构造器创建对应
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            // 接口或者存在默认构造方法，直接创建
            return objectFactory.create(resultType);
        } else if (shouldApplyAutomaticMappings(resultMap, false)) {
            // 自动映射
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
        }
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    /**
     * 使用 constructor 节点指定的列的值创建对象
     *
     * @param rsw                 结果集
     * @param resultType          类型
     * @param constructorMappings constructor 节点下子节点抽象
     * @param constructorArgTypes 构造器参数类型
     * @param constructorArgs     构造器参数值
     * @param columnPrefix        列前缀
     * @return
     */
    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
        boolean foundValues = false;
        for (ResultMapping constructorMapping : constructorMappings) {
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            // 获取列对应的值
            final Object value;
            try {
                if (constructorMapping.getNestedQueryId() != null) {
                    // constructor 子节点存在嵌套查询，
                    value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) {
                    // constructor 子节点存在嵌套的 resultMap
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
                } else {
                    // 获取 constructor 节点的子节点指定的列的值
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException | SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        // 使用给定的构造器创建对象
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    /**
     * 使用构造方法创建对象
     *
     * @param rsw                 结果集
     * @param resultType          结果类型
     * @param constructorArgTypes 构造方法参数类型
     * @param constructorArgs     构造方法参数值
     * @return
     * @throws SQLException
     */
    private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
        final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
        final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
        if (defaultConstructor != null) {
            // 默认构造方法不为空，使用默认构造方法创建对象
            return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
        } else {
            for (Constructor<?> constructor : constructors) {
                if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
                    // 使用满足条件的构造方法创建对象
                    return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
                }
            }
        }
        throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    /**
     * 使用构造方法创建对象
     *
     * @param rsw
     * @param resultType
     * @param constructorArgTypes
     * @param constructorArgs
     * @param constructor
     * @return
     * @throws SQLException
     */
    private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
        boolean foundValues = false;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            Class<?> parameterType = constructor.getParameterTypes()[i];
            String columnName = rsw.getColumnNames().get(i);
            TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
            Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    /**
     * 查找默认的构造方法
     *
     * @param constructors
     * @return
     */
    private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
        if (constructors.length == 1) {
            return constructors[0];
        }

        for (final Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
                return constructor;
            }
        }
        return null;
    }

    /**
     * 是否允许构造方法使用类型处理器
     *
     * @param constructor
     * @param jdbcTypes
     * @return
     */
    private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length != jdbcTypes.size()) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 创建单列对应的 Java  对象
     *
     * @param rsw
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            // 结果映射不为空取第一个结果映射的列
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    //
    // NESTED QUERY
    //

    /**
     * 获取 constructor 节点的子节点嵌套查询的值
     *
     * @param rs                 结果集
     * @param constructorMapping constructor 节点的子节点抽象
     * @param columnPrefix       列前缀
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        // 准备嵌套查询的参数
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = constructorMapping.getJavaType();
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            value = resultLoader.loadResult();
        }
        return value;
    }

    /**
     * 获取嵌套查询的值
     *
     * @param rs
     * @param metaResultObject
     * @param propertyMapping
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = propertyMapping.getJavaType();
            if (executor.isCached(nestedQuery, key)) {
                // 如果已经缓存属性值，则从缓存中获取
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
                value = DEFERRED;
            } else {
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                    value = DEFERRED;
                } else {
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    /**
     * 准备嵌套查询的参数
     *
     * @param rs
     * @param resultMapping
     * @param parameterType
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    /**
     * 准备简单的嵌套查询参数
     *
     * @param rs            结果集
     * @param resultMapping 列映射
     * @param parameterType 查询的参数的类型
     * @param columnPrefix  列前缀
     * @return
     * @throws SQLException
     */
    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    /**
     * 准备组合的嵌套查询参数
     *
     * @param rs            结果集
     * @param resultMapping 列映射
     * @param parameterType 查询的参数的类型
     * @param columnPrefix  列前缀
     * @return
     * @throws SQLException
     */
    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        // 循环列
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                // 设置列的值到参数中
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    /**
     * 实例化参数对象
     *
     * @param parameterType
     * @return
     */
    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<>();
        } else if (ParamMap.class.equals(parameterType)) {
            return new HashMap<>(); // issue #649
        } else {
            return objectFactory.create(parameterType);
        }
    }

    //
    // DISCRIMINATOR
    //

    /**
     * 获取 resultMap 中 discriminator 对应列的值对应的 ResultMap
     *
     * @param rs
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        Set<String> pastDiscriminators = new HashSet<>();
        Discriminator discriminator = resultMap.getDiscriminator();
        while (discriminator != null) {
            // 先获取 discriminator 对应列的值
            final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
            // 然后获取值对应的 resultMap
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            if (configuration.hasResultMap(discriminatedMapId)) {
                resultMap = configuration.getResultMap(discriminatedMapId);
                Discriminator lastDiscriminator = discriminator;
                discriminator = resultMap.getDiscriminator();
                // resultMap -> discriminator -> resultMap 循环引用可导致 discriminator == lastDiscriminator
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    break;
                }
            } else {
                break;
            }
        }
        return resultMap;
    }

    /**
     * 获取 discriminator 对应列的值
     *
     * @param rs            结果集
     * @param discriminator 鉴别器
     * @param columnPrefix  列的前缀
     * @return
     * @throws SQLException
     */
    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {

        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    /**
     * 前缀添加到列名前
     *
     * @param columnName
     * @param prefix
     * @return
     */
    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    //
    // HANDLE NESTED RESULT MAPS
    //

    /**
     * 处理带嵌套 resultMap 的行
     *
     * @param rsw           结果集
     * @param resultMap     结果映射
     * @param resultHandler 结果处理器
     * @param rowBounds     分页信息
     * @param parentMapping collection|association 节点对应的映射
     * @throws SQLException
     */
    private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
        ResultSet resultSet = rsw.getResultSet();
        // 跳过分页
        skipRows(resultSet, rowBounds);
        Object rowValue = previousRowValue;
        while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
            // 获取 resultMap 中 discriminator 对应列的值对应的 ResultMap
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
                }
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
            } else {
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
                if (partialObject == null) {
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
                }
            }
        }
        if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
            storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
            previousRowValue = null;
        } else if (rowValue != null) {
            previousRowValue = rowValue;
        }
    }

    //
    // NESTED RESULT MAP (JOIN MAPPING)
    //

    /**
     * 设置嵌套的 resultMap 的值到属性中
     *
     * @param rsw          结果集
     * @param resultMap    结果映射
     * @param metaObject   行对应的值
     * @param parentPrefix 列前缀
     * @param parentRowKey 表示 resultMap 实例的 key
     * @param newObject
     * @return 是否设置了嵌套的 resultMap 的值到对象的属性中
     */
    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                // 列中存在嵌套的 resultMap
                try {
                    // 获取嵌套的 resultMap
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    if (resultMapping.getColumnPrefix() == null) {
                        // 仅当未为嵌套结果映射指定columnPrefix时，才尝试填充循环引用
                        // try to fill circular reference only when columnPrefix
                        // is not specified for the nested result map (issue #215)
                        Object ancestorObject = ancestorObjects.get(nestedResultMapId);
                        if (ancestorObject != null) {
                            if (newObject) {
                                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
                            }
                            continue;
                        }
                    }
                    // 嵌套的 resultMap 的 key
                    final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                    // 嵌套的 resultMap 和外层 resultMap 组合的 key
                    final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
                    // 获取缓存的嵌套的 resultMap 对应的值
                    Object rowValue = nestedResultObjects.get(combinedKey);
                    boolean knownValue = rowValue != null;
                    instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
                    if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
                        // 如果任意不为能空的列具有值，获取嵌套的 resultMap 的值
                        rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
                        if (rowValue != null && !knownValue) {
                            // 设置嵌套的 resultMap 的值到对象的属性中
                            linkObjects(metaObject, resultMapping, rowValue);
                            foundValues = true;
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
                }
            }
        }
        return foundValues;
    }

    /**
     * 获取列的前缀
     *
     * @param parentPrefix
     * @param resultMapping
     * @return
     */
    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    /**
     * 存在不为空的列
     *
     * @param resultMapping
     * @param columnPrefix
     * @param rsw
     * @return
     * @throws SQLException
     */
    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            ResultSet rs = rsw.getResultSet();
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    return true;
                }
            }
            return false;
        } else if (columnPrefix != null) {
            for (String columnName : rsw.getColumnNames()) {
                if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * 获取嵌套的 resultMap
     *
     * @param rs
     * @param nestedResultMapId
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    //
    // UNIQUE RESULT KEY
    //

    /**
     * 创建标识 resultMap 的 key
     *
     * @param resultMap
     * @param rsw
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMap.getId());
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        if (resultMappings.isEmpty()) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                createRowKeyForMap(rsw, cacheKey);
            } else {
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        if (cacheKey.getUpdateCount() < 2) {
            return CacheKey.NULL_CACHE_KEY;
        }
        return cacheKey;
    }

    /**
     * 将子 key 和父 key 结合到一起
     *
     * @param rowKey
     * @param parentRowKey
     * @return
     */
    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    /**
     * 获取表示返回结果标识的 ResultMapping 列表
     *
     * @param resultMap
     * @return
     */
    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.isEmpty()) {
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    /**
     * 从映射的 列中更新 key
     *
     * @param resultMap
     * @param rsw
     * @param cacheKey
     * @param resultMappings
     * @param columnPrefix
     * @throws SQLException
     */
    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.isSimple()) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null || configuration.isReturnInstanceForEmptyRow()) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    /**
     * 根据没有映射的列创建 key
     *
     * @param resultMap
     * @param rsw
     * @param cacheKey
     * @param columnPrefix
     * @throws SQLException
     */
    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    /**
     * 创建返回类型为 Map 的 key
     *
     * @param rsw
     * @param cacheKey
     * @throws SQLException
     */
    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    /**
     * 将列的值设置到对象属性中
     *
     * @param metaObject    对象
     * @param resultMapping 列映射
     * @param rowValue      列映射对应的行的值
     */
    private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
        if (collectionProperty != null) {
            // collection 节点，添加对象到集合中
            final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
            targetMetaObject.add(rowValue);
        } else {
            // association 节点，设置对象的属性值
            metaObject.setValue(resultMapping.getProperty(), rowValue);
        }
    }

    /**
     * 获取或实例化对象的集合属性
     *
     * @param resultMapping
     * @param metaObject
     * @return
     */
    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {
            // 对象属性值为 null
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {
                    // 如果对象属性类型为 Collection，创建并设置属性
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;
        }
        return null;
    }

    /**
     * 是否包含返回结果的类型处理器
     *
     * @param rsw
     * @param resultType
     * @return
     */
    private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
        if (rsw.getColumnNames().size() == 1) {
            return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
        }
        return typeHandlerRegistry.hasTypeHandler(resultType);
    }

}
