package com.xorlev.gatekeeper.providers.discovery;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.framework.imps.CuratorFrameworkState;
import com.netflix.curator.framework.state.ConnectionState;
import com.netflix.curator.retry.RetryNTimes;
import com.netflix.curator.x.discovery.ServiceCache;
import com.netflix.curator.x.discovery.ServiceDiscovery;
import com.netflix.curator.x.discovery.ServiceDiscoveryBuilder;
import com.netflix.curator.x.discovery.ServiceInstance;
import com.netflix.curator.x.discovery.details.ServiceCacheListener;
import com.xorlev.gatekeeper.AppConfig;
import com.xorlev.gatekeeper.data.Cluster;
import com.xorlev.gatekeeper.data.Server;
import com.xorlev.gatekeeper.providers.discovery.AbstractClusterDiscovery;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2013-07-27
 *
 * @author Michael Rose <elementation@gmail.com>
 */
public class ZookeeperClusterDiscovery extends AbstractClusterDiscovery {
    private CuratorFramework zk;

    private ServiceDiscovery<Void> dsc;
    private List<ServiceCache<Void>> serviceCacheList = Lists.newArrayList();

    private ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public void startUp() throws Exception {
        setupZookeeper();
        setupServiceDiscovery();
        updateInstances();
    }

    @Override
    public void shutDown() throws Exception {
        log.info("Shutting down...");

        executorService.shutdown();
        if (dsc != null) dsc.close();
        if (zk != null) zk.close();
    }

    private void setupZookeeper() {
        String quorum = AppConfig.getString("zookeeper.quorum");
        String namespace = AppConfig.getString("zookeeper.namespace");
        log.info("Starting Zookeeper with connectString={}", quorum);
        zk = CuratorFrameworkFactory.builder()
                .connectString(quorum)
                .connectionTimeoutMs(2000)
                .retryPolicy(new RetryNTimes(6, 1000))
                .namespace(namespace.isEmpty() ? null : namespace)
                .build();

        zk.start();
    }

    private void setupServiceDiscovery() throws Exception {
        if (zk != null && zk.getState() == CuratorFrameworkState.STARTED) {

            dsc = ServiceDiscoveryBuilder.builder(Void.class)
                    .basePath(AppConfig.getString("zookeeper.discoveryPath"))
                    .client(zk)
                    .build();

            dsc.start();

            initializeServiceCaches();
        }
    }

    private void initializeServiceCaches() throws Exception {
        // Close any caches (if exists)
        for (ServiceCache<Void> cache : serviceCacheList) {
            cache.close();
        }
        serviceCacheList = Lists.newArrayList();

        // Grab each cluster, build a service cache, and add listeners to update config file
        for (final String c : AppConfig.getStringList("clusters")) {
            ServiceCache<Void> cache = dsc.serviceCacheBuilder().name(c).build();

            cache.addListener(new ServiceCacheListener() {
                public void cacheChanged() {
                    log.info("Service {} modified, rewriting config", c);
                    updateInstances();
                }

                public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                }
            }, executorService);

            cache.start();
            serviceCacheList.add(cache);

            // If context changes, rebuild config
            AppConfig.addCallback("cluster." + c + ".context", new Runnable() {
                public void run() {
                    try {
                        updateInstances();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

        }

        // If clusters change, re-init our service caches and config.
        AppConfig.addCallback("clusters", new Runnable() {
            public void run() {
                try {
                    initializeServiceCaches();
                    updateInstances();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public List<Cluster> clusters() {
        List<Cluster> clusterList = Lists.newArrayListWithExpectedSize(serviceCacheList.size());
        for (ServiceCache<Void> cache : serviceCacheList) {
            if (!cache.getInstances().isEmpty()) {
                Cluster cluster = clusterFromInstance(cache.getInstances().get(0));

                for (ServiceInstance<Void> instance : cache.getInstances()) {
                    cluster.getServers().add(convertInstance(instance));
                }

                log.info("Discovery: cluster=[{}] has {} instances, {}...",
                        cluster.getClusterName(), cluster.getServers().size(), Iterables.limit(cluster.getServers(), 5));
                clusterList.add(cluster);
            }
        }
        return clusterList;
    }

    private Cluster clusterFromInstance(ServiceInstance<Void> instance) {
        Cluster cluster = new Cluster(instance.getName());
        if (instance.getSslPort() != null) {
            cluster.setProtocol("https");
        }

        return cluster;
    }

    protected Server convertInstance(ServiceInstance<Void> instance) {
        Integer port = instance.getSslPort() != null ? instance.getSslPort() : instance.getPort();
        return new Server(instance.getAddress(), port);
    }

}
