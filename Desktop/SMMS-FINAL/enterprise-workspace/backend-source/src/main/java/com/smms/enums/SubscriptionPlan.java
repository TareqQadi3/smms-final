package com.smms.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SubscriptionPlan {
    TRIAL       (30,  5,   50,    "تجريبي",      "Trial"),
    BASIC       (365, 25,  200,   "أساسي",       "Basic"),
    PROFESSIONAL(365, 75,  -1,    "احترافي",     "Professional"),
    ENTERPRISE  (-1,  -1,  -1,    "مؤسسي",       "Enterprise");

    /** Default duration in days (-1 = no expiry) */
    private final int defaultDurationDays;
    /** Max active users (-1 = unlimited) */
    private final int defaultMaxUsers;
    /** Max work orders per month (-1 = unlimited) */
    private final int defaultMaxWorkOrdersPerMonth;
    private final String labelAr;
    private final String labelEn;
}
