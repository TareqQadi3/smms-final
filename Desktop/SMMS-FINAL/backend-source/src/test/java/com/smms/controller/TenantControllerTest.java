package com.smms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.dto.request.CreateTenantRequest;
import com.smms.dto.response.CreateTenantResponse;
import com.smms.dto.response.TenantResponse;
import com.smms.dto.response.TenantSubscriptionResponse;
import com.smms.entity.Tenant;
import com.smms.entity.TenantSubscription;
import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;
import com.smms.service.PlanEnforcementService;
import com.smms.service.SubscriptionService;
import com.smms.service.TenantService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SEC-I: the platform tenant API returns DTOs with a fixed shape, never entities. */
class TenantControllerTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private TenantService tenantService;
    private SubscriptionService subscriptionService;
    private TenantController controller;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        subscriptionService = mock(SubscriptionService.class);
        controller = new TenantController(tenantService, subscriptionService, mock(PlanEnforcementService.class));
    }

    private Tenant tenant() {
        return Tenant.builder().id(id).name("T").nameAr("ت").slug("t-1").isActive(true).createdAt(Instant.now()).build();
    }

    private TenantSubscription subscription() {
        return TenantSubscription.builder().id(UUID.randomUUID()).tenantId(id).planType(PlanType.FREE)
            .status(TenantStatus.ACTIVE).startDate(Instant.now()).notes("n").updatedAt(Instant.now()).build();
    }

    private Set<String> keys(Object o) {
        JsonNode n = json.valueToTree(o);
        Set<String> out = new java.util.TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void controllerIsSystemAdminOnly() {
        assertThat(TenantController.class.getAnnotation(PreAuthorize.class).value()).contains("system_admin");
    }

    @Test
    void listReturnsPagedDtosWithFixedFields() {
        when(tenantService.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(tenant())));
        Page<TenantResponse> page = controller.listAll(0, 50, null).getBody();
        assertThat(page.getContent()).hasSize(1);
        assertThat(keys(page.getContent().get(0)))
            .containsExactlyInAnyOrder("id", "name", "nameAr", "slug", "active", "createdAt");
    }

    @Test
    void listIsBoundedAndSortWhitelisted() {
        when(tenantService.list(any(Pageable.class))).thenReturn(Page.empty());
        controller.listAll(0, 5000, List.of("name", "desc"));
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(tenantService).list(c.capture());
        assertThat(c.getValue().getPageSize()).isEqualTo(100);
        assertThat(c.getValue().getSort().getOrderFor("name").isDescending()).isTrue();

        assertThatThrownBy(() -> controller.listAll(0, 10, List.of("notes"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.listAll(-1, 10, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createReturnsTypedDtoWithoutEntityInternals() {
        when(tenantService.create("T", "ت", "t-1")).thenReturn(tenant());
        when(subscriptionService.createDefault(id)).thenReturn(subscription());
        CreateTenantRequest req = new CreateTenantRequest();
        req.setName("T"); req.setNameAr("ت"); req.setSlug("t-1");

        CreateTenantResponse body = controller.create(req).getBody();

        assertThat(keys(body)).containsExactlyInAnyOrder("tenant", "subscription");
        assertThat(keys(body.tenant())).doesNotContain("deleted");
        assertThat(keys(body.subscription()))
            .containsExactlyInAnyOrder("tenantId", "planType", "status", "startDate", "endDate", "notes")
            .doesNotContain("id", "createdAt", "updatedAt");
    }

    @Test
    void subscriptionEndpointsReturnTheSubscriptionDto() {
        TenantSubscription s = subscription();
        when(subscriptionService.get(id)).thenReturn(s);
        when(subscriptionService.suspend(id, "why")).thenReturn(s);
        when(subscriptionService.activate(id)).thenReturn(s);
        when(subscriptionService.changePlan(id, PlanType.PRO, 30)).thenReturn(s);
        assertThat(controller.getSubscription(id).getBody()).isInstanceOf(TenantSubscriptionResponse.class);
        assertThat(controller.suspend(id, "why").getBody()).isInstanceOf(TenantSubscriptionResponse.class);
        assertThat(controller.activate(id).getBody()).isInstanceOf(TenantSubscriptionResponse.class);
        assertThat(controller.changePlan(id, PlanType.PRO, 30).getBody()).isInstanceOf(TenantSubscriptionResponse.class);
    }

    @Test
    void getByIdAndToggleReturnTheTenantDto() {
        when(tenantService.getById(id)).thenReturn(tenant());
        when(tenantService.toggleActive(id, false)).thenReturn(tenant());
        assertThat(controller.getById(id).getBody()).isInstanceOf(TenantResponse.class);
        assertThat(controller.toggleActive(id, false).getBody()).isInstanceOf(TenantResponse.class);
    }

    @Test
    void analyticsNeverReturnsExceptionText() {
        when(tenantService.listAll()).thenReturn(List.of(tenant()));
        when(subscriptionService.get(id)).thenThrow(new RuntimeException("jdbc:postgresql://secret-host/db failed"));
        var out = controller.allUsage().getBody();
        assertThat(out.get(0).get("error")).isEqualTo("unavailable");
        assertThat(out.toString()).doesNotContain("secret-host");
    }

    // ── request validation ──────────────────────────────────

    @Test
    void createRequestIsValidated() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        CreateTenantRequest ok = new CreateTenantRequest();
        ok.setName("Acme"); ok.setSlug("acme-1");
        assertThat(v.validate(ok)).isEmpty();

        CreateTenantRequest noName = new CreateTenantRequest();
        assertThat(v.validate(noName)).isNotEmpty();

        CreateTenantRequest badSlug = new CreateTenantRequest();
        badSlug.setName("Acme"); badSlug.setSlug("Bad Slug!");
        assertThat(v.validate(badSlug)).isNotEmpty();

        CreateTenantRequest longName = new CreateTenantRequest();
        longName.setName("x".repeat(201));
        assertThat(v.validate(longName)).isNotEmpty();
    }
}
