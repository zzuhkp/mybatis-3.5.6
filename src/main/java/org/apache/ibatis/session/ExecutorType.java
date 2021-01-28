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
 * Executor 类型
 *
 * @author Clinton Begin
 */
public enum ExecutorType {

    /**
     * 普通的执行器
     */
    SIMPLE,

    /**
     * 重用预处理语句（PreparedStatement）
     */
    REUSE,

    /**
     * 不仅重用语句还会执行批量更新
     */
    BATCH
}
