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
package org.apache.ibatis.scripting;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;

/**
 * 动态 SQL 生成使用的脚本语言驱动
 */
public interface LanguageDriver {

    /**
     * 创建一个将实际参数传递给 JDBC 语句的 ParameterHandler
     * <p>
     * Creates a {@link ParameterHandler} that passes the actual parameters to the the JDBC statement.
     *
     * @param mappedStatement 正在被执行的语句
     *                        The mapped statement that is being executed
     * @param parameterObject 参数
     *                        The input parameter object (can be null)
     * @param boundSql        执行动态语言后生成的 SQL
     *                        The resulting SQL once the dynamic language has been executed.
     * @return the parameter handler
     * @author Frank D. Martinez [mnesarco]
     * @see DefaultParameterHandler
     */
    ParameterHandler createParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql);

    /**
     * 创建一个持有从 mapper xml 文件读取的语句的 SqlSource,
     * 启动程序时，当从类或者 xml 文件读取 MappedStatement 时被调用
     * <p>
     * Creates an {@link SqlSource} that will hold the statement read from a mapper xml file.
     * It is called during startup, when the mapped statement is read from a class or an xml file.
     *
     * @param configuration Mybatis 配置
     *                      The MyBatis configuration
     * @param script        mapper xml 文件中的 select|insert|update|delete 节点
     *                      XNode parsed from a XML file
     * @param parameterType 从 mapper 方法或者 xml 文件 parameterType 属性获取的参数类型
     *                      input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
     * @return the sql source
     */
    SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType);

    /**
     * 创建一个持有从 mapper xml 文件读取的语句的 SqlSource,
     * 启动程序时，当从类或者 xml 文件读取 MappedStatement 时被调用
     * <p>
     * Creates an {@link SqlSource} that will hold the statement read from an annotation.
     * It is called during startup, when the mapped statement is read from a class or an xml file.
     *
     * @param configuration Mybatis 配置
     *                      The MyBatis configuration
     * @param script        注解的内容
     *                      The content of the annotation
     * @param parameterType 从 mapper 方法或者 xml 文件 parameterType 属性获取的参数类型
     *                      input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
     * @return the sql source
     */
    SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType);

}
