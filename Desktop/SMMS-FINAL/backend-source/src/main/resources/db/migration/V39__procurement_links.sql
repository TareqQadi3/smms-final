-- V39: links between the ledger (V33 columns) and procurement / material requests, and PO -> MR.
-- The ledger columns are empty at this point, so the constraints validate immediately.
ALTER TABLE purchase_orders
    ADD CONSTRAINT fk_po_material_request FOREIGN KEY (material_request_id, tenant_id)
    REFERENCES material_requests(id, tenant_id) NOT VALID;
ALTER TABLE purchase_orders VALIDATE CONSTRAINT fk_po_material_request;

ALTER TABLE inventory_movements
    ADD CONSTRAINT fk_im_mr_line FOREIGN KEY (material_request_line_id) REFERENCES material_request_lines(id) NOT VALID;
ALTER TABLE inventory_movements VALIDATE CONSTRAINT fk_im_mr_line;

ALTER TABLE inventory_movements
    ADD CONSTRAINT fk_im_po_receipt_line FOREIGN KEY (po_receipt_line_id) REFERENCES po_receipt_lines(id) NOT VALID;
ALTER TABLE inventory_movements VALIDATE CONSTRAINT fk_im_po_receipt_line;
