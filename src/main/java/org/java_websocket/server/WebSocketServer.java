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

package org.java_websocket.server;

import org.java_websocket.*;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshakeBuilder;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <tt>WebSocketServer</tt> is an abstract class that only takes care of the
 * HTTP handshake portion of WebSockets. It's up to a subclass to add
 * functionality/purpose to the server.
 */
public abstract class WebSocketServer extends AbstractWebSocket implements Runnable {

	public static int DECODERS = Runtime.getRuntime().availableProcessors();

	/**
	 * Holds the list of active WebSocket connections. "Active" means WebSocket
	 * handshake is complete and socket can be written to, or read from.
	 */
	private final Collection<WebSocket> connections;
	/**
	 * The port number that this WebSocket server should listen on. Default is
	 * WebSocket.DEFAULT_PORT.
	 */
	private final InetSocketAddress address;
	/**
	 * The socket channel for this WebSocket server.
	 */
	private ServerSocketChannel server;
	/**
	 * The 'Selector' used to get event keys from the underlying socket.
	 */
	private Selector selector;
	/**
	 * The Draft of the WebSocket protocol the Server is adhering to.
	 */
	private List<Draft> drafts;

	private Thread selectorthread;

	private final AtomicBoolean isclosed = new AtomicBoolean( false );

	private ThreadPoolExecutor threadPoolExecutor = null;

	private List<WebSocketImpl> iqueue;
	private final ReferenceQueue<ByteBuffer> buffers;

	private WebSocketServerFactory wsf = new DefaultWebSocketServerFactory();

	/**
	 * Creates a WebSocketServer that will attempt to
	 * listen on port <var>WebSocket.DEFAULT_PORT</var>.
	 *
	 * @see #WebSocketServer(InetSocketAddress, int, List, Collection) more details here
	 */
	public WebSocketServer() {
		this( new InetSocketAddress( WebSocket.DEFAULT_PORT ), DECODERS, null );
	}

	/**
	 * Creates a WebSocketServer that will attempt to bind/listen on the given <var>address</var>.
	 *
	 * @param address The address to listen to
	 */
	public WebSocketServer( InetSocketAddress address ) {
		this( address, DECODERS, null );
	}

	/**
	 * @param address      The address (host:port) this server should listen on.
	 * @param decodercount The number of threads that will be used to process the incoming network data. By default this will be <code>Runtime.getRuntime().availableProcessors()</code>
	 * @see #WebSocketServer(InetSocketAddress, int, List, Collection) more details here
	 */
	public WebSocketServer( InetSocketAddress address, int decodercount ) {
		this( address, decodercount, null );
	}

	/**
	 * @param address The address (host:port) this server should listen on.
	 * @param drafts  The versions of the WebSocket protocol that this server
	 *                instance should comply to. Clients that use an other protocol version will be rejected.
	 * @see #WebSocketServer(InetSocketAddress, int, List, Collection) more details here
	 */
	public WebSocketServer( InetSocketAddress address, List<Draft> drafts ) {
		this( address, DECODERS, drafts );
	}

	/**
	 * @param address      The address (host:port) this server should listen on.
	 * @param decodercount The number of threads that will be used to process the incoming network data. By default this will be <code>Runtime.getRuntime().availableProcessors()</code>
	 * @param drafts       The versions of the WebSocket protocol that this server
	 *                     instance should comply to. Clients that use an other protocol version will be rejected.
	 * @see #WebSocketServer(InetSocketAddress, int, List, Collection) more details here
	 */
	public WebSocketServer( InetSocketAddress address, int decodercount, List<Draft> drafts ) {
		this( address, decodercount, drafts, new HashSet<WebSocket>() );
	}

	/**
	 * Creates a WebSocketServer that will attempt to bind/listen on the given <var>address</var>,
	 * and comply with <tt>Draft</tt> version <var>draft</var>.
	 *
	 * @param address              The address (host:port) this server should listen on.
	 * @param decodercount         The number of threads that will be used to process the incoming network data. By default this will be <code>Runtime.getRuntime().availableProcessors()</code>
	 * @param drafts               The versions of the WebSocket protocol that this server
	 *                             instance should comply to. Clients that use an other protocol version will be rejected.
	 * @param connectionscontainer Allows to specify a collection that will be used to store the websockets in. <br>
	 *                             If you plan to often iterate through the currently connected websockets you may want to use a collection that does not require synchronization like a {@link CopyOnWriteArraySet}. In that case make sure that you overload {@link #removeConnection(WebSocket)} and {@link #addConnection(WebSocket)}.<br>
	 *                             By default a {@link HashSet} will be used.
	 * @see #removeConnection(WebSocket) for more control over syncronized operation
	 * @see <a href="https://github.com/TooTallNate/Java-WebSocket/wiki/Drafts" > more about drafts</a>
	 */
	public WebSocketServer( InetSocketAddress address, int decodercount, List<Draft> drafts, Collection<WebSocket> connectionscontainer ) {
		if( address == null || decodercount < 1 || connectionscontainer == null ) {
			throw new IllegalArgumentException( "address and connectionscontainer must not be null and you need at least 1 decoder" );
		}

		if( drafts == null )
			this.drafts = Collections.emptyList();
		else
			this.drafts = drafts;

		this.address = address;
		this.connections = connectionscontainer;
		setTcpNoDelay( false );
		setReuseAddr( false );
		iqueue = new LinkedList<WebSocketImpl>();
		buffers = new ReferenceQueue<ByteBuffer>();
		threadPoolExecutor = new ThreadPoolExecutor( 4, Integer.MAX_VALUE, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new WebSocketThreadFactory() );
	}


	/**
	 * Starts the server selectorthread that binds to the currently set port number and
	 * listeners for WebSocket connection requests. Creates a fixed thread pool with the size {@link WebSocketServer#DECODERS}<br>
	 * May only be called once.
	 * <p>
	 * Alternatively you can call {@link WebSocketServer#run()} directly.
	 *
	 * @throws IllegalStateException Starting an instance again
	 */
	public void start() {
		if( selectorthread != null )
			throw new IllegalStateException( getClass().getName() + " can only be started once." );
		new Thread( this ).start();
	}

	/**
	 * Closes all connected clients sockets, then closes the underlying
	 * ServerSocketChannel, effectively killing the server socket selectorthread,
	 * freeing the port the server was bound to and stops all internal workerthreads.
	 * <p>
	 * If this method is called before the server is started it will never start.
	 *
	 * @param timeout Specifies how many milliseconds the overall close handshaking may take altogether before the connections are closed without proper close handshaking.<br>
	 * @throws InterruptedException Interrupt
	 */
	public void stop( int timeout ) throws InterruptedException {
		if( !isclosed.compareAndSet( false, true ) ) { // this also makes sure that no further connections will be added to this.connections
			return;
		}

		List<WebSocket> socketsToClose;

		// copy the connections in a list (prevent callback deadlocks)
		synchronized(connections) {
			socketsToClose = new ArrayList<WebSocket>( connections );
		}

		for( WebSocket ws : socketsToClose ) {
			ws.close( CloseFrame.GOING_AWAY );
		}

		wsf.close();

		synchronized(this) {
			if( selectorthread != null && selector != null ) {
				selector.wakeup();
				selectorthread.join( timeout );
			}
		}
	}

	public void stop() throws IOException, InterruptedException {
		stop( 0 );
	}

	/**
	 * PLEASE use the method getConnections() in the future!
	 * <p>
	 * Returns a WebSocket[] of currently connected clients.
	 * Its iterators will be failfast and its not judicious
	 * to modify it.
	 *
	 * @return The currently connected clients.
	 */
	@Deprecated
	public Collection<WebSocket> connections() {
		return getConnections();
	}

	/**
	 * Returns  all currently connected clients.
	 * This collection does not allow any modification e.g. removing a client.
	 *
	 * @return A unmodifiable collection of all currently connected clients
	 * @since 1.3.8
	 */
	public Collection<WebSocket> getConnections() {
		return Collections.unmodifiableCollection( connections );
	}

	public InetSocketAddress getAddress() {
		return this.address;
	}

	/**
	 * Gets the port number that this server listens on.
	 *
	 * @return The port number.
	 */
	public int getPort() {
		int port = getAddress().getPort();
		if( port == 0 && server != null ) {
			port = server.socket().getLocalPort();
		}
		return port;
	}

	public List<Draft> getDraft() {
		return Collections.unmodifiableList( drafts );
	}

	// Runnable IMPLEMENTATION /////////////////////////////////////////////////
	public void run() {
		synchronized(this) {
			if( selectorthread != null )
				throw new IllegalStateException( getClass().getName() + " can only be started once." );
			selectorthread = Thread.currentThread();
			if( isclosed.get() ) {
				return;
			}
		}
		selectorthread.setName( "WebSocketSelector-" + selectorthread.getId() );
		try {
			server = ServerSocketChannel.open();
			server.configureBlocking( false );
			ServerSocket socket = server.socket();
			socket.setReceiveBufferSize( WebSocketImpl.RCVBUF );
			socket.setReuseAddress( isReuseAddr() );
			socket.bind( address );
			selector = Selector.open();
			server.register( selector, server.validOps() );
			startConnectionLostTimer();
			onStart();
		} catch ( IOException ex ) {
			handleFatal( null, ex );
			return;
		}
		try {
			int iShutdownCount = 5;
			int selectTimeout = 0;
			while( !selectorthread.isInterrupted() && iShutdownCount != 0 ) {
				SelectionKey key = null;
				WebSocketImpl conn = null;
				try {
					if( isclosed.get() ) {
						selectTimeout = 5;
					}
					int keyCount = selector.select( selectTimeout );
					if( keyCount == 0 && isclosed.get() ) {
						iShutdownCount--;
					}
					Set<SelectionKey> keys = selector.selectedKeys();
					Iterator<SelectionKey> i = keys.iterator();

					while( i.hasNext() ) {
						key = i.next();
						conn = null;

						if( !key.isValid() ) {
							// Object o = key.attachment();
							continue;
						}

						if( key.isAcceptable() ) {
							if( !onConnect( key ) ) {
								key.cancel();
								continue;
							}

							SocketChannel channel = server.accept();
							if( channel == null ) {
								continue;
							}
							channel.configureBlocking( false );
							Socket socket = channel.socket();
							socket.setTcpNoDelay( isTcpNoDelay() );
							socket.setKeepAlive( true );
							WebSocketImpl w = wsf.createWebSocket( this, drafts );
							w.key = channel.register( selector, SelectionKey.OP_READ, w );
							try {
								w.channel = wsf.wrapChannel( channel, w.key );
								i.remove();
								continue;
							} catch ( IOException ex ) {
								if( w.key != null )
									w.key.cancel();

								handleIOException( w.key, null, ex );
							}
							continue;
						}

						if( key.isReadable() ) {
							conn = ( WebSocketImpl ) key.attachment();
							ByteBuffer buf = takeBuffer();
							if( conn.channel == null ) {
								if( key != null )
									key.cancel();

								handleIOException( key, conn, new IOException() );
								continue;
							}
							try {
								if( SocketChannelIOHelper.read( buf, conn, conn.channel ) ) {
									if( buf.hasRemaining() ) {
										queue( conn, buf );
										i.remove();
										if( conn.channel instanceof WrappedByteChannel ) {
											if( ( ( WrappedByteChannel ) conn.channel ).isNeedRead() ) {
												iqueue.add( conn );
											}
										}
									}
								}
							} catch ( IOException e ) {
								throw e;
							}
						}
						if( key.isWritable() ) {
							conn = ( WebSocketImpl ) key.attachment();
							if( SocketChannelIOHelper.batch( conn, conn.channel ) ) {
								if( key.isValid() )
									key.interestOps( SelectionKey.OP_READ );
							}
						}
					}
					while( !iqueue.isEmpty() ) {
						conn = iqueue.remove( 0 );
						WrappedByteChannel c = ( ( WrappedByteChannel ) conn.channel );
						ByteBuffer buf = takeBuffer();
						try {
							if( SocketChannelIOHelper.readMore( buf, conn, c ) )
								iqueue.add( conn );
							if( buf.hasRemaining() ) {
								queue( conn, buf );
							}
						} catch ( IOException e ) {
							throw e;
						}

					}
				} catch ( CancelledKeyException e ) {
					// an other thread may cancel the key
				} catch ( ClosedByInterruptException e ) {
					return; // do the same stuff as when InterruptedException is thrown
				} catch ( IOException ex ) {
					if( key != null )
						key.cancel();
					handleIOException( key, conn, ex );
				}
			}

		} catch ( RuntimeException e ) {
			// should hopefully never occur
			handleFatal( null, e );
		} finally {
			stopConnectionLostTimer();
			if( threadPoolExecutor != null ) {
				threadPoolExecutor.shutdown();
			}
			if( selector != null ) {
				try {
					selector.close();
				} catch ( IOException e ) {
					onError( null, e );
				}
			}
			if( server != null ) {
				try {
					server.close();
				} catch ( IOException e ) {
					onError( null, e );
				}
			}
		}
	}

	/**
	 * Create a new bytebuffer
	 * @return a bytebuffer
	 */
	private ByteBuffer createBuffer() {
		WeakReference<ByteBuffer> result = new WeakReference<ByteBuffer>( ByteBuffer.allocate( WebSocketImpl.RCVBUF ), buffers );
		return result.get();
	}

	private void queue( WebSocketImpl ws, ByteBuffer buf ) {
		threadPoolExecutor.execute( new WebSocketWorkerRunnable( ws, buf ) );
	}

	/**
	 * Take a buffer from the reference queue or create a new bytebuffer
	 * @return a bytebuffer
	 */
	private ByteBuffer takeBuffer() {
		synchronized(buffers) {
			Reference<? extends ByteBuffer> result = buffers.poll();
			if( result != null ) {
				return result.get();
			}
		}
		return createBuffer();
	}

	private void handleIOException( SelectionKey key, WebSocket conn, IOException ex ) {
		// onWebsocketError( conn, ex );// conn may be null here
		if( conn != null ) {
			conn.closeConnection( CloseFrame.ABNORMAL_CLOSE, ex.getMessage() );
		} else if( key != null ) {
			SelectableChannel channel = key.channel();
			if( channel != null && channel.isOpen() ) { // this could be the case if the IOException ex is a SSLException
				try {
					channel.close();
				} catch ( IOException e ) {
					// there is nothing that must be done here
				}
				if( WebSocketImpl.DEBUG )
					System.out.println( "Connection closed because of " + ex );
			}
		}
	}

	private void handleFatal( WebSocket conn, Exception e ) {
		onError( conn, e );
		if( threadPoolExecutor != null ) {
			threadPoolExecutor.shutdown();
		}
		if( selectorthread != null ) {
			selectorthread.interrupt();
		}
		try {
			stop();
		} catch ( IOException e1 ) {
			onError( null, e1 );
		} catch ( InterruptedException e1 ) {
			Thread.currentThread().interrupt();
			onError( null, e1 );
		}
	}


	@Override
	public final void onWebsocketMessage( WebSocket conn, String message ) {
		onMessage( conn, message );
	}

	@Override
	@Deprecated
	public/*final*/void onWebsocketMessageFragment( WebSocket conn, Framedata frame ) {// onFragment should be overloaded instead
		onFragment( conn, frame );
	}

	@Override
	public final void onWebsocketMessage( WebSocket conn, ByteBuffer blob ) {
		onMessage( conn, blob );
	}

	@Override
	public final void onWebsocketOpen( WebSocket conn, Handshakedata handshake ) {
		if( addConnection( conn ) ) {
			onOpen( conn, ( ClientHandshake ) handshake );
		}
	}

	@Override
	public final void onWebsocketClose( WebSocket conn, int code, String reason, boolean remote ) {
		selector.wakeup();
		if( removeConnection( conn ) ) {
			onClose( conn, code, reason, remote );
		}
	}

	/**
	 * This method performs remove operations on the connection and therefore also gives control over whether the operation shall be synchronized
	 * <p>
	 * {@link #WebSocketServer(InetSocketAddress, int, List, Collection)} allows to specify a collection which will be used to store current connections in.<br>
	 * Depending on the type on the connection, modifications of that collection may have to be synchronized.
	 *
	 * @param ws The Webscoket connection which should be removed
	 * @return Removing connection successful
	 */
	protected boolean removeConnection( WebSocket ws ) {
		boolean removed = false;
		synchronized(connections) {
			if( this.connections.contains( ws ) ) {
				removed = this.connections.remove( ws );
			} else {
				//Don't throw an assert error if the ws is not in the list. e.g. when the other endpoint did not send any handshake. see #512
				if( WebSocketImpl.DEBUG ) {
					System.out.println( "Removing connection which is not in the connections collection! Possible no handshake recieved! " + ws );
				}
			}
		}
		if( isclosed.get() && connections.size() == 0 ) {
			selectorthread.interrupt();
		}
		return removed;
	}

	@Override
	public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer( WebSocket conn, Draft draft, ClientHandshake request ) throws InvalidDataException {
		return super.onWebsocketHandshakeReceivedAsServer( conn, draft, request );
	}

	/**
	 * @param ws the Webscoket connection which should be added
	 * @return Adding connection successful
	 * @see #removeConnection(WebSocket)
	 */
	protected boolean addConnection( WebSocket ws ) {
		if( !isclosed.get() ) {
			synchronized(connections) {
				boolean succ = this.connections.add( ws );
				assert ( succ );
				return succ;
			}
		} else {
			// This case will happen when a new connection gets ready while the server is already stopping.
			ws.close( CloseFrame.GOING_AWAY );
			return true;// for consistency sake we will make sure that both onOpen will be called
		}
	}

	@Override
	public final void onWebsocketError( WebSocket conn, Exception ex ) {
		onError( conn, ex );
	}

	@Override
	public final void onWriteDemand( WebSocket w ) {
		WebSocketImpl conn = ( WebSocketImpl ) w;
		try {
			conn.key.interestOps( SelectionKey.OP_READ | SelectionKey.OP_WRITE );
		} catch ( CancelledKeyException e ) {
			// the thread which cancels key is responsible for possible cleanup
			conn.outQueue.clear();
		}
		selector.wakeup();
	}

	@Override
	public void onWebsocketCloseInitiated( WebSocket conn, int code, String reason ) {
		onCloseInitiated( conn, code, reason );
	}

	@Override
	public void onWebsocketClosing( WebSocket conn, int code, String reason, boolean remote ) {
		onClosing( conn, code, reason, remote );

	}

	public void onCloseInitiated( WebSocket conn, int code, String reason ) {
	}

	public void onClosing( WebSocket conn, int code, String reason, boolean remote ) {

	}

	public final void setWebSocketFactory( WebSocketServerFactory wsf ) {
		this.wsf = wsf;
	}

	public final WebSocketFactory getWebSocketFactory() {
		return wsf;
	}

	/**
	 * Returns whether a new connection shall be accepted or not.<br>
	 * Therefore method is well suited to implement some kind of connection limitation.<br>
	 *
	 * @param key the SelectionKey for the new connection
	 * @return Can this new connection be accepted
	 * @see #onOpen(WebSocket, ClientHandshake)
	 * @see #onWebsocketHandshakeReceivedAsServer(WebSocket, Draft, ClientHandshake)
	 **/
	protected boolean onConnect( SelectionKey key ) {
		//FIXME
		return true;
	}

	/**
	 * Getter to return the socket used by this specific connection
	 *
	 * @param conn The specific connection
	 * @return The socket used by this connection
	 */
	private Socket getSocket( WebSocket conn ) {
		WebSocketImpl impl = ( WebSocketImpl ) conn;
		return ( ( SocketChannel ) impl.key.channel() ).socket();
	}

	@Override
	public InetSocketAddress getLocalSocketAddress( WebSocket conn ) {
		return ( InetSocketAddress ) getSocket( conn ).getLocalSocketAddress();
	}

	@Override
	public InetSocketAddress getRemoteSocketAddress( WebSocket conn ) {
		return ( InetSocketAddress ) getSocket( conn ).getRemoteSocketAddress();
	}

	/**
	 * Called after an opening handshake has been performed and the given websocket is ready to be written on.
	 *
	 * @param conn      The <tt>WebSocket</tt> instance this event is occuring on.
	 * @param handshake The handshake of the websocket instance
	 */
	public abstract void onOpen( WebSocket conn, ClientHandshake handshake );

	/**
	 * Called after the websocket connection has been closed.
	 *
	 * @param conn   The <tt>WebSocket</tt> instance this event is occuring on.
	 * @param code   The codes can be looked up here: {@link CloseFrame}
	 * @param reason Additional information string
	 * @param remote Returns whether or not the closing of the connection was initiated by the remote host.
	 **/
	public abstract void onClose( WebSocket conn, int code, String reason, boolean remote );

	/**
	 * Callback for string messages received from the remote host
	 *
	 * @param conn    The <tt>WebSocket</tt> instance this event is occuring on.
	 * @param message The UTF-8 decoded message that was received.
	 * @see #onMessage(WebSocket, ByteBuffer)
	 **/
	public abstract void onMessage( WebSocket conn, String message );

	/**
	 * Called when errors occurs. If an error causes the websocket connection to fail {@link #onClose(WebSocket, int, String, boolean)} will be called additionally.<br>
	 * This method will be called primarily because of IO or protocol errors.<br>
	 * If the given exception is an RuntimeException that probably means that you encountered a bug.<br>
	 *
	 * @param conn Can be null if there error does not belong to one specific websocket. For example if the servers port could not be bound.
	 * @param ex   The exception causing this error
	 **/
	public abstract void onError( WebSocket conn, Exception ex );

	/**
	 * Called when the server started up successfully.
	 * <p>
	 * If any error occured, onError is called instead.
	 */
	public abstract void onStart();

	/**
	 * Callback for binary messages received from the remote host
	 *
	 * @param conn    The <tt>WebSocket</tt> instance this event is occurring on.
	 * @param message The binary message that was received.
	 * @see #onMessage(WebSocket, ByteBuffer)
	 **/
	public void onMessage( WebSocket conn, ByteBuffer message ) {
	}

	/**
	 * Callback for fragmented frames
	 *
	 * @param conn     The <tt>WebSocket</tt> instance this event is occurring on.
	 * @param fragment The fragmented frame
	 * @see WebSocket#sendFragmentedFrame(org.java_websocket.framing.Framedata.Opcode, ByteBuffer, boolean)
	 */
	@Deprecated
	public void onFragment( WebSocket conn, Framedata fragment ) {
	}

	/**
	 * Send a text to all connected endpoints
	 *
	 * @param text the text to send to the endpoints
	 */
	public void broadcast( String text ) {
		broadcast( text, connections );
	}

	/**
	 * Send a byte array to all connected endpoints
	 *
	 * @param data the data to send to the endpoints
	 */
	public void broadcast( byte[] data ) {
		broadcast( data, connections );
	}

	/**
	 * Send a byte array to a specific collection of websocket connections
	 *
	 * @param data    the data to send to the endpoints
	 * @param clients a collection of endpoints to whom the text has to be send
	 */
	public void broadcast( byte[] data, Collection<WebSocket> clients ) {
		if( data == null || clients == null ) {
			throw new IllegalArgumentException();
		}
		Map<Draft, List<Framedata>> draftFrames = new HashMap<Draft, List<Framedata>>();
		ByteBuffer byteBufferData = ByteBuffer.wrap( data );
		synchronized(clients) {
			for( WebSocket client : clients ) {
				if( client != null ) {
					Draft draft = client.getDraft();
					if( !draftFrames.containsKey( draft ) ) {
						List<Framedata> frames = draft.createFrames( byteBufferData, false );
						draftFrames.put( draft, frames );
					}
					try {
						client.sendFrame( draftFrames.get( draft ) );
					} catch ( WebsocketNotConnectedException e ) {
						//Ignore this exception in this case
					}
				}
			}
		}
	}

	/**
	 * Send a text to a specific collection of websocket connections
	 *
	 * @param text    the text to send to the endpoints
	 * @param clients a collection of endpoints to whom the text has to be send
	 */
	public void broadcast( String text, Collection<WebSocket> clients ) {
		if( text == null || clients == null ) {
			throw new IllegalArgumentException();
		}
		Map<Draft, List<Framedata>> draftFrames = new HashMap<Draft, List<Framedata>>();
		synchronized(clients) {
			for( WebSocket client : clients ) {
				if( client != null ) {
					Draft draft = client.getDraft();
					if( !draftFrames.containsKey( draft ) ) {
						List<Framedata> frames = draft.createFrames( text, false );
						draftFrames.put( draft, frames );
					}
					try {
						client.sendFrame( draftFrames.get( draft ) );
					} catch ( WebsocketNotConnectedException e ) {
						//Ignore this exception in this case
					}
				}
			}
		}
	}

	/**
	 * This class is used to process incoming data
	 */
	class WebSocketWorkerRunnable implements Runnable {

		private final WebSocketImpl ws;
		private final ByteBuffer buf;

		WebSocketWorkerRunnable( WebSocketImpl ws, ByteBuffer buf ) {
			this.ws = ws;
			this.buf = buf;
		}
		@Override
		public void run() {
			try {
				ws.decode( buf );
			} catch ( RuntimeException e ) {
				handleFatal( ws, e );
			} catch ( Exception e ) {
				System.err.println( "Error while reading from remote connection: " + e );
				e.printStackTrace();
			}
		}
	}

	/**
	 * Factory for the Threadpoolexecutor used in the WebSocketServer
	 */
	static class WebSocketThreadFactory implements ThreadFactory {
		private final static AtomicInteger poolNumber = new AtomicInteger( 1 );
		private final ThreadGroup group;
		private final AtomicInteger threadNumber = new AtomicInteger( 1 );
		private final String namePrefix;

		WebSocketThreadFactory() {
			SecurityManager s = System.getSecurityManager();
			group = ( s != null ) ? s.getThreadGroup() :
					Thread.currentThread().getThreadGroup();
			namePrefix = "WebSocketPool-" +
					poolNumber.getAndIncrement() +
					"-thread-";
		}

		public Thread newThread( Runnable r ) {
			Thread t = new Thread( group, r,
					namePrefix + threadNumber.getAndIncrement(),
					0 );
			if( t.isDaemon() )
				t.setDaemon( false );
			if( t.getPriority() != Thread.NORM_PRIORITY )
				t.setPriority( Thread.NORM_PRIORITY );
			return t;
		}
	}
}
