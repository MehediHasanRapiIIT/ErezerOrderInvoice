package com.erezer.admin.employee;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.employee.Employee.AccountType;
import com.erezer.admin.employee.Employee.SalaryAccount;
import com.erezer.admin.employee.EmployeeDtos.EmployeeFile;
import com.erezer.admin.employee.EmployeeDtos.EmployeeRequest;
import com.erezer.admin.employee.EmployeeDtos.EmployeeSummary;
import com.erezer.admin.employee.EmployeeDtos.EmployeeView;
import com.erezer.admin.employee.EmployeeDtos.SalaryAccountDto;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Employee records, with their photo, NID and ID card images. */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final FileService files;

    EmployeeService(EmployeeRepository employees, FileService files) {
        this.employees = employees;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummary> list(String q, Pageable pageable) {
        Page<Employee> page = q == null || q.isBlank()
                ? employees.findAll(pageable)
                : employees.search("%" + q.trim().toLowerCase(Locale.ROOT) + "%", pageable);
        return PageResponse.of(page, e -> new EmployeeSummary(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getPhone(),
                e.getNidNumber(), e.getPresentAddress(), files.url(e.getPhoto())));
    }

    @Transactional(readOnly = true)
    public EmployeeView get(long id) {
        return toView(require(id));
    }

    @Transactional
    public EmployeeView create(EmployeeRequest request) {
        String code = request.employeeCode().trim();
        String nid = request.nidNumber().trim();
        if (employees.existsByEmployeeCodeIgnoreCase(code)) {
            throw codeTaken(code);
        }
        if (employees.existsByNidNumber(nid)) {
            throw nidTaken();
        }
        return toView(employees.save(new Employee(details(request, null))));
    }

    @Transactional
    public EmployeeView update(long id, EmployeeRequest request) {
        Employee employee = require(id);
        if (request.version() == null || request.version() != employee.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This employee was changed by someone else. Reload and try again.");
        }
        String code = request.employeeCode().trim();
        if (employees.existsByEmployeeCodeIgnoreCaseAndIdNot(code, id)) {
            throw codeTaken(code);
        }
        if (employees.existsByNidNumberAndIdNot(request.nidNumber().trim(), id)) {
            throw nidTaken();
        }
        employee.apply(details(request, employee));
        return toView(employees.saveAndFlush(employee));
    }

    /** The images are left for the nightly clean-up of files nothing refers to. */
    @Transactional
    public void delete(long id) {
        employees.delete(require(id));
    }

    private Employee require(long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee", id));
    }

    private Employee.Details details(EmployeeRequest r, Employee current) {
        StoredFile photo = files.requireEmployeeImage(r.photoFileId(), current == null ? null : current.getPhoto().getId());
        StoredFile nidImage = files.requireEmployeeImage(r.nidImageFileId(), current == null ? null : current.getNidImage().getId());
        StoredFile idCard = r.idCardImageFileId() == null ? null : files.requireEmployeeImage(r.idCardImageFileId(),
                current == null || current.getIdCardImage() == null ? null : current.getIdCardImage().getId());
        List<SalaryAccount> accounts = r.salaryAccounts() == null ? List.of()
                : r.salaryAccounts().stream().map(EmployeeService::toAccount).toList();
        return new Employee.Details(r.employeeCode().trim(), r.fullName().trim(), r.phone().trim(), blankToNull(r.fatherName()),
                blankToNull(r.fatherPhone()), blankToNull(r.motherName()), blankToNull(r.motherPhone()), r.presentAddress().trim(),
                blankToNull(r.permanentAddress()), r.nidNumber().trim(), photo, nidImage, idCard, accounts);
    }

    private static SalaryAccount toAccount(SalaryAccountDto a) {
        String provider = blankToNull(a.provider());
        if (provider == null && (a.type() == AccountType.BANK || a.type() == AccountType.OTHER)) {
            throw ApiException.badRequest("ACCOUNT_PROVIDER_REQUIRED",
                    a.type() == AccountType.BANK ? "Enter the bank's name" : "Enter what kind of account it is");
        }
        boolean bank = a.type() == AccountType.BANK;
        return new SalaryAccount(a.type(), provider, blankToNull(a.accountName()), a.accountNumber().trim(),
                bank ? blankToNull(a.branch()) : null, bank ? blankToNull(a.routingNumber()) : null);
    }

    private EmployeeView toView(Employee e) {
        return new EmployeeView(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getPhone(), e.getFatherName(),
                e.getFatherPhone(), e.getMotherName(), e.getMotherPhone(), e.getPresentAddress(), e.getPermanentAddress(),
                e.getNidNumber(), file(e.getPhoto()), file(e.getNidImage()), file(e.getIdCardImage()),
                e.getSalaryAccounts().stream()
                        .map(a -> new SalaryAccountDto(a.getType(), a.getProvider(), a.getAccountName(), a.getAccountNumber(), a.getBranch(), a.getRoutingNumber()))
                        .toList(),
                e.getCreatedAt(), e.getVersion());
    }

    private EmployeeFile file(StoredFile file) {
        return file == null ? null : new EmployeeFile(file.getId(), files.url(file));
    }

    private static ApiException codeTaken(String code) {
        return ApiException.conflict("EMPLOYEE_CODE_EXISTS", "Employee ID " + code + " is already used");
    }

    private static ApiException nidTaken() {
        return ApiException.conflict("NID_EXISTS", "An employee with this NID number is already registered");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
