-- generate full detail LODs built before block models again
update FullData set Regenerate = 1 where DetailLevel = 0 and DataFormatVersion < 3;
