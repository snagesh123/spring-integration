/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.jdbc.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.endpoint.PollingConsumer;
import org.springframework.integration.handler.advice.AbstractRequestHandlerAdvice;
import org.springframework.integration.jdbc.JdbcOutboundGateway;
import org.springframework.integration.jdbc.MessagePreparedStatementSetter;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;

/**
 * @author Dave Syer
 * @author Oleg Zhurakousky
 * @author Gary Russell
 * @author Artem Bilan
 * @author Gunnar Hillert
 *
 * @since 2.0
 *
 */
public class JdbcOutboundGatewayParserTests {

	private JdbcTemplate jdbcTemplate;

	private MessageChannel channel;

	private ConfigurableApplicationContext context;

	private MessagingTemplate messagingTemplate;

	private static volatile int adviceCalled;

	@Test
	public void testMapPayloadMapReply() {
		setUp("handlingMapPayloadJdbcOutboundGatewayTest.xml", getClass());
		assertTrue(this.context.containsBean("jdbcGateway"));
		Message<?> message = MessageBuilder.withPayload(Collections.singletonMap("foo", "bar")).build();
		this.channel.send(message);

		Message<?> reply = this.messagingTemplate.receive();
		assertNotNull(reply);
		@SuppressWarnings("unchecked")
		Map<String, ?> payload = (Map<String, ?>) reply.getPayload();
		assertEquals("bar", payload.get("name"));

		Map<String, Object> map = this.jdbcTemplate.queryForMap("SELECT * from FOOS");
		assertEquals("Wrong id", message.getHeaders().getId().toString(), map.get("ID"));
		assertEquals("Wrong name", "bar", map.get("name"));

		JdbcOutboundGateway gateway = context.getBean("jdbcGateway.handler", JdbcOutboundGateway.class);
		assertEquals(23, TestUtils.getPropertyValue(gateway, "order"));
		Assert.assertTrue(TestUtils.getPropertyValue(gateway, "requiresReply", Boolean.class));
		assertEquals(1, adviceCalled);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testKeyGeneration() {
		setUp("handlingKeyGenerationJdbcOutboundGatewayTest.xml", getClass());
		Message<?> message = MessageBuilder.withPayload(Collections.singletonMap("foo", "bar")).build();

		this.channel.send(message);

		Message<?> reply = this.messagingTemplate.receive();
		assertNotNull(reply);

		Map<String, ?> payload = (Map<String, ?>) reply.getPayload();
		Object id = payload.get("ID");
		assertNotNull(id);

		Map<String, Object> map = this.jdbcTemplate.queryForMap("SELECT * from BARS");
		assertEquals("Wrong id", id, map.get("ID"));
		assertEquals("Wrong name", "bar", map.get("name"));

		this.jdbcTemplate.execute("DELETE FROM BARS");

		MessageChannel setterRequest = this.context.getBean("setterRequest", MessageChannel.class);
		setterRequest.send(new GenericMessage<String>("bar2"));
		reply = this.messagingTemplate.receive();
		assertNotNull(reply);

		payload = (Map<String, ?>) reply.getPayload();
		id = payload.get("ID");
		assertNotNull(id);
		map = this.jdbcTemplate.queryForMap("SELECT * from BARS");
		assertEquals("Wrong id", id, map.get("ID"));
		assertEquals("Wrong name", "bar2", map.get("name"));
	}

	@Test
	public void testCountUpdates() {
		setUp("handlingCountUpdatesJdbcOutboundGatewayTest.xml", getClass());
		Message<?> message = MessageBuilder.withPayload(Collections.singletonMap("foo", "bar")).build();

		this.channel.send(message);

		Message<?> reply = this.messagingTemplate.receive();
		assertNotNull(reply);
		@SuppressWarnings("unchecked")
		Map<String, ?> payload = (Map<String, ?>) reply.getPayload();
		assertEquals(1, payload.get("updated"));
	}

	@Test
	public void testWithPoller() throws Exception {
		setUp("JdbcOutboundGatewayWithPollerTest-context.xml", this.getClass());
		Message<?> message = MessageBuilder.withPayload(Collections.singletonMap("foo", "bar")).build();

		this.channel.send(message);

		Message<?> reply = this.messagingTemplate.receive();
		assertNotNull(reply);
		@SuppressWarnings("unchecked")
		Map<String, ?> payload = (Map<String, ?>) reply.getPayload();
		assertEquals("bar", payload.get("name"));

		Map<String, Object> map = this.jdbcTemplate.queryForMap("SELECT * from BAZZ");
		assertEquals("Wrong id", message.getHeaders().getId().toString(), map.get("ID"));
		assertEquals("Wrong name", "bar", map.get("name"));
	}

	@Test
	public void testWithSelectQueryOnly() throws Exception {
		setUp("JdbcOutboundGatewayWithSelectTest-context.xml", getClass());
		Message<?> message = MessageBuilder.withPayload(100).build();

		this.channel.send(message);

		@SuppressWarnings("unchecked")
		Message<Map<String, Object>> reply = (Message<Map<String, Object>>) this.messagingTemplate.receive();

		String id = (String) reply.getPayload().get("id");
		Integer status = (Integer) reply.getPayload().get("status");
		String name = (String) reply.getPayload().get("name");

		assertEquals("100", id);
		assertEquals(Integer.valueOf(3), status);
		assertEquals("Cartman", name);
	}

	@Test
	public void testReplyTimeoutIsSet() {
		setUp("JdbcOutboundGatewayWithPollerTest-context.xml", getClass());

		PollingConsumer outboundGateway = this.context.getBean("jdbcOutboundGateway", PollingConsumer.class);

		DirectFieldAccessor accessor = new DirectFieldAccessor(outboundGateway);
		Object source = accessor.getPropertyValue("handler");
		accessor = new DirectFieldAccessor(source);
		source = accessor.getPropertyValue("messagingTemplate");

		MessagingTemplate messagingTemplate = (MessagingTemplate) source;

		accessor = new DirectFieldAccessor(messagingTemplate);

		Long  sendTimeout = (Long) accessor.getPropertyValue("sendTimeout");
		assertEquals("Wrong sendTimeout", Long.valueOf(444L),  sendTimeout);

	}

	@Test
	public void testDefaultMaxMessagesPerPollIsSet() {
		setUp("JdbcOutboundGatewayWithPollerTest-context.xml", this.getClass());

		PollingConsumer pollingConsumer = this.context.getBean(PollingConsumer.class);

		DirectFieldAccessor accessor = new DirectFieldAccessor(pollingConsumer);
		Object source = accessor.getPropertyValue("handler");
		accessor = new DirectFieldAccessor(source);
		source = accessor.getPropertyValue("poller"); //JdbcPollingChannelAdapter
		accessor = new DirectFieldAccessor(source);
		Integer maxRowsPerPoll = (Integer) accessor.getPropertyValue("maxRows");
		assertEquals("maxRowsPerPoll should default to 1", Integer.valueOf(1),  maxRowsPerPoll);

	}

	@Test
	public void testMaxMessagesPerPollIsSet() {
		setUp("JdbcOutboundGatewayWithPoller2Test-context.xml", this.getClass());

		PollingConsumer pollingConsumer = this.context.getBean(PollingConsumer.class);

		DirectFieldAccessor accessor = new DirectFieldAccessor(pollingConsumer);
		Object source = accessor.getPropertyValue("handler");
		accessor = new DirectFieldAccessor(source);
		source = accessor.getPropertyValue("poller"); //JdbcPollingChannelAdapter
		accessor = new DirectFieldAccessor(source);
		Integer maxRowsPerPoll = (Integer) accessor.getPropertyValue("maxRows");
		assertEquals("maxRowsPerPoll should default to 10", Integer.valueOf(10),  maxRowsPerPoll);
	}

	@Test //INT-1029
	public void testOutboundGatewayInsideChain() {
		setUp("handlingMapPayloadJdbcOutboundGatewayTest.xml", getClass());

		String beanName = "org.springframework.integration.handler.MessageHandlerChain#" +
				"0$child.jdbc-outbound-gateway-within-chain.handler";
		JdbcOutboundGateway jdbcMessageHandler = this.context.getBean(beanName, JdbcOutboundGateway.class);

		MessageChannel channel = this.context.getBean("jdbcOutboundGatewayInsideChain", MessageChannel.class);

		assertFalse(TestUtils.getPropertyValue(jdbcMessageHandler, "requiresReply", Boolean.class));

		channel.send(MessageBuilder.withPayload(Collections.singletonMap("foo", "bar")).build());

		PollableChannel outbound = this.context.getBean("replyChannel", PollableChannel.class);
		Message<?> reply = outbound.receive(10000);
		assertNotNull(reply);
		@SuppressWarnings("unchecked")
		Map<String, ?> payload = (Map<String, ?>) reply.getPayload();
		assertEquals("bar", payload.get("name"));
	}


	@After
	public void tearDown() {
		if (this.context != null) {
			this.context.close();
		}
	}

	protected void setupMessagingTemplate() {
		PollableChannel pollableChannel = this.context.getBean("output", PollableChannel.class);
		this.messagingTemplate = new MessagingTemplate();
		this.messagingTemplate.setDefaultDestination(pollableChannel);
		this.messagingTemplate.setReceiveTimeout(10000);
	}

	public void setUp(String name, Class<?> cls) {
		this.context = new ClassPathXmlApplicationContext(name, cls);
		this.jdbcTemplate = new JdbcTemplate(this.context.getBean("dataSource", DataSource.class));
		this.channel = this.context.getBean("target", MessageChannel.class);
		setupMessagingTemplate();
	}

	public static class FooAdvice extends AbstractRequestHandlerAdvice {

		@Override
		protected Object doInvoke(ExecutionCallback callback, Object target, Message<?> message) throws Exception {
			adviceCalled++;
			return callback.execute();
		}

	}

	public static class TestMessagePreparedStatementSetter implements MessagePreparedStatementSetter {

		@Override
		public void setValues(PreparedStatement ps, Message<?> requestMessage) throws SQLException {
			ps.setObject(1, requestMessage.getPayload());
		}

	}

}
