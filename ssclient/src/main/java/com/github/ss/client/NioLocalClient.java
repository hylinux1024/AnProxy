package com.github.ss.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import com.github.ss.cipher.CryptFactory;
import com.github.ss.cipher.ICrypt;
import com.github.ss.utils.Config;
import com.github.ss.utils.Constant;
import com.github.ss.utils.PackageHeader;
import com.github.ss.utils.Socks5Utils;
import com.github.ss.utils.Utils;


public class NioLocalClient {

    private static final Logger logger = Logger.getLogger("NioLocalClient");

    private boolean stop = false;
    private ServerSocketChannel serverSocketChannel;

    public void startClient(Config config) {
        try {
            List<ClientChannelHandler> handlerList = new LinkedList<>();
            Selector selector = Selector.open();
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.socket().bind(
                    new InetSocketAddress(config.getClientListenIp(), config.getClientListenPort()));
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            logger.info("start nio socks5 client proxy.");
            while (!stop) {
                int count = selector.select();
                if (count <= 0) {
                    continue;
                }
                Iterator<SelectionKey> keyIt = selector.selectedKeys().iterator();
                while (keyIt.hasNext()) {
                    SelectionKey key = keyIt.next();
                    keyIt.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        ServerSocketChannel serverSocket = (ServerSocketChannel) key
                                .channel();
                        SocketChannel socketChannel = serverSocket.accept();
                        handlerList.add(new ClientChannelHandler(selector,
                                socketChannel, config));
                    } else {
                        logger.info("before handler number:" + handlerList.size());
						/*
						Iterator<ClientChannelHandler> it = handlerList.iterator();
						while(it.hasNext()){
							ClientChannelHandler handler = it.next();
							handler.handle(key);
							if(handler.isDestroy()){
								handler.destory();
								it.remove();
							}
						}
						*/
                        List<ClientChannelHandler> toDestoryList = new LinkedList<ClientChannelHandler>();
                        for (ClientChannelHandler handler : handlerList) {
                            handler.handle(key);
                            if (handler.isDestroy()) {
                                toDestoryList.add(handler);
                            }
                        }
                        for (ClientChannelHandler handler : toDestoryList) {
                            handler.destroy();
                            handlerList.remove(handler);
                        }

                        logger.info("after handler number:" + handlerList.size());
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopLocalClient() {
        stop = true;
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

class ClientChannelHandler {
    private static final Logger logger = Logger.getLogger("ClientChannelHandler");

    private static final int STAGE_INIT = 0;
    private static final int STAGE_SOCKS_HELLO_OK = 1;
    private static final int STAGE_TARGET_SENT_TO_REMOTE = 2;
    private static final int STAGE_REMOTE_CONNECTED = 3;

    private Selector selector;
    private SocketChannel localSocketChannel;
    private SocketChannel remoteSocketChannel;
    private int stage;
    private boolean destroy = false;
    private ICrypt crypt;
    private List<byte[]> byteQueue = new LinkedList<>();
    private long lastExecuteTime = -1;
    private PackageHeader packageHeader = null;
    private Config config = null;
    private ByteArrayOutputStream stream = new ByteArrayOutputStream();

    public ClientChannelHandler(Selector selector,
                                SocketChannel localSocketChannel, Config config) {
        this.localSocketChannel = localSocketChannel;
        try {
            this.selector = selector;
            this.localSocketChannel.configureBlocking(false);
            this.localSocketChannel.register(selector, SelectionKey.OP_READ);
            this.config = config;
            this.crypt = CryptFactory.get(config.getEncryptMethod(), config.getEncryptPassword());
            stage = STAGE_INIT;
        } catch (IOException e) {
            logger.severe(e.getMessage());
        }
    }


    /**
     * Read data from socketChannel
     * <p>
     * if data from local, return it directly;
     * if data from remote, decrypt it and return it;
     *
     * @param socketChannel
     * @return
     * @throws IOException
     */
    private byte[] readData(SocketChannel socketChannel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(this.config.getTransferBufferSize());
        int readBytes = socketChannel.read(buf);
        if (readBytes <= 0) {
            return null;
        }
        buf.flip();
        byte[] data = new byte[readBytes];
        buf.get(data);
        if (this.localSocketChannel == socketChannel) {
            // data from local,return it;
            return data;
        } else {
            // data from remote, decrypt and return
            this.stream.reset();
            this.crypt.decrypt(data, this.stream);
            return stream.toByteArray();
        }
    }

    private void read(SelectionKey key, SocketChannel socketChannel)
            throws IOException {
        byte[] data = readData(socketChannel);
        if (data == null) {
            this.destroy = true;
            return;
        }
        if (stage == STAGE_INIT && socketChannel == this.localSocketChannel) {
            byte socksVer = data[0];
            if (socksVer != 5) {
                // socks5 version is wrong.
                socketChannel.write(ByteBuffer.wrap(Socks5Utils.versionError()));
                this.destroy = true;
            } else {
                // socks5 version is ok.
                socketChannel.write(ByteBuffer.wrap(Socks5Utils.versionOk()));
                stage = STAGE_SOCKS_HELLO_OK;
            }
        } else if (stage == STAGE_SOCKS_HELLO_OK && socketChannel == this.localSocketChannel) {
            data = Arrays.copyOfRange(data, 3, data.length);
            // connect remote proxy server.
            try {
                remoteSocketChannel = SocketChannel.open();
                remoteSocketChannel.configureBlocking(false);
                remoteSocketChannel.register(this.selector, SelectionKey.OP_CONNECT);
                remoteSocketChannel.connect(new InetSocketAddress(this.config.getProxyServerIp(), this.config.getProxyServerPort()));
            } catch (Exception e) {
                logger.severe("fail to connnect remote proxy server.");
                // tell socks5 client, fail to connect.
                socketChannel.write(ByteBuffer.wrap(Socks5Utils.connectServerError()));
                this.destroy = true;
                return;
            }
            // tell socks5 client, success to connect.
            socketChannel.write(ByteBuffer.wrap(Socks5Utils.connectServerSuccess()));

            this.packageHeader = Utils.parserHeader(data);
            if (this.packageHeader != null) {
                logger.info("connect " + packageHeader.getAddress() + ":" + Utils.byteToPort(packageHeader.getPort()));
            }

            // add the received data into queue.
            byteQueue.add(data);
            stage = STAGE_TARGET_SENT_TO_REMOTE;
        } else if (stage == STAGE_TARGET_SENT_TO_REMOTE && socketChannel == this.localSocketChannel) {
            // add the received data into queue
            // when this client is starting to connect proxy server but not connected.
            byteQueue.add(data);
        } else if (stage == STAGE_REMOTE_CONNECTED) {
            if (socketChannel == this.localSocketChannel) {
                this.stream.reset();
                this.crypt.encrypt(data, this.stream);
                Utils.writeToSocketChannel(this.stream.toByteArray(), this.remoteSocketChannel);
            } else {
                Utils.writeToSocketChannel(data, this.localSocketChannel);
            }
        }

    }

    private void connect(SelectionKey key, SocketChannel socketChannel)
            throws IOException {
        if (this.remoteSocketChannel == socketChannel) {
            if (socketChannel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ);
                this.stage = STAGE_REMOTE_CONNECTED;
                // this client once connected proxy server and send the received data to proxy server.
                if (byteQueue.size() > 0) {
                    for (byte[] tmpData : byteQueue) {
                        this.stream.reset();
                        this.crypt.encrypt(tmpData, stream);
                        Utils.writeToSocketChannel(stream.toByteArray(), this.remoteSocketChannel);
                    }
                }
            }
        }
    }

    private void write(SelectionKey key, SocketChannel socketChannel)
            throws IOException {
    }

    public void handle(SelectionKey key) {
        if (lastExecuteTime != -1 && (System.currentTimeMillis() - lastExecuteTime) >= Constant.TIME_OUT) {
            logger.info("timeout to connect " + (this.packageHeader == null ? "" : this.packageHeader.getAddress()));
            this.destroy = true;
            return;
        }
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel == this.localSocketChannel
                || channel == this.remoteSocketChannel) {
            try {
                if (key.isReadable()) {
                    read(key, channel);
                }
                if (key.isWritable()) {
                    write(key, channel);
                }
                if (key.isConnectable()) {
                    connect(key, channel);
                }
                lastExecuteTime = System.currentTimeMillis();
            } catch (Exception e) {
                this.destroy = true;
            }
        }
    }


    public boolean isDestroy() {
        return destroy;
    }

    public void destroy() {
        if (this.packageHeader != null) {
            logger.info("disconnect " + packageHeader.getAddress() + ":" + Utils.byteToPort(packageHeader.getPort()));
        }

        if (this.localSocketChannel != null) {
            try {
                this.localSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (this.remoteSocketChannel != null) {
            try {
                this.remoteSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

}

