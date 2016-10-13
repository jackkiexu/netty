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
package net.gleamynode.netty.channel.socket.nio;

import static net.gleamynode.netty.channel.Channels.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import net.gleamynode.netty.channel.AbstractChannelSink;
import net.gleamynode.netty.channel.Channel;
import net.gleamynode.netty.channel.ChannelEvent;
import net.gleamynode.netty.channel.ChannelFuture;
import net.gleamynode.netty.channel.ChannelPipeline;
import net.gleamynode.netty.channel.ChannelState;
import net.gleamynode.netty.channel.ChannelStateEvent;
import net.gleamynode.netty.channel.MessageEvent;
import net.gleamynode.netty.util.NamePreservingRunnable;
import org.apache.log4j.Logger;

class NioServerSocketPipelineSink extends AbstractChannelSink {

    static final Logger logger = Logger.getLogger(NioServerSocketPipelineSink.class);
    private static final AtomicInteger nextId = new AtomicInteger();

    private final int id = nextId.incrementAndGet();
    private final NioWorker[] workers;
    private final AtomicInteger workerIndex = new AtomicInteger();

    NioServerSocketPipelineSink(Executor workerExecutor, int workerCount) {
        workers = new NioWorker[workerCount];
        for (int i = 0; i < workers.length; i ++) {
            workers[i] = new NioWorker(id, i + 1, workerExecutor);
        }
    }

    public void eventSunk(ChannelPipeline pipeline, ChannelEvent e) throws Exception {
        logger.info("eventSunk :" + pipeline +", e:"+ e);
        Channel channel = e.getChannel();
        if (channel instanceof NioServerSocketChannel) {
            handleServerSocket(e);
        } else if (channel instanceof NioSocketChannel) {
            handleAcceptedSocket(e);
        }
    }

    private void handleServerSocket(ChannelEvent e) {
        logger.info("handleServerSocket:"+e);
        if (!(e instanceof ChannelStateEvent)) {
            return;
        }

        ChannelStateEvent event = (ChannelStateEvent) e;
        NioServerSocketChannel channel = (NioServerSocketChannel) event.getChannel();
        ChannelFuture future = event.getFuture();
        ChannelState state = event.getState();
        Object value = event.getValue();

        logger.info("channel:"+channel +", future:"+future + ", state: " + state +", value:"+value);
        switch (state) {
        case OPEN:
            if (Boolean.FALSE.equals(value)) {
                close(channel, future);
            }
            break;
        case BOUND:
            if (value != null) {
                bind(channel, future, (SocketAddress) value);
            } else {
                close(channel, future);
            }
            break;
        }
    }

    private void handleAcceptedSocket(ChannelEvent e) {
        logger.info("handleAcceptedSocket e:" + e);
        if (e instanceof ChannelStateEvent) {
            ChannelStateEvent event = (ChannelStateEvent) e;
            NioSocketChannel channel = (NioSocketChannel) event.getChannel();
            ChannelFuture future = event.getFuture();
            ChannelState state = event.getState();
            Object value = event.getValue();

            logger.info("event:"+event +", channel:"+channel + ", future:"+future +", state:"+state + ", value:"+value);
            switch (state) {
            case OPEN:
                if (Boolean.FALSE.equals(value)) {
                    NioWorker.close(channel, future);
                }
                break;
            case BOUND:
            case CONNECTED:
                if (value == null) {
                    NioWorker.close(channel, future);
                }
                break;
            case INTEREST_OPS:
                NioWorker.setInterestOps(channel, future, ((Integer) value).intValue());
                break;
            }
        } else if (e instanceof MessageEvent) {
            MessageEvent event = (MessageEvent) e;
            NioSocketChannel channel = (NioSocketChannel) event.getChannel();
            logger.info("MessageEvent :" + e +", channel :" + channel);
            channel.writeBuffer.offer(event);
            NioWorker.write(channel);
        }
    }

    private void bind(NioServerSocketChannel channel, ChannelFuture future, SocketAddress localAddress) {
        logger.info("bind channel:"+channel + ", future:"+future +  ", localAddress:" + localAddress);
        boolean bound = false;
        boolean bossStarted = false;
        try {
            channel.socket.socket().bind(localAddress, channel.getConfig().getBacklog());
            bound = true;

            future.setSuccess();
            logger.info("begin fireChannelBound " + channel + ", localAddress : " + localAddress);
            fireChannelBound(channel, channel.getLocalAddress());
            logger.info("over fireChannelBound " + channel + ", localAddress : " + localAddress);

            Executor bossExecutor =
                ((NioServerSocketChannelFactory) channel.getFactory()).bossExecutor;
            logger.info("begin Boss run channel :" + channel);
            bossExecutor.execute(new NamePreservingRunnable(
                    new Boss(channel),
                    "New I/O server boss #" + id + " (channelId: " + channel.getId() +
                            ", " + channel.getLocalAddress() + ')'));
            logger.info("over Boss run channel :" + channel);
            bossStarted = true;
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        } finally {
            if (!bossStarted && bound) {
                close(channel, future);
            }
        }
    }

    private void close(NioServerSocketChannel channel, ChannelFuture future) {
        boolean bound = channel.isBound();
        try {
            channel.socket.close();
            future.setSuccess();
            if (channel.setClosed()) {
                if (bound) {
                    fireChannelUnbound(channel);
                }
                fireChannelClosed(channel);
            }
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        }
    }

    NioWorker nextWorker() {
        return workers[Math.abs(
                workerIndex.getAndIncrement() % workers.length)];
    }

    private class Boss implements Runnable {
        private final NioServerSocketChannel channel;

        Boss(NioServerSocketChannel channel) {
            this.channel = channel;
        }

        public void run() {
            logger.info("Boss run channel:"+channel);
            for (;;) {
                try {

                    // gather client request
                    // the accept() method blocks until an incoming connection arrives
                    logger.info("channel:"+ channel + " doing accept()");
                    SocketChannel acceptedSocket = channel.socket.accept();
                    logger.info("acceptedSocket : "+ acceptedSocket);

                    try {

                        ChannelPipeline pipeline = channel.getConfig().getPipelineFactory().getPipeline();
                        logger.info("channel:"+ channel + " doing accept() result : " + acceptedSocket + ", pipeline:" + pipeline);
                        NioWorker worker = nextWorker();
                        logger.info("worker:"+worker);
                        worker.register(new NioAcceptedSocketChannel(
                                        channel.getFactory(), pipeline, channel,
                                        NioServerSocketPipelineSink.this,
                                        acceptedSocket, worker), null);
                    } catch (Exception e) {
                        logger.warn(
                                "Failed to initialize an accepted socket.", e);
                        try {
                            acceptedSocket.close();
                        } catch (IOException e2) {
                            logger.warn(
                                    "Failed to close a partially accepted socket.",
                                    e2);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Thrown every second to get ClosedChannelException
                    // raised.
                } catch (ClosedChannelException e) {
                    // Closed as requested.
                    break;
                } catch (IOException e) {
                    logger.warn(
                            "Failed to accept a connection.", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        // Ignore
                    }
                }
            }
        }
    }
}
