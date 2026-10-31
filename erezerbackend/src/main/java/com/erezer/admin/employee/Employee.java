package com.erezer.admin.employee;

import com.erezer.admin.common.persistence.AuditableEntity;
import com.erezer.admin.file.StoredFile;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** A person the shop employs. Not a login: whether they can sign in is decided under Staff Management. */
@Entity
@Table(name = "employees")
public class Employee extends AuditableEntity {

    public enum AccountType {
        BANK,
        BKASH,
        NAGAD,
        ROCKET,
        OTHER
    }

    /** Where salary goes. */
    @Embeddable
    public static class SalaryAccount {

        @Enumerated(EnumType.STRING)
        @Column(name = "account_type", nullable = false, length = 10)
        private AccountType type;

        @Column(name = "provider", length = 80)
        private String provider;

        @Column(name = "account_name", length = 120)
        private String accountName;

        @Column(name = "account_number", nullable = false, length = 60)
        private String accountNumber;

        @Column(name = "branch", length = 120)
        private String branch;

        @Column(name = "routing_number", length = 30)
        private String routingNumber;

        protected SalaryAccount() {
            // for JPA
        }

        SalaryAccount(AccountType type, String provider, String accountName, String accountNumber, String branch, String routingNumber) {
            this.type = type;
            this.provider = provider;
            this.accountName = accountName;
            this.accountNumber = accountNumber;
            this.branch = branch;
            this.routingNumber = routingNumber;
        }

        public AccountType getType() {
            return type;
        }

        public String getProvider() {
            return provider;
        }

        public String getAccountName() {
            return accountName;
        }

        public String getAccountNumber() {
            return accountNumber;
        }

        public String getBranch() {
            return branch;
        }

        public String getRoutingNumber() {
            return routingNumber;
        }
    }

    /** Everything the form sets. */
    record Details(String employeeCode, String fullName, String phone, String fatherName, String fatherPhone,
            String motherName, String motherPhone, String presentAddress, String permanentAddress, String nidNumber,
            StoredFile photo, StoredFile nidImage, StoredFile idCardImage, List<SalaryAccount> salaryAccounts) {
    }

    @Column(name = "employee_code", nullable = false, length = 40)
    private String employeeCode;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "father_name", length = 120)
    private String fatherName;

    @Column(name = "father_phone", length = 30)
    private String fatherPhone;

    @Column(name = "mother_name", length = 120)
    private String motherName;

    @Column(name = "mother_phone", length = 30)
    private String motherPhone;

    @Column(name = "present_address", nullable = false, length = 500)
    private String presentAddress;

    @Column(name = "permanent_address", length = 500)
    private String permanentAddress;

    @Column(name = "nid_number", nullable = false, length = 20)
    private String nidNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_image_id", nullable = false)
    private StoredFile photo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "nid_image_id", nullable = false)
    private StoredFile nidImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_card_image_id")
    private StoredFile idCardImage;

    @ElementCollection
    @CollectionTable(name = "employee_salary_accounts", joinColumns = @JoinColumn(name = "employee_id"))
    @OrderColumn(name = "position")
    private List<SalaryAccount> salaryAccounts = new ArrayList<>();

    protected Employee() {
        // for JPA
    }

    Employee(Details details) {
        apply(details);
    }

    final void apply(Details d) {
        this.employeeCode = d.employeeCode();
        this.fullName = d.fullName();
        this.phone = d.phone();
        this.fatherName = d.fatherName();
        this.fatherPhone = d.fatherPhone();
        this.motherName = d.motherName();
        this.motherPhone = d.motherPhone();
        this.presentAddress = d.presentAddress();
        this.permanentAddress = d.permanentAddress();
        this.nidNumber = d.nidNumber();
        this.photo = d.photo();
        this.nidImage = d.nidImage();
        this.idCardImage = d.idCardImage();
        this.salaryAccounts.clear();
        this.salaryAccounts.addAll(d.salaryAccounts());
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public String getFatherName() {
        return fatherName;
    }

    public String getFatherPhone() {
        return fatherPhone;
    }

    public String getMotherName() {
        return motherName;
    }

    public String getMotherPhone() {
        return motherPhone;
    }

    public String getPresentAddress() {
        return presentAddress;
    }

    public String getPermanentAddress() {
        return permanentAddress;
    }

    public String getNidNumber() {
        return nidNumber;
    }

    public StoredFile getPhoto() {
        return photo;
    }

    public StoredFile getNidImage() {
        return nidImage;
    }

    public StoredFile getIdCardImage() {
        return idCardImage;
    }

    public List<SalaryAccount> getSalaryAccounts() {
        return salaryAccounts;
    }
}
