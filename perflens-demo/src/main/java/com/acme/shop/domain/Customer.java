package com.acme.shop.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class Customer {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    private String email;
    private String tier;

    protected Customer() {}

    public Customer(String name, String email, String tier) {
        this.name = name;
        this.email = email;
        this.tier = tier;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getTier() { return tier; }
}
