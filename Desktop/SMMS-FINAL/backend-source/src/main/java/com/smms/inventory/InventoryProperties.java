package com.smms.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Keys {@code app.inventory.*}; safe defaults in code. */
@Component
@ConfigurationProperties(prefix = "app.inventory")
public class InventoryProperties {
    /** Row-lock wait before the request fails with 409 "busy", milliseconds (SET LOCAL lock_timeout). */
    private int lockTimeoutMs = 5000;
    /** Zone used to turn from/to dates of the movement history into instants. */
    private String zone = "Asia/Riyadh";
    /** Maximum span of a movement history query, days. */
    private int maxRangeDays = 366;
    /** Default span when from/to are omitted, days. */
    private int defaultRangeDays = 30;
    /** Send LOW_STOCK notifications. */
    private boolean lowStockNotifications = true;

    public int getLockTimeoutMs() { return lockTimeoutMs; }
    public void setLockTimeoutMs(int v) { this.lockTimeoutMs = Math.max(100, v); }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public int getMaxRangeDays() { return maxRangeDays; }
    public void setMaxRangeDays(int v) { this.maxRangeDays = v; }
    public int getDefaultRangeDays() { return defaultRangeDays; }
    public void setDefaultRangeDays(int v) { this.defaultRangeDays = v; }
    public boolean isLowStockNotifications() { return lowStockNotifications; }
    public void setLowStockNotifications(boolean v) { this.lowStockNotifications = v; }
}
