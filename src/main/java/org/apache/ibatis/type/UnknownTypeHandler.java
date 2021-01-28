/**
 * Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;

/**
 * 不知道 Java 类型的处理器，设置参数时根据方法参数查找类型处理器设置参数，获取结果时根据 JDBC 的元信息查找类型处理器获取结果
 *
 * @author Clinton Begin
 */
public class UnknownTypeHandler extends BaseTypeHandler<Object> {

    private static final ObjectTypeHandler OBJECT_TYPE_HANDLER = new ObjectTypeHandler();

    // TODO Rename to 'configuration' after removing the 'configuration' property(deprecated property) on parent class
    private final Configuration config;

    private final Supplier<TypeHandlerRegistry> typeHandlerRegistrySupplier;

    /**
     * The constructor that pass a MyBatis configuration.
     *
     * @param configuration a MyBatis configuration
     * @since 3.5.4
     */
    public UnknownTypeHandler(Configuration configuration) {
        this.config = configuration;
        this.typeHandlerRegistrySupplier = configuration::getTypeHandlerRegistry;
    }

    /**
     * The constructor that pass the type handler registry.
     *
     * @param typeHandlerRegistry a type handler registry
     * @deprecated Since 3.5.4, please use the {@link #UnknownTypeHandler(Configuration)}.
     */
    @Deprecated
    public UnknownTypeHandler(TypeHandlerRegistry typeHandlerRegistry) {
        this.config = new Configuration();
        this.typeHandlerRegistrySupplier = () -> typeHandlerRegistry;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
        throws SQLException {
        TypeHandler handler = resolveTypeHandler(parameter, jdbcType);
        handler.setParameter(ps, i, parameter, jdbcType);
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName)
        throws SQLException {
        TypeHandler<?> handler = resolveTypeHandler(rs, columnName);
        return handler.getResult(rs, columnName);
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex)
        throws SQLException {
        TypeHandler<?> handler = resolveTypeHandler(rs.getMetaData(), columnIndex);
        if (handler == null || handler instanceof UnknownTypeHandler) {
            handler = OBJECT_TYPE_HANDLER;
        }
        return handler.getResult(rs, columnIndex);
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex)
        throws SQLException {
        return cs.getObject(columnIndex);
    }

    /**
     * 解析类型处理器
     *
     * @param parameter
     * @param jdbcType
     * @return
     */
    private TypeHandler<?> resolveTypeHandler(Object parameter, JdbcType jdbcType) {
        TypeHandler<?> handler;
        if (parameter == null) {
            handler = OBJECT_TYPE_HANDLER;
        } else {
            handler = typeHandlerRegistrySupplier.get().getTypeHandler(parameter.getClass(), jdbcType);
            // check if handler is null (issue #270)
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = OBJECT_TYPE_HANDLER;
            }
        }
        return handler;
    }

    /**
     * 解析类型处理器
     *
     * @param rs
     * @param column
     * @return
     */
    private TypeHandler<?> resolveTypeHandler(ResultSet rs, String column) {
        try {
            // 列名->列索引
            Map<String, Integer> columnIndexLookup;
            columnIndexLookup = new HashMap<>();
            ResultSetMetaData rsmd = rs.getMetaData();
            int count = rsmd.getColumnCount();
            boolean useColumnLabel = config.isUseColumnLabel();
            for (int i = 1; i <= count; i++) {
                String name = useColumnLabel ? rsmd.getColumnLabel(i) : rsmd.getColumnName(i);
                columnIndexLookup.put(name, i);
            }
            Integer columnIndex = columnIndexLookup.get(column);
            TypeHandler<?> handler = null;
            if (columnIndex != null) {
                handler = resolveTypeHandler(rsmd, columnIndex);
            }
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = OBJECT_TYPE_HANDLER;
            }
            return handler;
        } catch (SQLException e) {
            throw new TypeException("Error determining JDBC type for column " + column + ".  Cause: " + e, e);
        }
    }

    /**
     * 解析类型处理器
     *
     * @param rsmd
     * @param columnIndex
     * @return
     */
    private TypeHandler<?> resolveTypeHandler(ResultSetMetaData rsmd, Integer columnIndex) {
        TypeHandler<?> handler = null;
        JdbcType jdbcType = safeGetJdbcTypeForColumn(rsmd, columnIndex);
        Class<?> javaType = safeGetClassForColumn(rsmd, columnIndex);
        if (javaType != null && jdbcType != null) {
            handler = typeHandlerRegistrySupplier.get().getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
            handler = typeHandlerRegistrySupplier.get().getTypeHandler(javaType);
        } else if (jdbcType != null) {
            handler = typeHandlerRegistrySupplier.get().getTypeHandler(jdbcType);
        }
        return handler;
    }

    /**
     * 获取 JDBC 类型
     *
     * @param rsmd
     * @param columnIndex
     * @return
     */
    private JdbcType safeGetJdbcTypeForColumn(ResultSetMetaData rsmd, Integer columnIndex) {
        try {
            return JdbcType.forCode(rsmd.getColumnType(columnIndex));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取列索引对应的 Java 类型
     *
     * @param rsmd
     * @param columnIndex
     * @return
     */
    private Class<?> safeGetClassForColumn(ResultSetMetaData rsmd, Integer columnIndex) {
        try {
            return Resources.classForName(rsmd.getColumnClassName(columnIndex));
        } catch (Exception e) {
            return null;
        }
    }
}
