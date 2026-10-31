package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Short asset reference embedded in other responses ({@code assetCode} comes from the asset register, V40).
 */
@Data @Builder
public class AssetRef {
    UUID id;
    String assetCode;
    String name;
    String nameAr;
}
