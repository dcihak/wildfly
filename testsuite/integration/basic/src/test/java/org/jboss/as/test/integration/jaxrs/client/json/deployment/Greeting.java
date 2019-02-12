package org.jboss.as.test.integration.jaxrs.client.json.deployment;

public class Greeting {
    
    private Greeter greeter;
    private String message;

    public Greeting() {
    }
    
    private void constructHello() {
        this.message = "Hello " + greeter + "!";
    }
    
    public Greeting(Greeter greeter) {
        this.greeter = greeter;
        constructHello();
    }
    
    @Override
    public String toString() {
        return this.message;
    }
    
}
