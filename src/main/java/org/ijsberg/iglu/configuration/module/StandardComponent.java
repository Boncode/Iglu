/*
 * Copyright 2011-2014 Jeroen Meetsma - IJsberg Automatisering BV
 *
 * This file is part of Iglu.
 *
 * Iglu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Iglu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Iglu.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.ijsberg.iglu.configuration.module;

import org.ijsberg.iglu.configuration.Component;
import org.ijsberg.iglu.configuration.ConfigurationException;
import org.ijsberg.iglu.configuration.Facade;
import org.ijsberg.iglu.util.reflection.MethodInvocation;
import org.ijsberg.iglu.util.reflection.ReflectionSupport;
import org.ijsberg.iglu.util.types.Converter;

import java.lang.reflect.*;
import java.util.*;

/**
 * Standard implementation of Component.
 */
public class StandardComponent implements Component, InvocationHandler {

	public static final String PROPERTIES_PROPERTY_KEY = "properties";
	public static final String REGISTER_LISTENER_METHOD_NAME = "register";
	public static final String UNREGISTER_LISTENER_METHOD_NAME = "unregister";

	protected Object implementation;
	private Class<?>[] interfaces;
	private Properties properties;
	private Properties setterInjectedProperties = new Properties();

	private HashMap<Class<?>, InvocationHandler> invocationHandlers = new HashMap<Class<?>, InvocationHandler>();
	private HashMap<String, Set<Class<?>>> injectedProxyTypesByComponentId = new HashMap<String, Set<Class<?>>>();
	private HashMap<Class<?>,Object> injectedProxiesByType = new HashMap<>();

	private Map<Component, Map<Class<?>, Object>> registeredListenersByComponent = new HashMap<Component, Map<Class<?>, Object>>();

	public StandardComponent(Object implementation) {
		if (implementation == null) {
			throw new NullPointerException("implementation can not be null");
		}
		this.implementation = implementation;
		this.interfaces = ReflectionSupport.getInterfacesForClass(implementation.getClass()).toArray(new Class<?>[0]);
	}

	@Override
	/**
	 * @throws NullPointerException if the cluster does not expose a component with ID componentId
	 */
	public void setReference(Facade facade, String componentId, Class<?>... interfaces) {

		if (injectedProxyTypesByComponentId.containsKey(componentId)) {
			resetReference(facade, componentId, interfaces);
		} else {
			Set<Class<?>> injectedProxyTypes = injectProxies(componentId, Arrays.asList(interfaces), facade);
			injectedProxyTypesByComponentId.put(componentId, injectedProxyTypes);
			addFacadeByType(Arrays.asList(interfaces), facade, componentId);
		}
	}

	/**
	 * @param facade
	 * @param componentId
	 * @param interfaces
	 */
	private void resetReference(Facade facade, String componentId, Class<?>[] interfaces) {
		Set<Class<?>> currentlyInjectedInterfaces = injectedProxyTypesByComponentId.get(componentId);

		Set<Class<?>> exposedInterfaces = new HashSet<Class<?>>(Arrays.asList(interfaces));

		Set<Class<?>> interfacesToBeRemoved = new HashSet<Class<?>>(currentlyInjectedInterfaces);
		interfacesToBeRemoved.removeAll(exposedInterfaces);
		currentlyInjectedInterfaces.removeAll(interfacesToBeRemoved);
//		injectNulls(componentId, interfacesToBeRemoved);

		Set<Class<?>> interfacesToAdd = new HashSet<Class<?>>(exposedInterfaces);
		interfacesToAdd.removeAll(currentlyInjectedInterfaces);

		Set<Class<?>> injectedProxyTypes = injectProxies(componentId, interfacesToAdd, facade);
		currentlyInjectedInterfaces.addAll(injectedProxyTypes);

		if (currentlyInjectedInterfaces.isEmpty()) {
			injectedProxyTypesByComponentId.remove(componentId);
			removeFacadeByType(Arrays.asList(interfaces));
		}
	}

	private void removeFacadeByType(Collection<Class<?>> interfaces) {
		if(interfaces != null) {
			for(Class<?> interfaceX : interfaces) {
				injectedProxiesByType.remove(interfaceX);
			}
		}

	}

	/**
	 * @param componentId
	 */
	public void removeDependency(String componentId) {
//		injectNulls(componentId, injectedProxyTypesByComponentId.get(componentId));
		Set<Class<?>> removedInterfaces = injectedProxyTypesByComponentId.remove(componentId);
		removeFacadeByType(removedInterfaces);

	}

	/**
	 * @param component
	 */
	public void register(Component component) {
		for (Class<?> interfaceClass : component.getInterfaces()) {
			try {
				Method method = implementation.getClass().getMethod(REGISTER_LISTENER_METHOD_NAME, interfaceClass);
				Object listenerProxy = component.createProxy(interfaceClass);
				System.out.println("registering proxy for " + interfaceClass.getSimpleName() + " in component " + this.implementation.getClass().getSimpleName());
				invokeMethod(method, listenerProxy);
				saveRegisteredListenerProxy(component, interfaceClass, listenerProxy);
			} catch (NoSuchMethodException ignore) {
				//System.out.println("FAILED to register proxy for " + interfaceClass.getSimpleName() + " in component " + this.implementation.getClass().getSimpleName());
			}
		}
	}


	@Override
	public void unregister(Component component) {
		Map<Class<?>, Object> registeredListeners = registeredListenersByComponent.get(component);
		if (registeredListeners != null) {
			for (Class<?> interfaceClass : component.getInterfaces()) {
				try {
					Method method = implementation.getClass().getMethod(UNREGISTER_LISTENER_METHOD_NAME, interfaceClass);
					Object listenerProxy = registeredListeners.get(interfaceClass);
					if (listenerProxy != null) {
						invokeMethod(method, listenerProxy);
						registeredListeners.remove(interfaceClass);
					}
				} catch (NoSuchMethodException ignore) {
				}
			}
		}
	}

	private void saveRegisteredListenerProxy(Component component,
											 Class<?> interfaceClass, Object listenerProxy) {

		Map<Class<?>, Object> registeredListeners = registeredListenersByComponent.get(component);
		if (registeredListeners == null) {
			registeredListeners = new HashMap<Class<?>, Object>();
			registeredListenersByComponent.put(component, registeredListeners);
		}
		registeredListeners.put(interfaceClass, listenerProxy);
	}


	private HashSet<Class<?>> injectProxies(String otherComponentId, Collection<Class<?>> interfaces, Facade facade) {
		HashSet<Class<?>> injectedProxyTypes = new HashSet<Class<?>>();
		for (Method setter : getComponentSettersByPropertyKey(otherComponentId)) {

			for (Class<?> interfaceClass : interfaces) {
				if (setter.getParameterTypes()[0].isAssignableFrom(interfaceClass)) {
					Object proxy = facade.getProxy(otherComponentId, interfaceClass);
					System.out.println("injecting proxy for " + interfaceClass.getSimpleName() + " in component " + this.implementation.getClass().getSimpleName());
					invokeMethod(setter, proxy);
					injectedProxyTypes.add(interfaceClass);
				}
			}
		}
		return injectedProxyTypes;
	}

	/*

		//potentially unsafe behavior
		private void injectNulls(String otherComponentId, Set<Class<?>> interfaces) {
			for (Method setter : getComponentSettersByPropertyKey(otherComponentId)) {
				for (Class<?> interfaceClass : interfaces) {
					if (setter.getParameterTypes()[0].isAssignableFrom(interfaceClass)) {
						invokeMethod(setter, null);
					}
				}
			}
		}
	   */

	@Override
	public <T> T createProxy(Class<T> interfaceClass) {
		this.checkInterfaceValidity(interfaceClass);
		return (T) Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class[]{interfaceClass}, this);
	}


	protected <T> T getProxyForComponentReference(Class<T> interfaceClass) {
		return (T) injectedProxiesByType.get(interfaceClass);
	}

	private HashMap<Class<?>, Object> proxiesByInterface = new HashMap();

	@Override
	public <T> T getProxy(Class<T> interfaceClass) {
		if (proxiesByInterface.containsKey(interfaceClass)) {
			return (T) proxiesByInterface.get(interfaceClass);
		} else {
			T proxy = createProxy(interfaceClass);
			proxiesByInterface.put(interfaceClass, proxy);
			return proxy;
		}
	}


	private void checkInterfaceValidity(Class<?> interfaceClass) {
		if (!interfaceClass.isInterface()) {
			throw new IllegalArgumentException("class " + interfaceClass.getName() + " is not an interface");
		}
		if (!interfaceClass.isAssignableFrom(implementation.getClass())) {
			throw new IllegalArgumentException("class " + implementation.getClass().getName() + " does not implement " + interfaceClass.getName());
		}
	}

	@Override
	public Class<?>[] getInterfaces() {
		return interfaces;
	}

	@Override
	public void setProperties(Properties properties) {
		for (Object key : properties.keySet()) {
			//auto_configure_setters
			//setters are not exposed if not part of interface
			String value = properties.getProperty((String) key);
			injectPropertyIfMatchingSetterFound((String) key, value);
		}
		injectPropertyIfMatchingSetterFound(PROPERTIES_PROPERTY_KEY, properties);
		this.properties = properties;
	}

	@Override
	public Properties getProperties() {
		return properties;
	}

	/**
	 * @return properties that have actually been injected by setter
	 */
	public Properties getSetterInjectedProperties() {
		return setterInjectedProperties;
	}

	private Set<Method> getComponentSettersByPropertyKey(String key) {
		String setterName = "set" + makeFirstCharUpperCase(key);
		return ReflectionSupport.getMethodsByName(implementation.getClass(), setterName, 1);
	}

	public static String makeFirstCharUpperCase(String varName) {
		StringBuffer keyStrBuf = new StringBuffer(varName);
		keyStrBuf.replace(0, 1, (String.valueOf(keyStrBuf.charAt(0))).toUpperCase());
		return keyStrBuf.toString();
	}

	private void injectPropertyIfMatchingSetterFound(String key, Object value) {
		Set<Method> setters = getComponentSettersByPropertyKey(key);
		if (setters.size() > 1) {
			throw new ConfigurationException("more than 1 (" + setters.size() +
					") setter found for property '" + key + "'");
		}
		if (setters.size() == 1) {
			injectProperty(setters.iterator().next(), value);
			setterInjectedProperties.put(key, value);
		}
	}

	private void injectProperty(Method method, Object value) {
		Object injectingObject = Converter.convertToObject(value, method.getParameterTypes()[0]);
		invokeMethod(method, injectingObject);
	}

	private void invokeMethod(Method method, Object injectingObject) {
		try {
			method.invoke(implementation, injectingObject);
		} catch (InvocationTargetException ite) {
			if (ite.getCause() instanceof RuntimeException) {
				throw (RuntimeException) ite.getCause();
			}
			throw new RuntimeException("can't invoke method '" + method.getName() + "'" + " with argument " + injectingObject,
					ite.getCause());
		} catch (IllegalAccessException e) {
			throw new RuntimeException("can't invoke method '" + method.getName() + "'" + " with argument " + injectingObject, e);
		}
	}

	@Override
	public void setInvocationIntercepter(Class<?> interfaceClass, InvocationHandler handler) {
		this.checkInterfaceValidity(interfaceClass);
		invocationHandlers.put(interfaceClass, handler);
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] parameters) throws Throwable {

		//get handler for specific proxy interface
		InvocationHandler handler = null;
		Class<?>[] interfaces = proxy.getClass().getInterfaces();
		if(interfaces.length > 0) {
			handler = invocationHandlers.get(interfaces[0]);
		}
		try {
			if (handler == null) {
				//get handler for interface that declares invoked method
				handler = invocationHandlers.get(method.getDeclaringClass());
			}
			if (handler != null) {
				return handler.invoke(implementation, method, parameters);
			} else return method.invoke(implementation, parameters);
		} catch (Throwable t) {
			while ((t instanceof UndeclaredThrowableException || t instanceof InvocationTargetException) && (t = t.getCause()) != null) {}
			throw t;
		}
	}

	@Override
	public Object invoke(String methodName, Object... parameters) throws InvocationTargetException, NoSuchMethodException, IllegalArgumentException {
		MethodInvocation invocation = new MethodInvocation(this, implementation, methodName,
				getInterfaceMethodsByName(methodName, parameters.length).toArray(new Method[0]), parameters);
		return invocation.invoke();
	}

	private Set<Method> getInterfaceMethodsByName(String methodName, int nrofParameters) {
		Set<Method> retval = new HashSet<Method>();
		for (Class<?> clasz : interfaces) {
			retval.addAll(ReflectionSupport.getMethodsByName(clasz, methodName, nrofParameters));
		}
		return retval;
	}

	@Override
	public Set<Class<?>> getInjectedInterfaces(String componentId) {
		Set<Class<?>> retval = new HashSet<Class<?>>();
		if (injectedProxyTypesByComponentId.containsKey(componentId)) {
			retval.addAll(injectedProxyTypesByComponentId.get(componentId));
		}
		return retval;
	}

	public boolean equals(Object other) {
		return ((other instanceof StandardComponent) && ((StandardComponent) other).implementation == implementation) ||
				other == implementation;
	}

	public int hashCode() {
		return implementation.hashCode();
	}

	public String toString() {
		return "component with impl: " + implementation;
	}


	@Override
	public boolean implementsInterface(Class<?> interfaceClass) {
		return interfaceClass.isAssignableFrom(implementation.getClass());
	}

	private void addFacadeByType(Collection<Class<?>> interfaces, Facade facade, String componentId) {
		for(Class<?> interfaceX : interfaces) {
			injectedProxiesByType.put(interfaceX, facade.getProxy(componentId, interfaceX));
		}
	}
}
