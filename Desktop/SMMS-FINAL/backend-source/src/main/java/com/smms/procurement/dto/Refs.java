package com.smms.procurement.dto;

import java.util.UUID;

/** Small reference objects embedded in procurement and material-request responses. */
public final class Refs {
    private Refs() {}

    public record UserRef(UUID id, String fullName, String fullNameAr) {}

    public record CenterRef(UUID id, String name, String nameAr, String code) {}

    public record SupplierRef(UUID id, String code, String name, String nameAr) {}
}
