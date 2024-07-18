package org.java_websocket.issues;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.SSLContext;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.util.SSLContextUtil;
import org.junit.Test;

public class Issue1142Test {



  @Test(timeout = 4000)
  public void testWithoutSSLSession()
      throws IOException, URISyntaxException, InterruptedException {
    final CountDownLatch serverStarted = new CountDownLatch(1);

    WebSocketServer server = new MyWebSocketServer(0, serverStarted);
    server.start();
    serverStarted.await();
    final WebSocketClient webSocket = new WebSocketClient(new URI("ws://localhost:" + server.getPort())) {
      @Override
      public void onOpen(ServerHandshake handshakedata) {}

      @Override
      public void onMessage(String message) {
      }

      @Override
      public void onClose(int code, String reason, boolean remote) {
      }

      @Override
      public void onError(Exception ex) {
      }
    };
    webSocket.connectBlocking();
    assertFalse(webSocket.hasSSLSupport());
    try {
      webSocket.getSSLSession();
      assertFalse(false);
    } catch (IllegalArgumentException e) {
      // Fine
    }
    server.stop();
  }

  @Test(timeout = 4000)
  public void testWithSSLSession()
      throws IOException, URISyntaxException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException, UnrecoverableKeyException, CertificateException, InterruptedException {
    final CountDownLatch countServerDownLatch = new CountDownLatch(1);
    WebSocketServer server = new MyWebSocketServer(0, countServerDownLatch);
    SSLContext sslContext = SSLContextUtil.getContext();

    server.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(sslContext));

    server.start();
    countServerDownLatch.await();

    final WebSocketClient webSocket = new WebSocketClient(new URI("wss://localhost:" + server.getPort())) {
      @Override
      public void onOpen(ServerHandshake handshakedata) {
      }

      @Override
      public void onMessage(String message) {
      }

      @Override
      public void onClose(int code, String reason, boolean remote) {
      }

      @Override
      public void onError(Exception ex) {
      }
    };
    webSocket.setSocketFactory(sslContext.getSocketFactory());
    webSocket.connectBlocking();
    assertTrue(webSocket.hasSSLSupport());
    assertNotNull(webSocket.getSSLSession());
    server.stop();
  }

  private static class MyWebSocketServer extends WebSocketServer {

    private final CountDownLatch countServerLatch;

    public MyWebSocketServer(int port, CountDownLatch serverDownLatch) {
      super(new InetSocketAddress(port));
      this.countServerLatch = serverDownLatch;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    }

    @Override
    public void onMessage(WebSocket conn, String message) {

    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
      ex.printStackTrace();
    }

    @Override
    public void onStart() {
      countServerLatch.countDown();
    }
  }
}
