-- Migration 003: track phone number and device name per installation
ALTER TABLE installations ADD COLUMN phone_number TEXT NULL;
ALTER TABLE installations ADD COLUMN device_name TEXT NULL;
