import { describe, expect, it } from 'vitest';
import {
  canAddNote, canClearEmergency, canDeclareEmergency, canDeclareEmergencyAtCreate, canDecideApproval,
  canEscalate, canReadAssets, canReadEscalations, deriveActions,
} from './permissions';
import type { WorkOrderDetail } from './types';

const wo = (o: Partial<WorkOrderDetail> = {}): WorkOrderDetail =>
  ({ id: 'w1', ticketNumber: 'T-1', title: 't', status: 'in_progress', priority: 'high', subType: 'hvac', createdAt: '2026-01-01T00:00:00Z', ...o }) as WorkOrderDetail;

describe('emergency permissions', () => {
  it('declare: control room, MM, HS, admin; never on terminal or already-emergency tickets', () => {
    for (const r of ['control_room', 'maintenance_manager', 'hospital_supervisor', 'system_admin']) {
      expect(canDeclareEmergency(r, wo())).toBe(true);
    }
    for (const r of ['technician', 'resident', 'electrical_manager', 'operating_company_manager', undefined]) {
      expect(canDeclareEmergency(r, wo())).toBe(false);
    }
    expect(canDeclareEmergency('control_room', wo({ isEmergency: true }))).toBe(false);
    expect(canDeclareEmergency('control_room', wo({ status: 'closed' }))).toBe(false);
  });
  it('clear: MM, HS, admin only (not control room) and only while in emergency', () => {
    expect(canClearEmergency('maintenance_manager', wo({ isEmergency: true }))).toBe(true);
    expect(canClearEmergency('control_room', wo({ isEmergency: true }))).toBe(false);
    expect(canClearEmergency('system_admin', wo())).toBe(false);
  });
  it('emergency at creation follows the declare roles', () => {
    expect(canDeclareEmergencyAtCreate('control_room')).toBe(true);
    expect(canDeclareEmergencyAtCreate('resident')).toBe(false);
  });
});

describe('escalation permissions', () => {
  it('manual escalate: allowed roles, not paused, not terminal, below level 3', () => {
    expect(canEscalate('electrical_manager', wo())).toBe(true);
    expect(canEscalate('technician', wo())).toBe(false);
    expect(canEscalate('control_room', wo({ slaPaused: true }))).toBe(false);
    expect(canEscalate('control_room', wo({ escalationLevel: 3 }))).toBe(false);
    expect(canEscalate('control_room', wo({ status: 'cancelled' }))).toBe(false);
  });
  it('history is hidden from requesters and technicians', () => {
    expect(canReadEscalations('warehouse')).toBe(true);
    expect(canReadEscalations('resident')).toBe(false);
    expect(canReadEscalations('technician')).toBe(false);
  });
});

describe('notes, approvals, assets', () => {
  it('technician may note only on own active work order', () => {
    const own = wo({ assignedTo: { id: 'u1', fullName: 'T' }, status: 'in_progress' });
    expect(canAddNote('technician', 'u1', own)).toBe(true);
    expect(canAddNote('technician', 'u2', own)).toBe(false);
    expect(canAddNote('technician', 'u1', { ...own, status: 'completed' })).toBe(false);
    expect(canAddNote('control_room', 'x', own)).toBe(true);
    expect(canAddNote('resident', 'x', own)).toBe(false);
  });
  it('approval decisions: operating company manager only', () => {
    expect(canDecideApproval('operating_company_manager')).toBe(true);
    expect(canDecideApproval('maintenance_manager')).toBe(false);
    expect(canDecideApproval('system_admin')).toBe(false);
  });
  it('asset read excludes residents', () => {
    expect(canReadAssets('department')).toBe(true);
    expect(canReadAssets('resident')).toBe(false);
  });
});

describe('deriveActions (unchanged page behaviour)', () => {
  it('assign/request-info only while under review and not waiting for information', () => {
    const a = deriveActions(wo({ status: 'under_review' }), { id: 'c', role: 'control_room' });
    expect(a.canAssign && a.canRequestInfo).toBe(true);
    const b = deriveActions(wo({ status: 'under_review', currentStage: 'info_requested' }), { id: 'c', role: 'control_room' });
    expect(b.canAssign || b.canRequestInfo).toBe(false);
  });
  it('only the requester can provide information', () => {
    const w = wo({ status: 'under_review', currentStage: 'info_requested', requestedBy: { id: 'r1', fullName: 'R' } });
    expect(deriveActions(w, { id: 'r1', role: 'resident' }).canProvideInfo).toBe(true);
    expect(deriveActions(w, { id: 'r2', role: 'resident' }).canProvideInfo).toBe(false);
  });
  it('approval button for OCM only', () => {
    const w = wo({ status: 'waiting_approval' });
    expect(deriveActions(w, { id: 'o', role: 'operating_company_manager' }).canApproval).toBe(true);
    expect(deriveActions(w, { id: 'o', role: 'system_admin' }).canApproval).toBe(false);
  });
});
