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

package org.apache.skywalking.apm.agent.core.boot;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author zhangkewei
 */
public class DefaultNamedThreadFactory implements ThreadFactory {
    // 线程工厂序列
    private static final AtomicInteger BOOT_SERVICE_SEQ = new AtomicInteger(0);
    // 每个线程工厂对应的线程序列
    private final AtomicInteger threadSeq = new AtomicInteger(0);
    private final String namePrefix;
    public DefaultNamedThreadFactory(String name) {
        namePrefix = "SkywalkingAgent-" + BOOT_SERVICE_SEQ.incrementAndGet() + "-" + name + "-";
    }
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r,namePrefix + threadSeq.getAndIncrement());
        t.setDaemon(true);
        return t;
    }
}
