/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.jsonwebservice;

import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.jsonwebservice.JSONWebServiceAction;
import com.liferay.portal.kernel.jsonwebservice.JSONWebServiceActionMapping;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.CamelCaseUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.MethodParameter;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.service.ServiceContext;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jodd.bean.BeanUtil;

import jodd.typeconverter.TypeConverterManager;

import jodd.util.KeyValue;
import jodd.util.ReflectUtil;

/**
 * @author Igor Spasic
 */
public class JSONWebServiceActionImpl implements JSONWebServiceAction {

	public JSONWebServiceActionImpl(
		JSONWebServiceActionConfig jsonWebServiceActionConfig,
		JSONWebServiceActionParameters jsonWebServiceActionParameters) {

		_jsonWebServiceActionConfig = jsonWebServiceActionConfig;
		_jsonWebServiceActionParameters = jsonWebServiceActionParameters;
	}

	public JSONWebServiceActionMapping getJSONWebServiceActionMapping() {
		return _jsonWebServiceActionConfig;
	}

	public Object invoke() throws Exception {
		JSONRPCRequest jsonRPCRequest =
			_jsonWebServiceActionParameters.getJSONRPCRequest();

		if (jsonRPCRequest == null) {
			return _invokeActionMethod();
		}

		Object result = null;
		Exception exception = null;

		try {
			result = _invokeActionMethod();
		}
		catch (Exception e) {
			exception = e;

			_log.error(e, e);
		}

		return new JSONRPCResponse(jsonRPCRequest, result, exception);
	}

	private Object _convertListToArray(List<?> list, Class<?> componentType) {
		Object array = Array.newInstance(componentType, list.size());

		for (int i = 0; i < list.size(); i++) {
			Object entry = list.get(i);

			if (entry != null) {
				entry = TypeConverterManager.convertType(entry, componentType);
			}

			Array.set(array, i, entry);
		}

		return array;
	}

	private Object _createDefaultParameterValue(
			String parameterName, Class<?> parameterType)
		throws Exception {

		if (parameterName.equals("serviceContext") &&
			parameterType.equals(ServiceContext.class)) {

			return new ServiceContext();
		}

		String className = parameterType.getName();

		if (className.contains("com.liferay") && className.contains("Util")) {
			throw new IllegalArgumentException(
				"Not instantiating " + className);
		}

		return parameterType.newInstance();
	}

	private List<?> _generifyList(List<?> list, Class<?>[] types) {
		if (types == null) {
			return list;
		}

		if (types.length != 1) {
			return list;
		}

		List<Object> newList = new ArrayList<Object>(list.size());

		for (Object entry : list) {
			if (entry != null) {
				entry = TypeConverterManager.convertType(entry, types[0]);
			}

			newList.add(entry);
		}

		return newList;
	}

	private Map<?, ?> _generifyMap(Map<?, ?> map, Class<?>[] types) {
		if (types == null) {
			return map;
		}

		if (types.length != 2) {
			return map;
		}

		Map<Object, Object> newMap = new HashMap<Object, Object>(map.size());

		for (Map.Entry<?, ?> entry : map.entrySet()) {
			Object key = TypeConverterManager.convertType(
				entry.getKey(), types[0]);

			Object value = entry.getValue();

			if (value != null) {
				value = TypeConverterManager.convertType(value, types[1]);
			}

			newMap.put(key, value);
		}

		return newMap;
	}

	private void _injectInnerParametersIntoValue(
		String parameterName, Object parameterValue) {

		if (parameterValue == null) {
			return;
		}

		List<KeyValue<String, Object>> innerParameters =
			_jsonWebServiceActionParameters.getInnerParameters(parameterName);

		if (innerParameters == null) {
			return;
		}

		for (KeyValue<String, Object> innerParameter : innerParameters) {
			try {
				BeanUtil.setProperty(
					parameterValue, innerParameter.getKey(),
					innerParameter.getValue());
			}
			catch (Exception e) {
				if (_log.isDebugEnabled()) {
					_log.debug(
						"Unable to set inner parameter " + parameterName + "." +
							innerParameter.getKey(),
						e);
				}
			}
		}
	}

	private Object _invokeActionMethod() throws Exception {
		Method actionMethod = _jsonWebServiceActionConfig.getActionMethod();

		Class<?> actionClass = _jsonWebServiceActionConfig.getActionClass();

		Object[] parameters = _prepareParameters(actionClass);

		return actionMethod.invoke(actionClass, parameters);
	}

	private Object[] _prepareParameters(Class<?> actionClass) throws Exception {
		MethodParameter[] methodParameters =
			_jsonWebServiceActionConfig.getMethodParameters();

		Object[] parameters = new Object[methodParameters.length];

		for (int i = 0; i < methodParameters.length; i++) {
			String parameterName = methodParameters[i].getName();

			parameterName = CamelCaseUtil.normalizeCamelCase(parameterName);

			Object value = _jsonWebServiceActionParameters.getParameter(
				parameterName);

			Object parameterValue = null;

			if (value != null) {
				Class<?> parameterType = methodParameters[i].getType();

				if (value.equals(Void.TYPE)) {
					String parameterTypeName =
						_jsonWebServiceActionParameters.getParameterTypeName(
							parameterName);

					if (parameterTypeName != null) {
						ClassLoader classLoader = actionClass.getClassLoader();

						parameterType = classLoader.loadClass(
							parameterTypeName);
					}

					if (!ReflectUtil.isSubclass(
							parameterType, methodParameters[i].getType())) {

						throw new IllegalArgumentException(
							"Unmatched argument type " +
								parameterType.getName() +
									" for method argument " + i);
					}

					parameterValue = _createDefaultParameterValue(
						parameterName, parameterType);
				}
				else if (parameterType.equals(Calendar.class)) {
					Calendar calendar = Calendar.getInstance();

					calendar.setLenient(false);
					calendar.setTimeInMillis(
						GetterUtil.getLong(_valueToString(value)));

					parameterValue = calendar;
				}
				else if (parameterType.equals(List.class)) {
					String stringValue = _valueToString(value);

					stringValue = stringValue.trim();

					if (!stringValue.startsWith(StringPool.OPEN_BRACKET)) {
						stringValue = StringPool.OPEN_BRACKET.concat(
							stringValue).concat(StringPool.CLOSE_BRACKET);
					}

					List<?> list = JSONFactoryUtil.looseDeserializeSafe(
						stringValue, ArrayList.class);

					list = _generifyList(
						list, methodParameters[i].getGenericTypes());

					parameterValue = list;
				}
				else if (parameterType.equals(Locale.class)) {
					parameterValue = LocaleUtil.fromLanguageId(
						_valueToString(value));
				}
				else if (parameterType.equals(Map.class)) {
					Map<?, ?> map = JSONFactoryUtil.looseDeserializeSafe(
						_valueToString(value), HashMap.class);

					map = _generifyMap(
						map, methodParameters[i].getGenericTypes());

					parameterValue = map;
				}
				else if (parameterType.isArray()) {
					String stringValue = _valueToString(value);

					stringValue = stringValue.trim();

					if (!stringValue.startsWith(StringPool.OPEN_BRACKET)) {
						stringValue = StringPool.OPEN_BRACKET.concat(
							stringValue).concat(StringPool.CLOSE_BRACKET);
					}

					List<?> list = JSONFactoryUtil.looseDeserializeSafe(
						stringValue, ArrayList.class);

					parameterValue = _convertListToArray(
						list, parameterType.getComponentType());

				}
				else {
					try {
						parameterValue = TypeConverterManager.convertType(
							value, parameterType);
					}
					catch (ClassCastException cce) {
						String stringValue = _valueToString(value);

						stringValue = stringValue.trim();

						if (!stringValue.startsWith(
								StringPool.OPEN_CURLY_BRACE)) {

							throw cce;
						}

						parameterValue = JSONFactoryUtil.looseDeserializeSafe(
							stringValue, parameterType);
					}
				}
			}

			_injectInnerParametersIntoValue(parameterName, parameterValue);

			parameters[i] = parameterValue;
		}

		return parameters;
	}

	private String _valueToString(Object value) {
		Class<?> valueType = value.getClass();

		if (valueType.isArray()) {
			return StringUtil.merge((Object[])value);
		}

		return value.toString();
	}

	private static Log _log = LogFactoryUtil.getLog(
		JSONWebServiceActionImpl.class);

	private JSONWebServiceActionConfig _jsonWebServiceActionConfig;
	private JSONWebServiceActionParameters _jsonWebServiceActionParameters;

}