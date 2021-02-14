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
package org.apache.ibatis.cursor.defaults;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetWrapper;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * This is the default implementation of a MyBatis Cursor.
 * This implementation is not thread safe.
 *
 * @author Guillaume Darmont / guillaume@dropinocean.com
 */
public class DefaultCursor<T> implements Cursor<T> {

    // ResultSetHandler stuff
    /**
     * 结果集处理器
     */
    private final DefaultResultSetHandler resultSetHandler;

    /**
     * 结果映射
     */
    private final ResultMap resultMap;

    /**
     * 结果集
     */
    private final ResultSetWrapper rsw;

    /**
     * 分页信息
     */
    private final RowBounds rowBounds;


    protected final ObjectWrapperResultHandler<T> objectWrapperResultHandler = new ObjectWrapperResultHandler<>();

    /**
     * 迭代器
     */
    private final CursorIterator cursorIterator = new CursorIterator();

    /**
     * 是否已经获取过迭代器
     */
    private boolean iteratorRetrieved;

    private CursorStatus status = CursorStatus.CREATED;
    private int indexWithRowBound = -1;

    /**
     * 游标状态
     */
    private enum CursorStatus {

        /**
         * A freshly created cursor, database ResultSet consuming has not started.
         */
        CREATED,
        /**
         * A cursor currently in use, database ResultSet consuming has started.
         */
        OPEN,
        /**
         * A closed cursor, not fully consumed.
         */
        CLOSED,
        /**
         * A fully consumed cursor, a consumed cursor is always closed.
         */
        CONSUMED
    }

    public DefaultCursor(DefaultResultSetHandler resultSetHandler, ResultMap resultMap, ResultSetWrapper rsw, RowBounds rowBounds) {
        this.resultSetHandler = resultSetHandler;
        this.resultMap = resultMap;
        this.rsw = rsw;
        this.rowBounds = rowBounds;
    }

    @Override
    public boolean isOpen() {
        return status == CursorStatus.OPEN;
    }

    @Override
    public boolean isConsumed() {
        return status == CursorStatus.CONSUMED;
    }

    @Override
    public int getCurrentIndex() {
        return rowBounds.getOffset() + cursorIterator.iteratorIndex;
    }

    @Override
    public Iterator<T> iterator() {
        if (iteratorRetrieved) {
            throw new IllegalStateException("Cannot open more than one iterator on a Cursor");
        }
        if (isClosed()) {
            throw new IllegalStateException("A Cursor is already closed.");
        }
        iteratorRetrieved = true;
        return cursorIterator;
    }

    @Override
    public void close() {
        if (isClosed()) {
            return;
        }

        ResultSet rs = rsw.getResultSet();
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        } finally {
            status = CursorStatus.CLOSED;
        }
    }

    /**
     * 取下一条记录
     *
     * @return
     */
    protected T fetchNextUsingRowBound() {
        T result = fetchNextObjectFromDatabase();
        while (objectWrapperResultHandler.fetched && indexWithRowBound < rowBounds.getOffset()) {
            // 取到数据并且已经取到的数量小于偏移量
            result = fetchNextObjectFromDatabase();
        }
        return result;
    }

    protected T fetchNextObjectFromDatabase() {
        if (isClosed()) {
            return null;
        }

        try {
            objectWrapperResultHandler.fetched = false;
            status = CursorStatus.OPEN;
            if (!rsw.getResultSet().isClosed()) {
                resultSetHandler.handleRowValues(rsw, resultMap, objectWrapperResultHandler, RowBounds.DEFAULT, null);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        T next = objectWrapperResultHandler.result;
        if (objectWrapperResultHandler.fetched) {
            indexWithRowBound++;
        }
        // No more object or limit reached
        if (!objectWrapperResultHandler.fetched || getReadItemsCount() == rowBounds.getOffset() + rowBounds.getLimit()) {
            close();
            status = CursorStatus.CONSUMED;
        }
        objectWrapperResultHandler.result = null;

        return next;
    }

    /**
     * 游标是否已经关闭
     *
     * @return
     */
    private boolean isClosed() {
        return status == CursorStatus.CLOSED || status == CursorStatus.CONSUMED;
    }

    /**
     * 获取已经读取的记录数量
     *
     * @return
     */
    private int getReadItemsCount() {
        return indexWithRowBound + 1;
    }

    protected static class ObjectWrapperResultHandler<T> implements ResultHandler<T> {

        protected T result;

        protected boolean fetched;

        @Override
        public void handleResult(ResultContext<? extends T> context) {
            // 取一条就停止取数据，达到每次迭代一条记录的目的，下次获取的是 ResultSet 的下一条记录，节约内存
            this.result = context.getResultObject();
            context.stop();
            fetched = true;
        }
    }

    protected class CursorIterator implements Iterator<T> {

        /**
         * 下次迭代要获取的对象
         * Holder for the next object to be returned.
         */
        T object;

        /**
         * 表示某次迭代的索引
         * <p>
         * Index of objects returned using next(), and as such, visible to users.
         */
        int iteratorIndex = -1;

        @Override
        public boolean hasNext() {
            if (!objectWrapperResultHandler.fetched) {
                object = fetchNextUsingRowBound();
            }
            return objectWrapperResultHandler.fetched;
        }

        @Override
        public T next() {
            // Fill next with object fetched from hasNext()
            T next = object;

            if (!objectWrapperResultHandler.fetched) {
                // 直接调用 next 方法执行
                next = fetchNextUsingRowBound();
            }

            if (objectWrapperResultHandler.fetched) {
                objectWrapperResultHandler.fetched = false;
                object = null;
                iteratorIndex++;
                return next;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove element from Cursor");
        }
    }
}
