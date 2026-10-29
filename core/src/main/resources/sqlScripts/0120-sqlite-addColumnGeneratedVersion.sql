-- which FullDataSourceV2.GENERATED_VERSION built each column
alter table FullData add column ColumnGeneratedVersion BLOB NULL;
