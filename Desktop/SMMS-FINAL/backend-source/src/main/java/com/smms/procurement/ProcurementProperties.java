package com.smms.procurement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@code app.procurement.manager-approval-limit}: purchase orders whose total is above this amount
 * can only be approved by operating_company_manager or system_admin (a maintenance_manager may not).
 * Empty (default) = no threshold, every approver role may approve any amount.
 */
@Component
@ConfigurationProperties(prefix = "app.procurement")
public class ProcurementProperties {

    private BigDecimal managerApprovalLimit;

    public BigDecimal getManagerApprovalLimit() { return managerApprovalLimit; }

    public void setManagerApprovalLimit(BigDecimal limit) { this.managerApprovalLimit = limit; }
}
