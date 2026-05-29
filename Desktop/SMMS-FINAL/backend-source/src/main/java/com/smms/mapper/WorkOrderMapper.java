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
    WorkOrderResponse toResponse(WorkOrder wo);

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
