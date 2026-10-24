package com.mouss.ebankservice.service;

import com.mouss.ebankservice.entities.BankAccount;
import com.mouss.ebankservice.feign.CustomerRestClient;
import com.mouss.ebankservice.model.Customer;
import com.mouss.ebankservice.repository.BankAccountRepository;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service

public class EbankService {
    private BankAccountRepository accountRepository;
    private CustomerRestClient customerRestClient;

    public EbankService(BankAccountRepository accountRepository, CustomerRestClient customerRestClient){
        this.accountRepository = accountRepository;
        this.customerRestClient = customerRestClient;
    }

    public long count() {
        return accountRepository.count();
    }
    public List<BankAccount> getAllBankAccounts(){
        return accountRepository.findAll();
    }


    public BankAccount getBankAccountById(String id){
        BankAccount bankAccount = accountRepository.findById(id)
                .orElseThrow(() ->new RuntimeException("Bank Account not found"));
        bankAccount.setCustomer(
                customerRestClient.getCustomerById(bankAccount.getCustomerId()));
        return bankAccount;
    }
    public BankAccount save(BankAccount bankAccount) {
        try{
            Customer customer = customerRestClient.getCustomerById(bankAccount.getCustomerId());
            bankAccount.setId(UUID.randomUUID().toString());
            bankAccount.setCreationAt(new Date());
            return accountRepository.save(bankAccount);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }


    }
}

