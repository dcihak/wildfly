/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.clustering.cluster.ejb.remote;

import static org.junit.Assert.*;
import static org.jboss.as.test.shared.integration.ejb.security.PermissionUtils.createPermissionsXmlAsset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PropertyPermission;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ejb.EJBException;
import javax.naming.NamingException;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.as.test.clustering.EJBClientContextSelector;
import org.jboss.as.test.clustering.cluster.ClusterAbstractTestCase;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.Incrementor;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.InfinispanExceptionThrowingIncrementorBean;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.Result;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.SecureStatelessIncrementorBean;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.SlowToDestroyStatefulIncrementorBean;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.StatefulIncrementorBean;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.StatelessIncrementorBean;
import org.jboss.as.test.clustering.ejb.EJBDirectory;
import org.jboss.as.test.clustering.ejb.RemoteEJBDirectory;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.as.test.shared.util.DisableInvocationTestUtil;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Validates @Stateful vs @Stateless failover behavior of a remotely accessed clustered session beans.
 * @author Paul Ferraro
 */
@RunWith(Arquillian.class)
@RunAsClient
public class RemoteFailoverTestCase extends ClusterAbstractTestCase {
    private static final String MODULE_NAME = "remote-failover-test";
    private static final String CLIENT_PROPERTIES = "org/jboss/as/test/clustering/cluster/ejb/remote/jboss-ejb-client.properties";
    private static final String SECURE_CLIENT_PROPERTIES = "org/jboss/as/test/clustering/cluster/ejb/remote/jboss-ejb-client-secure.properties";

    private static final int COUNT = 20;
    private static final long CLIENT_TOPOLOGY_UPDATE_WAIT = TimeoutUtil.adjust(5000);
    private static final long INVOCATION_WAIT = TimeoutUtil.adjust(10);

    @BeforeClass
    public static void beforeClass() {
        DisableInvocationTestUtil.disable();
    }

    @Deployment(name = DEPLOYMENT_1, managed = false, testable = false)
    @TargetsContainer(CONTAINER_1)
    public static Archive<?> createDeploymentForContainer1() {
        return createDeployment();
    }

    @Deployment(name = DEPLOYMENT_2, managed = false, testable = false)
    @TargetsContainer(CONTAINER_2)
    public static Archive<?> createDeploymentForContainer2() {
        return createDeployment();
    }

    private static Archive<?> createDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, MODULE_NAME + ".jar");
        jar.addPackage(Incrementor.class.getPackage());
        jar.addPackage(EJBDirectory.class.getPackage());
        jar.setManifest(new StringAsset("Manifest-Version: 1.0\nDependencies: org.infinispan\n"));
        jar.addAsManifestResource(createPermissionsXmlAsset(
                new PropertyPermission(NODE_NAME_PROPERTY, "read")
        ), "permissions.xml");
        return jar;
    }

    @InSequence(1)
    @Test
    public void testStatelessFailover() throws Exception {
        this.testStatelessFailover(CLIENT_PROPERTIES, StatelessIncrementorBean.class);
    }

    @InSequence(4)
    @Test
    public void testSecureStatelessFailover() throws Exception {
        this.testStatelessFailover(SECURE_CLIENT_PROPERTIES, SecureStatelessIncrementorBean.class);
    }

    private void testStatelessFailover(String properties, Class<? extends Incrementor> beanClass) throws Exception {
        // TODO Elytron: Once support for legacy EJB properties has been added back, actually set the EJB properties
        // that should be used for this test using properties and ensure the EJB client context is reset
        // to its original state at the end of the test
        EJBClientContextSelector.setup(properties);
        try (EJBDirectory context = new RemoteEJBDirectory(MODULE_NAME)) {
            Incrementor bean = context.lookupStateless(beanClass, Incrementor.class);

            // Allow sufficient time for client to receive full topology
            Thread.sleep(CLIENT_TOPOLOGY_UPDATE_WAIT);

            List<String> results = new ArrayList<>(COUNT);
            for (int i = 0; i < COUNT; ++i) {
                Result<Integer> result = bean.increment();
                results.add(result.getNode());
                Thread.sleep(INVOCATION_WAIT);
            }

            for (String node: NODES) {
                int frequency = Collections.frequency(results, node);
                assertTrue(String.valueOf(frequency) + " invocations were routed to " + node, frequency > 0);
            }

            undeploy(DEPLOYMENT_1);

            for (int i = 0; i < COUNT; ++i) {
                Result<Integer> result = bean.increment();
                results.set(i, result.getNode());
                Thread.sleep(INVOCATION_WAIT);
            }

            Assert.assertEquals(0, Collections.frequency(results, NODE_1));
            Assert.assertEquals(COUNT, Collections.frequency(results, NODE_2));

            deploy(DEPLOYMENT_1);

            // Allow sufficient time for client to receive new topology
            Thread.sleep(CLIENT_TOPOLOGY_UPDATE_WAIT);

            for (int i = 0; i < COUNT; ++i) {
                Result<Integer> result = bean.increment();
                results.set(i, result.getNode());
                Thread.sleep(INVOCATION_WAIT);
            }

            for (String node: NODES) {
                int frequency = Collections.frequency(results, node);
                assertTrue(String.valueOf(frequency) + " invocations were routed to " + node, frequency > 0);
            }

            stop(CONTAINER_2);

            for (int i = 0; i < COUNT; ++i) {
                Result<Integer> result = bean.increment();
                results.set(i, result.getNode());
                Thread.sleep(INVOCATION_WAIT);
            }

            Assert.assertEquals(COUNT, Collections.frequency(results, NODE_1));
            Assert.assertEquals(0, Collections.frequency(results, NODE_2));

            start(CONTAINER_2);

            // Allow sufficient time for client to receive new topology
            Thread.sleep(CLIENT_TOPOLOGY_UPDATE_WAIT);

            for (int i = 0; i < COUNT; ++i) {
                Result<Integer> result = bean.increment();
                results.set(i, result.getNode());
                Thread.sleep(INVOCATION_WAIT);
            }

            for (String node: NODES) {
                int frequency = Collections.frequency(results, node);
                assertTrue(String.valueOf(frequency) + " invocations were routed to " + node, frequency > 0);
            }
        }
    }

    @InSequence(2)
    @Test
    public void testStatefulFailover() throws Exception {
        // TODO Elytron: Once support for legacy EJB properties has been added back, actually set the EJB properties
        // that should be used for this test using CLIENT_PROPERTIES and ensure the EJB client context is reset
        // to its original state at the end of the test
        EJBClientContextSelector.setup(CLIENT_PROPERTIES);
        try (EJBDirectory context = new RemoteEJBDirectory(MODULE_NAME)) {
            Incrementor bean = context.lookupStateful(StatefulIncrementorBean.class, Incrementor.class);

            Result<Integer> result = bean.increment();
            String target = result.getNode();
            int count = 1;

            Assert.assertEquals(count++, result.getValue().intValue());

            // Bean should retain weak affinity for this node
            for (int i = 0; i < COUNT; ++i) {
                result = bean.increment();
                Assert.assertEquals(count++, result.getValue().intValue());
                Assert.assertEquals(String.valueOf(i), target, result.getNode());
            }

            undeploy(this.findDeployment(target));

            result = bean.increment();
            // Bean should failover to other node
            String failoverTarget = result.getNode();

            Assert.assertEquals(count++, result.getValue().intValue());
            Assert.assertNotEquals(target, failoverTarget);

            deploy(this.findDeployment(target));

            // Allow sufficient time for client to receive new topology
            Thread.sleep(CLIENT_TOPOLOGY_UPDATE_WAIT);

            result = bean.increment();
            String failbackTarget = result.getNode();
            Assert.assertEquals(count++, result.getValue().intValue());
            // Bean should retain weak affinity for this node
            Assert.assertEquals(failoverTarget, failbackTarget);

            result = bean.increment();
            // Bean may have acquired new weak affinity
            target = result.getNode();
            Assert.assertEquals(count++, result.getValue().intValue());

            // Bean should retain weak affinity for this node
            for (int i = 0; i < COUNT; ++i) {
                result = bean.increment();
                Assert.assertEquals(count++, result.getValue().intValue());
                Assert.assertEquals(String.valueOf(i), target, result.getNode());
            }

            stop(this.findContainer(target));

            result = bean.increment();
            // Bean should failover to other node
            failoverTarget = result.getNode();

            Assert.assertEquals(count++, result.getValue().intValue());
            Assert.assertNotEquals(target, failoverTarget);

            start(this.findContainer(target));

            // Allow sufficient time for client to receive new topology
            Thread.sleep(CLIENT_TOPOLOGY_UPDATE_WAIT);

            result = bean.increment();
            failbackTarget = result.getNode();
            Assert.assertEquals(count++, result.getValue().intValue());
            // Bean should retain weak affinity for this node
            Assert.assertEquals(failoverTarget, failbackTarget);

            result = bean.increment();
            // Bean may have acquired new weak affinity
            target = result.getNode();
            Assert.assertEquals(count++, result.getValue().intValue());

            // Bean should retain weak affinity for this node
            for (int i = 0; i < COUNT; ++i) {
                result = bean.increment();
                Assert.assertEquals(count++, result.getValue().intValue());
                Assert.assertEquals(String.valueOf(i), target, result.getNode());
            }
        }
    }

    @Test
    public void testGracefulShutdownConcurrentFailover() throws Exception {
        this.testConcurrentFailover(new GracefulRestartLifecycle());
    }

    @Test
    @Ignore("Needs graceful undeploy support")
    public void testGracefulUndeployConcurrentFailover() throws Exception {
        this.testConcurrentFailover(new RedeployLifecycle());
    }

    public void testConcurrentFailover(Lifecycle lifecycle) throws Exception {
        // TODO Elytron: Once support for legacy EJB properties has been added back, actually set the EJB properties
        // that should be used for this test using CLIENT_PROPERTIES and ensure the EJB client context is reset
        // to its original state at the end of the test
        EJBClientContextSelector.setup(CLIENT_PROPERTIES);
        try (EJBDirectory directory = new RemoteEJBDirectory(MODULE_NAME)) {
            Incrementor bean = directory.lookupStateful(SlowToDestroyStatefulIncrementorBean.class, Incrementor.class);
            AtomicInteger count = new AtomicInteger();

            // Allow sufficient time for client to receive full topology
            Thread.sleep(CLIENT_TOPOLOGY_UPDATE_WAIT);

            String target = bean.increment().getNode();
            count.incrementAndGet();
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                Future<?> future = executor.scheduleWithFixedDelay(new IncrementTask(bean, count, latch), 0, INVOCATION_WAIT, TimeUnit.MILLISECONDS);
                latch.await();

                lifecycle.stop(target);

                future.cancel(false);
                try {
                    future.get();
                } catch (CancellationException e) {
                    // Ignore
                }

                lifecycle.start(target);

                latch = new CountDownLatch(1);
                future = executor.scheduleWithFixedDelay(new LookupTask(directory, SlowToDestroyStatefulIncrementorBean.class, latch), 0, INVOCATION_WAIT, TimeUnit.MILLISECONDS);
                latch.await();

                lifecycle.stop(target);

                future.cancel(false);
                try {
                    future.get();
                } catch (CancellationException e) {
                    // Ignore
                }

                lifecycle.start(target);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Test for WFLY-5788.
     */
    @InSequence(5)
    @Test
    public void testClientException() throws Exception {
        // TODO Elytron: Once support for legacy EJB properties has been added back, actually set the EJB properties
        // that should be used for this test using CLIENT_PROPERTIES and ensure the EJB client context is reset
        // to its original state at the end of the test
        EJBClientContextSelector.setup(CLIENT_PROPERTIES);
        try (EJBDirectory context = new RemoteEJBDirectory(MODULE_NAME)) {
            Incrementor bean = context.lookupStateful(InfinispanExceptionThrowingIncrementorBean.class, Incrementor.class);

            bean.increment();
        } catch (Exception ejbException) {
            assertTrue("Expected exception wrapped in EJBException", ejbException instanceof EJBException);
            assertNull("Cause of EJBException has not been removed", ejbException.getCause());
            return;
        }
        fail("Expected EJBException but didn't catch it");
    }

    private class IncrementTask implements Runnable {
        private final Incrementor bean;
        private final CountDownLatch latch;
        private final AtomicInteger value;

        IncrementTask(Incrementor bean, AtomicInteger value, CountDownLatch latch) {
            this.bean = bean;
            this.value = value;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                int value = this.bean.increment().getValue();
                Assert.assertEquals(this.value.incrementAndGet(), value);
            } finally {
                this.latch.countDown();
            }
        }
    }

    private class LookupTask implements Runnable {
        private final EJBDirectory directory;
        private final Class<? extends Incrementor> beanClass;
        private final CountDownLatch latch;

        LookupTask(EJBDirectory directory, Class<? extends Incrementor> beanClass, CountDownLatch latch) {
            this.directory = directory;
            this.beanClass = beanClass;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                this.directory.lookupStateful(this.beanClass, Incrementor.class);
            } catch (NamingException e) {
                throw new IllegalStateException(e);
            } finally {
                this.latch.countDown();
            }
        }
    }
}
