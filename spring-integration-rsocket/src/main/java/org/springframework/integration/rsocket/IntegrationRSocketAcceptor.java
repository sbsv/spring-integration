/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.rsocket;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.ReactiveMessageHandler;
import org.springframework.messaging.handler.CompositeMessageCondition;
import org.springframework.messaging.handler.DestinationPatternsMessageCondition;
import org.springframework.messaging.handler.invocation.reactive.HandlerMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.reactive.SyncHandlerMethodArgumentResolver;
import org.springframework.messaging.rsocket.RSocketMessageHandler;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import io.rsocket.ConnectionSetupPayload;
import io.rsocket.RSocket;

/**
 * The {@link RSocketMessageHandler} extension for Spring Integration needs.
 * <p>
 * The most of logic is copied from {@link org.springframework.messaging.rsocket.MessageHandlerAcceptor}.
 * That cannot be extended because it is {@link final}.
 * <p>
 * This class adds an {@link IntegrationRSocketEndpoint} beans detection and registration functionality,
 * as well as serves as a container over an internal {@link IntegrationRSocket} implementation.
 *
 * @author Artem Bilan
 *
 * @since 5.2
 *
 * @see org.springframework.messaging.rsocket.MessageHandlerAcceptor
 */
class IntegrationRSocketAcceptor extends RSocketMessageHandler
		implements BiFunction<ConnectionSetupPayload, RSocket, RSocket> {

	private static final Method HANDLE_MESSAGE_METHOD =
			ReflectionUtils.findMethod(ReactiveMessageHandler.class, "handleMessage", Message.class);

	@Nullable
	private MimeType defaultDataMimeType;

	private MimeType defaultMetadataMimeType = IntegrationRSocket.COMPOSITE_METADATA;

	/**
	 * Configure the default content type to use for data payloads.
	 * <p>By default this is not set. However a server acceptor will use the
	 * content type from the {@link io.rsocket.ConnectionSetupPayload}, so this is typically
	 * required for clients but can also be used on servers as a fallback.
	 * @param defaultDataMimeType the MimeType to use
	 */
	public void setDefaultDataMimeType(@Nullable MimeType defaultDataMimeType) {
		this.defaultDataMimeType = defaultDataMimeType;
	}

	/**
	 * Configure the default {@code MimeType} for payload data if the
	 * {@code SETUP} frame did not specify one.
	 * <p>By default this is set to {@code "message/x.rsocket.composite-metadata.v0"}
	 * @param mimeType the MimeType to use
	 */
	public void setDefaultMetadataMimeType(MimeType mimeType) {
		Assert.notNull(mimeType, "'metadataMimeType' is required");
		this.defaultMetadataMimeType = mimeType;
	}

	public boolean detectEndpoints() {
		ApplicationContext applicationContext = getApplicationContext();
		if (applicationContext != null && getHandlerMethods().isEmpty()) {
			return applicationContext
					.getBeansOfType(IntegrationRSocketEndpoint.class)
					.values()
					.stream()
					.peek(this::addEndpoint)
					.count() > 0;
		}
		else {
			return false;
		}
	}

	public void addEndpoint(IntegrationRSocketEndpoint endpoint) {
		registerHandlerMethod(endpoint, HANDLE_MESSAGE_METHOD,
				new CompositeMessageCondition(
						new DestinationPatternsMessageCondition(endpoint.getPath(), getRouteMatcher())));
	}

	@Override
	protected List<? extends HandlerMethodArgumentResolver> initArgumentResolvers() {
		return Collections.singletonList(new MessageHandlerMethodArgumentResolver());
	}

	@Override
	protected Predicate<Class<?>> initHandlerPredicate() {
		return (clazz) -> false;
	}

	@Override
	public RSocket apply(ConnectionSetupPayload setupPayload, RSocket sendingRSocket) {
		return createRSocket(setupPayload, sendingRSocket);
	}

	protected IntegrationRSocket createRSocket(ConnectionSetupPayload setupPayload, RSocket rsocket) {
		RSocketStrategies rsocketStrategies = getRSocketStrategies();
		MimeType dataMimeType =
				StringUtils.hasText(setupPayload.dataMimeType())
						? MimeTypeUtils.parseMimeType(setupPayload.dataMimeType())
						: this.defaultDataMimeType;
		Assert.notNull(dataMimeType, "No `dataMimeType` in the ConnectionSetupPayload and no default value");

		MimeType metadataMimeType =
				StringUtils.hasText(setupPayload.dataMimeType())
						? MimeTypeUtils.parseMimeType(setupPayload.metadataMimeType())
						: this.defaultMetadataMimeType;
		Assert.notNull(dataMimeType, "No `metadataMimeType` in the ConnectionSetupPayload and no default value");
		return new IntegrationRSocket(this::handleMessage, getRouteMatcher(),
				RSocketRequester.wrap(rsocket, dataMimeType, metadataMimeType, rsocketStrategies),
				dataMimeType, metadataMimeType, rsocketStrategies.dataBufferFactory());
	}

	private static final class MessageHandlerMethodArgumentResolver implements SyncHandlerMethodArgumentResolver {

		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return true;
		}

		@Override
		public Object resolveArgumentValue(MethodParameter parameter, Message<?> message) {
			return message;
		}

	}

}
