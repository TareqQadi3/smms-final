package com.smms.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.*;
import com.smms.enums.RequesterConfirmation;
import com.smms.enums.TicketPriority;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping smoke for the feature-program-1 fields (V13). */
class WorkOrderMapperTest {

    private final WorkOrderMapper mapper = Mappers.getMapper(WorkOrderMapper.class);

    @Test
    void newColumnsDefaultToNeutralValues() {
        WorkOrderResponse r = mapper.toResponse(WorkOrder.builder().build());
        assertThat(r.getAssetId()).isNull();
        assertThat(r.getAsset()).isNull();
        assertThat(r.getDepartmentId()).isNull();
        assertThat(r.isEmergency()).isFalse();
        assertThat(r.isSlaPaused()).isFalse();
        assertThat(r.getSlaPausedSeconds()).isZero();
        assertThat(r.getRequesterConfirmation()).isNull();
        assertThat(r.getEscalationLevel()).isZero();
    }

    @Test
    void mapsAssetDepartmentPauseEmergencyAndConfirmation() {
        Asset asset = Asset.builder().assetCode("AST-001").name("Pump").nameAr("مضخة").build();
        asset.setId(UUID.randomUUID());
        Department dep = Department.builder().name("ER").nameAr("طوارئ").code("ER").build();
        dep.setId(UUID.randomUUID());
        Instant now = Instant.parse("2026-01-01T10:00:00Z");

        WorkOrder wo = WorkOrder.builder()
            .asset(asset).department(dep)
            .slaPausedAt(now).slaPausedSeconds(90)
            .emergency(true).emergencyDeclaredAt(now)
            .requesterConfirmation(RequesterConfirmation.disputed).requesterConfirmedAt(now)
            .priority(TicketPriority.critical)
            .build();

        WorkOrderResponse r = mapper.toResponse(wo);
        assertThat(r.getAssetId()).isEqualTo(asset.getId());
        assertThat(r.getAsset().getId()).isEqualTo(asset.getId());
        assertThat(r.getAsset().getName()).isEqualTo("Pump");
        assertThat(r.getAsset().getNameAr()).isEqualTo("مضخة");
        assertThat(r.getAsset().getAssetCode()).isEqualTo("AST-001");
        assertThat(r.getDepartmentId()).isEqualTo(dep.getId());
        assertThat(r.isEmergency()).isTrue();
        assertThat(r.getEmergencyDeclaredAt()).isEqualTo(now);
        assertThat(r.isSlaPaused()).isTrue();
        assertThat(r.getSlaPausedSeconds()).isEqualTo(90);
        assertThat(r.getRequesterConfirmation()).isEqualTo(RequesterConfirmation.disputed);
        assertThat(r.getRequesterConfirmedAt()).isEqualTo(now);
    }

    @Test
    void jsonUsesContractNames() throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        WorkOrder wo = WorkOrder.builder().emergency(true)
            .requesterConfirmation(RequesterConfirmation.accepted).build();
        JsonNode n = json.readTree(json.writeValueAsString(mapper.toResponse(wo)));
        assertThat(n.get("isEmergency").asBoolean()).isTrue();
        assertThat(n.has("emergency")).isFalse();
        assertThat(n.get("requesterConfirmation").asText()).isEqualTo("accepted");
        assertThat(n.get("slaPaused").asBoolean()).isFalse();
        assertThat(n.get("escalationLevel").asInt()).isZero();
    }

    @Test
    void newWorkOrderHasNullVersionSoItIsTreatedAsNew() {
        assertThat(WorkOrder.builder().build().getVersion()).isNull();
    }
}
