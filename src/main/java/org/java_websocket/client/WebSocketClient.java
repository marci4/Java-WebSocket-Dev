/*
 * Copyright (c) 2010-2018 Nathan Rajlich
 *
 *  Permission is hereby granted, free of charge, to any person
 *  obtaining a copy of this software and associated documentation
 *  files (the "Software"), to deal in the Software without
 *  restriction, including without limitation the rights to use,
 *  copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the
 *  Software is furnished to do so, subject to the following
 *  conditions:
 *
 *  The above copyright notice and this permission notice shall be
 *  included in all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 *  OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 *  HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 *  WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 *  OTHER DEALINGS IN THE SOFTWARE.
 */

package org.java_websocket.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

import org.java_websocket.AbstractWebSocket;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.Framedata.Opcode;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshake;

/**
 * A subclass must implement at least <var>onOpen</var>, <var>onClose</var>, and <var>onMessage</var> to be
 * useful. At runtime the user is expected to establish a connection via {@link #connect()}, then receive events like {@link #onMessage(String)} via the overloaded methods and to {@link #send(String)} data to the server.
 */
public abstract class WebSocketClient extends AbstractWebSocket implements Runnable, WebSocket {

	/**
	 * The URI this channel is supposed to connect to.
	 */
	protected URI uri = null;

	/**
	 * The underlying engine
	 */
	private WebSocketImpl engine = null;

	/**
	 * The socket for this WebSocketClient
	 */
	private Socket socket = null;

	/**
	 * The used OutputStream
	 */
	private OutputStream ostream;

	/**
	 * The used proxy, if any
	 */
	private Proxy proxy = Proxy.NO_PROXY;

	/**
	 * The thread to write outgoing message
	 */
	private Thread writeThread;

	/**
	 * The thread to connect and read message
	 */
	private Thread connectReadThread;

	/**
	 * The draft to use
	 */
	private Draft draft;

	/**
	 * The additional headers to use
	 */
	private Map<String,String> headers;

	/**
	 * The latch for connectBlocking()
	 */
	private CountDownLatch connectLatch = new CountDownLatch( 1 );

	/**
	 * The latch for closeBlocking()
	 */
	private CountDownLatch closeLatch = new CountDownLatch( 1 );

	/**
	 * The socket timeout value to be used in milliseconds.
	 */
	private int connectTimeout = 0;


	private final Object threadObject = new Object();

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The channel does not attampt to connect automatically. The connection
	 * will be established once you call <var>connect</var>.
	 *
	 * @param serverUri the server URI to connect to
	 */
	public WebSocketClient( URI serverUri ) {
		this( serverUri, new Draft_6455());
	}

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The channel does not attampt to connect automatically. The connection
	 * will be established once you call <var>connect</var>.
	 * @param serverUri the server URI to connect to
	 * @param protocolDraft The draft which should be used for this connection
	 */
	public WebSocketClient( URI serverUri , Draft protocolDraft ) {
		this( serverUri, protocolDraft, null, 0 );
	}

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The channel does not attampt to connect automatically. The connection
	 * will be established once you call <var>connect</var>.
	 * @param serverUri the server URI to connect to
	 * @param httpHeaders Additional HTTP-Headers
	 * @since 1.3.8
	 */
	public WebSocketClient( URI serverUri, Map<String,String> httpHeaders) {
		this(serverUri, new Draft_6455(), httpHeaders);
	}

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The channel does not attampt to connect automatically. The connection
	 * will be established once you call <var>connect</var>.
	 * @param serverUri the server URI to connect to
	 * @param protocolDraft The draft which should be used for this connection
	 * @param httpHeaders Additional HTTP-Headers
	 * @since 1.3.8
	 */
	public WebSocketClient( URI serverUri , Draft protocolDraft , Map<String,String> httpHeaders) {
		this(serverUri, protocolDraft, httpHeaders, 0);
	}

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The channel does not attampt to connect automatically. The connection
	 * will be established once you call <var>connect</var>.
	 * @param serverUri the server URI to connect to
	 * @param protocolDraft The draft which should be used for this connection
	 * @param httpHeaders Additional HTTP-Headers
	 * @param connectTimeout The Timeout for the connection
	 */
	public WebSocketClient( URI serverUri , Draft protocolDraft , Map<String,String> httpHeaders , int connectTimeout ) {
		if( serverUri == null ) {
			throw new IllegalArgumentException();
		} else if( protocolDraft == null ) {
			throw new IllegalArgumentException( "null as draft is permitted for `WebSocketServer` only!" );
		}
		this.uri = serverUri;
		this.draft = protocolDraft;
		this.headers = httpHeaders;
		this.connectTimeout = connectTimeout;
		setTcpNoDelay( false );
		setReuseAddr( false );
		this.engine = new WebSocketImpl( this, protocolDraft );
	}

	/**
	 * Returns the URI that this WebSocketClient is connected to.
	 * @return the URI connected to
	 */
	public URI getURI() {
		return uri;
	}

	/**
	 * Returns the protocol version this channel uses.<br>
	 * For more infos see https://github.com/TooTallNate/Java-WebSocket/wiki/Drafts
	 * @return The draft used for this client
	 */
	public Draft getDraft() {
		return draft;
	}

	/**
	 * Returns the socket to allow Hostname Verification
	 * @return the socket used for this connection
	 */
	public Socket getSocket() {
		return socket;
	}

	/**
	 * Reinitiates the websocket connection. This method does not block.
	 * @since 1.3.8
	 */
	public void reconnect() {
		reset();
		connect();
	}

	/**
	 * Same as <code>reconnect</code> but blocks until the websocket reconnected or failed to do so.<br>
	 * @return Returns whether it succeeded or not.
	 * @throws InterruptedException Thrown when the threads get interrupted
	 * @since 1.3.8
	 */
	public boolean reconnectBlocking() throws InterruptedException {
		reset();
		return connectBlocking();
	}

	/**
	 * Reset everything relevant to allow a reconnect
	 * @since 1.3.8
	 */
	private void reset() {
		Thread current = Thread.currentThread();
		synchronized (threadObject) {
			if (current == writeThread || current == connectReadThread) {
				throw new IllegalStateException("You cannot initialize a reconnect out of the websocket thread. Use reconnect in another thread to insure a successful cleanup.");
			}
		}
		try {
			closeBlocking();
			synchronized (threadObject) {
				if (writeThread != null) {
					this.writeThread.interrupt();
					this.writeThread = null;
				}
				if (connectReadThread != null) {
					this.connectReadThread.interrupt();
					this.connectReadThread = null;
				}
				this.draft.reset();
				if( this.socket != null ) {
					this.socket.close();
					this.socket = null;
				}
				connectLatch = new CountDownLatch( 1 );
				System.out.println("Count reset!");
				closeLatch = new CountDownLatch( 1 );
			}
		} catch ( Exception e ) {
			onError( e );
			engine.closeConnection( CloseFrame.ABNORMAL_CLOSE, e.getMessage() );
			return;
		}
		this.engine = new WebSocketImpl( this, this.draft );
	}

	/**
	 * Initiates the websocket connection. This method does not block.
	 */
	public void connect() {
		synchronized (threadObject) {
			if (connectReadThread != null)
				throw new IllegalStateException("WebSocketClient objects are not reuseable");
			connectReadThread = new Thread(this);
			connectReadThread.setName("WebSocketConnectReadThread-" + connectReadThread.getId());
			connectReadThread.start();
		}
	}

	/**
	 * Same as <code>connect</code> but blocks until the websocket connected or failed to do so.<br>
	 * @return Returns whether it succeeded or not.
	 * @throws InterruptedException Thrown when the threads get interrupted
	 */
	public boolean connectBlocking() throws InterruptedException {
		connect();
		connectLatch.await();
		return engine.isOpen();
	}

	/**
	 * Same as <code>connect</code> but blocks with a timeout until the websocket connected or failed to do so.<br>
	 * @param timeout
	 *               The connect timeout
	 * @param timeUnit
	 *                The timeout time unit
	 * @return Returns whether it succeeded or not.
	 * @throws InterruptedException Thrown when the threads get interrupted
	 */
	public boolean connectBlocking(long timeout, TimeUnit timeUnit) throws InterruptedException {
		connect();
		return connectLatch.await(timeout, timeUnit) && engine.isOpen();
	}

	/**
	 * Initiates the websocket close handshake. This method does not block<br>
	 * In oder to make sure the connection is closed use <code>closeBlocking</code>
	 */
	public void close() {
		synchronized (threadObject) {
			if (writeThread != null) {
				engine.close(CloseFrame.NORMAL);
			}
		}
	}
	/**
	 * Same as <code>close</code> but blocks until the websocket closed or failed to do so.<br>
	 * @throws InterruptedException Thrown when the threads get interrupted
	 */
	public void closeBlocking() throws InterruptedException {
		close();
		System.out.println("Count await!");
		closeLatch.await();
	}

	/**
	 * Sends <var>text</var> to the connected websocket server.
	 *
	 * @param text
	 *            The string which will be transmitted.
	 */
	public void send( String text ) throws NotYetConnectedException {
		engine.send( text );
	}

	/**
	 * Sends binary <var> data</var> to the connected webSocket server.
	 *
	 * @param data
	 *            The byte-Array of data to send to the WebSocket server.
	 */
	public void send( byte[] data ) throws NotYetConnectedException {
		engine.send( data );
	}

	@Override
	public <T> T getAttachment() {
		return engine.getAttachment();
	}

	@Override
	public <T> void setAttachment(T attachment) {
		engine.setAttachment( attachment );
	}

	@Override
	protected Collection<WebSocket> getConnections() {
		return Collections.singletonList((WebSocket ) engine );
	}

	@Override
	public void sendPing() throws NotYetConnectedException {
		engine.sendPing( );
	}

	public void run() {
		InputStream istream;
		try {
			boolean isNewSocket = false;

			if( socket == null ) {
				socket = new Socket( proxy );
				isNewSocket = true;

			} else if( socket.isClosed() ) {
				throw new IOException();
			}

			socket.setTcpNoDelay( isTcpNoDelay() );
			socket.setReuseAddress( isReuseAddr() );

			if( !socket.isBound() ) {
				socket.connect( new InetSocketAddress( uri.getHost(), getPort() ), connectTimeout );
			}

			// if the socket is set by others we don't apply any TLS wrapper
			if (isNewSocket && "wss".equals( uri.getScheme())) {

				SSLContext sslContext = SSLContext.getInstance("TLS");
				sslContext.init(null, null, null);
				SSLSocketFactory factory = sslContext.getSocketFactory();
				socket = factory.createSocket(socket, uri.getHost(), getPort(), true);
			}

			istream = socket.getInputStream();
			ostream = socket.getOutputStream();

			sendHandshake();
		} catch ( /*IOException | SecurityException | UnresolvedAddressException | InvalidHandshakeException | ClosedByInterruptException | SocketTimeoutException */Exception e ) {
			onWebsocketError( engine, e );
			engine.closeConnection( CloseFrame.NEVER_CONNECTED, e.getMessage() );
			return;
		}
		synchronized (threadObject) {
			writeThread = new Thread(new WebsocketWriteThread());
			writeThread.start();
		}

		byte[] rawbuffer = new byte[ WebSocketImpl.RCVBUF ];
		int readBytes;

		try {
			while ( !isClosing() && !isClosed() && ( readBytes = istream.read( rawbuffer ) ) != -1 ) {
				engine.decode( ByteBuffer.wrap( rawbuffer, 0, readBytes ) );
			}
			engine.eot();
		} catch ( IOException e ) {
			handleIOException(e);
		} catch ( RuntimeException e ) {
			// this catch case covers internal errors only and indicates a bug in this websocket implementation
			onError( e );
			engine.closeConnection( CloseFrame.ABNORMAL_CLOSE, e.getMessage() );
		}
		synchronized (threadObject) {
			connectReadThread = null;
		}
	}

	/**
	 * Extract the specified port
	 * @return the specified port or the default port for the specific scheme
	 */
	private int getPort() {
		int port = uri.getPort();
		if( port == -1 ) {
			String scheme = uri.getScheme();
			if( "wss".equals( scheme ) ) {
				return WebSocket.DEFAULT_WSS_PORT;
			} else if(  "ws".equals( scheme ) ) {
				return WebSocket.DEFAULT_PORT;
			} else {
				throw new IllegalArgumentException( "unknown scheme: " + scheme );
			}
		}
		return port;
	}

	/**
	 * Create and send the handshake to the other endpoint
	 * @throws InvalidHandshakeException  a invalid handshake was created
	 */
	private void sendHandshake() throws InvalidHandshakeException {
		String path;
		String part1 = uri.getRawPath();
		String part2 = uri.getRawQuery();
		if( part1 == null || part1.length() == 0 )
			path = "/";
		else
			path = part1;
		if( part2 != null )
			path += '?' + part2;
		int port = getPort();
		String host = uri.getHost() + ( 
			(port != WebSocket.DEFAULT_PORT && port != WebSocket.DEFAULT_WSS_PORT)
			? ":" + port 
			: "" );

		HandshakeImpl1Client handshake = new HandshakeImpl1Client();
		handshake.setResourceDescriptor( path );
		handshake.put( "Host", host );
		if( headers != null ) {
			for( Map.Entry<String,String> kv : headers.entrySet() ) {
				handshake.put( kv.getKey(), kv.getValue() );
			}
		}
		engine.startHandshake( handshake );
	}

	/**
	 * This represents the state of the connection.
	 */
	public READYSTATE getReadyState() {
		return engine.getReadyState();
	}

	/**
	 * Calls subclass' implementation of <var>onMessage</var>.
	 */
	@Override
	public final void onWebsocketMessage( WebSocket conn, String message ) {
		onMessage( message );
	}

	@Override
	public final void onWebsocketMessage( WebSocket conn, ByteBuffer blob ) {
		onMessage( blob );
	}

	/**
	 * Calls subclass' implementation of <var>onOpen</var>.
	 */
	@Override
	public final void onWebsocketOpen( WebSocket conn, Handshakedata handshake ) {
		startConnectionLostTimer();
		onOpen( (ServerHandshake) handshake );
		synchronized (threadObject) {
			connectLatch.countDown();
			System.out.println("Count down open!");
		}
	}

	/**
	 * Calls subclass' implementation of <var>onClose</var>.
	 */
	@Override
	public final void onWebsocketClose( WebSocket conn, int code, String reason, boolean remote ) {
		stopConnectionLostTimer();
		synchronized (threadObject) {
			if (writeThread != null)
				writeThread.interrupt();
			onClose( code, reason, remote );
			System.out.println("Count down!");
			connectLatch.countDown();
			closeLatch.countDown();
		}
	}

	/**
	 * Calls subclass' implementation of <var>onIOError</var>.
	 */
	@Override
	public final void onWebsocketError( WebSocket conn, Exception ex ) {
		onError( ex );
	}

	@Override
	public final void onWriteDemand( WebSocket conn ) {
		// nothing to do
	}

	@Override
	public void onWebsocketCloseInitiated( WebSocket conn, int code, String reason ) {
		onCloseInitiated( code, reason );
	}

	@Override
	public void onWebsocketClosing( WebSocket conn, int code, String reason, boolean remote ) {
		onClosing( code, reason, remote );
	}

	/**
	 * Send when this peer sends a close handshake
	 *
	 * @param code The codes can be looked up here: {@link CloseFrame}
	 * @param reason Additional information string
	 */
	public void onCloseInitiated( int code, String reason ) {
		//To overwrite
	}

	/** Called as soon as no further frames are accepted
	 *
	 * @param code The codes can be looked up here: {@link CloseFrame}
	 * @param reason Additional information string
	 * @param remote Returns whether or not the closing of the connection was initiated by the remote host.
	 */
	public void onClosing( int code, String reason, boolean remote ) {
		//To overwrite
	}

	/**
	 * Getter for the engine
	 * @return the engine
	 */
	public WebSocket getConnection() {
		return engine;
	}

	@Override
	public InetSocketAddress getLocalSocketAddress( WebSocket conn ) {
		if( socket != null )
			return (InetSocketAddress) socket.getLocalSocketAddress();
		return null;
	}

	@Override
	public InetSocketAddress getRemoteSocketAddress( WebSocket conn ) {
		if( socket != null )
			return (InetSocketAddress) socket.getRemoteSocketAddress();
		return null;
	}

	// ABTRACT METHODS /////////////////////////////////////////////////////////

	/**
	 * Called after an opening handshake has been performed and the given websocket is ready to be written on.
	 * @param handshakedata The handshake of the websocket instance
	 */
	public abstract void onOpen( ServerHandshake handshakedata );

	/**
	 * Callback for string messages received from the remote host
	 *
	 * @see #onMessage(ByteBuffer)
	 * @param message The UTF-8 decoded message that was received.
	 **/
	public abstract void onMessage( String message );

	/**
	 * Called after the websocket connection has been closed.
	 *
	 * @param code
	 *            The codes can be looked up here: {@link CloseFrame}
	 * @param reason
	 *            Additional information string
	 * @param remote
	 *            Returns whether or not the closing of the connection was initiated by the remote host.
	 **/
	public abstract void onClose( int code, String reason, boolean remote );

	/**
	 * Called when errors occurs. If an error causes the websocket connection to fail {@link #onClose(int, String, boolean)} will be called additionally.<br>
	 * This method will be called primarily because of IO or protocol errors.<br>
	 * If the given exception is an RuntimeException that probably means that you encountered a bug.<br>
	 *
	 * @param ex The exception causing this error
	 **/
	public abstract void onError( Exception ex );

	/**
	 * Callback for binary messages received from the remote host
	 *
	 * @see #onMessage(String)
	 *
	 * @param bytes
	 *            The binary message that was received.
	 **/
	public void onMessage( ByteBuffer bytes ) {
		//To overwrite
	}

	/**
	 * Callback for fragmented frames
	 * @see WebSocket#sendFragmentedFrame(org.java_websocket.framing.Framedata.Opcode, ByteBuffer, boolean)
	 * @param frame The fragmented frame
	 */
	@Deprecated
	public void onFragment( Framedata frame ) {
		//To overwrite
	}

	private class WebsocketWriteThread implements Runnable {
		@Override
		public void run() {
			Thread.currentThread().setName( "WebSocketWriteThread-" + Thread.currentThread().getId() );
			try {
				try {
					while( !Thread.interrupted() ) {
						ByteBuffer buffer = engine.outQueue.take();
						ostream.write( buffer.array(), 0, buffer.limit() );
						ostream.flush();
					}
				} catch ( InterruptedException e ) {
					for (ByteBuffer buffer : engine.outQueue) {
						ostream.write( buffer.array(), 0, buffer.limit() );
						ostream.flush();
					}
				}
			} catch ( IOException e ) {
				handleIOException( e );
			} finally {
				closeSocket();
				writeThread = null;
			}
		}
	}

	/**
	 * Closing the socket
	 */
	private void closeSocket() {
		try {
			if( socket != null ) {
				socket.close();
			}
		} catch ( IOException ex ) {
			onWebsocketError( this, ex );
		}
	}

	/**
	 * Method to set a proxy for this connection
	 * @param proxy the proxy to use for this websocket client
	 */
	public void setProxy( Proxy proxy ) {
		if( proxy == null )
			throw new IllegalArgumentException();
		this.proxy = proxy;
	}

	/**
	 * Accepts bound and unbound sockets.<br>
	 * This method must be called before <code>connect</code>.
	 * If the given socket is not yet bound it will be bound to the uri specified in the constructor.
	 * @param socket The socket which should be used for the connection
	 */
	public void setSocket( Socket socket ) {
		if( this.socket != null ) {
			throw new IllegalStateException( "socket has already been set" );
		}
		this.socket = socket;
	}

	@Override
	public void sendFragmentedFrame( Opcode op, ByteBuffer buffer, boolean fin ) {
		engine.sendFragmentedFrame( op, buffer, fin );
	}

	@Override
	public boolean isOpen() {
		return engine.isOpen();
	}

	@Override
	public boolean isFlushAndClose() {
		return engine.isFlushAndClose();
	}

	@Override
	public boolean isClosed() {
		return engine.isClosed();
	}

	@Override
	public boolean isClosing() {
		return engine.isClosing();
	}

	@Override
	@Deprecated
	public boolean isConnecting() {
		return engine.isConnecting();
	}

	@Override
	public boolean hasBufferedData() {
		return engine.hasBufferedData();
	}

	@Override
	public void close( int code ) {
		engine.close();
	}

	@Override
	public void close( int code, String message ) {
		engine.close( code, message );
	}

	@Override
	public void closeConnection( int code, String message ) {
		engine.closeConnection( code, message );
	}

	@Override
	public void send( ByteBuffer bytes ) throws IllegalArgumentException , NotYetConnectedException {
		engine.send( bytes );
	}

	@Override
	public void sendFrame( Framedata framedata ) {
		engine.sendFrame( framedata );
	}

	@Override
	public void sendFrame( Collection<Framedata> frames ) {
		engine.sendFrame( frames );
	}

	@Override
	public InetSocketAddress getLocalSocketAddress() {
		return engine.getLocalSocketAddress();
	}
	@Override
	public InetSocketAddress getRemoteSocketAddress() {
		return engine.getRemoteSocketAddress();
	}
	
	@Override
	public String getResourceDescriptor() {
		return uri.getPath();
	}


	/**
	 * Method to give some additional info for specific IOExceptions
	 * @param e the IOException causing a eot.
	 */
	private void handleIOException( IOException e ) {
		if (e instanceof SSLException) {
			onError( e );
		}
		engine.eot();
	}
}
