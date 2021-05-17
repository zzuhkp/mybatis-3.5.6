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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * 缓存装饰器，获取对象时会打印日志，对命中率进行简单的统计 |
 *
 * @author Clinton Begin
 */
public class LoggingCache implements Cache {

    /**
     * 日志对象
     */
    private final Log log;

    /**
     * 代理的缓存对象
     */
    private final Cache delegate;

    /**
     * 请求获取对象的数量
     */
    protected int requests = 0;

    /**
     * 命中数量
     */
    protected int hits = 0;

    public LoggingCache(Cache delegate) {
        this.delegate = delegate;
        this.log = LogFactory.getLog(getId());
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    @Override
    public void putObject(Object key, Object object) {
        delegate.putObject(key, object);
    }

    @Override
    public Object getObject(Object key) {
        requests++;
        final Object value = delegate.getObject(key);
        if (value != null) {
            hits++;
        }
        if (log.isDebugEnabled()) {
            log.debug("Cache Hit Ratio [" + getId() + "]: " + getHitRatio());
        }
        return value;
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    /**
     * 获取命中率
     *
     * @return
     */
    private double getHitRatio() {
        return (double) hits / (double) requests;
    }

}
