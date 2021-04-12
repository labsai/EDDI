package ai.labs.p2p.impl;

import ai.labs.p2p.IPeer;
import ai.labs.p2p.IPeerMessage;
import ai.labs.p2p.IServer;
import ai.labs.p2p.impl.security.PrivateKeyReader;
import ai.labs.p2p.impl.security.PublicKeyReader;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;


/**
 * @author rpi
 */
@Slf4j
public class Server implements IServer {

    private final String PRIVATE_KEY_FILE = "./priv.key";
    private final String PUBLIC_KEY_FILE = "./pub.key";
    private final String SERVER_ID = "./server.id";
    private final int PORT = 42042;
    private final String DEFAULT_PEER = "eddi-peer.labs.ai";

    private static final int SOCKETTIMEOUT = 10000;

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private UUID serverId;
    private Peer myself = new Peer();

    private ServerSocket serverSocket;
    private String hostname;

    private final ThreadPoolExecutor serverSocketExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    private final ThreadPoolExecutor serverSocketWorker = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
    private final ThreadPoolExecutor findPeersExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);

    private final List<IPeer> connectedPeers = new ArrayList<>();


    @Inject
    public Server(
            @Named("p2pServer.hostname") String hostname,
            @Named("p2pServer.startserver") boolean startServer
    ) {
        this.hostname = hostname;
        if (startServer) {
            init();
        }
    }

    @Override
    public void init() {

        try {
            try {
                privateKey = PrivateKeyReader.get(PRIVATE_KEY_FILE);
            } catch (NoSuchFileException noSuchFileException) {
                // do nothing -> generate public/private key
            }
            try {
                publicKey = PublicKeyReader.get(PUBLIC_KEY_FILE);
            } catch (NoSuchFileException noSuchFileException) {
                // do nothing -> generate public/private key
            }
            try {
                serverId = UUID.fromString(Files.readString(Paths.get(SERVER_ID)));
            } catch (NoSuchFileException noSuchFileException) {
                // do nothing -> generate new
            }
            if (privateKey == null) {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                serverId = UUID.randomUUID();

                Files.write(Paths.get(PRIVATE_KEY_FILE), keyPair.getPrivate().getEncoded());
                Files.write(Paths.get(PUBLIC_KEY_FILE), keyPair.getPublic().getEncoded());
                Files.write(Paths.get(SERVER_ID), serverId.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            log.error("Error on priv/pub key", ex);
        }

        myself.setName(serverId.toString());
        myself.setHostName(hostname);
        myself.setPort(PORT);
        myself.setPublicKey(publicKey.toString());


        serverSocketExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    Socket clientSocket = serverSocket.accept();

                    serverSocketWorker.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {

                                InputStream is = clientSocket.getInputStream();
                                byte[] message = is.readAllBytes();
                                OutputStream os = clientSocket.getOutputStream();
                                PeerMessageHandler peerMessageHandler = new PeerMessageHandler(message);
                                peerMessageHandler.handleMessage(Server.this, os);
                                is.close();
                                os.close();
                                clientSocket.close();
                            } catch (IOException e) {
                                log.error("Error on receive from peer");
                            }
                        }
                    });
                } catch (IOException e) {
                    log.error("Error opening peer to peer port", e);
                }

            }
        });

        findPeersExecutor.execute(new Runnable() {

            @SneakyThrows
            @Override
            public void run()  {
                while (true) {

                    if (connectedPeers.isEmpty()) {
                        Peer defaultPeer = new Peer();
                        defaultPeer.setAuthState(IPeer.PeerAuthState.PUBLIC);
                        defaultPeer.setHostName(DEFAULT_PEER);
                        connectedPeers.add(defaultPeer);
                    }

                    for (IPeer peer : connectedPeers) {
                        connectToPeer(peer);
                    }

                    Thread.currentThread().wait(10000);
                }
            }
        });

    }

    @Override
    public List<IPeer> getAvailablePeers() {
        return connectedPeers;
    }

    @Override
    public void connectToPeer(IPeer peer) {
        try {
            Socket socket = SocketFactory.getDefault().createSocket(peer.getHostName(), peer.getPort());
            OutputStream os = socket.getOutputStream();
            PeerMessage message = new PeerMessage();
            message.setPeerMessageType(IPeerMessage.PeerMessageType.REGISTER);
            message.setPeer(myself);
            os.write(message.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            InputStream is = socket.getInputStream();
            byte[] byteResponse = is.readAllBytes();
            PeerMessageHandler peerMessageHandler = new PeerMessageHandler(byteResponse);
            peerMessageHandler.handleMessage(this, socket.getOutputStream());
        } catch (IOException e) {
            log.error("Connect to peer {} failed!", peer.getHostName(), e);
        }

    }

    @Override
    public IPeer getMyself() {
        return myself;
    }

}
