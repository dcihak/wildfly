package org.jboss.as.test.integration.jaxrs.client.json;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.test.integration.jaxrs.client.json.deployment.ApplicationConfig;
import org.jboss.as.test.integration.jaxrs.client.json.deployment.Greeter;
import org.jboss.as.test.integration.jaxrs.client.json.deployment.Greeting;
import org.jboss.as.test.integration.jaxrs.client.json.deployment.HelloWorld;
import org.jboss.as.test.integration.jaxrs.client.json.deployment.HelloWorldInterface;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.net.URL;

/**
 * Test contains client calling the resteasy endpoint sending a null JSON object with ResteasyWebTarget.
 * Test for [ WFLY-9091 ].
 *
 * @author Daniel Cihak
 */
@RunWith(Arquillian.class)
public class NullJSONObjectResteasyWebTargetTestCase {

    private static final String DEPLOYMENT = "resteasy-test";
    private static Client client;

    @ArquillianResource
    private URL url;

    @Deployment(name = DEPLOYMENT)
    public static Archive<?> createDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, DEPLOYMENT + ".war");
        war.addClasses(HelloWorld.class, HelloWorldInterface.class, Greeting.class, Greeter.class, ApplicationConfig.class);
        //war.addAsManifestResource(new StringAsset("Dependencies: org.jboss.resteasy.resteasy-jackson2-provider \n"), "MANIFEST.MF");

        //war.addAsManifestResource(NullJSONObjectResteasyWebTargetTestCase.class.getPackage(), "jboss-deployment-structure.xml", "jboss-deployment-structure.xml");

//        war.addAsManifestResource(new StringAsset(
//                "<jboss-deployment-structure>" + " <deployment>" + " <dependencies>"
//                + " <module name=\"org.jboss.resteasy\" export=\"true\" />"
//                + " </dependencies>"
//                + " </deployment>"
//                + "</jboss-deployment-structure>"), "jboss-deployment-structure.xml");
//        File[] libs1 = Maven.resolver()
//                .loadPomFromFile("pom.xml").resolve("org.jboss.resteasy:resteasy-jackson2-provider")
//                .withTransitivity().as(File.class);
//
//
//        war.addAsLibraries(libs1);

        war.as(org.jboss.shrinkwrap.api.exporter.ZipExporter.class).exportTo(new java.io.File("/home/dcihak/Work/" + war.getName()), true);
        return war;
    }

    @BeforeClass
    public static void setUpClient() {
        client = ClientBuilder.newClient();
    }

    @AfterClass
    public static void close() {
        client.close();
    }

    @Test
    @RunAsClient
    public void testResteasyWebTarget() {
        System.out.println(
                ((ResteasyWebTarget) client
                        .target(url.toExternalForm())
                        .path("resteasy-test")
                        .path("ws")
                        .path("hello"))
                .proxy(HelloWorldInterface.class)
                .helloEntity(new Greeter("world")));
    }
}
