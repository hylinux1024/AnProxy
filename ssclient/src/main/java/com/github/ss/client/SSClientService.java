package com.github.ss.client;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

import com.github.ss.utils.AppUtils;
import com.github.ss.utils.Config;

import java.util.logging.Logger;


public class SSClientService extends Service {

    private static final Logger logger = Logger.getLogger("SSClientService");

    private static final String TAG = SSClientService.class.getSimpleName();
    private NioLocalClient localClient;
    private Config config;

    private String localAddress = "127.0.0.1";
    private int localPort = 8888;

    private String method = "aes-256-cfb";
    private String password = "tbox888666";
    private String server = "52.199.25.74";
    private int serverPort = 9001;

    private Thread task;

    public SSClientService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setupConfig();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getDataFromIntent(intent);
        setupConfig();
        runOnBackend();
        return super.onStartCommand(intent, flags, startId);
    }


    protected void runOnBackend() {
        if (task != null) {
            logger.info("SSClient already running!");
            return;
        }
        task = new Thread() {
            @Override
            public void run() {
                localClient.startClient(config);
            }
        };
        task.start();
        logger.info("SSClient is running!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logger.info("SSClient is stop!");
        if (localClient != null) {
            localClient.stopLocalClient();
            localClient = null;
        }
        if (task != null) {
            task.interrupt();
            task = null;
        }
        if (!AppUtils.isMainProcess(this)) {
            Process.killProcess(Process.myPid());
        }

    }

    private void getDataFromIntent(Intent intent) {
        if (intent != null) {
            if (intent.hasExtra("localAddress")) {
                localAddress = intent.getStringExtra("localAddress");
                localPort = intent.getIntExtra("localPort", 8888);
                method = intent.getStringExtra("method");
                password = intent.getStringExtra("password");
                server = intent.getStringExtra("server");
                serverPort = intent.getIntExtra("serverPort", 9009);
            }

        }
    }

    private void setupConfig() {
        localClient = new NioLocalClient();
        config = new Config();
        config.setClientListenIp(localAddress);
        config.setClientListenPort(localPort);

        config.setEncryptMethod(method);
        config.setEncryptPassword(password);
        config.setProxyServerIp(server);
        config.setProxyServerPort(serverPort);
    }
}
