/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019, Red Hat, Inc., and individual contributors
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
package org.jboss.as.test.integration.jmx.endpoint;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests if a memory leak occurs when a new JMX connection is unsuccessful.
 *
 * Test case for [ JBEAP-16931 ]
 */
@RunWith(Arquillian.class)
@RunAsClient
public class JMXRemotingMemoryLeakTestCase {

    private final Logger log = Logger.getLogger(JMXRemotingMemoryLeakTestCase.class);

    @ContainerResource
    private ManagementClient managementClient;

    @Deployment
    public static Archive<?> getDeployment() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class);
        archive.addClass(JMXRemotingMemoryLeakTestCase.class);
        return archive;
    }

    @Test
    public void testJMXRemotingMemoryLeak() throws Exception {
        final String address = managementClient.getMgmtAddress() + ":8080";
        String jmxUrl = "service:jmx:remote+http://" + address;
        log.info("Using jboss jmx remoting url: " + jmxUrl);

        JMXServiceURL url = new JMXServiceURL(jmxUrl);
        int i = 0;
        int nonNull = 0;
        int exceptionThrownClosing = 0;

        // Do an initial GC to get a baseline free memory.
        System.gc();
        long initialBytesFree = Runtime.getRuntime().freeMemory();
        long halfInitialBytesFree = (long) (initialBytesFree  / 2);
        log.info(new Date() + " | begin with bytes free: " + initialBytesFree);

        JMXConnector client = JMXConnectorFactory.newJMXConnector(url, null);
        while (i <= 80000) {
            assertEquals(0, nonNull);
            JMXConnector connector = null;
            try {
                client.connect();
            } catch (Exception e) {
                if (i == 0) {
                    e.printStackTrace();
                }
                if (connector != null) {
                    nonNull++;
                    try {
                        connector.close();
                    } catch (Exception e1) {
                        exceptionThrownClosing++;
                    }
                }
            }
            i++;
            if (i % 1000 == 0) {
                // Do a full GC before measuring again
                System.gc();

                long bytesFree = Runtime.getRuntime().freeMemory();
                log.info(new Date() + " | tried " + i + " | returned non-null " + nonNull
                        + " | exception thrown closing " + exceptionThrownClosing + " bytes Free= " + bytesFree);
                if (bytesFree < halfInitialBytesFree) {
                    fail("Half of the memory is gone, even after full garbage collecting.");
                }
            }
        }
    }
}
