package ai.labs.p2p.impl;

import ai.labs.p2p.IClient;
import ai.labs.p2p.IPeer;
import ai.labs.p2p.IPeerMessage;
import ai.labs.p2p.impl.security.EncryptDecrypt;
import ai.labs.p2p.impl.security.PrivateKeyReader;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;


/**
 * @author rpi
 */
@Setter
@Slf4j
public class Client implements IClient {


    @Override
    public String sendMessage(IPeerMessage request, IPeer peer, PrivateKey privateKey) {

        String serRequest = request.toString();
        try {
            byte[] encRequest = EncryptDecrypt.encrypt(serRequest, peer.getPublicKey());
            Socket socket = SocketFactory.getDefault().createSocket(peer.getHostName(), peer.getPort());
            OutputStream os = socket.getOutputStream();
            os.write(encRequest);
            os.flush();
            InputStream is = socket.getInputStream();
            byte[] cypherResponse = is.readAllBytes();
            String strCypherResponse = new String(cypherResponse, StandardCharsets.UTF_8);
            String response = EncryptDecrypt.decrypt(strCypherResponse, privateKey);
            log.info("response of peer {} is {}", peer.getHostName(), response);
            os.close();
            is.close();
            socket.close();
            return  response;

        } catch (BadPaddingException | IllegalBlockSizeException | InvalidKeyException | NoSuchPaddingException | NoSuchAlgorithmException | IOException e) {
            log.error("encryption failed or send", e);
        }


        return null;
    }
}
