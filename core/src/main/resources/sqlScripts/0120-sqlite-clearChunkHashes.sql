-- rebuild every chunk the next time it's loaded,
-- so models stretched by occlusion culling get fixed
delete from ChunkHash;
