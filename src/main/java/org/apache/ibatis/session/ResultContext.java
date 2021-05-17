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
package org.apache.ibatis.session;

/**
 * @author Clinton Begin
 */
public interface ResultContext<T> {

    /**
     * 获取当前处理的查询结果
     *
     * @return
     */
    T getResultObject();

    /**
     * 当前已经读取查询结果的数量
     *
     * @return
     */
    int getResultCount();

    /**
     * 是否已经停止获取更多行
     *
     * @return
     */
    boolean isStopped();

    /**
     * 停止获取更多的行
     */
    void stop();

}
