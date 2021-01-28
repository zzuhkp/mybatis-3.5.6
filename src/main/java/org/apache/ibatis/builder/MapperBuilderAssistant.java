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
package org.apache.ibatis.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * mapper 元数据对象构建工具类
 *
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

    /**
     * 命名空间，即 mapper 接口的全限定名，xml 配置
     */
    private String currentNamespace;

    /**
     * Mybatis 配置文件中 mappers->mapper 节点的 resource 或 url 属性
     */
    private final String resource;

    /**
     * 当前 mapper 使用的缓存
     */
    private Cache currentCache;

    /**
     * cache-ref 不能解析？
     */
    private boolean unresolvedCacheRef; // issue #676

    public MapperBuilderAssistant(Configuration configuration, String resource) {
        super(configuration);
        ErrorContext.instance().resource(resource);
        this.resource = resource;
    }

    public String getCurrentNamespace() {
        return currentNamespace;
    }

    public void setCurrentNamespace(String currentNamespace) {
        if (currentNamespace == null) {
            throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
        }

        if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
            throw new BuilderException("Wrong namespace. Expected '"
                + this.currentNamespace + "' but found '" + currentNamespace + "'.");
        }

        this.currentNamespace = currentNamespace;
    }

    /**
     * 添加当前的命名空间作为前缀
     *
     * @param base
     * @param isReference
     * @return
     */
    public String applyCurrentNamespace(String base, boolean isReference) {
        if (base == null) {
            return null;
        }
        if (isReference) {
            // is it qualified with any namespace yet?
            if (base.contains(".")) {
                return base;
            }
        } else {
            // is it qualified with this namespace yet?
            if (base.startsWith(currentNamespace + ".")) {
                return base;
            }
            if (base.contains(".")) {
                throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
            }
        }
        return currentNamespace + "." + base;
    }

    /**
     * 获取 namespace 指定的 Cache
     *
     * @param namespace
     * @return
     */
    public Cache useCacheRef(String namespace) {
        if (namespace == null) {
            throw new BuilderException("cache-ref element requires a namespace attribute.");
        }
        try {
            unresolvedCacheRef = true;
            Cache cache = configuration.getCache(namespace);
            if (cache == null) {
                throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
            }
            currentCache = cache;
            unresolvedCacheRef = false;
            return cache;
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
        }
    }

    /**
     * 创建一个新的缓存对象
     *
     * @param typeClass     缓存类
     * @param evictionClass 过期策略类
     * @param flushInterval 刷新间隔
     * @param size          缓存数量
     * @param readWrite     缓存的对象是否可读可写
     * @param blocking
     * @param props
     * @return
     */
    public Cache useNewCache(Class<? extends Cache> typeClass,
                             Class<? extends Cache> evictionClass,
                             Long flushInterval,
                             Integer size,
                             boolean readWrite,
                             boolean blocking,
                             Properties props) {
        Cache cache = new CacheBuilder(currentNamespace)
            .implementation(valueOrDefault(typeClass, PerpetualCache.class))
            .addDecorator(valueOrDefault(evictionClass, LruCache.class))
            .clearInterval(flushInterval)
            .size(size)
            .readWrite(readWrite)
            .blocking(blocking)
            .properties(props)
            .build();
        configuration.addCache(cache);
        currentCache = cache;
        return cache;
    }

    /**
     * 添加 parameterMap 信息到配置
     *
     * @param id
     * @param parameterClass
     * @param parameterMappings
     * @return
     */
    public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
        id = applyCurrentNamespace(id, false);
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
        configuration.addParameterMap(parameterMap);
        return parameterMap;
    }

    /**
     * 创建 ParameterMapping
     *
     * @param parameterType
     * @param property
     * @param javaType
     * @param jdbcType
     * @param resultMap
     * @param parameterMode
     * @param typeHandler
     * @param numericScale
     * @return
     */
    public ParameterMapping buildParameterMapping(
        Class<?> parameterType,
        String property,
        Class<?> javaType,
        JdbcType jdbcType,
        String resultMap,
        ParameterMode parameterMode,
        Class<? extends TypeHandler<?>> typeHandler,
        Integer numericScale) {
        resultMap = applyCurrentNamespace(resultMap, true);

        // Class parameterType = parameterMapBuilder.type();
        Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

        return new ParameterMapping.Builder(configuration, property, javaTypeClass)
            .jdbcType(jdbcType)
            .resultMapId(resultMap)
            .mode(parameterMode)
            .numericScale(numericScale)
            .typeHandler(typeHandlerInstance)
            .build();
    }

    /**
     * 添加 ResultMap 到 Configuration
     *
     * @param id
     * @param type
     * @param extend
     * @param discriminator
     * @param resultMappings
     * @param autoMapping
     * @return
     */
    public ResultMap addResultMap(
        String id,
        Class<?> type,
        String extend,
        Discriminator discriminator,
        List<ResultMapping> resultMappings,
        Boolean autoMapping) {
        id = applyCurrentNamespace(id, false);
        extend = applyCurrentNamespace(extend, true);

        if (extend != null) {
            if (!configuration.hasResultMap(extend)) {
                throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
            }
            ResultMap resultMap = configuration.getResultMap(extend);
            List<ResultMapping> extendedResultMappings = new ArrayList<>(resultMap.getResultMappings());
            extendedResultMappings.removeAll(resultMappings);
            // Remove parent constructor if this resultMap declares a constructor.
            boolean declaresConstructor = false;
            for (ResultMapping resultMapping : resultMappings) {
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    declaresConstructor = true;
                    break;
                }
            }
            if (declaresConstructor) {
                extendedResultMappings.removeIf(resultMapping -> resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR));
            }
            resultMappings.addAll(extendedResultMappings);
        }
        ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
            .discriminator(discriminator)
            .build();
        configuration.addResultMap(resultMap);
        return resultMap;
    }

    /**
     * 构建 Discriminator
     *
     * @param resultType
     * @param column
     * @param javaType
     * @param jdbcType
     * @param typeHandler
     * @param discriminatorMap
     * @return
     */
    public Discriminator buildDiscriminator(
        Class<?> resultType,
        String column,
        Class<?> javaType,
        JdbcType jdbcType,
        Class<? extends TypeHandler<?>> typeHandler,
        Map<String, String> discriminatorMap) {
        ResultMapping resultMapping = buildResultMapping(
            resultType,
            null,
            column,
            javaType,
            jdbcType,
            null,
            null,
            null,
            null,
            typeHandler,
            new ArrayList<>(),
            null,
            null,
            false);
        Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
        for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
            String resultMap = e.getValue();
            resultMap = applyCurrentNamespace(resultMap, true);
            namespaceDiscriminatorMap.put(e.getKey(), resultMap);
        }
        return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
    }

    /**
     * 添加 MappedStatement 到 Configuration
     *
     * @param id             select|insert|update|delete 节点 id 属性值
     * @param sqlSource      解析出的 SQL
     * @param statementType  select|insert|update|delete 节点 statementType 属性值对应的语句类型，默认为 PREPARED
     * @param sqlCommandType select|insert|update|delete 节点表示哪一种 SQL 语句类型，如 select 查询
     * @param fetchSize      select|insert|update|delete 节点 fetchSize 属性值，表示驱动程序每次批量返回的结果行数
     * @param timeout        select|insert|update|delete 节点 timeout 属性值，表示驱动程序等待数据库返回结果的秒数
     * @param parameterMap   select|insert|update|delete 节点 parameterMap 属性值，已废弃，未来版本可能会移除
     * @param parameterType  select|insert|update|delete 节点 parameterType 属性值对应的 Java 类
     * @param resultMap      select|insert|update|delete 节点 resultMap 属性值
     * @param resultType     select|insert|update|delete 节点 resultType 属性值
     * @param resultSetType  select|insert|update|delete 节点 resultSetType 属性值解析出的结果集类型
     * @param flushCache     select|insert|update|delete 节点 flushCache 属性值，表示是否清空本地缓存和二级缓存，默认非 select 语句清空缓存
     * @param useCache       select|insert|update|delete 节点 useCache 属性值，表示是否将该语句的结果缓存到二级缓存，默认 select 语句缓存
     * @param resultOrdered  select 节点 resultOrdered 属性值，
     *                       仅针对嵌套结果 select 语句：如果为 true，将会假设包含了嵌套结果集或是分组，当返回一个主结果行时，就不会产生对前面结果集的引用
     * @param keyGenerator   主键生成器，根据 select 节点 useGeneratedKeys 属性值或 selectKey 节点创建
     * @param keyProperty    insert|update 节点 keyProperty 属性值，指能够唯一识别对象的属性，
     *                       MyBatis 会使用 getGeneratedKeys 的返回值或 insert 语句的 selectKey 子元素设置它的值。
     *                       如果生成列不止一个，可以用逗号分隔多个属性名称。
     * @param keyColumn      insert|update 节点 keyColumn 属性值，
     *                       设置生成键值在表中的列名，在某些数据库（像 PostgreSQL）中，当主键列不是表中的第一列的时候，是必须设置的。
     *                       如果生成列不止一个，可以用逗号分隔多个属性名称。
     * @param databaseId     select|insert|update|delete 节点 databaseId 属性值，表示语句适用的数据库
     * @param lang           语言驱动，用于解析 SQL
     * @param resultSets     select 节点 resultSets 属性值
     *                       仅适用于多结果集的情况。
     *                       它将列出语句执行后返回的结果集并赋予每个结果集一个名称，多个名称之间以逗号分隔。
     * @return
     * @see java.sql.Statement#setQueryTimeout(int)
     * @see java.sql.Statement#setFetchSize(int)
     */
    public MappedStatement addMappedStatement(
        String id,
        SqlSource sqlSource,
        StatementType statementType,
        SqlCommandType sqlCommandType,
        Integer fetchSize,
        Integer timeout,
        String parameterMap,
        Class<?> parameterType,
        String resultMap,
        Class<?> resultType,
        ResultSetType resultSetType,
        boolean flushCache,
        boolean useCache,
        boolean resultOrdered,
        KeyGenerator keyGenerator,
        String keyProperty,
        String keyColumn,
        String databaseId,
        LanguageDriver lang,
        String resultSets) {

        if (unresolvedCacheRef) {
            throw new IncompleteElementException("Cache-ref not yet resolved");
        }

        id = applyCurrentNamespace(id, false);
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

        MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
            .resource(resource)
            .fetchSize(fetchSize)
            .timeout(timeout)
            .statementType(statementType)
            .keyGenerator(keyGenerator)
            .keyProperty(keyProperty)
            .keyColumn(keyColumn)
            .databaseId(databaseId)
            .lang(lang)
            .resultOrdered(resultOrdered)
            .resultSets(resultSets)
            .resultMaps(getStatementResultMaps(resultMap, resultType, id))
            .resultSetType(resultSetType)
            .flushCacheRequired(valueOrDefault(flushCache, !isSelect))
            .useCache(valueOrDefault(useCache, isSelect))
            .cache(currentCache);

        ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
        if (statementParameterMap != null) {
            statementBuilder.parameterMap(statementParameterMap);
        }

        MappedStatement statement = statementBuilder.build();
        configuration.addMappedStatement(statement);
        return statement;
    }

    /**
     * Backward compatibility signature 'addMappedStatement'.
     *
     * @param id             the id
     * @param sqlSource      the sql source
     * @param statementType  the statement type
     * @param sqlCommandType the sql command type
     * @param fetchSize      the fetch size
     * @param timeout        the timeout
     * @param parameterMap   the parameter map
     * @param parameterType  the parameter type
     * @param resultMap      the result map
     * @param resultType     the result type
     * @param resultSetType  the result set type
     * @param flushCache     the flush cache
     * @param useCache       the use cache
     * @param resultOrdered  the result ordered
     * @param keyGenerator   the key generator
     * @param keyProperty    the key property
     * @param keyColumn      the key column
     * @param databaseId     the database id
     * @param lang           the lang
     * @return the mapped statement
     */
    public MappedStatement addMappedStatement(String id, SqlSource sqlSource, StatementType statementType,
                                              SqlCommandType sqlCommandType, Integer fetchSize, Integer timeout, String parameterMap, Class<?> parameterType,
                                              String resultMap, Class<?> resultType, ResultSetType resultSetType, boolean flushCache, boolean useCache,
                                              boolean resultOrdered, KeyGenerator keyGenerator, String keyProperty, String keyColumn, String databaseId,
                                              LanguageDriver lang) {
        return addMappedStatement(
            id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
            parameterMap, parameterType, resultMap, resultType, resultSetType,
            flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
            keyColumn, databaseId, lang, null);
    }

    private <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private ParameterMap getStatementParameterMap(
        String parameterMapName,
        Class<?> parameterTypeClass,
        String statementId) {
        parameterMapName = applyCurrentNamespace(parameterMapName, true);
        ParameterMap parameterMap = null;
        if (parameterMapName != null) {
            try {
                parameterMap = configuration.getParameterMap(parameterMapName);
            } catch (IllegalArgumentException e) {
                throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
            }
        } else if (parameterTypeClass != null) {
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            parameterMap = new ParameterMap.Builder(
                configuration,
                statementId + "-Inline",
                parameterTypeClass,
                parameterMappings).build();
        }
        return parameterMap;
    }

    private List<ResultMap> getStatementResultMaps(
        String resultMap,
        Class<?> resultType,
        String statementId) {
        resultMap = applyCurrentNamespace(resultMap, true);

        List<ResultMap> resultMaps = new ArrayList<>();
        if (resultMap != null) {
            String[] resultMapNames = resultMap.split(",");
            for (String resultMapName : resultMapNames) {
                try {
                    resultMaps.add(configuration.getResultMap(resultMapName.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IncompleteElementException("Could not find result map '" + resultMapName + "' referenced from '" + statementId + "'", e);
                }
            }
        } else if (resultType != null) {
            ResultMap inlineResultMap = new ResultMap.Builder(
                configuration,
                statementId + "-Inline",
                resultType,
                new ArrayList<>(),
                null).build();
            resultMaps.add(inlineResultMap);
        }
        return resultMaps;
    }

    /**
     * 解析节点元数据构建 ResultMapping
     * <p>
     * 可选的节点为：/mapper/resultMap/constructor 的子节点(idArg|arg) 或
     * /mapper/resultMap 节点下非 discriminator 的其他子节点(association|collection|case|id|result)
     *
     * @param resultType
     * @param property
     * @param column
     * @param javaType
     * @param jdbcType
     * @param nestedSelect
     * @param nestedResultMap
     * @param notNullColumn
     * @param columnPrefix
     * @param typeHandler
     * @param flags
     * @param resultSet
     * @param foreignColumn
     * @param lazy
     * @return
     */
    public ResultMapping buildResultMapping(
        Class<?> resultType,
        String property,
        String column,
        Class<?> javaType,
        JdbcType jdbcType,
        String nestedSelect,
        String nestedResultMap,
        String notNullColumn,
        String columnPrefix,
        Class<? extends TypeHandler<?>> typeHandler,
        List<ResultFlag> flags,
        String resultSet,
        String foreignColumn,
        boolean lazy) {
        Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
        List<ResultMapping> composites;
        if ((nestedSelect == null || nestedSelect.isEmpty()) && (foreignColumn == null || foreignColumn.isEmpty())) {
            composites = Collections.emptyList();
        } else {
            composites = parseCompositeColumnName(column);
        }
        return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
            .jdbcType(jdbcType)
            .nestedQueryId(applyCurrentNamespace(nestedSelect, true))
            .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
            .resultSet(resultSet)
            .typeHandler(typeHandlerInstance)
            .flags(flags == null ? new ArrayList<>() : flags)
            .composites(composites)
            .notNullColumns(parseMultipleColumnNames(notNullColumn))
            .columnPrefix(columnPrefix)
            .foreignColumn(foreignColumn)
            .lazy(lazy)
            .build();
    }

    /**
     * Backward compatibility signature 'buildResultMapping'.
     *
     * @param resultType      the result type
     * @param property        the property
     * @param column          the column
     * @param javaType        the java type
     * @param jdbcType        the jdbc type
     * @param nestedSelect    the nested select
     * @param nestedResultMap the nested result map
     * @param notNullColumn   the not null column
     * @param columnPrefix    the column prefix
     * @param typeHandler     the type handler
     * @param flags           the flags
     * @return the result mapping
     */
    public ResultMapping buildResultMapping(Class<?> resultType, String property, String column, Class<?> javaType,
                                            JdbcType jdbcType, String nestedSelect, String nestedResultMap, String notNullColumn, String columnPrefix,
                                            Class<? extends TypeHandler<?>> typeHandler, List<ResultFlag> flags) {
        return buildResultMapping(
            resultType, property, column, javaType, jdbcType, nestedSelect,
            nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
    }

    /**
     * Gets the language driver.
     *
     * @param langClass the lang class
     * @return the language driver
     * @deprecated Use {@link Configuration#getLanguageDriver(Class)}
     */
    @Deprecated
    public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
        return configuration.getLanguageDriver(langClass);
    }

    private Set<String> parseMultipleColumnNames(String columnName) {
        Set<String> columns = new HashSet<>();
        if (columnName != null) {
            if (columnName.indexOf(',') > -1) {
                StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
                while (parser.hasMoreTokens()) {
                    String column = parser.nextToken();
                    columns.add(column);
                }
            } else {
                columns.add(columnName);
            }
        }
        return columns;
    }

    /**
     * 解析复合的列
     *
     * @param columnName
     * @return
     */
    private List<ResultMapping> parseCompositeColumnName(String columnName) {
        List<ResultMapping> composites = new ArrayList<>();
        if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
            StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
            while (parser.hasMoreTokens()) {
                String property = parser.nextToken();
                String column = parser.nextToken();
                ResultMapping complexResultMapping = new ResultMapping.Builder(
                    configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
                composites.add(complexResultMapping);
            }
        }
        return composites;
    }

    /**
     * 解析 Java 类型
     *
     * @param resultType
     * @param property
     * @param javaType
     * @return
     */
    private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
        if (javaType == null && property != null) {
            try {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getSetterType(property);
            } catch (Exception e) {
                // ignore, following null check statement will deal with the situation
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    /**
     * 解析 Java 类型
     *
     * @param resultType
     * @param property
     * @param javaType
     * @param jdbcType
     * @return
     */
    private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
        if (javaType == null) {
            if (JdbcType.CURSOR.equals(jdbcType)) {
                javaType = java.sql.ResultSet.class;
            } else if (Map.class.isAssignableFrom(resultType)) {
                javaType = Object.class;
            } else {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getGetterType(property);
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

}
