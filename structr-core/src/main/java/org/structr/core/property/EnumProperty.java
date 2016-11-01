/**
 * Copyright (C) 2010-2016 Structr GmbH
 *
 * This file is part of Structr <http://structr.org>.
 *
 * Structr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Structr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.core.property;

import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.structr.api.search.SortType;
import org.structr.common.SecurityContext;
import org.structr.common.error.FrameworkException;
import org.structr.common.error.ValueToken;
import org.structr.core.GraphObject;
import org.structr.core.converter.PropertyConverter;
import org.structr.schema.SchemaHelper;

/**
 * A property that stores and retrieves a simple enum value of the given type.
 *
 *
 */
public class EnumProperty<T extends Enum> extends AbstractPrimitiveProperty<T> {

	private static final Logger logger = LoggerFactory.getLogger(EnumProperty.class.getName());
	private Class<T> enumType          = null;

	public EnumProperty(final String name, final Class<T> enumType) {
		this(name, enumType, null);
	}

	public EnumProperty(final String jsonName, final String dbName, final Class<T> enumType) {
		this(jsonName, dbName, enumType, null);
	}

	public EnumProperty(final String name, final Class<T> enumType, final T defaultValue) {
		this(name, name, enumType, defaultValue);
	}

	public EnumProperty(final String jsonName, final String dbName, final Class<T> enumType, final T defaultValue) {

		super(jsonName, dbName, defaultValue);

		this.enumType = enumType;
		addEnumValuesToFormat();
	}

	@Override
	public String typeName() {
		return "Enum";
	}

	@Override
	public Class valueType() {
		return enumType;
	}

	@Override
	public SortType getSortType() {
		return SortType.Default;
	}

	@Override
	public PropertyConverter<T, String> databaseConverter(SecurityContext securityContext) {
		return databaseConverter(securityContext, null);
	}

	@Override
	public PropertyConverter<T, String> databaseConverter(SecurityContext securityContext, GraphObject entity) {
		return new DatabaseConverter(securityContext, entity);
	}

	@Override
	public PropertyConverter<String, T> inputConverter(SecurityContext securityContext) {
		return new InputConverter(securityContext);
	}

	@Override
	public Object fixDatabaseProperty(Object value) {

		if (value != null) {

			if (value instanceof String) {
				return value;
			}
		}

		return null;
	}

	public Class<T> getEnumType() {
		return enumType;
	}

	// ----- CMIS support -----
	@Override
	public PropertyType getDataType() {
		return PropertyType.STRING;
	}

	protected class DatabaseConverter extends PropertyConverter<T, String> {

		public DatabaseConverter(SecurityContext securityContext, GraphObject entity) {
			super(securityContext, entity);
		}

		@Override
		public T revert(String source) throws FrameworkException {

			if (source != null) {

				try {
					return (T) Enum.valueOf(enumType, source);

				} catch (Throwable t) {

					logger.warn("Cannot convert database value {} to enum of type {}, ignoring.", new Object[] { source, SchemaHelper.parseClassName(enumType.getSimpleName()) } );
				}
			}

			return null;

		}

		@Override
		public String convert(T source) throws FrameworkException {

			if (source != null) {

				return source.toString();
			}

			return null;
		}

	}

	protected class InputConverter extends PropertyConverter<String, T> {

		public InputConverter(SecurityContext securityContext) {
			super(securityContext, null);
		}

		@Override
		public String revert(T source) throws FrameworkException {

			if (source != null) {

				return source.toString();
			}

			return null;
		}

		@Override
		public T convert(String source) throws FrameworkException {

			if (source != null) {

				try {
					return (T) Enum.valueOf(enumType, source);

				} catch (Throwable t) {

					throw new FrameworkException(422, "Cannot parse input for property " + jsonName(), new ValueToken(declaringClass.getSimpleName(), EnumProperty.this, enumType.getEnumConstants()));
				}
			}

			return null;

		}

	}

	private void addEnumValuesToFormat() {

		this.format = "";

		for (T enumConst : enumType.getEnumConstants()) {
			this.format += (enumConst.toString()) + ",";
		}

		this.format = this.format.substring(0, this.format.length() - 1);
	}
}
