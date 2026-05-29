package com.smms.mapper;

import com.smms.dto.response.CenterSummary;
import com.smms.dto.response.UserResponse;
import com.smms.entity.Center;
import com.smms.entity.User;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "active",               expression = "java(user.isActive())")
    @Mapping(target = "center",               source = "center")
    @Mapping(target = "complexName",          source = "complexName")
    @Mapping(target = "buildingNumber",       source = "buildingNumber")
    @Mapping(target = "unitNumber",           source = "unitNumber")
    @Mapping(target = "departmentName",       source = "departmentName")
    @Mapping(target = "departmentSupervisor", source = "departmentSupervisor")
    @Mapping(target = "departmentEmail",      source = "departmentEmail")
    @Mapping(target = "extensionNumber",      source = "extensionNumber")
    @Mapping(target = "jobTitle",             source = "jobTitle")
    UserResponse toResponse(User user);

    @Mapping(target = "id",     source = "id")
    @Mapping(target = "name",   source = "name")
    @Mapping(target = "nameAr", source = "nameAr")
    @Mapping(target = "code",   source = "code")
    CenterSummary toCenterSummary(Center center);
}
