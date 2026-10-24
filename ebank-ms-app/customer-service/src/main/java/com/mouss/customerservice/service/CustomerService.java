package com.mouss.customerservice.service;

import com.mouss.customerservice.entities.Customer;
import com.mouss.customerservice.repository.CustomerRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {
    private CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public long count() {
        return customerRepository.count();
    }
    @McpTool(description = "Get all customers")
    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    @McpTool(description = "Get customer by id")
    public Customer findCustomerById(@McpToolParam(description = "The Customer id") Long id){
        return customerRepository.findById(id).orElseThrow(() ->new RuntimeException("Customer not found"));
    }
    @McpTool(description = "Save a new customer")
    public Customer saveCustomer(@McpToolParam(description = "The Customer to save (name , email") Customer customer){
        return customerRepository.save(customer);
    }
}
