/**
 * Copyright (C) 2010-2017 Structr GmbH
 *
 * This file is part of Structr <http://structr.org>.
 *
 * Structr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Structr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.websocket;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.structr.common.AccessMode;
import org.structr.common.SecurityContext;
import org.structr.common.error.FrameworkException;
import org.structr.console.Console;
import org.structr.core.GraphObject;
import org.structr.core.Services;
import org.structr.core.app.App;
import org.structr.core.app.StructrApp;
import org.structr.core.auth.Authenticator;
import org.structr.core.entity.Principal;
import org.structr.core.graph.Tx;
import org.structr.rest.auth.AuthHelper;
import org.structr.rest.auth.SessionHelper;
import org.structr.web.entity.FileBase;
import org.structr.web.entity.User;
import org.structr.websocket.command.AbstractCommand;
import org.structr.websocket.command.FileUploadHandler;
import org.structr.websocket.command.LoginCommand;
import org.structr.websocket.message.MessageBuilder;
import org.structr.websocket.message.WebSocketMessage;

//~--- classes ----------------------------------------------------------------
/**
 *
 *
 *
 */
public class StructrWebSocket implements WebSocketListener {

	private static final Logger logger = LoggerFactory.getLogger(StructrWebSocket.class.getName());
	private static final Map<String, Class> commandSet = new LinkedHashMap<>();

	//~--- fields ---------------------------------------------------------
	private Session session = null;
	private Gson gson = null;
	private HttpServletRequest request = null;
	private SecurityContext securityContext = null;
	private WebsocketController syncController = null;
	private Map<String, FileUploadHandler> uploads = null;
	private Authenticator authenticator = null;
	private String pagePath = null;
	private Console console = null;

	//~--- constructors ---------------------------------------------------
	public StructrWebSocket() {}

	public StructrWebSocket(final WebsocketController syncController, final Gson gson, final Authenticator authenticator) {

		this.uploads = new LinkedHashMap<>();
		this.syncController = syncController;
		this.gson = gson;
		this.authenticator = authenticator;

	}

	//~--- methods --------------------------------------------------------
	public void setRequest(final HttpServletRequest request) {
		this.request = request;
	}

	@Override
	public void onWebSocketConnect(final Session session) {

		logger.debug("New connection with protocol {}", session.getProtocolVersion());

		this.session = session;

		syncController.registerClient(this);

		pagePath = request.getQueryString();

	}

	@Override
	public void onWebSocketClose(final int closeCode, final String message) {

		logger.debug("Connection closed with closeCode {} and message {}", new Object[]{closeCode, message});

		final App app = StructrApp.getInstance(securityContext);

		try (final Tx tx = app.tx()) {

			this.session = null;

			syncController.unregisterClient(this);

			// flush and close open uploads
			for (FileUploadHandler upload : uploads.values()) {

				upload.finish();
			}

			tx.success();
			uploads.clear();

		} catch (FrameworkException fex) {

			logger.error("Error while closing connection", fex);

		}

	}

	@Override
	public void onWebSocketText(final String data) {

		final Services servicesInstance = Services.getInstance();

		// wait for service layer to be initialized
		while (!servicesInstance.isInitialized()) {
			try { Thread.sleep(1000); } catch(InterruptedException iex) { }
		}

		if (data == null) {
			logger.warn("Empty text message received.");
			return;
		}

		logger.debug("############################################################ RECEIVED \n{}", data.substring(0, Math.min(data.length(), 1000)));

		// parse web socket data from JSON
		final WebSocketMessage webSocketData = gson.fromJson(data, WebSocketMessage.class);

		final App app = StructrApp.getInstance(securityContext);

		final String command = webSocketData.getCommand();
		final Class type = commandSet.get(command);

		final String sessionIdFromMessage = webSocketData.getSessionId();

		if (type != null) {

			try (final Tx tx = app.tx()) {

				if (sessionIdFromMessage != null) {

					// try to authenticated this connection by sessionId
					authenticate(sessionIdFromMessage);
				}

				// we only permit LOGIN commands if authentication based on sessionId was not successful
				if (!isAuthenticated() && !type.equals(LoginCommand.class)) {

					// send 401 Authentication Required
					send(MessageBuilder.status().code(401).message("").build(), true);

					tx.success();

					return;
				}

				tx.success();

			} catch (FrameworkException t) {

				logger.warn("Unable to parse message.", t);

			}

			// process message
			try {

				AbstractCommand abstractCommand = (AbstractCommand) type.newInstance();

				abstractCommand.setWebSocket(this);
				abstractCommand.setSession(session);
				abstractCommand.setCallback(webSocketData.getCallback());

				// The below blocks allow a websocket command to manage its own
				// transactions in case of bulk processing commands etc.
				if (abstractCommand.requiresEnclosingTransaction()) {

					try (final Tx tx = app.tx()) {

						// store authenticated-Flag in webSocketData
						// so the command can access it
						webSocketData.setSessionValid(isAuthenticated());

						abstractCommand.processMessage(webSocketData);

						// commit transaction
						tx.success();
					}

				} else {

					try (final Tx tx = app.tx()) {

						// store authenticated-Flag in webSocketData
						// so the command can access it
						webSocketData.setSessionValid(isAuthenticated());

						// commit transaction
						tx.success();
					}

					// process message without transaction context!
					abstractCommand.processMessage(webSocketData);

				}

			} catch (FrameworkException | InstantiationException | IllegalAccessException t) {

				try (final Tx tx = app.tx()) {

					// send 400 Bad Request
					if (t instanceof FrameworkException) {

						final FrameworkException fex = (FrameworkException)t;

						send(MessageBuilder.status().code(fex.getStatus()).message(fex.toString()).jsonErrorObject(fex.toJSON()).callback(webSocketData.getCallback()).build(), true);

					} else {

						send(MessageBuilder.status().code(400).message(t.toString()).build(), true);

					}

					// commit transaction
					tx.success();

				} catch (FrameworkException fex) {
					logger.warn("", fex);
				}

				return;

			}

		} else {

			logger.warn("Unknown command {}", command);

			// send 400 Bad Request
			send(MessageBuilder.status().code(400).message("Unknown command").build(), true);

			return;

		}
	}

	public void send(final WebSocketMessage message, final boolean clearSessionId) {

		boolean isAuthenticated = false;

		try (final Tx tx = StructrApp.getInstance(securityContext).tx()) {

			isAuthenticated = isAuthenticated();

			tx.success();

		} catch (FrameworkException t) {
			logger.warn("", t);
		}

		// return session status to client
		message.setSessionValid(isAuthenticated);

		// whether to clear the token (all command except LOGIN (for now) should absolutely do this!)
		if (clearSessionId) {

			message.setSessionId(null);
		}

		if ("LOGIN".equals(message.getCommand()) && !isAuthenticated) {

			message.setMessage("User has no backend access.");
			message.setCode(403);

			//logger.warn("NOT sending message to unauthenticated client.");
		}

		try (final Tx tx = StructrApp.getInstance(securityContext).tx()) {

			if (message.getCode() == 0) {
				// default is: 200 OK
				message.setCode(200);
			}

			final String msg = gson.toJson(message, WebSocketMessage.class);

			logger.debug("################### Private message: {}", message.getCommand());
			logger.debug("############################################################ SENDING \n{}", msg);

			// Clear custom view here. This is necessary because the security context is reused for all websocket frames.
			if (securityContext != null) {
				securityContext.clearCustomView();
			}

			session.getRemote().sendString(msg);

			tx.success();

		} catch (Throwable t) {
			// ignore
			logger.debug("Unable to send websocket message to remote client");
		}

	}

	// ----- file handling -----
	public void createFileUploadHandler(FileBase file) {

		final String uuid = file.getProperty(GraphObject.id);

		uploads.put(uuid, new FileUploadHandler(file));

	}

	public void removeFileUploadHandler(final String uuid) {

		uploads.remove(uuid);

	}

	private FileUploadHandler handleExistingFile(final String uuid) {

		FileUploadHandler newHandler = null;

		try {

			FileBase file = (FileBase) StructrApp.getInstance(securityContext).getNodeById(uuid);

			if (file != null) {

				newHandler = new FileUploadHandler(file);

				//uploads.put(uuid, newHandler);
			}

		} catch (FrameworkException ex) {

			logger.warn("File not found with id " + uuid, ex);

		}

		return newHandler;

	}

	public void handleFileChunk(final String uuid, final int sequenceNumber, final int chunkSize, final byte[] data, final int chunks) throws IOException {

		FileUploadHandler upload = uploads.get(uuid);

		if (upload == null) {

			upload = handleExistingFile(uuid);
		}

		if (upload != null) {

			upload.handleChunk(sequenceNumber, chunkSize, data, chunks);

		}

	}

	private void authenticate(final String sessionId) {

		final Principal user = AuthHelper.getPrincipalForSessionId(sessionId);

		if (user != null) {

			try {

				final boolean sessionValid = !SessionHelper.isSessionTimedOut(SessionHelper.getSessionBySessionId(sessionId));

				if (sessionValid) {
					this.setAuthenticated(sessionId, user);
				} else {

					logger.warn("Session {} timed out - last accessed by {} ({})", sessionId, user.getName(), user.getUuid());

					SessionHelper.clearSession(sessionId);

					SessionHelper.invalidateSession(SessionHelper.getSessionBySessionId(sessionId));

					AuthHelper.sendLogoutNotification(user);

                    invalidateConsole();

				}

			} catch (FrameworkException ex) {
				logger.warn("FXE", ex);
			}

		}

	}

	public static void addCommand(final Class command) {

		try {

			final AbstractCommand msg = (AbstractCommand) command.newInstance();

			commandSet.put(msg.getCommand(), command);

		} catch (Throwable t) {

			logger.error("Unable to add command {}", command.getName());

		}

	}

	public Session getSession() {

		return session;

	}

	public HttpServletRequest getRequest() {

		return request;

	}

	public Principal getCurrentUser() {

		return (securityContext == null ? null : securityContext.getUser(false));

	}

	public SecurityContext getSecurityContext() {

		return securityContext;

	}

	public String getPagePath() {

		return pagePath;

	}

	public boolean isAuthenticated() {

		final Principal user = getCurrentUser();
		return (user != null && (isPrivilegedUser(user) || isFrontendWebsocketAccessEnabled()));

	}

	public boolean isPrivilegedUser(Principal user) {

		return (user != null && (user.getProperty(Principal.isAdmin) || user.getProperty(User.backendUser)));

	}

	public boolean isFrontendWebsocketAccessEnabled() {

		return Boolean.parseBoolean(StructrApp.getConfigurationValue(Services.WEBSOCKET_FRONTEND_ACCESS, "false"));

	}

	public Authenticator getAuthenticator() {

		return authenticator;

	}

	public void invalidateConsole() {

		this.console = null;

	}

	public Console getConsole() {

		if (this.securityContext != null) {

			if (this.console != null) {

				return this.console;

			} else {

				this.console = new Console(securityContext, null);
				return this.console;

			}

		}

		return null;

	}

	//~--- set methods ----------------------------------------------------
	public void setAuthenticated(final String sessionId, final Principal user) {
		this.securityContext = SecurityContext.getInstance(user, AccessMode.Backend);
	}

	@Override
	public void onWebSocketBinary(final byte[] bytes, int i, int i1) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public void onWebSocketError(final Throwable t) {
		logger.debug("Error in StructrWebSocket occured", t);
	}

}
