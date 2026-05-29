package com.smms.mapper;

import com.smms.dto.response.CenterSummary;
import com.smms.dto.response.ControlRoomSummary;
import com.smms.dto.response.UserSummary;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "٢٠٢٦-٠٥-٢٩T٠٦:٥٨:١٩+0300",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.46.0.v20260407-0427, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class WorkOrderMapperImpl implements WorkOrderMapper {

    @Override
    public WorkOrderResponse toResponse(WorkOrder wo) {
        if ( wo == null ) {
            return null;
        }

        WorkOrderResponse.WorkOrderResponseBuilder workOrderResponse = WorkOrderResponse.builder();

        workOrderResponse.requestedBy( toUserSummary( wo.getRequestedBy() ) );
        workOrderResponse.assignedTo( toUserSummary( wo.getAssignedTo() ) );
        workOrderResponse.center( toCenterSummary( wo.getCenter() ) );
        workOrderResponse.controlRoom( toControlRoomSummary( wo.getControlRoom() ) );
        workOrderResponse.approvalDecision( wo.getApprovalDecision() );
        workOrderResponse.closedAt( wo.getClosedAt() );
        workOrderResponse.completedDate( wo.getCompletedDate() );
        workOrderResponse.createdAt( wo.getCreatedAt() );
        workOrderResponse.currentStage( wo.getCurrentStage() );
        workOrderResponse.description( wo.getDescription() );
        workOrderResponse.id( wo.getId() );
        workOrderResponse.locationDetail( wo.getLocationDetail() );
        workOrderResponse.locked( wo.isLocked() );
        workOrderResponse.needsMaterials( wo.isNeedsMaterials() );
        workOrderResponse.priority( wo.getPriority() );
        workOrderResponse.rejectionReason( wo.getRejectionReason() );
        workOrderResponse.reopenedCount( wo.getReopenedCount() );
        workOrderResponse.slaBreached( wo.isSlaBreached() );
        workOrderResponse.slaDeadline( wo.getSlaDeadline() );
        workOrderResponse.status( wo.getStatus() );
        workOrderResponse.subType( wo.getSubType() );
        workOrderResponse.techReport( wo.getTechReport() );
        workOrderResponse.ticketNumber( wo.getTicketNumber() );
        workOrderResponse.title( wo.getTitle() );
        workOrderResponse.warehouseNotes( wo.getWarehouseNotes() );

        return workOrderResponse.build();
    }

    @Override
    public UserSummary toUserSummary(User user) {
        if ( user == null ) {
            return null;
        }

        UserSummary.UserSummaryBuilder userSummary = UserSummary.builder();

        userSummary.id( user.getId() );
        userSummary.username( user.getUsername() );
        userSummary.fullName( user.getFullName() );
        userSummary.role( user.getRole() );

        return userSummary.build();
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

    @Override
    public ControlRoomSummary toControlRoomSummary(ControlRoom cr) {
        if ( cr == null ) {
            return null;
        }

        ControlRoomSummary.ControlRoomSummaryBuilder controlRoomSummary = ControlRoomSummary.builder();

        controlRoomSummary.id( cr.getId() );
        controlRoomSummary.name( cr.getName() );
        controlRoomSummary.type( cr.getType() );
        controlRoomSummary.code( cr.getCode() );
        controlRoomSummary.nameAr( cr.getNameAr() );

        return controlRoomSummary.build();
    }
}
