/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal SMTP submission server that speaks STARTTLS — the one thing the
 * GreenMail SMTP server does not. It records every command, whether a command
 * arrived before or after the TLS upgrade, and the message data, so a test can
 * assert that no credential ever crossed the wire unencrypted.
 * <p>
 * One connection per instance; good enough for one send per test.
 */
final class StartTlsSmtpServer implements AutoCloseable {

    /** A command as the server saw it. */
    record Command(String line, boolean overTls) {
    }

    private final ServerSocket server;
    private final SSLContext tls;
    private final String rcptReply;
    private final Thread thread;
    final List<Command> commands = new CopyOnWriteArrayList<>();
    final StringBuilder data = new StringBuilder();

    /**
     * @param rcptReply
     *            the reply to {@code RCPT TO}, e.g. {@code 250 ok} or
     *            {@code 550 5.1.1 no such user}
     */
    StartTlsSmtpServer(SSLContext tls, String rcptReply) throws IOException {
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.server.setSoTimeout(10_000);
        this.tls = tls;
        this.rcptReply = rcptReply;
        this.thread = new Thread(this::serve, "starttls-smtp-test-server");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        try (Socket plain = server.accept()) {
            plain.setSoTimeout(10_000);
            Socket socket = plain;
            boolean overTls = false;
            BufferedReader in = reader(socket);
            OutputStream out = socket.getOutputStream();
            write(out, "220 test.local ESMTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                commands.add(new Command(line, overTls));
                String verb = line.split(" ", 2)[0].toUpperCase(Locale.ROOT);
                switch (verb) {
                    case "EHLO" ->
                        write(out,
                                overTls
                                        ? "250-test.local\r\n250 AUTH PLAIN LOGIN XOAUTH2"
                                        : "250-test.local\r\n250-STARTTLS\r\n250 AUTH PLAIN LOGIN XOAUTH2");
                    case "STARTTLS" -> {
                        write(out, "220 2.0.0 go ahead");
                        SSLSocket upgraded = (SSLSocket) tls.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true);
                        upgraded.setUseClientMode(false);
                        upgraded.startHandshake();
                        socket = upgraded;
                        overTls = true;
                        in = reader(socket);
                        out = socket.getOutputStream();
                    }
                    case "AUTH" -> write(out, "235 2.7.0 accepted");
                    case "MAIL" -> write(out, "250 2.1.0 ok");
                    case "RCPT" -> write(out, rcptReply);
                    case "DATA" -> {
                        write(out, "354 go");
                        String body;
                        while ((body = in.readLine()) != null && !".".equals(body)) {
                            data.append(body).append('\n');
                        }
                        write(out, "250 2.0.0 Ok: queued as TEST123");
                    }
                    case "QUIT" -> {
                        write(out, "221 bye");
                        return;
                    }
                    case "RSET", "NOOP" -> write(out, "250 ok");
                    default -> write(out, "502 unknown");
                }
            }
        } catch (SocketTimeoutException e) {
            // nobody connected
        } catch (IOException e) {
            // the client hung up
        }
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
    }

    private static void write(OutputStream out, String reply) throws IOException {
        out.write((reply + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() throws Exception {
        server.close();
        thread.join(5000);
    }
}
