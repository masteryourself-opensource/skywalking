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

package org.apache.skywalking.apm.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.ConfigNotFoundException;
import org.apache.skywalking.apm.agent.core.conf.SnifferConfigInitializer;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.EnhanceContext;
import org.apache.skywalking.apm.agent.core.plugin.InstrumentDebuggingClass;
import org.apache.skywalking.apm.agent.core.plugin.PluginBootstrap;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.PluginFinder;
import org.apache.skywalking.apm.agent.core.plugin.bootstrap.BootstrapInstrumentBoost;
import org.apache.skywalking.apm.agent.core.plugin.jdk9module.JDK9ModuleExporter;

import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * The main entrance of sky-walking agent, based on javaagent mechanism.
 *
 * @author wusheng
 */
public class SkyWalkingAgent {
    private static final ILog logger = LogManager.getLogger(SkyWalkingAgent.class);

    /**
     * Main entrance. Use byte-buddy transform to enhance all classes, which define in plugins.
     *
     * -javaagent:/path/agent.jar=k1=v1,k2=v2, 必须放在 -jar 之前, 否则不生效
     * agentArgs 表示等号之后的参数即 k1=v1,k2=v2
     *
     * @param agentArgs
     * @param instrumentation
     * @throws PluginException
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) throws PluginException, IOException {
        final PluginFinder pluginFinder;
        try {
            // 1. 初始化配置
            SnifferConfigInitializer.initialize(agentArgs);

            // 2. 加载插件并分类
            pluginFinder = new PluginFinder(new PluginBootstrap().loadPlugins());

        } catch (ConfigNotFoundException ce) {
            logger.error(ce, "SkyWalking agent could not find config. Shutting down.");
            return;
        } catch (AgentPackageNotFoundException ape) {
            logger.error(ape, "Locate agent.jar failure. Shutting down.");
            return;
        } catch (Exception e) {
            logger.error(e, "SkyWalking agent initialized failure. Shutting down.");
            return;
        }
        // 3. 创建 ByteBuddy 实例
        final ByteBuddy byteBuddy = new ByteBuddy()
            .with(TypeValidation.of(Config.Agent.IS_OPEN_DEBUGGING_CLASS));
        // 指定需要忽略的类
        AgentBuilder agentBuilder = new AgentBuilder.Default(byteBuddy)
            .ignore(
                nameStartsWith("net.bytebuddy.")
                    .or(nameStartsWith("org.slf4j."))
                    .or(nameStartsWith("org.groovy."))
                    .or(nameContains("javassist"))
                    .or(nameContains(".asm."))
                    .or(nameStartsWith("sun.reflect"))
                    .or(allSkyWalkingAgentExcludeToolkit())
                    .or(ElementMatchers.<TypeDescription>isSynthetic()));
        // jdk9 的模块化
        JDK9ModuleExporter.EdgeClasses edgeClasses = new JDK9ModuleExporter.EdgeClasses();
        try {
            // 将必要的一些工具类装载到 BootStrap Classloader 中
            // 为什么需要这样设计呢? 因为可以对 jdk 的一些类做增强, 但由于双亲委派机制
            // BootStrap Classloader 对 skywalking 增强的工具类不可见, 所以需要把一些必要的类注册到 BootStrap Classloader
            agentBuilder = BootstrapInstrumentBoost.inject(pluginFinder, instrumentation, agentBuilder, edgeClasses);
        } catch (Exception e) {
            logger.error(e, "SkyWalking agent inject bootstrap instrumentation failure. Shutting down.");
            return;
        }

        try {
            // 绕开 jdk9 模块系统
            agentBuilder = JDK9ModuleExporter.openReadEdge(instrumentation, agentBuilder, edgeClasses);
        } catch (Exception e) {
            logger.error(e, "SkyWalking agent open read edge in JDK 9+ failure. Shutting down.");
            return;
        }

        // 定制化 Agent 行为
        agentBuilder
                // 指定要拦截的类, 这里构造一个巨大的插件查询条件
            .type(pluginFinder.buildMatch())
                // 指定 Transformer 插桩增强类
            .transform(new Transformer(pluginFinder))
                // REDEFINITION 和 RETRANSFORMATION 区别在于是否保存修改前的内容
                // REDEFINITION 会覆盖原来方法, RETRANSFORMATION 会重新定义一个方法
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // 对 Transformer 后的监听, 输出日志
            .with(new Listener())
                // 将 Agent 安装到 instrumentation
            .installOn(instrumentation);

        try {
            // 4. 启动服务
            ServiceManager.INSTANCE.boot();
        } catch (Exception e) {
            logger.error(e, "Skywalking agent boot failure.");
        }

        // 5. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                ServiceManager.INSTANCE.shutdown();
            }
        }, "skywalking service shutdown thread"));
    }

    private static class Transformer implements AgentBuilder.Transformer {
        private PluginFinder pluginFinder;

        Transformer(PluginFinder pluginFinder) {
            this.pluginFinder = pluginFinder;
        }

        /**
         * 字节码插桩
         *
         * @param builder         当前拦截到的类的字节码
         * @param typeDescription 简单当成 class, 包含了类的描述信息
         * @param classLoader     加载当前拦截到的 classloader
         * @param module
         */
        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
            ClassLoader classLoader, JavaModule module) {
            // 根据传入的类, 判断能否找到增强的插件
            List<AbstractClassEnhancePluginDefine> pluginDefines = pluginFinder.find(typeDescription);
            if (pluginDefines.size() > 0) {
                DynamicType.Builder<?> newBuilder = builder;
                EnhanceContext context = new EnhanceContext();
                for (AbstractClassEnhancePluginDefine define : pluginDefines) {
                    // 调用插件的拦截方法, 获取拦截后的字节码
                    DynamicType.Builder<?> possibleNewBuilder = define.define(typeDescription, newBuilder, classLoader, context);
                    if (possibleNewBuilder != null) {
                        newBuilder = possibleNewBuilder;
                    }
                }
                if (context.isEnhanced()) {
                    logger.debug("Finish the prepare stage for {}.", typeDescription.getName());
                }

                return newBuilder;
            }

            logger.debug("Matched class {}, but ignore by finding mechanism.", typeDescription.getTypeName());
            return builder;
        }
    }

    private static ElementMatcher.Junction<NamedElement> allSkyWalkingAgentExcludeToolkit() {
        return nameStartsWith("org.apache.skywalking.").and(not(nameStartsWith("org.apache.skywalking.apm.toolkit.")));
    }

    private static class Listener implements AgentBuilder.Listener {
        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {

        }

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
            boolean loaded, DynamicType dynamicType) {
            if (logger.isDebugEnable()) {
                logger.debug("On Transformation class {}.", typeDescription.getName());
            }

            InstrumentDebuggingClass.INSTANCE.log(dynamicType);
        }

        @Override
        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
            boolean loaded) {

        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
            Throwable throwable) {
            logger.error("Enhance class " + typeName + " error.", throwable);
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        }
    }
}
