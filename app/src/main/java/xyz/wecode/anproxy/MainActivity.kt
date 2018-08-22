package xyz.wecode.anproxy

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.github.ss.client.SSClientService

class MainActivity : AppCompatActivity() {
    lateinit var pIntent: Intent
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pIntent = Intent(this, SSClientService::class.java)
        startService(pIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(pIntent)
    }
}
