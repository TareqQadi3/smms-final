package com.smms.mapper;

import com.smms.dto.response.*;
import com.smms.entity.*;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface WorkOrderMapper {

    @Mapping(target = "requestedBy", source = "requestedBy")
    @Mapping(target = "assignedTo",  source = "assignedTo")
    @Mapping(target = "center",      source = "center")
    @Mapping(target = "controlRoom", source = "controlRoom")
    @Mapping(target = "possibleDuplicates", ignore = true)
    @Mapping(target = "assetId",      source = "asset.id")
    @Mapping(target = "asset",        source = "asset")
    @Mapping(target = "departmentId", source = "department.id")
    @Mapping(target = "emergency",    source = "emergency")
    @Mapping(target = "slaPaused",    expression = "java(wo.getSlaPausedAt() != null)")
    // computed by the workflow module (escalation rows), not a work_orders column
    @Mapping(target = "escalationLevel", ignore = true)
    WorkOrderResponse toResponse(WorkOrder wo);

    AssetRef toAssetRef(Asset asset);

    @Mapping(target = "id",       source = "id")
    @Mapping(target = "username", source = "username")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "role",     source = "role")
    UserSummary toUserSummary(User user);

    @Mapping(target = "id",     source = "id")
    @Mapping(target = "name",   source = "name")
    @Mapping(target = "nameAr", source = "nameAr")
    @Mapping(target = "code",   source = "code")
    CenterSummary toCenterSummary(Center center);

    @Mapping(target = "id",   source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "type", source = "type")
    ControlRoomSummary toControlRoomSummary(ControlRoom cr);
}
