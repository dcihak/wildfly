/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jboss.as.test.integration.jaxrs.client.json.deployment;

public class Greeter {

    private String name;

    public Greeter() {
    }

    public Greeter(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return this.name;
    }
    
}
