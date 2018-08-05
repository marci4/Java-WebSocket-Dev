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

package org.java_websocket.issues;

import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.util.SocketUtil;
import org.java_websocket.util.ThreadCheck;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.fail;

public class Issue745Test {

    @Rule
    public ThreadCheck zombies = new ThreadCheck();

    private CountDownLatch countServerDownLatch = new CountDownLatch(1);

    @Test
    public void testIssue() throws Exception {
        int port = SocketUtil.getAvailablePort();
        final WebSocketClient webSocket = new WebSocketClient(new URI("ws://echo.websocket.org")) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                System.out.println("open");
            }

            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("closed");
            }

            @Override
            public void onError(Exception ex) {
            }
        };
        webSocket.connectBlocking();
        for (int i = 0; i < 100; i++) {
            System.out.println(i);
            webSocket.reconnect();
        }
    }
}
