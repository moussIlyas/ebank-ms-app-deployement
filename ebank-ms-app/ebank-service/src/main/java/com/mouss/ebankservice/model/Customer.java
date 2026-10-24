package com.mouss.ebankservice.model;

import jakarta.persistence.Id;
import lombok.*;

@Setter @Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {
    @Id
    private Long id;
    private String name;
    private String email;
}
