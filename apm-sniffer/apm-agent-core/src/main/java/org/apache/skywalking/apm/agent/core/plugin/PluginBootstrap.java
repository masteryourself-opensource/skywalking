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

package org.apache.skywalking.apm.agent.core.plugin;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * Plugins finder.
 * Use {@link PluginResourcesResolver} to find all plugins,
 * and ask {@link PluginCfg} to load all plugin definitions.
 *
 * @author wusheng
 */
public class PluginBootstrap {
    private static final ILog logger = LogManager.getLogger(PluginBootstrap.class);

    /**
     * load all plugins.
     *
     * @return plugin definition list.
     */
    public List<AbstractClassEnhancePluginDefine> loadPlugins() throws AgentPackageNotFoundException {
        // 初始化 AgentClassLoader
        AgentClassLoader.initDefaultLoader();

        PluginResourcesResolver resolver = new PluginResourcesResolver();
        // 读取所有的 skywalking-plugin.def 资源文件
        List<URL> resources = resolver.getResources();

        if (resources == null || resources.size() == 0) {
            logger.info("no plugin files (skywalking-plugin.def) found, continue to start application.");
            return new ArrayList<AbstractClassEnhancePluginDefine>();
        }

        for (URL pluginUrl : resources) {
            try {
                // 解析文件内容, 将其封装成 PluginDefine 集合
                PluginCfg.INSTANCE.load(pluginUrl.openStream());
            } catch (Throwable t) {
                logger.error(t, "plugin file [{}] init failure.", pluginUrl);
            }
        }
        // 获取从上一步解析的所有插件
        List<PluginDefine> pluginClassList = PluginCfg.INSTANCE.getPluginClassList();

        List<AbstractClassEnhancePluginDefine> plugins = new ArrayList<AbstractClassEnhancePluginDefine>();
        for (PluginDefine pluginDefine : pluginClassList) {
            try {
                logger.debug("loading plugin class {}.", pluginDefine.getDefineClass());
                // 使用 classloader 实例化出插件对象
                AbstractClassEnhancePluginDefine plugin =
                    (AbstractClassEnhancePluginDefine)Class.forName(pluginDefine.getDefineClass(),
                        true,
                        AgentClassLoader.getDefault())
                        .newInstance();
                // 加载到插件集合中
                plugins.add(plugin);
            } catch (Throwable t) {
                logger.error(t, "load plugin [{}] failure.", pluginDefine.getDefineClass());
            }
        }

        // 加载基于 xml 定义的插件
        plugins.addAll(DynamicPluginLoader.INSTANCE.load(AgentClassLoader.getDefault()));

        return plugins;

    }

}
