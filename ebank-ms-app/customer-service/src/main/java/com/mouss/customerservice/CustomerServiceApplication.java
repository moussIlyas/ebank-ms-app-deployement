package com.mouss.customerservice;

import com.mouss.customerservice.entities.Customer;
import com.mouss.customerservice.service.CustomerService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class CustomerServiceApplication {

    public static void main(String[] args) {

        SpringApplication.run(CustomerServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(CustomerService customerService) {
        return args -> {
            if (customerService.count() > 0) {
                return;
            }
            List<String> names = List.of("Ilyas", "Omar", "Nadir");
            names.forEach(name -> {
                customerService.saveCustomer(Customer.builder()
                        .name(name).email(name+"@gmail.com")
                        .build());

            });
        };
    }

}
