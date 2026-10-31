package com.erezer.admin.employee;

import com.erezer.admin.employee.Employee.AccountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class EmployeeDtos {

    private static final String PHONE = "^[0-9+\\-\\s]{6,30}$";
    /** Bangladesh NID numbers have 10, 13 or 17 digits. */
    private static final String NID = "^(\\d{10}|\\d{13}|\\d{17})$";

    private EmployeeDtos() {
    }

    /** @param branch and {@code routingNumber} are for bank accounts only */
    public record SalaryAccountDto(
            @NotNull AccountType type,
            @Size(max = 80) String provider,
            @Size(max = 120) String accountName,
            @NotBlank @Size(max = 60) String accountNumber,
            @Size(max = 120) String branch,
            @Size(max = 30) String routingNumber) {
    }

    /** @param version only on edit: the version the form loaded */
    public record EmployeeRequest(
            @NotBlank @Size(max = 40) String employeeCode,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = PHONE, message = "is not a valid phone number") String phone,
            @Size(max = 120) String fatherName,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String fatherPhone,
            @Size(max = 120) String motherName,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String motherPhone,
            @NotBlank @Size(max = 500) String presentAddress,
            @Size(max = 500) String permanentAddress,
            @NotBlank @Pattern(regexp = NID, message = "must have 10, 13 or 17 digits") String nidNumber,
            @NotNull Long photoFileId,
            @NotNull Long nidImageFileId,
            Long idCardImageFileId,
            @Size(max = 10) List<@Valid SalaryAccountDto> salaryAccounts,
            Long version) {
    }

    /** A row of the list: the photo, but no NID image, parents or salary accounts. */
    public record EmployeeSummary(long id, String employeeCode, String fullName, String phone, String nidNumber,
            String presentAddress, String photoUrl) {
    }

    public record EmployeeFile(long fileId, String url) {
    }

    public record EmployeeView(
            long id,
            String employeeCode,
            String fullName,
            String phone,
            String fatherName,
            String fatherPhone,
            String motherName,
            String motherPhone,
            String presentAddress,
            String permanentAddress,
            String nidNumber,
            EmployeeFile photo,
            EmployeeFile nidImage,
            EmployeeFile idCardImage,
            List<SalaryAccountDto> salaryAccounts,
            Instant createdAt,
            long version) {
    }
}
