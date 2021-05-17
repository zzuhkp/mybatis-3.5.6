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
package org.apache.ibatis.io;

import java.io.InputStream;
import java.net.URL;

/**
 * 包装了多个 ClassLoader 的类
 * <p>
 * A class to wrap access to multiple class loaders making them work as one
 *
 * @author Clinton Begin
 */
public class ClassLoaderWrapper {

    /**
     * 默认的类加载器
     * <p>
     * 包可见级别，Resources 可直接设置值
     */
    ClassLoader defaultClassLoader;

    /**
     * 系统类加载器
     */
    ClassLoader systemClassLoader;

    ClassLoaderWrapper() {
        try {
            systemClassLoader = ClassLoader.getSystemClassLoader();
        } catch (SecurityException ignored) {
            // AccessControlException on Google App Engine
        }
    }

    /**
     * 获取 URL
     * <p>
     * Get a resource as a URL using the current class path
     *
     * @param resource - the resource to locate
     * @return the resource or null
     */
    public URL getResourceAsURL(String resource) {
        return getResourceAsURL(resource, getClassLoaders(null));
    }

    /**
     * 获取类路径下的 URL
     * <p>
     * Get a resource from the classpath, starting with a specific class loader
     *
     * @param resource    - the resource to find
     * @param classLoader - the first classloader to try
     * @return the stream or null
     */
    public URL getResourceAsURL(String resource, ClassLoader classLoader) {
        return getResourceAsURL(resource, getClassLoaders(classLoader));
    }

    /**
     * 获取 InputStream
     * <p>
     * Get a resource from the classpath
     *
     * @param resource - the resource to find
     * @return the stream or null
     */
    public InputStream getResourceAsStream(String resource) {
        return getResourceAsStream(resource, getClassLoaders(null));
    }

    /**
     * 获取 InputStream
     * <p>
     * Get a resource from the classpath, starting with a specific class loader
     *
     * @param resource    - the resource to find
     * @param classLoader - the first class loader to try
     * @return the stream or null
     */
    public InputStream getResourceAsStream(String resource, ClassLoader classLoader) {
        return getResourceAsStream(resource, getClassLoaders(classLoader));
    }

    /**
     * 从类路径下加载类
     * <p>
     * Find a class on the classpath (or die trying)
     *
     * @param name - the class to look for
     * @return - the class
     * @throws ClassNotFoundException Duh.
     */
    public Class<?> classForName(String name) throws ClassNotFoundException {
        return classForName(name, getClassLoaders(null));
    }

    /**
     * 从类路径下加载类
     * <p>
     * Find a class on the classpath, starting with a specific classloader (or die trying)
     *
     * @param name        - the class to look for
     * @param classLoader - the first classloader to try
     * @return - the class
     * @throws ClassNotFoundException Duh.
     */
    public Class<?> classForName(String name, ClassLoader classLoader) throws ClassNotFoundException {
        return classForName(name, getClassLoaders(classLoader));
    }

    /**
     * 获取 InputStream
     * <p>
     * Try to get a resource from a group of classloaders
     *
     * @param resource    - the resource to get
     * @param classLoader - the classloaders to examine
     * @return the resource or null
     */
    InputStream getResourceAsStream(String resource, ClassLoader[] classLoader) {
        for (ClassLoader cl : classLoader) {
            if (null != cl) {

                // try to find the resource as passed
                InputStream returnValue = cl.getResourceAsStream(resource);

                // now, some class loaders want this leading "/", so we'll add it and try again if we didn't find the resource
                if (null == returnValue) {
                    returnValue = cl.getResourceAsStream("/" + resource);
                }

                if (null != returnValue) {
                    return returnValue;
                }
            }
        }
        return null;
    }

    /**
     * 获取 URL
     * <p>
     * Get a resource as a URL using the current class path
     *
     * @param resource    - the resource to locate
     * @param classLoader - the class loaders to examine
     * @return the resource or null
     */
    URL getResourceAsURL(String resource, ClassLoader[] classLoader) {

        URL url;

        for (ClassLoader cl : classLoader) {

            if (null != cl) {

                // look for the resource as passed in...
                url = cl.getResource(resource);

                // ...but some class loaders want this leading "/", so we'll add it
                // and try again if we didn't find the resource
                if (null == url) {
                    url = cl.getResource("/" + resource);
                }

                // "It's always in the last place I look for it!"
                // ... because only an idiot would keep looking for it after finding it, so stop looking already.
                if (null != url) {
                    return url;
                }

            }

        }

        // didn't find it anywhere.
        return null;

    }

    /**
     * 使用给定的类加载器加载类
     * <p>
     * Attempt to load a class from a group of classloaders
     *
     * @param name        - the class to load
     * @param classLoader - the group of classloaders to examine
     * @return the class
     * @throws ClassNotFoundException - Remember the wisdom of Judge Smails: Well, the world needs ditch diggers, too.
     */
    Class<?> classForName(String name, ClassLoader[] classLoader) throws ClassNotFoundException {

        for (ClassLoader cl : classLoader) {

            if (null != cl) {

                try {

                    return Class.forName(name, true, cl);

                } catch (ClassNotFoundException e) {
                    // we'll ignore this until all classloaders fail to locate the class
                }

            }

        }

        throw new ClassNotFoundException("Cannot find class: " + name);

    }

    /**
     * 获取类加载器
     *
     * @param classLoader
     * @return
     */
    ClassLoader[] getClassLoaders(ClassLoader classLoader) {
        // NOTE: 可能存在重复或为 null 的类加载器
        return new ClassLoader[]{
            classLoader,
            defaultClassLoader,
            Thread.currentThread().getContextClassLoader(),
            getClass().getClassLoader(),
            systemClassLoader};
    }

}
