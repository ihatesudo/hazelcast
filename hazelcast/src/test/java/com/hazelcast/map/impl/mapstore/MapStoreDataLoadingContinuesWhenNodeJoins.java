/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.hazelcast.map.impl.mapstore;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.config.MapStoreConfig.InitialLoadMode;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapStore;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.SlowTest;
import com.hazelcast.util.EmptyStatement;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Test if a node joining a cluster which is loading data works.
 * <p/>
 * Thanks Lukas Blunschi (@lukasblu) for contributing this test originally.
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category(SlowTest.class)
public class MapStoreDataLoadingContinuesWhenNodeJoins extends HazelcastTestSupport {

    private static final String MAP_NAME = "testMap";
    private static final boolean SIMULATE_SECOND_NODE = true;
    private static final int WRITE_DELAY_SECONDS = 5;
    private static final int PRELOAD_SIZE = 1000;
    private static final int MS_PER_LOAD = 300;

    private static final ILogger LOGGER = Logger.getLogger(MapStoreDataLoadingContinuesWhenNodeJoins.class);

    private static final AtomicLong thread1Shutdown = new AtomicLong(-1L);
    private static final AtomicLong thread2Shutdown = new AtomicLong(-1L);
    private static final AtomicInteger mapSize = new AtomicInteger(-1);

    @Test(timeout = 600000)
    public void testNoDeadLockDuringJoin() throws Exception {
        // create shared hazelcast config
        final Config config = new XmlConfigBuilder().build();

        // disable JMX to make sure lazy loading works asynchronously
        config.setProperty("hazelcast.jmx", "false");
        // get map config
        MapConfig mapConfig = config.getMapConfig(MAP_NAME);

        // create shared map store implementation
        // - use slow loading (300ms per map entry)
        final CountDownLatch halfOfKeysAreLoaded = new CountDownLatch(1);
        final InMemoryMapStore store = new InMemoryMapStore(halfOfKeysAreLoaded, MS_PER_LOAD, false);
        store.preload(PRELOAD_SIZE);

        // configure map store
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setInitialLoadMode(InitialLoadMode.LAZY);
        mapStoreConfig.setWriteDelaySeconds(WRITE_DELAY_SECONDS);
        mapStoreConfig.setClassName(null);
        mapStoreConfig.setImplementation(store);
        mapConfig.setMapStoreConfig(mapStoreConfig);
        final TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);

        final CountDownLatch node1Started = new CountDownLatch(1);
        final CountDownLatch node1FinishedLoading = new CountDownLatch(1);
        // thread 1:
        // start a single node and load the data
        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {

                HazelcastInstance hcInstance = factory.newHazelcastInstance(config);
                // try-finally to stop hazelcast instance
                node1Started.countDown();
                try {
                    // get map
                    // this will trigger loading the data
                    IMap<String, String> map = hcInstance.getMap(MAP_NAME);
                    map.size();
                    node1FinishedLoading.countDown();
                } finally {
                    thread1Shutdown.set(System.currentTimeMillis());
                    hcInstance.getLifecycleService().shutdown();
                }
            }
        }, "Thread 1");
        thread1.start();

        node1Started.await();
        // thread 2:
        // simulate a second member which joins the cluster
        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                HazelcastInstance hcInstance = factory.newHazelcastInstance(config);
                try {
                    hcInstance.getMap(MAP_NAME);
                    final int loadTimeMillis = MS_PER_LOAD * PRELOAD_SIZE;
                    node1FinishedLoading.await(loadTimeMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    EmptyStatement.ignore(e);
                } finally {
                    thread2Shutdown.set(System.currentTimeMillis());
                    hcInstance.getLifecycleService().shutdown();
                }
            }
        }, "Thread 2");
        thread2.start();

        // join threads
        thread1.join();
        thread2.join();

        // assert correct shutdown order
        if (thread1Shutdown.get() > thread2Shutdown.get()) {
            fail("Thread 2 was shutdown before thread 1.");
        }
    }

    @Test(timeout = 600000)
    public void testDataLoadedCorrectly() throws Exception {
        // create shared hazelcast config
        final Config config = new XmlConfigBuilder().build();
        // disable JMX to make sure lazy loading works asynchronously
        config.setProperty("hazelcast.jmx", "false");

        // get map config
        MapConfig mapConfig = config.getMapConfig(MAP_NAME);

        // create shared map store implementation
        // - use slow loading (300ms per map entry)
        final CountDownLatch halfOfKeysAreLoaded = new CountDownLatch(1);
        final InMemoryMapStore store = new InMemoryMapStore(halfOfKeysAreLoaded, 300, false);
        store.preload(PRELOAD_SIZE);

        // configure map store
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setInitialLoadMode(InitialLoadMode.LAZY);
        mapStoreConfig.setWriteDelaySeconds(WRITE_DELAY_SECONDS);
        mapStoreConfig.setClassName(null);
        mapStoreConfig.setImplementation(store);
        mapConfig.setMapStoreConfig(mapStoreConfig);

        final TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);

        final CountDownLatch node1Started = new CountDownLatch(1);
        final CountDownLatch node1FinishedLoading = new CountDownLatch(1);

        // thread 1:
        // start a single node and load the data
        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {
                HazelcastInstance instance = factory.newHazelcastInstance(config);
                // try-finally to stop hazelcast instance
                node1Started.countDown();
                try {

                    // get map
                    // this will trigger loading the data
                    final IMap<String, String> map = instance.getMap(MAP_NAME);
                    mapSize.set(map.size());
                    node1FinishedLoading.countDown();
                    assertTrueEventually(new AssertTask() {
                        @Override
                        public void run() throws Exception {
                            assertEquals(PRELOAD_SIZE, map.size());
                        }
                    }, 5);
                    // -------------------------------------------------- {20s}

                } finally {
                    instance.getLifecycleService().shutdown();
                }
            }
        }, "Thread 1");
        thread1.start();
        // wait 10s after starting first thread
        node1Started.await();
        // thread 2:
        // simulate a second member which joins the cluster
        Thread thread2 = new Thread(new Runnable() {

            @Override
            public void run() {
                HazelcastInstance instance = factory.newHazelcastInstance(config);
                // try-finally to stop hazelcast instance
                try {
                    // get map
                    instance.getMap(MAP_NAME);
                    final int loadTimeMillis = MS_PER_LOAD * PRELOAD_SIZE;
                    node1FinishedLoading.await(loadTimeMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    EmptyStatement.ignore(e);
                } finally {
                    instance.getLifecycleService().shutdown();
                }
            }
        }, "Thread 2");
        if (SIMULATE_SECOND_NODE) {
            thread2.start();
        }

        // join threads
        thread1.join();
        if (SIMULATE_SECOND_NODE) {
            thread2.join();
        }
    }

    public class InMemoryMapStore implements MapStore<String, String> {

        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<String, String>();
        private final AtomicInteger countLoadAllKeys = new AtomicInteger(0);

        private final CountDownLatch halfOfKeysAreLoaded;
        private final int msPerLoad;
        private final boolean sleepBeforeLoadAllKeys;

        InMemoryMapStore(CountDownLatch halfOfKeysAreLoaded, int msPerLoad, boolean sleepBeforeLoadAllKeys) {
            this.halfOfKeysAreLoaded = halfOfKeysAreLoaded;
            this.msPerLoad = msPerLoad;
            this.sleepBeforeLoadAllKeys = sleepBeforeLoadAllKeys;
        }

        void preload(int size) {
            for (int i = 0; i < size; i++) {
                store.put("k" + i, "v" + i);
            }
        }

        @Override
        public String load(String key) {
            if (msPerLoad > 0) {
                sleep(msPerLoad, false);
            }
            return store.get(key);
        }

        @Override
        public Map<String, String> loadAll(Collection<String> keys) {
            List<String> keysList = new ArrayList<String>(keys);
            int size = keys.size();
            Collections.sort(keysList);
            Map<String, String> result = new HashMap<String, String>();
            int count = 0;
            for (String key : keys) {
                if (msPerLoad > 0) {
                    sleep(msPerLoad, false);
                }
                String value = store.get(key);
                if (value != null) {
                    result.put(key, value);
                }
                if (count > size / 2) {
                    halfOfKeysAreLoaded.countDown();
                }
                count += 1;
            }
            return result;
        }

        @Override
        public Set<String> loadAllKeys() {
            // sleep 5s to highlight asynchronous behavior
            if (sleepBeforeLoadAllKeys) {
                sleep(5000, true);
            }

            countLoadAllKeys.incrementAndGet();
            Set<String> result = new HashSet<String>(store.keySet());
            List<String> resultList = new ArrayList<String>(result);
            Collections.sort(resultList);
            return result;
        }

        @Override
        public void store(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void storeAll(Map<String, String> map) {
            store.putAll(map);
        }

        @Override
        public void delete(String key) {
            store.remove(key);
        }

        @Override
        public void deleteAll(Collection<String> keys) {
            List<String> keysList = new ArrayList<String>(keys);
            Collections.sort(keysList);
            for (String key : keys) {
                store.remove(key);
            }
        }
    }

    public void sleep(int ms, boolean log) {
        sleepMillis(ms);
        if (log) {
            LOGGER.info("Slept " + TimeUnit.MILLISECONDS.toSeconds(ms) + "seconds.");
        }
    }
}
