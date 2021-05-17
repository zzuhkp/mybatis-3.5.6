/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * SQL 执行器
 *
 * @author Clinton Begin
 */
public interface Executor {

    ResultHandler NO_RESULT_HANDLER = null;

    /**
     * 插入或更新
     *
     * @param ms
     * @param parameter
     * @return
     * @throws SQLException
     */
    int update(MappedStatement ms, Object parameter) throws SQLException;

    /**
     * 查询列表
     *
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param resultHandler
     * @param cacheKey
     * @param boundSql
     * @param <E>
     * @return
     * @throws SQLException
     */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;

    /**
     * 查询列表
     *
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param resultHandler
     * @param <E>
     * @return
     * @throws SQLException
     */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;

    <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException;

    /**
     * 刷新批处理的 SQL 到数据库
     *
     * @return
     * @throws SQLException
     */
    List<BatchResult> flushStatements() throws SQLException;

    /**
     * 提交事务
     *
     * @param required
     * @throws SQLException
     */
    void commit(boolean required) throws SQLException;

    /**
     * 回滚事务
     *
     * @param required
     * @throws SQLException
     */
    void rollback(boolean required) throws SQLException;

    /**
     * 创建一个缓存的 key
     *
     * @param ms
     * @param parameterObject
     * @param rowBounds
     * @param boundSql
     * @return
     */
    CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);

    /**
     * 语句是否被缓存
     *
     * @param ms
     * @param key
     * @return
     */
    boolean isCached(MappedStatement ms, CacheKey key);

    /**
     * 清空 Executor 中缓存的查询结果
     */
    void clearLocalCache();

    /**
     * 从缓存中加载对象的属性值，或记录要从缓存中获取的属性
     *
     * @param ms           映射的语句
     * @param resultObject 返回的结果
     * @param property     结果的属性
     * @param key          语句对应的缓存 key
     * @param targetType   属性的类型
     */
    void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);

    /**
     * 获取事务对象
     *
     * @return
     */
    Transaction getTransaction();

    /**
     * 关闭连接
     *
     * @param forceRollback
     */
    void close(boolean forceRollback);

    /**
     * 连接是否已关闭
     *
     * @return
     */
    boolean isClosed();

    /**
     * 设置当前执行器的包装器
     *
     * @param executor
     */
    void setExecutorWrapper(Executor executor);

}
