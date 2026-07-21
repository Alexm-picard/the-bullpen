package net.thebullpen.baseball.domain;

import java.util.List;

/**
 * A repository's over-fetch page result: the trimmed {@code rows} plus whether a next page exists.
 *
 * <p>ADR-0015: {@code domain/} is a shared kernel for read-side query PROJECTIONS, but a "page" is
 * a transport concept (it exists only because an HTTP endpoint took {@code ?page=&size=}). So a
 * repository returns this minimal carrier - just the rows and {@code hasNext} (decided by an
 * over-fetch of {@code LIMIT size + 1}) - and the controller assembles the wire envelope ({@code
 * api/dto/OpsEventsPage}, {@code api/dto/PostPredictionsPage}) from it plus the request's own
 * {@code page}/{@code size}. That keeps {@code data/} free of any {@code api/dto} import (the C1
 * ArchUnit rule) without a pass-through mapper per page type.
 */
public record PagedRows<T>(List<T> rows, boolean hasNext) {}
