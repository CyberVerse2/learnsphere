/**
 * Domain records mirroring the database tables (and {@code profiles.json}), plus an enum for every
 * status and type column. Enums stored as text implement {@link edu.learnsphere.core.model.CodedEnum};
 * small integer scales ({@link edu.learnsphere.core.model.Rating}, {@link edu.learnsphere.core.model.Confidence},
 * {@link edu.learnsphere.core.model.Difficulty}) are stored as their {@code value()}.
 *
 * <p>Conventions: ids are UUID strings; times are epoch millis; nullable columns are boxed types or
 * nullable strings and documented as such; text columns that are never null default to the empty string.
 */
package edu.learnsphere.core.model;
