///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.7.3
//DEPS io.netty:netty-all:4.1.100.Final
/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2023 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;


/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
@Command(name = "echo-server", description = "A server which echos requests to the console.", showDefaultValues = true, subcommands = AutoComplete.GenerateCompletion.class)
public class EchoServer implements Callable<Integer> {
    @Option(names = {"-H", "--host"}, defaultValue = "127.0.0.1", description = "The host to listen on.")
    private String host;

    @SuppressWarnings("unused")
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message.")
    private boolean usageHelpRequested;

    @Option(names = {"-p", "--port"}, description = "The port to listen on.", defaultValue = "8080")
    private int port;

    @SuppressWarnings("unused")
    private static class Transport {
        @Option(names = "--http", description = "Starts a HTTP server.")
        private boolean http;
        @Option(names = "--tcp", description = "Starts a TCP server.")
        private boolean tcp;
        @Option(names = "--udp", description = "Starts a UDP server.")
        private boolean udp;

    }

    @ArgGroup
    private Transport transport;

    @Option(names = {"-v", "--verbose"}, description = "Prints verbose output.")
    private boolean verbose;

    @Spec
    private CommandSpec spec;

    private final PrintStream stdout;

    private final PrintStream stderr;
    private Ansi ansi;

    private EchoServer(final PrintStream stdout, final PrintStream stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public static void main(final String... args) {
        final CommandLine commandLine = new CommandLine(new EchoServer(System.out, System.err));
        commandLine.setOut(new PrintWriter(System.out));
        final int exitStatus = commandLine.execute(args);
        System.exit(exitStatus);
    }

    @Override
    public Integer call() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(stdout::println));
        try (Server server = createServer()) {
            server.start().whenComplete((s, error) -> {
                if (error != null) {
                    error.printStackTrace(stderr);
                    System.exit(1);
                }
                print("@|bold Listening on %s:%d|@%n", s.host(), s.port());
            });
            Thread.currentThread().join();
        }
        return 0;
    }

    private void print() {
        stdout.println();
        stdout.flush();
    }

    private void print(final String fmt, final Object... args) {
        print(stdout, fmt, args);
    }

    private void printError(final String fmt, final Object... args) {
        print(stderr, "@|red " + fmt + "|@", args);
    }

    private void print(final PrintStream os, final String fmt, final Object... args) {
        os.println(format(fmt, args));
        os.flush();
    }

    private String format(final String fmt, final Object... args) {
        if (ansi == null) {
            ansi = spec.commandLine().getColorScheme().ansi();
        }
        return ansiFormat(ansi, String.format(fmt, args));
    }

    private String ansiFormat(final Ansi ansi, final String value) {
        return ansi.string(value);
    }

    private Server createServer() {
        if (transport != null && transport.tcp) {
            return new TcpServer();
        }
        if (transport != null && transport.udp) {
            return new UdpServer();
        }
        return new HttpServer();
    }

    private abstract class Server implements Runnable, AutoCloseable {
        final EventLoopGroup mainGroup = new NioEventLoopGroup(1);
        final EventLoopGroup workerGroup = new NioEventLoopGroup();
        final Lock writeLock = new ReentrantLock();

        volatile ChannelFuture channelFuture;


        private final AtomicBoolean running = new AtomicBoolean(false);
        protected final CompletableFuture<Server> startFuture;

        private Server() {
            startFuture = new CompletableFuture<>();
        }


        void stop() {
            running.set(false);
            final var channelFuture = this.channelFuture;
            channelFuture.channel().closeFuture().syncUninterruptibly();
        }

        CompletionStage<Server> start() {
            if (running.compareAndSet(false, true)) {
                ForkJoinPool.commonPool().submit(this);
            }
            return startFuture;
        }

        boolean isRunning() {
            return running.get();
        }

        String host() {
            return host;
        }

        int port() {
            return port;
        }

        @Override
        public void close() throws Exception {
            if (!startFuture.isDone()) {
                startFuture.cancel(true);
            }
            stop();
        }
    }

    private class HttpServer extends Server {

        @Override
        public void run() {

            // Create the child handler
            final var childHandler = new ChannelInitializer<SocketChannel>() {

                @Override
                protected void initChannel(final SocketChannel ch) {
                    final var pipeline = ch.pipeline();
                    pipeline.addLast(new HttpRequestDecoder());
                    pipeline.addLast(new HttpResponseEncoder());
                    pipeline.addLast(new HttpObjectAggregator(1024 * 1024 * 10));

                    // Echo back
                    pipeline.addLast(new SimpleChannelInboundHandler<>() {
                        private final ByteArrayOutputStream data = new ByteArrayOutputStream();

                        @Override
                        protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) {
                            if (msg instanceof HttpRequest) {
                                if (HttpUtil.is100ContinueExpected((HttpRequest) msg)) {
                                    final var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);
                                    ctx.write(response);
                                }
                            }
                            if (msg instanceof HttpContent) {
                                final var content = (HttpContent) msg;
                                final var buffer = content.content();
                                try {
                                    writeLock.lock();
                                    try {
                                        buffer.readBytes(data, buffer.readableBytes());
                                    } finally {
                                        writeLock.unlock();
                                    }

                                    if (msg instanceof LastHttpContent) {
                                        final String responseBody;
                                        writeLock.lock();
                                        try {
                                            responseBody = data.toString(StandardCharsets.UTF_8);
                                            data.reset();
                                        } finally {
                                            writeLock.unlock();
                                        }
                                        final LastHttpContent trailer = (LastHttpContent) msg;
                                        final HttpVersion version;
                                        final HttpHeaders headers;
                                        if (msg instanceof HttpRequest) {
                                            version = ((HttpRequest) msg).protocolVersion();
                                            headers = ((HttpRequest) msg).headers();
                                        } else {
                                            version = HttpVersion.HTTP_1_1;
                                            headers = new DefaultHttpHeaders();
                                        }
                                        if (headers != null) {
                                            headers.forEach(entry -> print("%s: %s", entry.getKey(), entry.getValue()));
                                        }
                                        trailer.trailingHeaders()
                                                .forEach(entry -> print("%s: %s", entry.getKey(), entry.getValue()));
                                        // Write a response back
                                        print(responseBody);
                                        final var response = new DefaultFullHttpResponse(version, HttpResponseStatus.OK, Unpooled.copiedBuffer(responseBody + "\r\n", StandardCharsets.UTF_8));
                                        ctx.writeAndFlush(response)
                                                // Not efficient, but this is for testing only
                                                .addListener(ChannelFutureListener.CLOSE);
                                    }
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }
                        }

                        @Override
                        public void exceptionCaught(final ChannelHandlerContext ctx, Throwable cause) {
                            // Close the connection when an exception is raised.
                            cause.printStackTrace(stderr);
                            ctx.close();
                        }
                    });
                }
            };
            final var bootstrap = new ServerBootstrap();
            bootstrap.group(mainGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .childHandler(childHandler);

            channelFuture = bootstrap.bind(host, port)
                    .addListener(future -> startFuture.complete(HttpServer.this));
        }
    }

    private class TcpServer extends Server {

        @Override
        public void run() {

            // Create the child handler
            final var childHandler = new ChannelInitializer<SocketChannel>() {

                @Override
                protected void initChannel(final SocketChannel ch) {
                    final var pipeline = ch.pipeline();
                    // Echo back
                    pipeline.addLast(new ChannelInboundHandlerAdapter() {

                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, Object msg) {
                            if (msg instanceof ByteBuf) {
                                stdout.print(((ByteBuf) msg).toString(StandardCharsets.UTF_8));
                            }
                            ctx.write(msg);
                        }

                        @Override
                        public void channelReadComplete(final ChannelHandlerContext ctx) {
                            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
                        }

                        @Override
                        public void exceptionCaught(final ChannelHandlerContext ctx, Throwable cause) {
                            // Close the connection when an exception is raised.
                            cause.printStackTrace(stderr);
                            ctx.close();
                        }
                    });
                }
            };
            final var bootstrap = new ServerBootstrap();
            bootstrap.group(mainGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .childHandler(childHandler);

            channelFuture = bootstrap.bind(host, port)
                    .addListener(future -> startFuture.complete(TcpServer.this));
        }
    }

    private class UdpServer extends Server {

        @Override
        public void run() {

            // Create the child handler
            final var handler = new ChannelInitializer<DatagramChannel>() {

                @Override
                protected void initChannel(final DatagramChannel ch) {
                    final var pipeline = ch.pipeline();
                    // Echo back
                    pipeline.addLast(new SimpleChannelInboundHandler<DatagramPacket>() {

                        @Override
                        protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket msg) throws Exception {
                            msg.content().readBytes(stdout, msg.content().readableBytes());
                            ctx.write(msg);
                        }

                        @Override
                        public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
                            ctx.flush();
                            super.channelReadComplete(ctx);
                        }
                    });
                }
            };

            final var bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(handler);
            channelFuture = bootstrap.bind(host, port)
                    .addListener(future -> startFuture.complete(UdpServer.this));
        }
    }
}
