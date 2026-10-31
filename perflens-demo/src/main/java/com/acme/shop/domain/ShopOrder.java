package com.acme.shop.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
public class ShopOrder {
    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;

    private LocalDate createdOn;

    @OneToMany(mappedBy = "order")
    private List<OrderLine> lines = new ArrayList<>();

    protected ShopOrder() {}

    public ShopOrder(Customer customer, LocalDate createdOn) {
        this.customer = customer;
        this.createdOn = createdOn;
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public LocalDate getCreatedOn() { return createdOn; }
    public List<OrderLine> getLines() { return lines; }
}
