-- Set when an image was exported from a Figma frame, so exporting it again replaces the old copy.
ALTER TABLE design_upload ADD COLUMN figma_node_id varchar(64);
ALTER TABLE design_upload ADD CONSTRAINT uq_design_upload_figma_node UNIQUE (design_source_id, figma_node_id);
