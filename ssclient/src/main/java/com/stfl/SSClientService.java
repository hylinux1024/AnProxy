package com.stfl;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.text.TextUtils;

import com.stfl.network.NioLocalServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;


public class SSClientService extends Service {

    private static final Logger logger = Logger.getLogger("SSClientService");

    private static final String TAG = SSClientService.class.getSimpleName();

    private String localAddress = "127.0.0.1";
    private int localPort = 9898;

    public static final String METHOD = "method";
    public static final String PASSWORD = "password";
    public static final String SERVER = "server";
    public static final String SERVER_PORT = "server_port";

    private String method = "aes-256-cfb";
    private String password = "u1rRWTssNv0p";
    private String server = "198.199.101.152";
    private int serverPort = 8388;

    private boolean started = false;

    private ExecutorService pool = Executors.newFixedThreadPool(1);

    public SSClientService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getDataFromIntent(intent);
        runOnBackend();
        return START_STICKY;
    }


    protected void runOnBackend() {
        if (started) {
            pool.shutdown();
            logger.info("SSClient is reset " + server);
            return;
        }
        try {
            com.stfl.misc.Config conf = new com.stfl.misc.Config(server, serverPort, localAddress, localPort, method, password);
            NioLocalServer server = new NioLocalServer(conf);
            pool.execute(server);
            started = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        logger.info("SSClient is running " + server);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        started = false;
        logger.info("SSClient is stop!");
        pool.shutdown();
    }

    private void getDataFromIntent(Intent intent) {
        if (intent != null) {
            if (intent.hasExtra(SERVER) && intent.getStringExtra(SERVER) != null) {
                String m = intent.getStringExtra(METHOD);
                if (!TextUtils.isEmpty(m)) {
                    method = m;
                }
                String p = intent.getStringExtra(PASSWORD);
                if (!TextUtils.isEmpty(p)) {
                    password = p;
                }

                String s = intent.getStringExtra(SERVER);
                if (!TextUtils.isEmpty(s)) {
                    server = s;
                }
                serverPort = intent.getIntExtra(SERVER_PORT, 9009);
            }

        }
    }

}
