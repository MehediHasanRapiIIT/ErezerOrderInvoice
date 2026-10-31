package com.erezer.admin.dailyexpense;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.dailyexpense.DailyExpense.Status;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.CategoryRequest;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.CategoryView;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.CreateRequest;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.DailyExpenseView;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.Person;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.Receipt;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.Summary;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.security.Role;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserDirectory;
import com.erezer.admin.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employees and moderators apply for what they spent; an admin sees the details, enters what was paid and approves,
 * or rejects with a reason. Kept apart from the Expenses menu: nothing here changes its totals, profit or the dashboard.
 */
@Service
public class DailyExpenseService {

    /** The list's filters. {@code categoryId} 0 means "Other". */
    public record Filter(Status status, Long categoryId, Long userId, LocalDate from, LocalDate to, String q) {
    }

    private final DailyExpenseRepository expenses;
    private final DailyExpenseCategoryRepository categories;
    private final UserRepository users;
    private final UserDirectory userDirectory;
    private final FileService files;
    private final JdbcClient jdbc;
    private final AppProperties appProperties;
    private final Clock clock;

    DailyExpenseService(DailyExpenseRepository expenses, DailyExpenseCategoryRepository categories,
            UserRepository users, UserDirectory userDirectory, FileService files, JdbcClient jdbc,
            AppProperties appProperties, Clock clock) {
        this.expenses = expenses;
        this.categories = categories;
        this.users = users;
        this.userDirectory = userDirectory;
        this.files = files;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    // ---- Applying ----

    @Transactional
    public DailyExpenseView create(CreateRequest request, long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.conflict("ADMIN_NO_DAILY_EXPENSE", "Daily expenses are added by employees and moderators");
        }
        requireNotFuture(request.date());
        DailyExpenseCategory category = categoryFor(request, null);
        String other = otherFor(request);
        StoredFile receipt = request.receiptFileId() == null ? null : files.requireDailyExpenseReceipt(request.receiptFileId());
        DailyExpense saved = expenses.save(new DailyExpense(userId, request.date(), category, other, request.amount(),
                blankToNull(request.details()), receipt));
        return toViews(List.of(saved)).getFirst();
    }

    /** Changes any expense, even one already decided; what was paid stays, so the amount can't drop below it. */
    @Transactional
    public DailyExpenseView update(long id, CreateRequest request, long editorId) {
        DailyExpense expense = require(id);
        requireNotOwn(expense, editorId);
        if (request.version() == null || request.version() != expense.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This expense was changed by someone else. Reload and try again.");
        }
        requireNotFuture(request.date());
        if (expense.getPaid().compareTo(request.amount()) > 0) {
            throw ApiException.badRequest("AMOUNT_BELOW_PAID", "The amount can't be less than what was already paid");
        }
        StoredFile receipt = request.receiptFileId() == null ? expense.getReceipt() : files.requireDailyExpenseReceipt(request.receiptFileId());
        expense.update(request.date(), categoryFor(request, expense.getCategory()), otherFor(request), request.amount(),
                blankToNull(request.details()), receipt);
        return toViews(List.of(expenses.saveAndFlush(expense))).getFirst();
    }

    /** @param current the expense's category now, which may stay even if since switched off */
    private DailyExpenseCategory categoryFor(CreateRequest request, DailyExpenseCategory current) {
        if (request.categoryId() == null) {
            return null;
        }
        if (current != null && current.getId().equals(request.categoryId())) {
            return current;
        }
        return categories.findById(request.categoryId()).filter(DailyExpenseCategory::isActive)
                .orElseThrow(() -> ApiException.badRequest("INVALID_CATEGORY", "Choose a category from the list"));
    }

    private static String otherFor(CreateRequest request) {
        if (request.categoryId() != null) {
            return null;
        }
        String other = blankToNull(request.otherCategory());
        if (other == null) {
            throw ApiException.badRequest("OTHER_REQUIRED", "Write what the expense was for");
        }
        return other;
    }

    private void requireNotFuture(LocalDate date) {
        if (date.isAfter(LocalDate.now(clock.withZone(appProperties.zoneId())))) {
            throw ApiException.badRequest("FUTURE_DATE", "The date can't be in the future");
        }
    }

    /**
     * Someone can take back their own application until it is decided. Whoever may delete expenses can remove anyone
     * else's, even one already decided.
     */
    @Transactional
    public void delete(long id, long userId, boolean mayDeleteAny) {
        DailyExpense expense = require(id);
        if (expense.getUserId() == userId) {
            requirePending(expense);
        } else if (!mayDeleteAny) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOURS", "This expense isn't yours");
        }
        expenses.delete(expense);
    }

    // ---- Deciding ----

    @Transactional
    public DailyExpenseView approve(long id, BigDecimal paid, long adminId) {
        DailyExpense expense = require(id);
        requireNotOwn(expense, adminId);
        requirePending(expense);
        requireWithinAmount(paid, expense);
        expense.approve(paid, adminId, clock.instant());
        return toViews(List.of(expense)).getFirst();
    }

    @Transactional
    public DailyExpenseView reject(long id, String reason, long adminId) {
        DailyExpense expense = require(id);
        requireNotOwn(expense, adminId);
        requirePending(expense);
        expense.reject(reason.trim(), adminId, clock.instant());
        return toViews(List.of(expense)).getFirst();
    }

    /** Records a later payment on an approved expense, so a due can be cleared. */
    @Transactional
    public DailyExpenseView updatePaid(long id, BigDecimal paid, long adminId) {
        DailyExpense expense = require(id);
        requireNotOwn(expense, adminId);
        if (expense.getStatus() != Status.APPROVED) {
            throw ApiException.conflict("NOT_APPROVED", "Only an approved expense can be paid");
        }
        requireWithinAmount(paid, expense);
        expense.setPaid(paid);
        return toViews(List.of(expense)).getFirst();
    }

    // ---- Lists ----

    @Transactional(readOnly = true)
    public PageResponse<DailyExpenseView> list(Filter filter, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params, true);
        long total = jdbc.sql("select count(*) from daily_expenses e" + JOINS + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Long> ids = jdbc.sql("select e.id from daily_expenses e" + JOINS + where
                        + " order by e.expense_date desc, e.id desc limit :limit offset :offset")
                .params(params).query(Long.class).list();
        Map<Long, DailyExpense> byId = expenses.findByIdIn(ids).stream().collect(Collectors.toMap(DailyExpense::getId, Function.identity()));
        return new PageResponse<>(toViews(ids.stream().map(byId::get).toList()), page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public Summary summary(Filter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params, false);
        return jdbc.sql("""
                        select count(*) filter (where e.status = 'APPROVED'),
                               coalesce(sum(e.amount) filter (where e.status = 'APPROVED'), 0),
                               coalesce(sum(e.paid) filter (where e.status = 'APPROVED'), 0),
                               count(*) filter (where e.status = 'PENDING'),
                               coalesce(sum(e.amount) filter (where e.status = 'PENDING'), 0)
                        from daily_expenses e""" + JOINS + where)
                .params(params)
                .query((rs, i) -> new Summary(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(2).subtract(rs.getBigDecimal(3)), rs.getLong(4), rs.getBigDecimal(5)))
                .single();
    }

    /** Everyone who has applied, for the filter. */
    @Transactional(readOnly = true)
    public List<Person> people() {
        return jdbc.sql("""
                        select distinct u.id, u.full_name from daily_expenses e join users u on u.id = e.user_id
                        order by u.full_name
                        """)
                .query((rs, i) -> new Person(rs.getLong(1), rs.getString(2)))
                .list();
    }

    private static final String JOINS = " left join daily_expense_categories c on c.id = e.category_id";

    private static String where(Filter f, Map<String, Object> params, boolean withStatus) {
        List<String> conditions = new ArrayList<>();
        if (withStatus && f.status() != null) {
            conditions.add("e.status = :status");
            params.put("status", f.status().name());
        }
        if (f.categoryId() != null) {
            if (f.categoryId() == 0) {
                conditions.add("e.category_id is null");
            } else {
                conditions.add("e.category_id = :category");
                params.put("category", f.categoryId());
            }
        }
        if (f.userId() != null) {
            conditions.add("e.user_id = :user");
            params.put("user", f.userId());
        }
        if (f.from() != null) {
            conditions.add("e.expense_date >= :from");
            params.put("from", java.sql.Date.valueOf(f.from()));
        }
        if (f.to() != null) {
            conditions.add("e.expense_date <= :to");
            params.put("to", java.sql.Date.valueOf(f.to()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            conditions.add("(lower(coalesce(e.details, '')) like :q or lower(coalesce(e.other_category, '')) like :q"
                    + " or lower(coalesce(c.name, '')) like :q)");
            params.put("q", "%" + f.q().trim().toLowerCase(Locale.ROOT) + "%");
        }
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    // ---- Categories ----

    @Transactional(readOnly = true)
    public List<CategoryView> categories() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("select category_id, count(*) from daily_expenses where category_id is not null group by category_id")
                .query((rs, i) -> counts.put(rs.getLong(1), rs.getLong(2))).list();
        return categories.findAllByOrderByNameAsc().stream()
                .map(c -> new CategoryView(c.getId(), c.getName(), c.isActive(), counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public void createCategory(CategoryRequest request) {
        String name = request.name().trim();
        if (categories.existsByNameIgnoreCase(name)) {
            throw categoryTaken(name);
        }
        categories.save(new DailyExpenseCategory(name));
    }

    @Transactional
    public void updateCategory(long id, CategoryRequest request) {
        DailyExpenseCategory category = category(id);
        String name = request.name().trim();
        if (categories.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw categoryTaken(name);
        }
        category.rename(name);
        if (request.active() != null) {
            category.setActive(request.active());
        }
    }

    /** Only a category nobody has used can be deleted; a used one is switched off to keep past records intact. */
    @Transactional
    public void deleteCategory(long id) {
        DailyExpenseCategory category = category(id);
        if (expenses.countByCategoryId(id) > 0) {
            throw ApiException.conflict("CATEGORY_IN_USE", "This category has expenses. Deactivate it instead so past records keep their category.");
        }
        categories.delete(category);
    }

    private DailyExpenseCategory category(long id) {
        return categories.findById(id).orElseThrow(() -> ApiException.notFound("Category", id));
    }

    private static ApiException categoryTaken(String name) {
        return ApiException.conflict("CATEGORY_EXISTS", "A category named " + name + " already exists");
    }

    // ---- Rules ----

    private DailyExpense require(long id) {
        return expenses.findById(id).orElseThrow(() -> ApiException.notFound("Daily expense", id));
    }

    private static void requirePending(DailyExpense expense) {
        if (expense.getStatus() != Status.PENDING) {
            throw ApiException.conflict("ALREADY_DECIDED", "This expense has already been approved or rejected");
        }
    }

    private static void requireNotOwn(DailyExpense expense, long reviewerId) {
        if (expense.getUserId() == reviewerId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWN_EXPENSE", "You can't decide or change your own expense");
        }
    }

    private static void requireWithinAmount(BigDecimal paid, DailyExpense expense) {
        if (paid.compareTo(expense.getAmount()) > 0) {
            throw ApiException.badRequest("PAID_TOO_MUCH", "Paid can't be more than the expense");
        }
    }

    private List<DailyExpenseView> toViews(List<DailyExpense> list) {
        List<Long> people = new ArrayList<>();
        list.forEach(e -> {
            people.add(e.getUserId());
            if (e.getReviewedBy() != null) {
                people.add(e.getReviewedBy());
            }
        });
        Map<Long, String> names = userDirectory.namesById(people);
        return list.stream().map(e -> {
            DailyExpenseCategory category = e.getCategory();
            StoredFile receipt = e.getReceipt();
            return new DailyExpenseView(e.getId(), new Person(e.getUserId(), UserDirectory.nameOf(names, e.getUserId())),
                    e.getExpenseDate(), category == null ? null : category.getId(),
                    category == null ? e.getOtherCategory() : category.getName(), category == null, e.getAmount(), e.getPaid(),
                    e.getDue(), e.getDetails(),
                    receipt == null ? null : new Receipt(files.url(receipt), receipt.getContentType()), e.getStatus().name(),
                    e.getRejectionReason(), e.getReviewedBy() == null ? null : UserDirectory.nameOf(names, e.getReviewedBy()),
                    e.getReviewedAt(), e.getCreatedAt(), e.getVersion());
        }).sorted(Comparator.comparing(DailyExpenseView::date).reversed().thenComparing(Comparator.comparingLong(DailyExpenseView::id).reversed()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
