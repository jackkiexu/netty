/*
 * Copyright (C) 2008  Trustin Heuiseung Lee
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, 5th Floor, Boston, MA 02110-1301 USA
 */
package net.gleamynode.netty.example.echo;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import net.gleamynode.netty.bootstrap.ClientBootstrap;
import net.gleamynode.netty.channel.ChannelFactory;
import net.gleamynode.netty.channel.socket.nio.NioClientSocketChannelFactory;

public class EchoClient {

    public static void main(String[] args) throws Exception {
        // Print usage if no argument is specified.


        // Parse options.
        String host = "127.0.0.1";
        int port = 1297;
        int firstMessageSize = 256;

        // Start client.
        ChannelFactory factory =
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool());

        ClientBootstrap bootstrap = new ClientBootstrap(factory);
        EchoHandler handler = new EchoHandler(firstMessageSize);

        bootstrap.getPipeline().addLast("handler", handler);
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("keepAlive", true);

        bootstrap.connect(new InetSocketAddress(host, port));

        // Start performance monitor.
        new ThroughputMonitor(handler).start();
    }
}
