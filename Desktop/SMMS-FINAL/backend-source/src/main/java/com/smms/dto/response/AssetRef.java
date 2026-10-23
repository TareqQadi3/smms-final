package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Short asset reference embedded in other responses. {@code assetCode} stays null until the asset
 * register migration (V40) adds the column and maps it.
 */
@Data @Builder
public class AssetRef {
    UUID id;
    String assetCode;
    String name;
    String nameAr;
}
