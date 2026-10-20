-- Which key-encryption key wrapped each vault data key (roadmap 1.4), e.g. 'local:3fa9c1d2' or
-- 'aws-kms:arn:aws:kms:...'. Lets the KEK rotate: the re-wrap job finds vaults not on the current
-- KEK and re-wraps only their data key. Memory content is never re-encrypted.
-- NULL means "wrapped by a local master key before this column existed".
ALTER TABLE vaults ADD COLUMN kek_id VARCHAR(300);
CREATE INDEX ix_vaults_kek ON vaults (kek_id);
