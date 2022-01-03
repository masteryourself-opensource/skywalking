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

package org.apache.skywalking.apm.agent.core.plugin.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.pool.TypePool;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.ByteBuddyCoreClasses;
import org.apache.skywalking.apm.agent.core.plugin.InstrumentDebuggingClass;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.PluginFinder;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.jdk9module.JDK9ModuleExporter;
import org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * If there is Bootstrap instrumentation plugin declared in plugin list, BootstrapInstrumentBoost inject the necessary
 * classes into bootstrap class loader, including generated dynamic delegate classes.
 *
 * @author wusheng
 */
public class BootstrapInstrumentBoost {
    private static final ILog logger = LogManager.getLogger(BootstrapInstrumentBoost.class);

    private static final String[] HIGH_PRIORITY_CLASSES = {
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.BootstrapInterRuntimeAssist",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceConstructorInterceptor",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor",
        "org.apache.skywalking.apm.agent.core.plugin.bootstrap.IBootstrapLog",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.OverrideCallable",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult"
    };

    private static String INSTANCE_METHOD_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.InstanceMethodInterTemplate";
    private static String INSTANCE_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.InstanceMethodInterWithOverrideArgsTemplate";
    private static String CONSTRUCTOR_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.ConstructorInterTemplate";
    private static String STATIC_METHOD_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.StaticMethodInterTemplate";
    private static String STATIC_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.StaticMethodInterWithOverrideArgsTemplate";

    public static AgentBuilder inject(PluginFinder pluginFinder, Instrumentation instrumentation, AgentBuilder agentBuilder,
        JDK9ModuleExporter.EdgeClasses edgeClasses) throws PluginException {
        // 所有要注入到 Bootstrap Classloader 的类, key 是类限定名, value 是字节码
        Map<String, byte[]> classesTypeMap = new HashMap<String, byte[]>();
        // 针对于目标类是 jdk 核心类库的插件, 这里根据插件的拦截点不同（实例方法, 静态方法, 构造方法）
        // 使用不同的模板来定义新的拦截器的核心处理逻辑, 并且将插件本身定义的拦截器的全类名赋值给模板的 TARGET_INTERCEPTOR 字段
        // 最终这些新拦截器的核心处理逻辑都会被 Bootstrap Classloader 加载
        if (!prepareJREInstrumentation(pluginFinder, classesTypeMap)) {
            return agentBuilder;
        }
        // 把 skywalking 的一些核心工具类加载到 classesTypeMap
        for (String highPriorityClass : HIGH_PRIORITY_CLASSES) {
            loadHighPriorityClass(classesTypeMap, highPriorityClass);
        }
        // 把 ByteBuddy 的一些核心工具类加载到 classesTypeMap
        for (String highPriorityClass : ByteBuddyCoreClasses.CLASSES) {
            loadHighPriorityClass(classesTypeMap, highPriorityClass);
        }

        /**
         * Prepare to open edge of necessary classes.
         */
        for (String generatedClass : classesTypeMap.keySet()) {
            edgeClasses.add(generatedClass);
        }

        /**
         * Inject the classes into bootstrap class loader by using Unsafe Strategy.
         * ByteBuddy adapts the sun.misc.Unsafe and jdk.internal.misc.Unsafe automatically.
         * 把 classesTypeMap 中定义的一些字节码加载到 BootstrapCL 中
         */
        ClassInjector.UsingUnsafe.Factory factory = ClassInjector.UsingUnsafe.Factory.resolve(instrumentation);
        factory.make(null, null).injectRaw(classesTypeMap);
        agentBuilder = agentBuilder.with(new AgentBuilder.InjectionStrategy.UsingUnsafe.OfFactory(factory));


        return agentBuilder;
    }

    /**
     * Get the delegate class name.
     *
     * @param methodsInterceptor of original interceptor in the plugin
     * @return generated delegate class name
     */
    public static String internalDelegate(String methodsInterceptor) {
        return methodsInterceptor + "_internal";
    }

    /**
     * Load the delegate class from current class loader, mostly should be AppClassLoader.
     *
     * @param methodsInterceptor of original interceptor in the plugin
     * @return generated delegate class
     */
    public static Class forInternalDelegateClass(String methodsInterceptor) {
        try {
            // 直接构造出之前放入到 BootstrapCL 中的 xx_internal 对象
            return Class.forName(internalDelegate(methodsInterceptor));
        } catch (ClassNotFoundException e) {
            throw new PluginException(e.getMessage(), e);
        }
    }

    /**
     * Generate dynamic delegate for ByteBuddy
     *
     * @param pluginFinder   gets the whole plugin list.
     * @param classesTypeMap hosts the class binary.
     * @return true if have JRE instrumentation requirement.
     * @throws PluginException when generate failure.
     */
    private static boolean prepareJREInstrumentation(PluginFinder pluginFinder,
                                                     Map<String, byte[]> classesTypeMap) throws PluginException {
        // 从加载 BootstrapInstrumentBoost 类的 classloader 获取类型池
        TypePool typePool = TypePool.Default.of(BootstrapInstrumentBoost.class.getClassLoader());
        // 找到所有要对 jdk 类库做增强的插件
        List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefines = pluginFinder.getBootstrapClassMatchDefine();
        // 循环所有的插件
        for (AbstractClassEnhancePluginDefine define : bootstrapClassMatchDefines) {
            // 循环处理插件的实例方法拦截器
            for (InstanceMethodsInterceptPoint point : define.getInstanceMethodsInterceptPoints()) {
                // 判断是否重写参数
                if (point.isOverrideArgs()) {
                    generateDelegator(classesTypeMap, typePool, INSTANCE_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE, point.getMethodsInterceptor());
                } else {
                    // 生成代理器
                    generateDelegator(classesTypeMap, typePool, INSTANCE_METHOD_DELEGATE_TEMPLATE, point.getMethodsInterceptor());
                }
            }
            // 循环处理插件的构造方法拦截器
            for (ConstructorInterceptPoint point : define.getConstructorsInterceptPoints()) {
                generateDelegator(classesTypeMap, typePool, CONSTRUCTOR_DELEGATE_TEMPLATE, point.getConstructorInterceptor());
            }
            // 循环处理插件的静态方法拦截器
            for (StaticMethodsInterceptPoint point : define.getStaticMethodsInterceptPoints()) {
                // 判断是否重写参数
                if (point.isOverrideArgs()) {
                    generateDelegator(classesTypeMap, typePool, STATIC_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE, point.getMethodsInterceptor());
                } else {
                    generateDelegator(classesTypeMap, typePool, STATIC_METHOD_DELEGATE_TEMPLATE, point.getMethodsInterceptor());
                }
            }
        }
        return bootstrapClassMatchDefines.size() > 0;
    }

    /**
     * Generate the delegator class based on given template class. This is preparation stage level code generation.
     * <p>
     * One key step to avoid class confliction between AppClassLoader and BootstrapClassLoader
     *
     * @param classesTypeMap     hosts injected binary of generated class
     * @param typePool           to generate new class
     * @param templateClassName  represents the class as template in this generation process. The templates are
     *                           pre-defined in SkyWalking agent core.
     * @param methodsInterceptor
     */
    private static void generateDelegator(Map<String, byte[]> classesTypeMap, TypePool typePool,
                                          String templateClassName, String methodsInterceptor) {
        // 给类名加上后缀 _internal
        String internalInterceptorName = internalDelegate(methodsInterceptor);
        try {
            // 如果说 CL 已经加载了 100 个类, 但是这个 CL 的 classpath 还有一些类未加载
            // 那么 typePool.describe() 方法可以把还未加载到的类拿到
            TypeDescription templateTypeDescription = typePool.describe(templateClassName)
                    .resolve();

            // 这里只是调用 make(), 即把字节码准备好
            DynamicType.Unloaded interceptorType = new ByteBuddy()
                    .redefine(templateTypeDescription, ClassFileLocator.ForClassLoader.of(BootstrapInstrumentBoost.class.getClassLoader()))
                    .name(internalInterceptorName)
                    // 给属性赋值
                    .field(named("TARGET_INTERCEPTOR")).value(methodsInterceptor)
                    .make();

            // 把模板进行编译, 然后给 TARGET_INTERCEPTOR 设置值, 最后编译放到 classesTypeMap
            classesTypeMap.put(internalInterceptorName, interceptorType.getBytes());

            InstrumentDebuggingClass.INSTANCE.log(interceptorType);
        } catch (Exception e) {
            throw new PluginException("Generate Dynamic plugin failure", e);
        }
    }

    /**
     * The class loaded by this method means it only should be loaded once in Bootstrap classloader, when bootstrap
     * instrumentation active by any plugin
     *
     * @param loadedTypeMap hosts all injected class
     * @param className     to load
     */
    private static void loadHighPriorityClass(Map<String, byte[]> loadedTypeMap,
                                              String className) throws PluginException {
        byte[] enhancedInstanceClassFile;
        try {
            String classResourceName = className.replaceAll("\\.", "/") + ".class";
            InputStream resourceAsStream = AgentClassLoader.getDefault().getResourceAsStream(classResourceName);

            if (resourceAsStream == null) {
                throw new PluginException("High priority class " + className + " not found.");
            }

            ByteArrayOutputStream os = new ByteArrayOutputStream();

            byte[] buffer = new byte[1024];
            int len;

            // read bytes from the input stream and store them in buffer
            while ((len = resourceAsStream.read(buffer)) != -1) {
                // write bytes from the buffer into output stream
                os.write(buffer, 0, len);
            }

            enhancedInstanceClassFile = os.toByteArray();
        } catch (IOException e) {
            throw new PluginException(e.getMessage(), e);
        }

        loadedTypeMap.put(className, enhancedInstanceClassFile);
    }
}
