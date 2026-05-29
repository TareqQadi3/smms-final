package com.smms.mapper;

import com.smms.dto.response.CenterSummary;
import com.smms.dto.response.UserResponse;
import com.smms.entity.Center;
import com.smms.entity.User;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "٢٠٢٦-٠٥-٢٩T٠٦:٥٨:١٩+0300",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.46.0.v20260407-0427, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class UserMapperImpl implements UserMapper {

    @Override
    public UserResponse toResponse(User user) {
        if ( user == null ) {
            return null;
        }

        UserResponse.UserResponseBuilder userResponse = UserResponse.builder();

        userResponse.center( toCenterSummary( user.getCenter() ) );
        userResponse.complexName( user.getComplexName() );
        userResponse.buildingNumber( user.getBuildingNumber() );
        userResponse.unitNumber( user.getUnitNumber() );
        userResponse.departmentName( user.getDepartmentName() );
        userResponse.departmentSupervisor( user.getDepartmentSupervisor() );
        userResponse.departmentEmail( user.getDepartmentEmail() );
        userResponse.extensionNumber( user.getExtensionNumber() );
        userResponse.jobTitle( user.getJobTitle() );
        userResponse.email( user.getEmail() );
        userResponse.fullName( user.getFullName() );
        userResponse.fullNameAr( user.getFullNameAr() );
        userResponse.id( user.getId() );
        userResponse.lastLogin( user.getLastLogin() );
        userResponse.phone( user.getPhone() );
        userResponse.role( user.getRole() );
        userResponse.username( user.getUsername() );

        userResponse.active( user.isActive() );

        return userResponse.build();
    }

    @Override
    public CenterSummary toCenterSummary(Center center) {
        if ( center == null ) {
            return null;
        }

        CenterSummary.CenterSummaryBuilder centerSummary = CenterSummary.builder();

        centerSummary.id( center.getId() );
        centerSummary.name( center.getName() );
        centerSummary.nameAr( center.getNameAr() );
        centerSummary.code( center.getCode() );

        return centerSummary.build();
    }
}
