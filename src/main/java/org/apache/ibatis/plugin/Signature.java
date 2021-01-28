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
package org.apache.ibatis.plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插件要拦截的方法签名
 * The annotation that indicate the method signature.
 *
 * @author Clinton Begin
 * @see Intercepts
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Signature {

    /**
     * 要拦截的类型
     * <p>
     * Returns the java type.
     *
     * @return the java type
     */
    Class<?> type();

    /**
     * 要拦截的方法名称
     * <p>
     * Returns the method name.
     *
     * @return the method name
     */
    String method();

    /**
     * 要拦截的方法的参数
     * <p>
     * Returns java types for method argument.
     *
     * @return java types for method argument
     */
    Class<?>[] args();
}
