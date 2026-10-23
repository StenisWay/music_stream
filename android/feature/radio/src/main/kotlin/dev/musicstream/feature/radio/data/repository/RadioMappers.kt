package dev.musicstream.feature.radio.data.repository

import dev.musicstream.core.database.entity.StationEntity
import dev.musicstream.feature.radio.data.remote.RadioStationDto
import dev.musicstream.feature.radio.domain.model.RadioStation

internal fun RadioStationDto.toDomain(favorite: Boolean) = RadioStation(
    id = uuid,
    name = name.trim(),
    streamUrl = urlResolved.ifBlank { url },
    country = country,
    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    faviconUrl = favicon.takeIf { it.startsWith("http") },
    codec = codec,
    bitrateKbps = bitrate,
    favorite = favorite,
)

internal fun StationEntity.toDomain() = RadioStation(
    id, name, streamUrl, country, tags.split(',').filter { it.isNotBlank() }, faviconUrl, codec, bitrateKbps, favorite = true,
)

internal fun RadioStation.toEntity(now: Long) =
    StationEntity(id, name, streamUrl, country, tags.joinToString(","), faviconUrl, codec, bitrateKbps, now)
