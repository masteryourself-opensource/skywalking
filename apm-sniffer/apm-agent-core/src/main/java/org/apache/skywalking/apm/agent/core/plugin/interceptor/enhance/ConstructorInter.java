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


package org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance;

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.loader.InterceptorInstanceLoader;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;

/**
 * The actual byte-buddy's interceptor to intercept constructor methods.
 * In this class, it provide a bridge between byte-buddy and sky-walking plugin.
 *
 * @author wusheng
 */
public class ConstructorInter {
    private static final ILog logger = LogManager.getLogger(ConstructorInter.class);

    /**
     * An {@link InstanceConstructorInterceptor}
     * This name should only stay in {@link String}, the real {@link Class} type will trigger classloader failure.
     * If you want to know more, please check on books about Classloader or Classloader appointment mechanism.
     */
    private InstanceConstructorInterceptor interceptor;

    /**
     * @param constructorInterceptorClassName class full name.
     */
    public ConstructorInter(String constructorInterceptorClassName, ClassLoader classLoader) throws PluginException {
        try {
            // 根据传入的构造方法拦截器类限定名反射创建实例对象
            interceptor = InterceptorInstanceLoader.load(constructorInterceptorClassName, classLoader);
        } catch (Throwable t) {
            throw new PluginException("Can't create InstanceConstructorInterceptor.", t);
        }
    }

    /**
     * Intercept the target constructor.
     *
     * @param obj target class instance.
     * @param allArguments all constructor arguments
     */
    @RuntimeType
    public void intercept(@This Object obj,
        @AllArguments Object[] allArguments) {
        try {
            // 类型转换
            EnhancedInstance targetObject = (EnhancedInstance)obj;
            // 当原生的构造方法执行完成后, 会调用这里的 onConstruct() 方法
            interceptor.onConstruct(targetObject, allArguments);
        } catch (Throwable t) {
            logger.error("ConstructorInter failure.", t);
        }

    }
}
