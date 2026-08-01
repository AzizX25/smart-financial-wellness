package com.azizx25.mockbank.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.exception.CustomerNotFoundException;

@Service
@Transactional(readOnly = true)
public class CustomerLookupService {

    private final BankCustomerRepository customerRepository;

    public CustomerLookupService(BankCustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public CustomerSummary loadByExternalCustomerId(String externalCustomerId) {
        return toSummary(resolveCustomer(externalCustomerId));
    }

    BankCustomer resolveCustomer(String externalCustomerId) {
        return customerRepository.findByExternalCustomerId(externalCustomerId)
                .orElseThrow(CustomerNotFoundException::new);
    }

    private CustomerSummary toSummary(BankCustomer customer) {
        return new CustomerSummary(
                customer.getExternalCustomerId(),
                customer.getDisplayName(),
                customer.getCountryCode(),
                customer.getStatus(),
                customer.getCreatedAt());
    }
}