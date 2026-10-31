import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useAuthStore } from '@/store/authStore';
import { NotesPanel } from './NotesPanel';
import { AttachmentsPanel } from './AttachmentsPanel';
import type { WorkOrderDetail } from '@/modules/workflow/types';

const addNote = vi.fn();
const attachments = vi.fn();
const blob = vi.fn();
const save = vi.fn();
vi.mock('@/modules/workflow/api', async (orig) => ({
  ...(await orig<typeof import('@/modules/workflow/api')>()),
  workflowApi: {
    addNote: (...a: unknown[]) => addNote(...a),
    attachments: (...a: unknown[]) => attachments(...a),
    attachmentBlob: (...a: unknown[]) => blob(...a),
  },
}));
vi.mock('@/modules/workflow/download', () => ({ saveBlob: (...a: unknown[]) => save(...a) }));

const wo = (o: Partial<WorkOrderDetail> = {}) =>
  ({ id: 'w1', ticketNumber: 'T-1', title: 't', status: 'in_progress', priority: 'high', subType: 'x', createdAt: '2026-01-01T00:00:00Z',
     assignedTo: { id: 'tech', fullName: 'Tech' }, ...o }) as WorkOrderDetail;

beforeEach(() => {
  [addNote, attachments, blob, save].forEach((m) => m.mockReset());
  act(() => useAuthStore.setState({ lang: 'en' }));
  URL.createObjectURL = vi.fn(() => 'blob:x');
  URL.revokeObjectURL = vi.fn();
});

describe('NotesPanel', () => {
  it('is hidden for roles that cannot add notes and for unassigned technicians', () => {
    const a = render(<NotesPanel wo={wo()} user={{ id: 'r', role: 'resident' }} onAdded={() => {}} />);
    expect(a.container).toBeEmptyDOMElement();
    a.unmount();
    const b = render(<NotesPanel wo={wo()} user={{ id: 'other', role: 'technician' }} onAdded={() => {}} />);
    expect(b.container).toBeEmptyDOMElement();
  });
  it('validates, posts with an idempotency key, clears and confirms', async () => {
    addNote.mockResolvedValue({ data: {} });
    const onAdded = vi.fn();
    render(<NotesPanel wo={wo()} user={{ id: 'tech', role: 'technician' }} onAdded={onAdded} />);
    await userEvent.click(screen.getByRole('button', { name: 'Save note' }));
    expect(await screen.findByText('Enter the note')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Note text'), 'Replaced the filter');
    await userEvent.click(screen.getByRole('button', { name: 'Save note' }));
    await waitFor(() => expect(addNote).toHaveBeenCalledWith('w1', 'Replaced the filter', expect.any(String)));
    expect(await screen.findByText('Note added')).toBeInTheDocument();
    expect(onAdded).toHaveBeenCalled();
  });
  it('409 shows the reload message and calls onConflict', async () => {
    addNote.mockRejectedValue({ response: { status: 409 } });
    const onConflict = vi.fn();
    render(<NotesPanel wo={wo()} user={{ id: 'x', role: 'control_room' }} onAdded={() => {}} onConflict={onConflict} />);
    await userEvent.type(screen.getByLabelText('Note text'), 'hello');
    await userEvent.click(screen.getByRole('button', { name: 'Save note' }));
    expect(await screen.findByText(/Reload and try again/)).toBeInTheDocument();
    expect(onConflict).toHaveBeenCalled();
  });
});

describe('AttachmentsPanel', () => {
  const items = [
    { id: 'a1', fileName: 'before.jpg', mimeType: 'image/jpeg', createdAt: '2026-01-02T00:00:00Z', photoPhase: 'before' },
    { id: 'a2', fileName: 'after.jpg', mimeType: 'image/jpeg', createdAt: '2026-01-02T00:00:00Z', photoPhase: 'after' },
    { id: 'a3', fileName: 'report.pdf', mimeType: 'application/pdf', createdAt: '2026-01-02T00:00:00Z', photoPhase: null },
  ];
  it('groups photos by phase and downloads through the authorised blob API', async () => {
    attachments.mockResolvedValue({ data: items });
    const b = new Blob(['x']);
    blob.mockResolvedValue({ data: b });
    render(<AttachmentsPanel workOrderId="w1" />);
    expect(await screen.findByRole('region', { name: 'Before' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'After' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Download: report.pdf' }));
    await waitFor(() => expect(save).toHaveBeenCalledWith(b, 'report.pdf'));
    expect(blob).toHaveBeenCalledWith('w1', 'a3');
  });
  it('shows an error when a download fails, empty and error states', async () => {
    attachments.mockResolvedValueOnce({ data: [items[2]] });
    blob.mockRejectedValue(new Error('x'));
    const { unmount } = render(<AttachmentsPanel workOrderId="w1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Download: report.pdf' }));
    expect(await screen.findByText('Download failed: report.pdf')).toBeInTheDocument();
    unmount();
    attachments.mockResolvedValueOnce({ data: [] });
    const e = render(<AttachmentsPanel workOrderId="w1" />);
    expect(await screen.findByText('No attachments')).toBeInTheDocument();
    e.unmount();
    attachments.mockRejectedValueOnce({ response: { status: 404 } });
    render(<AttachmentsPanel workOrderId="w1" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Not found');
  });
});
