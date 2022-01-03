/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */


package org.apache.skywalking.apm.agent.core.plugin.loader;

import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The <code>InterceptorInstanceLoader</code> is a classes finder and container.
 * <p>
 * This is a very important class in sky-walking's auto-instrumentation mechanism. If you want to fully understand why
 * need this, and how it works, you need have knowledge about Classloader appointment mechanism.
 * <p>
 * Created by wusheng on 16/8/2.
 */
public class InterceptorInstanceLoader {

    private static ConcurrentHashMap<String, Object> INSTANCE_CACHE = new ConcurrentHashMap<String, Object>();
    private static ReentrantLock INSTANCE_LOAD_LOCK = new ReentrantLock();

    /**
     * pluginLoader 作用有两个
     * key: 加载当前插件要拦截的那个类的类加载器
     * value: 既能加载插件拦截器, 又能加载要拦截的那个类的类加载器
     */
    private static Map<ClassLoader, ClassLoader> EXTEND_PLUGIN_CLASSLOADERS = new HashMap<ClassLoader, ClassLoader>();

    /**
     * Load an instance of interceptor, and keep it singleton.
     * Create {@link AgentClassLoader} for each targetClassLoader, as an extend classloader.
     * It can load interceptor classes from plugins, activations folders.
     *
     * @param className         the interceptor class, which is expected to be found
     * @param targetClassLoader the class loader for current application context
     * @param <T>               expected type
     * @return the type reference.
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws ClassNotFoundException
     * @throws AgentPackageNotFoundException
     */
    public static <T> T load(String className, ClassLoader targetClassLoader)
            throws IllegalAccessException, InstantiationException, ClassNotFoundException, AgentPackageNotFoundException {
        if (targetClassLoader == null) {
            targetClassLoader = InterceptorInstanceLoader.class.getClassLoader();
        }
        // 缓存的 key 是和 classloader 有关的, 因为可能存在一种情况, 一个字节码文件被不同的 CL 加载
        String instanceKey = className + "_OF_" + targetClassLoader.getClass().getName() + "@" + Integer.toHexString(targetClassLoader.hashCode());
        Object inst = INSTANCE_CACHE.get(instanceKey);
        if (inst == null) {
            INSTANCE_LOAD_LOCK.lock();
            try {
                // 这里会基于 [准备增强目标类的 CL] 重新构建一个 pluginLoader
                // 这样可以保证这个 pluginLoader 既可以访问 [准备增强目标类], 也可以访问插件类
                ClassLoader pluginLoader = EXTEND_PLUGIN_CLASSLOADERS.get(targetClassLoader);
                if (pluginLoader == null) {
                    // 这里需要重新构造 AgentClassLoader, 这样既可以对要加载的类进行访问, 也可以对插件进行访问, 称之为 pluginLoader
                    pluginLoader = new AgentClassLoader(targetClassLoader);
                    EXTEND_PLUGIN_CLASSLOADERS.put(targetClassLoader, pluginLoader);
                }
                // 实例化插件对象
                inst = Class.forName(className, true, pluginLoader).newInstance();
            } finally {
                INSTANCE_LOAD_LOCK.unlock();
            }
            if (inst != null) {
                INSTANCE_CACHE.put(instanceKey, inst);
            }
        }

        return (T) inst;
    }
}
