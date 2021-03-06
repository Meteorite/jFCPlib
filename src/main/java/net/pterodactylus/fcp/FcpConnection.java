/*
 * jFCPlib - FpcConnection.java - Copyright © 2008 David Roden
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package net.pterodactylus.fcp;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import net.pterodactylus.fcp.FcpUtils.TempInputStream;

/**
 * An FCP connection to a Freenet node.
 *
 * @author David ‘Bombe’ Roden &lt;bombe@freenetproject.org&gt;
 */
public class FcpConnection implements Closeable {

	/** Logger. */
	private static final Logger logger = Logger.getLogger(FcpConnection.class.getName());

	/** The default port for FCP v2. */
	public static final int DEFAULT_PORT = 9481;

	/** Listener management. */
	private final FcpListenerManager fcpListenerManager = new FcpListenerManager(this);

	/** The address of the node. */
	private final InetAddress address;

	/** The port number of the node’s FCP port. */
	private final int port;

	/** The remote socket. */
	private Socket remoteSocket;

	/** The input stream from the node. */
	private InputStream remoteInputStream;

	/** The output stream to the node. */
	private OutputStream remoteOutputStream;

	/** The connection handler. */
	private FcpConnectionHandler connectionHandler;

	/** Incoming message statistics. */
	private static final Map<String, Integer> incomingMessageStatistics = Collections.synchronizedMap(new HashMap<String, Integer>());

	/**
	 * Creates a new FCP connection to the freenet node running on localhost,
	 * using the default port.
	 *
	 * @throws UnknownHostException
	 *             if the hostname can not be resolved
	 */
	public FcpConnection() throws UnknownHostException {
		this(InetAddress.getLocalHost());
	}

	/**
	 * Creates a new FCP connection to the Freenet node running on the given
	 * host, listening on the default port.
	 *
	 * @param host
	 *            The hostname of the Freenet node
	 * @throws UnknownHostException
	 *             if <code>host</code> can not be resolved
	 */
	public FcpConnection(String host) throws UnknownHostException {
		this(host, DEFAULT_PORT);
	}

	/**
	 * Creates a new FCP connection to the Freenet node running on the given
	 * host, listening on the given port.
	 *
	 * @param host
	 *            The hostname of the Freenet node
	 * @param port
	 *            The port number of the node’s FCP port
	 * @throws UnknownHostException
	 *             if <code>host</code> can not be resolved
	 */
	public FcpConnection(String host, int port) throws UnknownHostException {
		this(InetAddress.getByName(host), port);
	}

	/**
	 * Creates a new FCP connection to the Freenet node running at the given
	 * address, listening on the default port.
	 *
	 * @param address
	 *            The address of the Freenet node
	 */
	public FcpConnection(InetAddress address) {
		this(address, DEFAULT_PORT);
	}

	/**
	 * Creates a new FCP connection to the Freenet node running at the given
	 * address, listening on the given port.
	 *
	 * @param address
	 *            The address of the Freenet node
	 * @param port
	 *            The port number of the node’s FCP port
	 */
	public FcpConnection(InetAddress address, int port) {
		this.address = address;
		this.port = port;
	}

	//
	// LISTENER MANAGEMENT
	//

	/**
	 * Adds the given listener to the list of listeners.
	 *
	 * @param fcpListener
	 *            The listener to add
	 */
	public void addFcpListener(FcpListener fcpListener) {
		fcpListenerManager.addListener(fcpListener);
	}

	/**
	 * Removes the given listener from the list of listeners.
	 *
	 * @param fcpListener
	 *            The listener to remove
	 */
	public void removeFcpListener(FcpListener fcpListener) {
		fcpListenerManager.removeListener(fcpListener);
	}

	public synchronized boolean isClosed() {
		return connectionHandler == null;
	}

	//
	// ACTIONS
	//

	/**
	 * Connects to the node.
	 *
	 * @throws IOException
	 *             if an I/O error occurs
	 * @throws IllegalStateException
	 *             if there is already a connection to the node
	 */
	public synchronized void connect() throws IOException, IllegalStateException {
		if (connectionHandler != null) {
			throw new IllegalStateException("already connected, disconnect first");
		}
		logger.info("connecting to " + address + ":" + port + "…");
		remoteSocket = new Socket(address, port);
		remoteInputStream = remoteSocket.getInputStream();
		remoteOutputStream = remoteSocket.getOutputStream();
		new Thread(connectionHandler = new FcpConnectionHandler(this, remoteInputStream)).start();
	}

	/**
	 * Disconnects from the node. If there is no connection to the node, this
	 * method does nothing.
	 *
	 * @deprecated Use {@link #close()} instead
	 */
	@Deprecated
	public synchronized void disconnect() {
		close();
	}

	/**
	 * Closes the connection. If there is no connection to the node, this
	 * method does nothing.
	 */
	@Override
	public void close() {
		handleDisconnect(null);
	}

	/**
	 * Sends the given FCP message.
	 *
	 * @param fcpMessage
	 *            The FCP message to send
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public synchronized void sendMessage(FcpMessage fcpMessage) throws IOException {
		logger.fine("sending message: " + fcpMessage.getName());
		fcpMessage.write(remoteOutputStream);
	}

	//
	// PACKAGE-PRIVATE METHODS
	//

	/**
	 * Handles the given message, notifying listeners. This message should only
	 * be called by {@link FcpConnectionHandler}.
	 *
	 * @param fcpMessage
	 *            The received message
	 */
	void handleMessage(FcpMessage fcpMessage) throws IOException{
		logger.fine("received message: " + fcpMessage.getName());
		String messageName = fcpMessage.getName();
		countMessage(messageName);
		if ("SimpleProgress".equals(messageName)) {
			fcpListenerManager.fireReceivedSimpleProgress(new SimpleProgress(fcpMessage));
		} else if ("ProtocolError".equals(messageName)) {
			fcpListenerManager.fireReceivedProtocolError(new ProtocolError(fcpMessage));
		} else if ("PersistentGet".equals(messageName)) {
			fcpListenerManager.fireReceivedPersistentGet(new PersistentGet(fcpMessage));
		} else if ("PersistentPut".equals(messageName)) {
			fcpListenerManager.fireReceivedPersistentPut(new PersistentPut(fcpMessage));
		} else if ("PersistentPutDir".equals(messageName)) {
			fcpListenerManager.fireReceivedPersistentPutDir(new PersistentPutDir(fcpMessage));
		} else if ("URIGenerated".equals(messageName)) {
			fcpListenerManager.fireReceivedURIGenerated(new URIGenerated(fcpMessage));
		} else if ("EndListPersistentRequests".equals(messageName)) {
			fcpListenerManager.fireReceivedEndListPersistentRequests(new EndListPersistentRequests(fcpMessage));
		} else if ("Peer".equals(messageName)) {
			fcpListenerManager.fireReceivedPeer(new Peer(fcpMessage));
		} else if ("PeerNote".equals(messageName)) {
			fcpListenerManager.fireReceivedPeerNote(new PeerNote(fcpMessage));
		} else if ("StartedCompression".equals(messageName)) {
			fcpListenerManager.fireReceivedStartedCompression(new StartedCompression(fcpMessage));
		} else if ("FinishedCompression".equals(messageName)) {
			fcpListenerManager.fireReceivedFinishedCompression(new FinishedCompression(fcpMessage));
		} else if ("GetFailed".equals(messageName)) {
			fcpListenerManager.fireReceivedGetFailed(new GetFailed(fcpMessage));
		} else if ("PutFetchable".equals(messageName)) {
			fcpListenerManager.fireReceivedPutFetchable(new PutFetchable(fcpMessage));
		} else if ("PutSuccessful".equals(messageName)) {
			fcpListenerManager.fireReceivedPutSuccessful(new PutSuccessful(fcpMessage));
		} else if ("PutFailed".equals(messageName)) {
			fcpListenerManager.fireReceivedPutFailed(new PutFailed(fcpMessage));
		} else if ("DataFound".equals(messageName)) {
			fcpListenerManager.fireReceivedDataFound(new DataFound(fcpMessage));
		} else if ("SubscribedUSKUpdate".equals(messageName)) {
			fcpListenerManager.fireReceivedSubscribedUSKUpdate(new SubscribedUSKUpdate(fcpMessage));
		} else if ("SubscribedUSK".equals(messageName)) {
			fcpListenerManager.fireReceivedSubscribedUSK(new SubscribedUSK(fcpMessage));
		} else if ("IdentifierCollision".equals(messageName)) {
			fcpListenerManager.fireReceivedIdentifierCollision(new IdentifierCollision(fcpMessage));
		} else if ("AllData".equals(messageName)) {
			InputStream payloadInputStream = getInputStream(FcpUtils.safeParseLong(fcpMessage.getField("DataLength")));
			fcpListenerManager.fireReceivedAllData(new AllData(fcpMessage, payloadInputStream));
		} else if ("EndListPeerNotes".equals(messageName)) {
			fcpListenerManager.fireReceivedEndListPeerNotes(new EndListPeerNotes(fcpMessage));
		} else if ("EndListPeers".equals(messageName)) {
			fcpListenerManager.fireReceivedEndListPeers(new EndListPeers(fcpMessage));
		} else if ("SSKKeypair".equals(messageName)) {
			fcpListenerManager.fireReceivedSSKKeypair(new SSKKeypair(fcpMessage));
		} else if ("PeerRemoved".equals(messageName)) {
			fcpListenerManager.fireReceivedPeerRemoved(new PeerRemoved(fcpMessage));
		} else if ("PersistentRequestModified".equals(messageName)) {
			fcpListenerManager.fireReceivedPersistentRequestModified(new PersistentRequestModified(fcpMessage));
		} else if ("PersistentRequestRemoved".equals(messageName)) {
			fcpListenerManager.fireReceivedPersistentRequestRemoved(new PersistentRequestRemoved(fcpMessage));
		} else if ("UnknownPeerNoteType".equals(messageName)) {
			fcpListenerManager.fireReceivedUnknownPeerNoteType(new UnknownPeerNoteType(fcpMessage));
		} else if ("UnknownNodeIdentifier".equals(messageName)) {
			fcpListenerManager.fireReceivedUnknownNodeIdentifier(new UnknownNodeIdentifier(fcpMessage));
		} else if ("FCPPluginReply".equals(messageName)) {
			InputStream payloadInputStream = getInputStream(FcpUtils.safeParseLong(fcpMessage.getField("DataLength")));
			fcpListenerManager.fireReceivedFCPPluginReply(new FCPPluginReply(fcpMessage, payloadInputStream));
		} else if ("PluginInfo".equals(messageName)) {
			fcpListenerManager.fireReceivedPluginInfo(new PluginInfo(fcpMessage));
		} else if ("PluginRemoved".equals(messageName)) {
			fcpListenerManager.fireReceivedPluginRemoved(new PluginRemoved(fcpMessage));
		} else if ("NodeData".equals(messageName)) {
			fcpListenerManager.fireReceivedNodeData(new NodeData(fcpMessage));
		} else if ("TestDDAReply".equals(messageName)) {
			fcpListenerManager.fireReceivedTestDDAReply(new TestDDAReply(fcpMessage));
		} else if ("TestDDAComplete".equals(messageName)) {
			fcpListenerManager.fireReceivedTestDDAComplete(new TestDDAComplete(fcpMessage));
		} else if ("ConfigData".equals(messageName)) {
			fcpListenerManager.fireReceivedConfigData(new ConfigData(fcpMessage));
		} else if ("NodeHello".equals(messageName)) {
			fcpListenerManager.fireReceivedNodeHello(new NodeHello(fcpMessage));
		} else if ("CloseConnectionDuplicateClientName".equals(messageName)) {
			fcpListenerManager.fireReceivedCloseConnectionDuplicateClientName(new CloseConnectionDuplicateClientName(fcpMessage));
		} else if ("SentFeed".equals(messageName)) {
			fcpListenerManager.fireSentFeed(new SentFeed(fcpMessage));
		} else if ("ReceivedBookmarkFeed".equals(messageName)) {
			fcpListenerManager.fireReceivedBookmarkFeed(new ReceivedBookmarkFeed(fcpMessage));
		} else {
			fcpListenerManager.fireMessageReceived(fcpMessage);
		}
	}

	/**
	 * Handles a disconnect from the node.
	 *
	 * @param throwable
	 *            The exception that caused the disconnect, or
	 *            <code>null</code> if there was no exception
	 */
	synchronized void handleDisconnect(Throwable throwable) {
		FcpUtils.close(remoteInputStream);
		FcpUtils.close(remoteOutputStream);
		FcpUtils.close(remoteSocket);
		if (connectionHandler != null) {
			connectionHandler.stop();
			connectionHandler = null;
			fcpListenerManager.fireConnectionClosed(throwable);
		}
	}

	//
	// PRIVATE METHODS
	//

	/**
	 * Incremets the counter in {@link #incomingMessageStatistics} by
	 * <cod>1</code> for the given message name.
	 *
	 * @param name
	 *            The name of the message to count
	 */
	private void countMessage(String name) {
		int oldValue = 0;
		if (incomingMessageStatistics.containsKey(name)) {
			oldValue = incomingMessageStatistics.get(name);
		}
		incomingMessageStatistics.put(name, oldValue + 1);
		logger.finest("count for " + name + ": " + (oldValue + 1));
	}

	private synchronized InputStream getInputStream(long dataLength) throws IOException {
		return new TempInputStream(remoteInputStream, dataLength);
	}

}
