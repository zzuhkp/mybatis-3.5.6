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
package org.apache.ibatis.reflection.wrapper;

import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 对象包装器，提供获取对象属性信息、设置属性、实例化属性值等方法
 *
 * @author Clinton Begin
 */
public interface ObjectWrapper {

    /**
     * 获取属性值
     *
     * @param prop
     * @return
     */
    Object get(PropertyTokenizer prop);

    /**
     * 设置属性值
     *
     * @param prop
     * @param value
     */
    void set(PropertyTokenizer prop, Object value);

    /**
     * 查找对象的属性
     *
     * @param name                属性名称，忽略大小写
     * @param useCamelCaseMapping 是否转为驼峰命名
     * @return
     */
    String findProperty(String name, boolean useCamelCaseMapping);

    /**
     * 获取通过 getter 方法获取到的属性名称
     *
     * @return
     */
    String[] getGetterNames();

    /**
     * 获取通过 setter 方法获取到的属性名称
     *
     * @return
     */
    String[] getSetterNames();

    /**
     * 获取属性名称对应的 setter 方法参数类型
     *
     * @param name
     * @return
     */
    Class<?> getSetterType(String name);

    /**
     * 获取属性名称对应的 getter 方法返回值类型
     *
     * @param name
     * @return
     */
    Class<?> getGetterType(String name);

    /**
     * 给定属性是否具有对应的 setter 方法
     *
     * @param name
     * @return
     */
    boolean hasSetter(String name);

    /**
     * 给定属性是否具有对应的 getter 方法
     *
     * @param name
     * @return
     */
    boolean hasGetter(String name);

    /**
     * 实例化属性值对象
     *
     * @param name
     * @param prop
     * @param objectFactory
     * @return
     */
    MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

    /**
     * 包装的对象是否为集合
     *
     * @return
     */
    boolean isCollection();

    /**
     * 将元素添加到包装的集合对象中
     *
     * @param element
     */
    void add(Object element);

    /**
     * 将列表中的元素添加到包装的集合元素中
     *
     * @param element
     * @param <E>
     */
    <E> void addAll(List<E> element);

}
